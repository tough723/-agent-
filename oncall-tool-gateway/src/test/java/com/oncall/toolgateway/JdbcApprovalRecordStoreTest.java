package com.oncall.toolgateway;

import com.oncall.domain.tool.RiskLevel;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JdbcApprovalRecordStore} 的验收测试。
 *
 * <p><b>为什么必须在真库上跑</b>：这个类的价值在于两件事——
 * 「十三个列真的被填对了」和「{@code decide} 的条件更新真的只有一个赢家」。
 * 用假的 {@code PreparedStatement} 断言 {@code setString} 调了几次，
 * 等于把断言写在被测代码里；而并发那一条用假对象根本测不出来。
 *
 * <p><b>建表语句从 {@code db/migration/V2} 里抽</b>，理由与
 * {@link JdbcToolAuditLogTest} 相同：用类里的 {@code CREATE_TABLE_SQL} 常量建表，
 * 测的就是一份 DDL 的副本，生产上真正执行的 V2 没人验证。
 */
class JdbcApprovalRecordStoreTest {

    private static final ToolAuditContext CTX =
            new ToolAuditContext("trace-abc", "run-1", "step-2", "alice");

    private JdbcDataSource dataSource;
    private JdbcApprovalRecordStore store;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:approval-" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        applyMigrationV2();
        store = new JdbcApprovalRecordStore(dataSource);
    }

    private static ApprovalRecord pending(String id) {
        return ApprovalRecord.pending(id, CTX, "scale_replicas", RiskLevel.HIGH,
                "alice", "{\"replicas\":8}");
    }

    // ------------------------------------------------------------ 往返

    @Test
    @DisplayName("写入 PENDING 后读回，每个字段原样一致")
    void roundTripPreservesEveryField() {
        store.insert(pending("ap-1"));

        ApprovalRecord r = store.find("ap-1").orElseThrow();
        assertThat(r.id()).isEqualTo("ap-1");
        assertThat(r.context().traceId()).isEqualTo("trace-abc");
        assertThat(r.context().runId()).isEqualTo("run-1");
        assertThat(r.context().stepId()).isEqualTo("step-2");
        assertThat(r.toolName()).isEqualTo("scale_replicas");
        assertThat(r.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(r.requester()).isEqualTo("alice");
        assertThat(r.argsSnapshot()).isEqualTo("{\"replicas\":8}");
        assertThat(r.decision()).isEqualTo(ApprovalDecision.PENDING);
        assertThat(r.approver()).isNull();
        assertThat(r.comment()).isNull();
        assertThat(r.decidedAt()).isNull();
        assertThat(r.requestedAt()).isNotNull();
    }

    @Test
    @DisplayName("可空列（run_id / step_id）写入 null 后读回仍是 null，不是空串")
    void nullableColumnsRoundTripAsNull() {
        store.insert(new ApprovalRecord("ap-1", new ToolAuditContext("t", null, null, null),
                "restart_pod", RiskLevel.LOW, "agent", "{}",
                ApprovalDecision.PENDING, null, null, Instant.now(), null));

        ApprovalRecord r = store.find("ap-1").orElseThrow();
        assertThat(r.context().runId()).isNull();
        assertThat(r.context().stepId()).isNull();
    }

    @Test
    @DisplayName("已决定的记录往返后仍带审批人、备注与决定时间")
    void decidedRecordRoundTrips() {
        store.insert(pending("ap-1"));
        assertThat(store.decide("ap-1", ApprovalDecision.REJECTED, "bob", "现在不能扩")).isTrue();

        ApprovalRecord r = store.find("ap-1").orElseThrow();
        assertThat(r.decision()).isEqualTo(ApprovalDecision.REJECTED);
        assertThat(r.approver()).isEqualTo("bob");
        assertThat(r.comment()).isEqualTo("现在不能扩");
        assertThat(r.decidedAt()).isNotNull();
    }

    // ------------------------------------------------------------ 主键

    @Test
    @DisplayName("同一个 id 写两次 → IllegalArgumentException，不是数据库异常")
    void duplicateIdIsRejected() {
        store.insert(pending("ap-1"));
        assertThatThrownBy(() -> store.insert(pending("ap-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已存在");
    }

    // ------------------------------------------------------------ 条件更新

    @Test
    @DisplayName("decide 两次，只有第一次返回 true —— 两个审批人同时点批准只有一个生效")
    void decideAppliesOnlyOnce() {
        store.insert(pending("ap-1"));

        assertThat(store.decide("ap-1", ApprovalDecision.GRANTED, "bob", "同意")).isTrue();
        assertThat(store.decide("ap-1", ApprovalDecision.REJECTED, "carol", "我不同意"))
                .as("第二个人的决定不能覆盖第一个人的").isFalse();

        ApprovalRecord r = store.find("ap-1").orElseThrow();
        assertThat(r.approver()).isEqualTo("bob");
        assertThat(r.decision()).isEqualTo(ApprovalDecision.GRANTED);
    }

    @Test
    @DisplayName("decide 一个不存在的 id → 返回 false 而不是抛异常")
    void decideReturnsFalseForUnknownId() {
        assertThat(store.decide("nope", ApprovalDecision.GRANTED, "bob", null)).isFalse();
    }

    @Test
    @DisplayName("decide 传 PENDING → 拒绝：结论必须是终态")
    void decideRejectsPendingOutcome() {
        assertThatThrownBy(() -> store.decide("ap-1", ApprovalDecision.PENDING, "bob", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("终态");
    }

    @Test
    @DisplayName("TIMED_OUT 带审批人 → 在写库前就被拦下（不能靠数据库的 CHECK 才发现）")
    void decideValidatesOutcomeShapeBeforeWriting() {
        store.insert(pending("ap-1"));
        assertThatThrownBy(() -> store.decide("ap-1", ApprovalDecision.TIMED_OUT, "bob", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TIMED_OUT 不应有 approver");
        // 记录必须仍然是 PENDING：校验失败不能留下半个状态
        assertThat(store.find("ap-1").orElseThrow().decision())
                .isEqualTo(ApprovalDecision.PENDING);
    }

    @Test
    @DisplayName("自批在写库前就被拦下")
    void decideRejectsSelfApproval() {
        store.insert(pending("ap-1"));
        assertThatThrownBy(() -> store.decide("ap-1", ApprovalDecision.GRANTED, "alice", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("审批人不能与申请人相同");
    }

    // ------------------------------------------------------------ 查询

    @Test
    @DisplayName("pending() 只列出待审批的，按请求时间排序")
    void pendingListsOnlyPending() throws Exception {
        Instant base = Instant.now().minus(10, ChronoUnit.MINUTES);
        store.insert(at("ap-old", base));
        store.insert(at("ap-new", base.plusSeconds(60)));
        store.insert(at("ap-done", base.plusSeconds(30)));
        store.decide("ap-done", ApprovalDecision.GRANTED, "bob", null);

        List<ApprovalRecord> pending = store.pending();
        assertThat(pending).extracting(ApprovalRecord::id)
                .containsExactly("ap-old", "ap-new");
    }

    @Test
    @DisplayName("pendingRequestedBefore 只返回请求时刻早于给定时刻的待审批记录")
    void pendingRequestedBeforeFiltersByTime() {
        Instant base = Instant.now().minus(10, ChronoUnit.MINUTES);
        store.insert(at("ap-stale", base));
        store.insert(at("ap-fresh", base.plusSeconds(300)));
        store.insert(at("ap-done", base));
        store.decide("ap-done", ApprovalDecision.GRANTED, "bob", null);

        List<ApprovalRecord> stale = store.pendingRequestedBefore(base.plusSeconds(60));
        assertThat(stale).extracting(ApprovalRecord::id).containsExactly("ap-stale");
    }

    @Test
    @DisplayName("count 反映行数")
    void countReflectsRows() {
        assertThat(store.count()).isZero();
        store.insert(pending("ap-1"));
        store.insert(pending("ap-2"));
        assertThat(store.count()).isEqualTo(2);
    }

    // ------------------------------------------------------------ 建表

    @Test
    @DisplayName("createSchemaIfMissing 建出来的表能正常读写")
    void createSchemaIfMissingWorks() throws Exception {
        JdbcDataSource fresh = new JdbcDataSource();
        fresh.setURL("jdbc:h2:mem:schema-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        JdbcApprovalRecordStore s = new JdbcApprovalRecordStore(fresh, "approval_record");
        s.createSchemaIfMissing();
        s.createSchemaIfMissing();   // 幂等
        s.insert(pending("ap-1"));
        assertThat(s.count()).isEqualTo(1);
    }

    // ------------------------------------------------------------ 工具

    private static ApprovalRecord at(String id, Instant requestedAt) {
        return new ApprovalRecord(id, CTX, "scale_replicas", RiskLevel.HIGH, "alice",
                "{}", ApprovalDecision.PENDING, null, null, requestedAt, null);
    }

    /**
     * 从真实的 V2 迁移文件里建 {@code approval_record}。
     *
     * <p>刻意不用 {@link JdbcApprovalRecordStore#CREATE_TABLE_SQL}：
     * 那样测的是 DDL 的副本，生产上跑的 V2 没人验证。
     */
    private void applyMigrationV2() throws IOException, SQLException {
        Path v2 = Path.of("..", "db", "migration", "V2__agent_execution.sql");
        assertThat(Files.exists(v2)).as("必须能读到 %s", v2.toAbsolutePath()).isTrue();
        String sql = Files.readString(v2);
        Matcher m = Pattern.compile(
                "CREATE TABLE IF NOT EXISTS approval_record\\s*\\((.*?)\\n\\);",
                Pattern.DOTALL).matcher(sql);
        assertThat(m.find()).as("V2 里必须能找到 approval_record 的建表语句").isTrue();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE approval_record (" + m.group(1) + "\n)");
        }
    }
}
