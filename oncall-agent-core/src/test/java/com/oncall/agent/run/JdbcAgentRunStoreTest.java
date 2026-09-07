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
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JdbcAgentRunStore} 对真实 PostgreSQL 的验证。
 *
 * <p>建表用 {@code db/migration/} 的迁移脚本原文（V2 建表 + V9 加重规划预算两列），
 * 不在 Java 里复制 DDL——复制就会与迁移脚本分叉，而分叉是静默的。
 *
 * <p><b>两个脚本都必须应用</b>：只应用 V2 的话，V9 加的
 * {@code budget_replans} / {@code used_replans} 两列不存在，INSERT 会直接失败。
 * 这类布线点漏了不是「少测一点」，而是整个测试类跑不起来。
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
    /** 提为字段是为了让「绕过应用层直接写 SQL」的测试能拿到连接。 */
    private PGSimpleDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv(PG_URL_ENV);
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "未设置 " + PG_URL_ENV + "，跳过真实数据库验证（本地正常；CI 里必须设置）");

        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(url);
        applyMigrations(dataSource);
        store = new JdbcAgentRunStore(dataSource);
    }

    /**
     * 按迁移顺序应用 V2 与 V9。
     *
     * <p><b>必须两个都应用</b>：V9 给 agent_run 加了
     * {@code budget_replans} / {@code used_replans} 两列，
     * 只应用 V2 的话 INSERT 会因为列不存在而直接失败。
     * 这类布线点漏了不会「少测一点」，而是整个测试类跑不起来。
     *
     * <p>V2 里 agent_step 的外键指向 agent_run，所以按子→父顺序 DROP。
     */
    private static void applyMigrations(DataSource ds) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS agent_step CASCADE");
            st.execute("DROP TABLE IF EXISTS agent_run CASCADE");
            st.execute("DROP TABLE IF EXISTS tool_audit_log CASCADE");
            st.execute("DROP TABLE IF EXISTS approval_record CASCADE");
        }
        // 顺序即迁移顺序：V2 建表，V9 加列与约束。
        // 切分交给 MigrationSql——裸 split(";") 会被注释里的分号切碎，见该类的类注释。
        MigrationSql.apply(ds, "V2__agent_execution.sql", "V9__agent_run_replan_budget.sql");
    }


    private static AgentRun sample(AutonomyLevel level) {
        return AgentRun.start("run-1", TraceId.adopt("oc-trace-run-1"), "grp-1", level,
                10, 100_000L, new BigDecimal("5.000000"), 2, T0);
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
                3, 10, 100_000L, new BigDecimal("5.000000"), 2,
                3, 900L, new BigDecimal("0.420000"), 1, T0, null);
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
                AutonomyLevel.ASSIST, 5, 1000L, new BigDecimal("1.000000"), 2, T0));

        AgentRun back = store.findById("run-2").orElseThrow();
        assertThat(back.alertGroupId()).isNull();
        assertThat(back.autonomyLevel()).isEqualTo(AutonomyLevel.ASSIST);
    }

    // ── V9 的 CHECK 约束：挡住绕过应用层的写入 ──────────────────

    @Test
    @DisplayName("★ chk_agent_run_replan_budget 挡住绕过应用层的越界写入")
    void checkConstraintBlocksBypassingWrites() throws Exception {
        // AgentRun 的构造器已经校验了 used <= budget，所以**通过应用层写不进越界值**。
        // 这条测试刻意绕过应用层，直接执行 SQL——手工修复脚本、别的语言的客户端
        // 都是这条路径。没有 CHECK 约束，预算护栏就只挡得住守规矩的调用方，
        // 而护栏被越过后「重规划预算耗尽」这个终止条件会静默失效。
        String sql = "INSERT INTO agent_run (id, trace_id, status, autonomy_level,"
                + " step_cursor, budget_steps, budget_tokens, budget_cost, budget_replans,"
                + " used_steps, used_tokens, used_cost, used_replans, created_at)"
                + " VALUES ('bad-1','oc-trace-bad','RUNNING','SHADOW',"
                + " 0, 10, 1000, 1.0, 2, 0, 0, 0, 5, '2026-09-07 03:00:00')";
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.execute(sql))
                    .as("used_replans=5 > budget_replans=2 必须被数据库拒绝")
                    .hasMessageContaining("chk_agent_run_replan_budget");
        }
        assertThat(store.findById("bad-1")).isEmpty();
    }

    @Test
    @DisplayName("★ 重规划预算两列往返；update 会落 used_replans 但不改 budget_replans")
    void replanBudgetColumnsRoundTrip() {
        AgentRun run = AgentRun.start("run-rp", TraceId.adopt("oc-trace-rp"), null,
                AutonomyLevel.BOUNDED_AUTO, 10, 100_000L, new BigDecimal("5.000000"), 3, T0);
        store.insert(run);

        AgentRun back = store.findById("run-rp").orElseThrow();
        assertThat(back.budgetReplans()).isEqualTo(3);
        assertThat(back.usedReplans()).isZero();

        // 重规划两次后落库：used_replans 是进度，必须被 update 写进去
        AgentRun advanced = run.consumeReplan().consumeReplan();
        store.update(advanced);

        AgentRun reloaded = store.findById("run-rp").orElseThrow();
        assertThat(reloaded.usedReplans()).as("worker 崩溃重启后不能归零").isEqualTo(2);
        assertThat(reloaded.budgetReplans()).as("预算是写入一次的列，不该在跑动中变").isEqualTo(3);
    }
}
