package com.oncall.agent.llm;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JdbcLlmCallLog} 对真实 PostgreSQL 的验证。
 *
 * <p><b>为什么这一张表不用 H2</b>：{@code llm_call_log} 是
 * {@code PARTITION BY RANGE (called_at)} 的分区表。H2 是否支持 PostgreSQL 的
 * 声明式分区方言并不确定，而赌方言兼容性的结果是「测试通过但生产写不进去」——
 * 那比没有测试更糟。所以这里直接连真实 PostgreSQL。
 *
 * <p><b>建表用的是 {@code db/migration/V4} 原文，不是 Java 里复制的一份</b>：
 * 复制一份就会与迁移脚本分叉，而分叉的 DDL 是静默的。
 * 读原文意味着「测试验的表」与「生产建的表」是同一份定义。
 *
 * <h2>为什么用环境变量开关而不是「连不上就跳过」</h2>
 * <p>如果写成「连不上就 assume 跳过」，那么 CI 里一旦服务容器没起来，
 * 这条测试会安静地跳过，而 CI 依然是绿的——那是一次假绿。
 * 所以规则是：<b>{@code ONCALL_TEST_PG_URL} 一旦设置就必须真的跑</b>，
 * 连不上直接失败；只有变量没设时才跳过，而跳过数会出现在
 * surefire 统计里，CI 的「测试统计」注解能看见。
 */
@DisplayName("JdbcLlmCallLog：对真实 PostgreSQL 的分区表写入")
class JdbcLlmCallLogTest {

    private static final String PG_URL_ENV = "ONCALL_TEST_PG_URL";

    private DataSource dataSource;
    private JdbcLlmCallLog log;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv(PG_URL_ENV);
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "未设置 " + PG_URL_ENV + "，跳过真实数据库验证（本地开发属正常；"
                        + "CI 里必须设置，否则本测试会静默不跑）");

        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        dataSource = ds;

        // 用 V4 原文建表：测试验的表与生产建的表是同一份定义。
        String ddl = readMigrationV4();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS llm_call_log CASCADE");
            for (String stmt : ddl.split(";")) {
                if (!stmt.isBlank()) {
                    st.execute(stmt);
                }
            }
        }
        log = new JdbcLlmCallLog(dataSource);
    }

    private static String readMigrationV4() throws Exception {
        // CI 的工作目录是仓库根，本地 IDE 可能是模块目录，两种都试。
        for (String p : new String[] {
                "db/migration/V4__llm_metering.sql",
                "../db/migration/V4__llm_metering.sql"}) {
            Path path = Path.of(p);
            if (Files.exists(path)) {
                return Files.readString(path);
            }
        }
        throw new IllegalStateException("找不到 V4__llm_metering.sql——"
                + "本测试必须用迁移脚本原文建表，不接受在 Java 里复制一份 DDL");
    }

    private static LlmCallRecord sample(CallOutcome outcome) {
        return LlmCallRecord.of(outcome,
                "oc-trace-1", "run-1", "PLANNER", "intent-classify.v3",
                "prompt...", "response...",
                1200, 340, 1540, 900,
                "SUCCESS", Instant.parse("2026-09-07T03:00:00Z"));
    }

    @Test
    @DisplayName("★ 写入分区表成功，且 model / latency_ms / is_retry 落的是 CallOutcome 的值")
    void writesIntoThePartitionedTable() throws Exception {
        log.record(sample(new CallOutcome("backup-model", 12_345L, 2, "primary-model")));

        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT model, latency_ms, is_retry, failover_from, prompt_version,"
                             + " cached_tokens, called_at FROM llm_call_log")) {
            assertThat(rs.next()).as("必须真的写进去了一行").isTrue();
            assertThat(rs.getString("model")).isEqualTo("backup-model");
            assertThat(rs.getInt("latency_ms")).isEqualTo(12_345);
            assertThat(rs.getBoolean("is_retry")).isTrue();
            assertThat(rs.getString("failover_from")).isEqualTo("primary-model");
            assertThat(rs.getString("prompt_version")).isEqualTo("intent-classify.v3");
            assertThat(rs.getInt("cached_tokens")).isEqualTo(900);
            assertThat(rs.getTimestamp("called_at").toInstant())
                    .isEqualTo(Instant.parse("2026-09-07T03:00:00Z"));
            assertThat(rs.next()).as("只应有一行").isFalse();
        }
    }

    @Test
    @DisplayName("可空列写 null 而不是空串——run_id 与 failover_from 都允许为空")
    void nullableColumnsAreWrittenAsNull() throws Exception {
        log.record(LlmCallRecord.of(
                new CallOutcome("primary-model", 42L, 1, null),
                "oc-trace-2", null, "EMBEDDING", "intent-classify.v3",
                null, null, 10, 0, 10, 0, "SUCCESS",
                Instant.parse("2026-09-07T03:00:00Z")));

        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT run_id, failover_from, prompt_masked FROM llm_call_log")) {
            assertThat(rs.next()).isTrue();
            rs.getString("run_id");
            assertThat(rs.wasNull()).as("run_id 应为 SQL NULL").isTrue();
            rs.getString("failover_from");
            assertThat(rs.wasNull()).as("failover_from 应为 SQL NULL").isTrue();
            rs.getString("prompt_masked");
            assertThat(rs.wasNull()).as("prompt_masked 应为 SQL NULL").isTrue();
        }
    }

    @Test
    @DisplayName("落库失败必须抛出来，不得吞掉——计量丢失是静默的")
    void failureIsNotSwallowed() {
        JdbcLlmCallLog broken = new JdbcLlmCallLog(dataSource, "llm_call_log_does_not_exist");
        assertThatThrownBy(() -> broken.record(
                sample(new CallOutcome("m", 1L, 1, null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("写入");
    }

    @Test
    @DisplayName("LlmCallRecord 拒绝空的 NOT NULL 列——在构造期就拒绝，而不是等数据库报错")
    void recordRejectsBlankRequiredColumns() {
        assertThatThrownBy(() -> LlmCallRecord.of(
                new CallOutcome("m", 1L, 1, null),
                "  ", "run-1", "PLANNER", "v1", null, null,
                0, 0, 0, 0, "SUCCESS", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traceId");
    }

    @Test
    @DisplayName("LlmCallRecord 拒绝负 token 数与负延迟")
    void recordRejectsNegativeNumbers() {
        assertThatThrownBy(() -> new LlmCallRecord(
                "t", null, "CHAT", "m", "v1", null, null,
                -1, 0, 0, 0, 1L, false, null, "SUCCESS", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LlmCallRecord(
                "t", null, "CHAT", "m", "v1", null, null,
                0, 0, 0, 0, -5L, false, null, "SUCCESS", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("latencyMs");
    }
}
