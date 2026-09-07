package com.oncall.agent.run;

import com.oncall.domain.autonomy.AutonomyLevel;
import com.oncall.domain.run.AgentRun;
import com.oncall.domain.run.RunStatus;
import com.oncall.domain.trace.TraceId;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JdbcAgentRunStore} 对真实 PostgreSQL 的验证。
 *
 * <p>建表用 {@code db/migration/V2__agent_execution.sql} 原文，不在 Java 里复制 DDL——
 * 复制就会与迁移脚本分叉，而分叉是静默的。
 *
 * <p>闸门与 {@code JdbcLlmCallLogTest} 相同：{@code ONCALL_TEST_PG_URL}
 * 一旦设置就必须真的跑，连不上直接失败。该变量挂在 CI 的 <b>job 级</b>，
 * 因为 {@code mvn -pl X -am test} 会把上游模块的测试重跑并覆盖其报告，
 * 只给单步注入会被后续步骤冲掉（D2-d 实测踩过）。
 */
@DisplayName("JdbcAgentRunStore：agent_run 落库与放权快照的 write-once")
class JdbcAgentRunStoreTest {

    private static final String PG_URL_ENV = "ONCALL_TEST_PG_URL";
    private static final Instant T0 = Instant.parse("2026-09-07T03:00:00Z");

    private JdbcAgentRunStore store;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv(PG_URL_ENV);
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "未设置 " + PG_URL_ENV + "，跳过真实数据库验证（本地正常；CI 里必须设置）");

        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        applyMigrationV2(ds);
        store = new JdbcAgentRunStore(ds);
    }

    /** V2 里 agent_step 的外键指向 agent_run，所以按子→父顺序 DROP。 */
    private static void applyMigrationV2(DataSource ds) throws Exception {
        String ddl = readMigration("db/migration/V2__agent_execution.sql",
                "../db/migration/V2__agent_execution.sql");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS agent_step CASCADE");
            st.execute("DROP TABLE IF EXISTS agent_run CASCADE");
            st.execute("DROP TABLE IF EXISTS tool_audit_log CASCADE");
            st.execute("DROP TABLE IF EXISTS approval_record CASCADE");
            for (String stmt : ddl.split(";")) {
                if (!stmt.isBlank()) {
                    st.execute(stmt);
                }
            }
        }
    }

    private static String readMigration(String... candidates) throws Exception {
        for (String p : candidates) {
            Path path = Path.of(p);
            if (Files.exists(path)) {
                return Files.readString(path);
            }
        }
        throw new IllegalStateException("找不到 V2__agent_execution.sql——"
                + "本测试必须用迁移脚本原文建表，不接受在 Java 里复制一份 DDL");
    }

    private static AgentRun sample(AutonomyLevel level) {
        return AgentRun.start("run-1", TraceId.adopt("oc-trace-run-1"), "grp-1", level,
                10, 100_000L, new BigDecimal("5.000000"), T0);
    }

    @Test
    @DisplayName("★ 写入后 14 列逐列读回一致")
    void roundTripsAllFourteenColumns() {
        store.insert(sample(AutonomyLevel.SHADOW));

        AgentRun back = store.findById("run-1").orElseThrow();
        assertThat(back.id()).isEqualTo("run-1");
        assertThat(back.traceId().value()).isEqualTo("oc-trace-run-1");
        assertThat(back.alertGroupId()).isEqualTo("grp-1");
        assertThat(back.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(back.autonomyLevel()).isEqualTo(AutonomyLevel.SHADOW);
        assertThat(back.stepCursor()).isZero();
        assertThat(back.budgetSteps()).isEqualTo(10);
        assertThat(back.budgetTokens()).isEqualTo(100_000L);
        assertThat(back.budgetCost()).isEqualByComparingTo("5.000000");
        assertThat(back.usedSteps()).isZero();
        assertThat(back.usedTokens()).isZero();
        assertThat(back.usedCost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(back.createdAt()).isEqualTo(T0);
        assertThat(back.finishedAt()).isNull();
        assertThat(store.findById("nope")).isEmpty();
    }

    @Test
    @DisplayName("★★ update() 不覆盖放权等级快照——本轮的核心断言")
    void updateDoesNotOverwriteTheAutonomySnapshot() {
        // 开跑时是 SHADOW。
        store.insert(sample(AutonomyLevel.SHADOW));

        // 之后运维把配置调到 BOUNDED_AUTO，进度更新带着新等级进来。
        AgentRun drifted = new AgentRun("run-1", TraceId.adopt("oc-trace-run-1"), "grp-1",
                RunStatus.RUNNING, AutonomyLevel.BOUNDED_AUTO,
                3, 10, 100_000L, new BigDecimal("5.000000"),
                3, 900L, new BigDecimal("0.420000"), T0, null);
        store.update(drifted);

        AgentRun back = store.findById("run-1").orElseThrow();
        // 进度确实推进了
        assertThat(back.stepCursor()).isEqualTo(3);
        assertThat(back.usedTokens()).isEqualTo(900L);
        assertThat(back.usedCost()).isEqualByComparingTo("0.420000");
        // ★ 但快照必须是「当时的」SHADOW，不是当前配置的 BOUNDED_AUTO。
        // 若 UPDATE 把 autonomy_level 也写回去，这里会读到 BOUNDED_AUTO，
        // 事后追责时就会以为这次排查是在全自动授权下跑的。
        assertThat(back.autonomyLevel())
                .as("放权等级快照必须固定为开跑那一刻的值")
                .isEqualTo(AutonomyLevel.SHADOW);
        // trace_id / 预算 / created_at 同样是 write-once
        assertThat(back.traceId().value()).isEqualTo("oc-trace-run-1");
        assertThat(back.budgetSteps()).isEqualTo(10);
        assertThat(back.createdAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("update() 能写入完成时刻与终态")
    void updateWritesTerminalState() {
        store.insert(sample(AutonomyLevel.SUGGEST));
        Instant end = T0.plusSeconds(45);
        store.update(sample(AutonomyLevel.SUGGEST)
                .consume(2, 300L, new BigDecimal("0.050000"))
                .finish(RunStatus.HANDED_OVER, end));

        AgentRun back = store.findById("run-1").orElseThrow();
        assertThat(back.status()).isEqualTo(RunStatus.HANDED_OVER);
        assertThat(back.finishedAt()).isEqualTo(end);
        assertThat(back.usedSteps()).isEqualTo(2);
    }

    @Test
    @DisplayName("主键冲突必须抛出来——静默丢弃会让一次排查凭空消失")
    void duplicateInsertThrows() {
        store.insert(sample(AutonomyLevel.SHADOW));
        assertThatThrownBy(() -> store.insert(sample(AutonomyLevel.SHADOW)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("写入 agent_run 失败");
    }

    @Test
    @DisplayName("update() 命中 0 行必须抛出来——否则「进度推进了」是一句假话")
    void updateMissingRowThrows() {
        assertThatThrownBy(() -> store.update(sample(AutonomyLevel.SHADOW)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("命中 0 行");
    }

    @Test
    @DisplayName("alert_group_id 可空，写 SQL NULL 而不是空串")
    void nullableAlertGroupIsWrittenAsNull() {
        store.insert(AgentRun.start("run-2", TraceId.adopt("oc-trace-run-2"), null,
                AutonomyLevel.ASSIST, 5, 1000L, new BigDecimal("1.000000"), T0));

        AgentRun back = store.findById("run-2").orElseThrow();
        assertThat(back.alertGroupId()).isNull();
        assertThat(back.autonomyLevel()).isEqualTo(AutonomyLevel.ASSIST);
    }
}
