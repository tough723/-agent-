package com.oncall.toolgateway;

import com.oncall.domain.tool.RiskLevel;
import com.oncall.domain.tool.ToolSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具审计 JDBC 实现的验收测试。
 *
 * <p><b>为什么必须在真库上跑</b>：这个类的价值在于「七个 {@code NOT NULL} 列真的被填上了」。
 * 用假的 {@code PreparedStatement} 断言 {@code setString} 被调了几次，
 * 等于把断言写在被测代码里——断言会绿，而线上照样写不进去。
 * 所以这里用 H2 建真表。
 *
 * <p><b>为什么建表语句从 {@code db/migration/V2} 里抽</b>：如果用类里的
 * {@code CREATE_TABLE_SQL} 常量建表，那被测的是一份 DDL 的副本，
 * 生产上真正执行的 V2 没人验证——某个 {@code NOT NULL} 写漏了，测试照样绿。
 * 与 {@code JdbcToolExecutionLedgerTest} 读 V7 的做法一致。
 */
class JdbcToolAuditLogTest {

    private static final ToolAuditContext CTX =
            new ToolAuditContext("trace-abc", "run-1", "step-2", "alice");

    private JdbcDataSource dataSource;
    private JdbcToolAuditLog auditLog;
    /** 从 V2 迁移文件里解析出来的 NOT NULL 列名。 */
    private List<String> notNullColumns;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        // DB_CLOSE_DELAY=-1：连接全部关闭后仍保留数据，否则每个连接看到的是一个空库
        dataSource.setURL("jdbc:h2:mem:audit-" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        notNullColumns = applyMigrationV2();
        auditLog = new JdbcToolAuditLog(dataSource);
    }

    // ---------------------------------------------------------- 往返

    @Test
    @DisplayName("写入后按 trace 读回，每个字段原样一致")
    void roundTripPreservesEveryField() {
        auditLog.record(ToolAuditEvent.passed(CTX, "scale_replicas", ToolSource.MCP,
                RiskLevel.HIGH, "{\"replicas\":2}", "ok", 137));

        List<ToolAuditEvent> rows = auditLog.byTrace("trace-abc");
        assertThat(rows).hasSize(1);
        ToolAuditEvent e = rows.get(0);
        assertThat(e.context().traceId()).isEqualTo("trace-abc");
        assertThat(e.context().runId()).isEqualTo("run-1");
        assertThat(e.context().stepId()).isEqualTo("step-2");
        assertThat(e.context().operator()).isEqualTo("alice");
        assertThat(e.toolName()).isEqualTo("scale_replicas");
        assertThat(e.toolSource()).isEqualTo(ToolSource.MCP);
        assertThat(e.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(e.argsMasked()).isEqualTo("{\"replicas\":2}");
        assertThat(e.resultMasked()).isEqualTo("ok");
        assertThat(e.gateOutcome()).isEqualTo(GateOutcome.PASSED);
        assertThat(e.durationMs()).isEqualTo(137);
        assertThat(e.calledAt()).isNotNull();
    }

    @Test
    @DisplayName("★ 七个 NOT NULL 列真的都被填上了——这是这个类存在的全部理由")
    void everyNotNullColumnIsPopulated() throws SQLException {
        // 刻意用一条可空字段尽量多的事件：只有 operator/runId/stepId 之外
        // 全部为 null 的情况最能暴露漏填。
        auditLog.record(ToolAuditEvent.denied(
                new ToolAuditContext("trace-nn", null, null, null),
                "mcp:cmdb:restart_service", ToolSource.MCP, RiskLevel.HIGH, null, "not in allowlist"));

        assertThat(notNullColumns)
                .as("V2 里必须能解析出 NOT NULL 列，否则这条断言就是空转")
                .isNotEmpty();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM tool_audit_log WHERE trace_id='trace-nn'")) {
            assertThat(rs.next()).isTrue();
            for (String col : notNullColumns) {
                Object v = rs.getObject(col);
                assertThat(v).as("NOT NULL 列 %s 必须被填上", col).isNotNull();
                if (v instanceof String s) {
                    assertThat(s).as("NOT NULL 列 %s 不能是占位空串", col).isNotEmpty();
                }
            }
        }
    }

    @Test
    @DisplayName("可空列为 null 时走 setNull 路径，读回来仍是 null 而不是空串")
    void nullableColumnsSurviveAsNull() {
        auditLog.record(ToolAuditEvent.denied(
                new ToolAuditContext("trace-null", null, null, null),
                "t", ToolSource.LOCAL, RiskLevel.LOW, null, "拒了"));

        ToolAuditEvent e = auditLog.byTrace("trace-null").get(0);
        assertThat(e.context().runId()).isNull();
        assertThat(e.context().stepId()).isNull();
        assertThat(e.context().operator()).isNull();
        assertThat(e.resultMasked()).as("没有结果与「结果是空串」必须能区分").isNull();
        assertThat(e.durationMs()).isNull();
        assertThat(e.argsMasked()).as("args_masked 是 NOT NULL，null 落成空串").isEmpty();
    }

    @Test
    @DisplayName("★ 耗时 0ms 与「没有耗时」必须能区分——否则 0 会被读成 null")
    void zeroDurationIsDistinguishedFromNull() {
        auditLog.record(ToolAuditEvent.passed(new ToolAuditContext("trace-zero", null, null, null),
                "t", ToolSource.LOCAL, RiskLevel.READ_ONLY, "", "ok", 0));
        auditLog.record(ToolAuditEvent.denied(new ToolAuditContext("trace-zero", null, null, null),
                "t", ToolSource.LOCAL, RiskLevel.READ_ONLY, "", "拒了"));

        List<ToolAuditEvent> rows = auditLog.byTrace("trace-zero");
        ToolAuditEvent passed = rows.stream()
                .filter(e -> e.gateOutcome() == GateOutcome.PASSED).findFirst().orElseThrow();
        ToolAuditEvent denied = rows.stream()
                .filter(e -> e.gateOutcome() == GateOutcome.DENIED).findFirst().orElseThrow();
        assertThat(passed.durationMs()).as("0ms 是真实测量值，不能变成 null").isZero();
        assertThat(denied.durationMs()).as("被拦下的调用没有执行耗时").isNull();
    }

    @Test
    @DisplayName("特殊字符原样往返：引号、换行、反斜杠、中文")
    void specialCharactersRoundTrip() {
        String nasty = "{\"msg\":\"第一行\\n第二行\",\"q\":\"他说\\\"你好\\\"\",\"p\":\"C:\\\\tmp\"}";
        auditLog.record(ToolAuditEvent.passed(new ToolAuditContext("trace-esc", null, null, null),
                "t", ToolSource.LOCAL, RiskLevel.LOW, nasty, "拒绝原因：变更窗口内", 1));

        ToolAuditEvent e = auditLog.byTrace("trace-esc").get(0);
        assertThat(e.argsMasked()).isEqualTo(nasty);
        assertThat(e.resultMasked()).isEqualTo("拒绝原因：变更窗口内");
    }

    // ---------------------------------------------------------- 查询

    @Test
    @DisplayName("按 trace 查询只返回该链路，并按时间升序")
    void byTraceIsScopedAndOrdered() {
        ToolAuditContext c1 = ToolAuditContext.of("trace-order");
        ToolAuditContext c2 = ToolAuditContext.of("trace-other");
        auditLog.record(new ToolAuditEvent(c1, "t1", ToolSource.LOCAL, RiskLevel.LOW,
                "a", null, GateOutcome.CLAMPED, null, null, Instant.now().minusSeconds(30)));
        auditLog.record(new ToolAuditEvent(c2, "t2", ToolSource.LOCAL, RiskLevel.LOW,
                "b", null, GateOutcome.PASSED, null, null, Instant.now().minusSeconds(20)));
        auditLog.record(new ToolAuditEvent(c1, "t3", ToolSource.LOCAL, RiskLevel.LOW,
                "c", null, GateOutcome.PASSED, null, null, Instant.now()));

        List<ToolAuditEvent> rows = auditLog.byTrace("trace-order");
        assertThat(rows).extracting(ToolAuditEvent::toolName).containsExactly("t1", "t3");
        assertThatThrownBy(() -> auditLog.byTrace("  "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("traceId");
    }

    @Test
    @DisplayName("count() 反映行数，供保留期清理任务使用")
    void countReflectsRows() {
        assertThat(auditLog.count()).isZero();
        auditLog.record(ToolAuditEvent.passed(CTX, "t", ToolSource.LOCAL, RiskLevel.LOW, "", "r", 1));
        auditLog.record(ToolAuditEvent.passed(CTX, "t", ToolSource.LOCAL, RiskLevel.LOW, "", "r", 1));
        assertThat(auditLog.count()).isEqualTo(2);
    }

    // ---------------------------------------------------------- 失败必须可见

    @Test
    @DisplayName("★ 写入失败必须抛出去——静默失败的审计比没有审计更危险，它让人以为有")
    void writeFailureIsNotSwallowed() {
        JdbcToolAuditLog broken = new JdbcToolAuditLog(dataSource, "no_such_table");
        assertThatThrownBy(() -> broken.record(
                ToolAuditEvent.passed(CTX, "t", ToolSource.LOCAL, RiskLevel.LOW, "", "r", 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("写入工具审计失败");
        assertThatThrownBy(broken::count)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("构造期拒绝 null dataSource 与 null 事件")
    void nullsAreRejectedEarly() {
        assertThatThrownBy(() -> new JdbcToolAuditLog(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dataSource");
        assertThatThrownBy(() -> auditLog.record(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("event");
    }

    @Test
    @DisplayName("createSchemaIfMissing 幂等：连跑两次不报错，且表结构与 V2 一致")
    void createSchemaIsIdempotent() throws SQLException {
        JdbcToolAuditLog fresh = new JdbcToolAuditLog(dataSource, "tool_audit_log_copy");
        fresh.createSchemaIfMissing();
        fresh.createSchemaIfMissing();
        fresh.record(ToolAuditEvent.passed(CTX, "t", ToolSource.LOCAL, RiskLevel.LOW, "", "r", 1));
        assertThat(fresh.count()).isEqualTo(1);
    }

    // ---------------------------------------------------------- helpers

    /**
     * 从 V2 迁移文件里抽出 {@code tool_audit_log} 的真实建表语句来建表，
     * 并顺带解析出 NOT NULL 列名（供上面那条关键断言使用）。
     *
     * @return 除主键 {@code id} 外的 NOT NULL 列名
     */
    private List<String> applyMigrationV2() throws IOException, SQLException {
        String sql = readMigration();
        Matcher m = Pattern.compile(
                "CREATE TABLE IF NOT EXISTS tool_audit_log\\s*\\((.*?)\\n\\);",
                Pattern.DOTALL).matcher(sql);
        assertThat(m.find()).as("V2 里必须能找到 tool_audit_log 的建表语句").isTrue();
        String body = m.group(1);
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE tool_audit_log (" + body + "\n)");
        }
        List<String> cols = new java.util.ArrayList<>();
        for (String line : body.split("\n")) {
            String t = line.trim();
            if (t.startsWith("id ") || t.isEmpty() || t.startsWith("--") || t.startsWith("CONSTRAINT")) {
                continue;
            }
            if (t.toUpperCase(java.util.Locale.ROOT).contains("NOT NULL")) {
                cols.add(t.split("\\s+")[0]);
            }
        }
        // id 是自增主键，由数据库生成，不算「实现必须填的列」
        cols.remove("id");
        assertThat(cols).as("从 V2 解析出的 NOT NULL 列").isNotEmpty();
        return List.copyOf(cols);
    }

    private static String readMigration() throws IOException {
        List<String> candidates = List.of(
                "../db/migration/V2__agent_execution.sql",
                "db/migration/V2__agent_execution.sql");
        for (String candidate : candidates) {
            Path p = Path.of(candidate);
            if (Files.isReadable(p)) {
                return Files.readString(p);
            }
        }
        throw new IOException("找不到 V2 迁移文件，尝试过：" + candidates);
    }
}
