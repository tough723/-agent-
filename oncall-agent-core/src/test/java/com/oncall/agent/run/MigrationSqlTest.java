package com.oncall.agent.run;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MigrationSql} 的切分规则测试。
 *
 * <h2>★ 这个类为什么存在：修一个「注释里的分号」事故，不能只靠删掉那个分号</h2>
 * <p>V9 的注释里写着 {@code ddl.split(";")} —— <b>那个注释里的分号本身</b>
 * 就被裸 {@code split(";")} 当成语句分隔符，切出的片段以孤立的 {@code "} 开头，
 * PostgreSQL 报 {@code Unterminated identifier}，两个 JDBC 测试的建表全部失败。
 *
 * <p>如果只把那行注释改掉，缺陷就换了个地方埋着：下一个人在注释里写一句
 * 带分号的话（这完全合法，{@code psql} 也这么处理），同样的事故会再来一次。
 * 所以真正的修复是<b>切分前先剥注释</b>，而这个类就是它的回归测试 ——
 * 否则下次有人「优化」掉剥注释这一步，不会有任何东西报警。
 */
class MigrationSqlTest {

    @Test
    @DisplayName("注释里的分号不是语句分隔符")
    void semicolonInsideLineCommentIsNotAStatementSeparator() {
        String ddl = """
                -- 说明：调用方是用 ddl.split(";") 逐条执行的
                CREATE TABLE t (id BIGINT);
                """;
        List<String> stmts = MigrationSql.statements(ddl);
        assertThat(stmts).hasSize(1);
        assertThat(stmts.get(0)).contains("CREATE TABLE t").doesNotContain("说明");
    }

    @Test
    @DisplayName("注释里出现未闭合的双引号也不会泄漏到语句里")
    void unterminatedQuoteInsideCommentDoesNotLeak() {
        // 这正是 V9 炸掉的那一段：split(";") 会切出以孤立 " 开头的片段
        String ddl = """
                --   用 ddl.split(";") 逐条执行迁移原文的，
                ALTER TABLE agent_run ADD COLUMN budget_replans INT NOT NULL DEFAULT 0;
                """;
        List<String> stmts = MigrationSql.statements(ddl);
        assertThat(stmts).hasSize(1);
        for (String s : stmts) {
            assertThat(count(s, '"')).as("语句里不应有未闭合的双引号: %s", s).isZero();
        }
    }

    @Test
    @DisplayName("单引号字符串里的 -- 不是注释")
    void doubleDashInsideStringLiteralIsNotAComment() {
        String ddl = "COMMENT ON COLUMN t.c IS '形如 a--b 的取值';\n";
        assertThat(MigrationSql.statements(ddl)).hasSize(1);
        assertThat(MigrationSql.statements(ddl).get(0)).contains("a--b");
    }

    @Test
    @DisplayName("SQL 里 '' 是转义的单引号，不会提前结束字符串")
    void doubledQuoteIsAnEscapedQuoteNotAStringEnd() {
        String ddl = "COMMENT ON COLUMN t.c IS 'it''s -- not a comment';\n";
        List<String> stmts = MigrationSql.statements(ddl);
        assertThat(stmts).hasSize(1);
        assertThat(stmts.get(0)).contains("-- not a comment");
    }

    @Test
    @DisplayName("空白片段被丢弃，末尾没有分号也能收到最后一条")
    void blankFragmentsAreDroppedAndTrailingStatementIsKept() {
        String ddl = "CREATE TABLE a (i INT);\n\n   \nCREATE TABLE b (i INT)";
        assertThat(MigrationSql.statements(ddl))
                .hasSize(2)
                .allSatisfy(s -> assertThat(s).isNotBlank());
    }

    /** 数一个字符出现的次数。{@code String} 没有 {@code count(Predicate)}，得走 IntStream。 */
    private static long count(String s, char target) {
        return s.chars().filter(c -> c == target).count();
    }

    /**
     * ★ 对<b>仓库里真实的 9 个迁移脚本</b>断言切分结果，不只是断言手写的样例。
     *
     * <p>手写的样例证明算法对；这条证明<b>当前这批脚本</b>切出来是干净的。
     * 两者缺一不可 —— 只测样例的话，下一个在注释里写分号的迁移脚本会安静地通过。
     */
    @Test
    @DisplayName("仓库里全部 9 个迁移脚本切出的每一段都没有未闭合引号")
    void everyShippedMigrationSplitsIntoCleanStatements() throws Exception {
        // 与 MigrationSql.read 同样的两个候选根：CI 在仓库根跑，本地 surefire 在模块目录跑。
        Path dir = Stream.of(Path.of("db/migration"), Path.of("../db/migration"))
                .filter(Files::isDirectory)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("找不到 db/migration"));
        List<Path> scripts;
        try (var s = Files.list(dir)) {
            scripts = s.filter(p -> p.toString().endsWith(".sql")).sorted().toList();
        }
        assertThat(scripts).hasSize(9);
        for (Path p : scripts) {
            for (String stmt : MigrationSql.statements(Files.readString(p))) {
                assertThat(count(stmt, '"'))
                        .as("%s 切出的片段有未闭合的双引号: %s", p.getFileName(), stmt)
                        .isZero();
                assertThat(count(stmt, '\''))
                        .as("%s 切出的片段有未闭合的单引号: %s", p.getFileName(), stmt)
                        .isZero();
            }
        }
    }
}
