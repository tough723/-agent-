package com.oncall.agent.run;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试用的迁移脚本读取与切分。
 *
 * <h2>★ 为什么必须有这个类：一次「注释里的分号」事故</h2>
 * <p>原本两个测试各自写着 {@code ddl.split(";")} 逐条执行迁移原文。
 * 这在 V9 上炸了 —— 因为 V9 的注释里有一行：
 * <pre>{@code --   JdbcAgentRunStoreTest 是用 ddl.split(";") 逐条执行迁移原文的，}</pre>
 * <b>那个注释里的分号本身就被当成语句分隔符</b>，切出来的片段以孤立的
 * {@code "} 开头，PostgreSQL 报 {@code Unterminated identifier}。
 *
 * <p>讽刺之处在于：那行注释正是在解释「为什么不用 {@code DO $$} 块，
 * 因为块体内的分号会被切碎」—— 然后被自己的分号切碎了。
 *
 * <p>而且这不是孤例：{@code V7} 的注释里有 {@code return resultOf(key);}，
 * 同样会让 split 段数比真实语句数多一（6 vs 5）。它一直没暴露，
 * 只因为<b>没有测试读过 V7</b>。
 *
 * <h2>为什么修切分器，而不是改注释</h2>
 * <p>注释里合法地可以出现分号 —— {@code psql} 就是这么处理的，
 * 所以 CI 的 DDL job 一直是绿的，只有 Java 侧炸。
 * <b>错的是「裸 split」这个做法，不是注释。</b>
 * 把注释里的分号删掉只是把地雷换个地方埋：
 * 下一个人在注释里写一句带分号的话，同样的事故会再来一次。
 *
 * <h2>为什么抽成共享类</h2>
 * <p>两个测试原本各有一份几乎相同的 {@code applyMigrations}。
 * 同一条解析规则写在两处必然分叉 —— 修了一处忘了另一处，
 * 于是「一个测试能建表、另一个不能」会安静地存在很久。
 */
final class MigrationSql {

    private MigrationSql() {
    }

    /** 按顺序应用迁移脚本。调用方负责先 DROP 相关表。 */
    static void apply(DataSource ds, String... files) throws Exception {
        for (String file : files) {
            String ddl = read("db/migration/" + file, "../db/migration/" + file);
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                for (String stmt : statements(ddl)) {
                    st.execute(stmt);
                }
            }
        }
    }

    /**
     * 剥掉 {@code --} 行注释，再按分号切成语句。
     *
     * <p>必须识别单引号字符串：字符串里的 {@code --} 不是注释
     * （已核实当前 9 个迁移脚本里没有这种情形，但这条规则不该依赖
     * 「恰好现在没有」）。
     */
    static List<String> statements(String ddl) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inString = false;
        int n = ddl.length();
        for (int i = 0; i < n; i++) {
            char c = ddl.charAt(i);
            if (inString) {
                cur.append(c);
                if (c == '\'') {
                    // '' 是 SQL 里单引号的转义，不是字符串结束
                    if (i + 1 < n && ddl.charAt(i + 1) == '\'') {
                        cur.append('\'');
                        i++;
                    } else {
                        inString = false;
                    }
                }
                continue;
            }
            if (c == '\'') {
                inString = true;
                cur.append(c);
                continue;
            }
            if (c == '-' && i + 1 < n && ddl.charAt(i + 1) == '-') {
                // 跳到行尾。保留换行，免得两条语句被粘成一行。
                while (i < n && ddl.charAt(i) != '\n') {
                    i++;
                }
                cur.append('\n');
                continue;
            }
            if (c == ';') {
                addIfNotBlank(out, cur);
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        addIfNotBlank(out, cur);
        return out;
    }

    private static void addIfNotBlank(List<String> out, StringBuilder cur) {
        String s = cur.toString().trim();
        if (!s.isEmpty()) {
            out.add(s);
        }
    }

    /** 从候选路径读取迁移脚本原文（不在 Java 里复制 DDL）。 */
    static String read(String... candidates) throws Exception {
        for (String p : candidates) {
            if (Files.exists(Path.of(p))) {
                return Files.readString(Path.of(p));
            }
        }
        throw new IllegalStateException("找不到迁移脚本 " + String.join(" / ", candidates)
                + "——本测试必须用迁移脚本原文建表，不接受在 Java 里复制一份 DDL");
    }
}
