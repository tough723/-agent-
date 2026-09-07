package com.oncall.agent.run;

import com.oncall.domain.run.AgentStep;
import com.oncall.domain.run.StepStatus;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JdbcAgentStepStore} 对真实 PostgreSQL 的验证。
 *
 * <p>必须连真实库：本测试的核心断言是「撞 {@code uq_agent_step_idem}
 * 时返回 {@code false} 而不是抛异常」，而那依赖 PostgreSQL 的
 * SQLSTATE {@code 23505}。用内存替身验不了这一条——
 * 替身会按我写的样子返回，而不是按数据库真实的样子返回。
 */
@DisplayName("JdbcAgentStepStore：幂等抢占与收尾")
class JdbcAgentStepStoreTest {

    private static final String PG_URL_ENV = "ONCALL_TEST_PG_URL";
    private static final Instant T0 = Instant.parse("2026-09-07T03:00:00Z");

    private JdbcAgentStepStore store;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv(PG_URL_ENV);
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "未设置 " + PG_URL_ENV + "，跳过真实数据库验证（本地正常；CI 里必须设置）");

        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        applyMigrations(ds);
        seedParentRun(ds);
        store = new JdbcAgentStepStore(ds);
    }

    /**
     * 按迁移顺序应用 V2 与 V9。
     *
     * <p><b>为什么这个类也要应用 V9</b>：它与 {@code JdbcAgentRunStoreTest}
     * 共用同一个 PostgreSQL，而两者都会 {@code DROP} 并重建 {@code agent_run}。
     * 若只有那个类应用 V9，这张表的 schema 就<b>取决于哪个类最后跑</b>——
     * 当前不会失败（本类的 INSERT 显式列出列名，而 V9 的新列有 DEFAULT 0），
     * 但那是运气不是设计。让两个类应用同一组迁移，schema 就与执行顺序无关。
     */
    /**
     * DROP 后按迁移顺序应用 V2 与 V9。
     *
     * <p><b>为什么这个类也要应用 V9</b>：它与 {@code JdbcAgentRunStoreTest}
     * 共用同一个 PostgreSQL，而两者都会 DROP 并重建 {@code agent_run}。
     * 若只有那个类应用 V9，这张表的 schema 就<b>取决于哪个类最后跑</b>。
     *
     * <p><b>切分交给 {@link MigrationSql}</b>：裸 {@code split(";")} 会被
     * 注释里的分号切碎，见该类的类注释。
     */
    private static void applyMigrations(DataSource ds) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS agent_step CASCADE");
            st.execute("DROP TABLE IF EXISTS agent_run CASCADE");
        }
        MigrationSql.apply(ds, "V2__agent_execution.sql", "V9__agent_run_replan_budget.sql");
    }

    /** agent_step.run_id 外键指向 agent_run(id)，必须先有父行。 */
    private static void seedParentRun(DataSource ds) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO agent_run (id, trace_id, status, autonomy_level,"
                    + " step_cursor, budget_steps, budget_tokens, budget_cost,"
                    + " used_steps, used_tokens, used_cost, created_at)"
                    + " VALUES ('run-1','oc-trace-1','RUNNING','SHADOW',"
                    + " 0, 10, 100000, 5.0, 0, 0, 0, '2026-09-07 03:00:00')");
        }
    }


    private static AgentStep step(String id, int seq, String idemKey) {
        return AgentStep.start(id, "run-1", seq, "scale_replicas",
                "{\"replicas\":3}", idemKey, T0);
    }

    @Test
    @DisplayName("★★ 撞 uq_agent_step_idem 返回 false 而不是抛异常——抢占失败是预期行为")
    void idempotencyConflictReturnsFalseInsteadOfThrowing() {
        assertThat(store.tryInsert(step("step-a", 0, "same-key"))).isTrue();

        // 另一个 worker 拿着同一个幂等键来抢：不同 id，但键相同。
        // 这不是故障，必须能被调用方与「数据库坏了」区分开。
        assertThat(store.tryInsert(step("step-b", 0, "same-key")))
                .as("幂等冲突必须返回 false，让调用方去读既有结果，而不是当故障重试")
                .isFalse();

        // 而且第一行没有被覆盖
        assertThat(store.findById("step-a")).isPresent();
        assertThat(store.findById("step-b")).isEmpty();
    }

    @Test
    @DisplayName("★ 11 列逐列往返，可空列写 SQL NULL")
    void roundTripsAllElevenColumns() {
        store.tryInsert(step("step-1", 2, "run-1|2|scale_replicas|{}"));

        AgentStep back = store.findById("step-1").orElseThrow();
        assertThat(back.id()).isEqualTo("step-1");
        assertThat(back.runId()).isEqualTo("run-1");
        assertThat(back.seq()).isEqualTo(2);
        assertThat(back.toolName()).isEqualTo("scale_replicas");
        assertThat(back.argsJson()).isEqualTo("{\"replicas\":3}");
        assertThat(back.idempotencyKey()).isEqualTo("run-1|2|scale_replicas|{}");
        assertThat(back.status()).isEqualTo(StepStatus.RUNNING);
        assertThat(back.resultSummary()).isNull();
        assertThat(back.errorMessage()).isNull();
        assertThat(back.startedAt()).isEqualTo(T0);
        assertThat(back.finishedAt()).isNull();
        assertThat(store.findById("nope")).isEmpty();
    }

    @Test
    @DisplayName("finish() 写收尾四列，且不动幂等键与开始时刻")
    void finishWritesOnlyTheClosingColumns() {
        store.tryInsert(step("step-1", 0, "k-0"));
        store.finish(step("step-1", 0, "k-0").succeed("扩容到 3 副本", T0.plusSeconds(9)));

        AgentStep back = store.findById("step-1").orElseThrow();
        assertThat(back.status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(back.resultSummary()).isEqualTo("扩容到 3 副本");
        assertThat(back.finishedAt()).isEqualTo(T0.plusSeconds(9));
        assertThat(back.idempotencyKey()).isEqualTo("k-0");
        assertThat(back.startedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("finish() 命中 0 行必须抛出来——否则「这一步收尾了」是句假话")
    void finishMissingRowThrows() {
        assertThatThrownBy(() -> store.finish(
                step("ghost", 0, "k-ghost").succeed("x", T0.plusSeconds(1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("命中 0 行");
    }

    @Test
    @DisplayName("findByRunId 按 seq 升序返回——断点续跑与复盘都依赖这个顺序")
    void findByRunIdOrdersBySeq() {
        store.tryInsert(step("s-3", 3, "k-3"));
        store.tryInsert(step("s-1", 1, "k-1"));
        store.tryInsert(step("s-2", 2, "k-2"));

        assertThat(store.findByRunId("run-1"))
                .extracting(AgentStep::seq)
                .containsExactly(1, 2, 3);
        assertThat(store.findByRunId("run-none")).isEmpty();
    }

    @Test
    @DisplayName("外键生效：run_id 不存在的步骤插不进去")
    void foreignKeyIsEnforced() {
        AgentStep orphan = new AgentStep("orphan", "run-does-not-exist", 0,
                null, null, "k-orphan", StepStatus.RUNNING, null, null, T0, null);
        assertThatThrownBy(() -> store.tryInsert(orphan))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非幂等冲突");
    }
}
