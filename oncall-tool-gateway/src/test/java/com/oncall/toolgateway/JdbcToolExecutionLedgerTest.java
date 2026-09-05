package com.oncall.toolgateway;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 幂等账本 JDBC 实现的验收测试 —— 对应不变量 I8 的物理保证。
 *
 * <p><b>为什么必须在真库上跑</b>：这个类的全部价值在于「数据库拒绝第二次插入」。
 * 用假的 {@code Connection} 测 SQL，等于把断言写在被测代码里 ——
 * 断言会绿，但线上多实例照样会二次扩容。所以这里用 H2 建真表。
 *
 * <p><b>为什么测试要读 {@code db/migration/V7}</b>：如果测试用的是
 * {@code CREATE_TABLE_SQL} 常量，那被测的是一份 DDL 的副本，
 * 生产上真正执行的 V7 却没人验证 —— 主键或 CHECK 约束写漏了，测试照样绿。
 * 所以这里从迁移文件里抽出真正的建表语句来建表。
 */
class JdbcToolExecutionLedgerTest {

    private static final String KEY = "run-1|1|scale_replicas|a1b2";
    private static final String TOOL = "scale_replicas";

    private JdbcDataSource dataSource;
    private JdbcToolExecutionLedger ledger;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        // DB_CLOSE_DELAY=-1：连接全部关闭后仍保留数据，否则每个连接看到的是一个空库
        dataSource.setURL("jdbc:h2:mem:ledger-" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        applyMigrationV7();
        ledger = new JdbcToolExecutionLedger(dataSource);
    }

    // ---------------------------------------------------------- 抢占语义

    @Test
    @DisplayName("第一次抢占成功，第二次同键抢占失败——这是防二次扩容的物理保证")
    void secondClaimOnSameKeyFails() {
        assertThat(ledger.claim(KEY, TOOL)).isTrue();
        assertThat(ledger.claim(KEY, TOOL)).as("同一个幂等键不可能被抢两次").isFalse();
        assertThat(ledger.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("不同键互不影响")
    void differentKeysAreIndependent() {
        assertThat(ledger.claim(KEY, TOOL)).isTrue();
        assertThat(ledger.claim(KEY + "|other", TOOL)).isTrue();
        assertThat(ledger.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("抢到手但没跑完：isCompleted=false 且没有可重放的结果")
    void claimedButNotCompletedHasNoReplayableResult() {
        ledger.claim(KEY, TOOL);

        assertThat(ledger.isCompleted(KEY)).isFalse();
        assertThat(ledger.resultOf(KEY)).as("还没跑完就不能有结果，否则会把空结果当成成功").isNull();
    }

    @Test
    @DisplayName("完成后：状态可查、结果可重放")
    void completeMakesResultReplayable() {
        ledger.claim(KEY, TOOL);
        ledger.complete(KEY, "{\"replicas\":3}");

        assertThat(ledger.isCompleted(KEY)).isTrue();
        assertThat(ledger.resultOf(KEY)).isEqualTo("{\"replicas\":3}");
        // 结果里可能含换行与引号，必须能原样取回
        assertThat(ledger.claim(KEY, TOOL)).isFalse();
    }

    @Test
    @DisplayName("release 后同一个键可以重新抢占（失败必须可重试）")
    void releaseAllowsRetry() {
        ledger.claim(KEY, TOOL);
        ledger.release(KEY);

        assertThat(ledger.size()).isZero();
        assertThat(ledger.claim(KEY, TOOL)).as("失败后不放行重试，这个幂等键就废了").isTrue();
    }

    @Test
    @DisplayName("release 不能删掉已完成的行——否则成功的结果会丢，下次重试就真的再执行一遍")
    void releaseDoesNotDeleteCompletedRow() {
        ledger.claim(KEY, TOOL);
        ledger.complete(KEY, "scaled");

        ledger.release(KEY);   // 一次迟到的失败回调

        assertThat(ledger.size()).as("已完成的记录必须保住").isEqualTo(1);
        assertThat(ledger.resultOf(KEY)).isEqualTo("scaled");
    }

    @Test
    @DisplayName("complete 在抢占行已被清理任务删掉时仍然写入结果")
    void completeSurvivesStaleCleanupRace() throws Exception {
        // 场景：实例抢占成功 → 执行了 30 分钟 → 清理任务判定为崩溃残留并删除
        // → 实例这才执行成功。此时结果必须仍然落库，否则下一次重试会真的再扩容一次。
        //
        // 刻意先把时间戳改到过去，而不是用 releaseStale(0)：
        // 后者的判定是 updated_at < now，插入与清理可能落在同一个时间刻度里，
        // 那样断言就会变成"看运气"。
        ledger.claim(KEY, TOOL);
        age(KEY, 30);
        assertThat(ledger.releaseStale(60_000)).isEqualTo(1);
        assertThat(ledger.size()).isZero();

        ledger.complete(KEY, "scaled");

        assertThat(ledger.isCompleted(KEY)).isTrue();
        assertThat(ledger.resultOf(KEY)).isEqualTo("scaled");
    }

    // ---------------------------------------------------------- 清理

    @Test
    @DisplayName("releaseStale 只删超时的 CLAIMED，不碰 COMPLETED，也不碰刚抢到的")
    void releaseStaleOnlyClearsOldClaims() throws Exception {
        ledger.claim("old", TOOL);
        ledger.claim("fresh", TOOL);
        ledger.claim("done", TOOL);
        ledger.complete("done", "ok");

        assertThat(ledger.releaseStale(60_000)).as("60 秒内没有残留").isZero();

        age("old", 10);

        assertThat(ledger.releaseStale(60_000)).isEqualTo(1);
        assertThat(ledger.resultOf("old")).isNull();
        assertThat(ledger.resultOf("done")).as("已完成的不能被清理").isEqualTo("ok");
        assertThat(ledger.claim("fresh", TOOL)).isFalse();
    }

    // ---------------------------------------------------------- 并发

    @Test
    @DisplayName("并发抢占：恰好一个线程拿到执行权，其余全部失败")
    void concurrentClaimHasExactlyOneWinner() throws Exception {
        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return ledger.claim(KEY, TOOL);
                }));
            }
            int winners = 0;
            for (Future<Boolean> f : futures) {
                if (f.get()) {
                    winners++;
                }
            }
            assertThat(winners).as("并发下必须恰好一个赢家，这是防二次扩容的唯一保证").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(ledger.size()).isEqualTo(1);
    }

    // ---------------------------------------------------------- 错误分类

    @Test
    @DisplayName("数据库故障必须抛出，绝不能被当成「已被抢占」")
    void databaseFailureIsNotReportedAsAlreadyClaimed() {
        // 把连接故障当成「已被抢占」的后果是：工具静默不执行，
        // 现象是「Agent 什么都不做」——比抛异常难查得多。
        DataSource broken = new DataSource() {
            @Override public Connection getConnection() throws SQLException {
                throw new SQLException("connection refused");
            }
            @Override public Connection getConnection(String u, String p) throws SQLException {
                throw new SQLException("connection refused");
            }
            @Override public PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(PrintWriter out) { }
            @Override public void setLoginTimeout(int seconds) { }
            @Override public int getLoginTimeout() { return 0; }
            @Override public Logger getParentLogger() { return null; }
            @Override public <T> T unwrap(Class<T> iface) { return null; }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
        JdbcToolExecutionLedger down = new JdbcToolExecutionLedger(broken);

        assertThatThrownBy(() -> down.claim(KEY, TOOL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("抢占执行权失败");
        assertThatThrownBy(() -> down.resultOf(KEY)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> down.release(KEY)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("构造器拒绝 null DataSource")
    void nullDataSourceRejected() {
        assertThatThrownBy(() -> new JdbcToolExecutionLedger(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------- 建表

    @Test
    @DisplayName("createSchemaIfMissing 幂等，且与 V7 的列定义一致")
    void createSchemaIsIdempotentAndMatchesMigration() throws Exception {
        JdbcToolExecutionLedger fresh = new JdbcToolExecutionLedger(dataSource, "claim_copy");
        fresh.createSchemaIfMissing();
        fresh.createSchemaIfMissing();   // 第二次不应报错

        assertThat(fresh.claim(KEY, TOOL)).isTrue();
        assertThat(fresh.claim(KEY, TOOL)).isFalse();
    }

    @Test
    @DisplayName("V7 的 CHECK 约束真的存在：非法状态被数据库拒绝")
    void migrationCheckConstraintIsReal() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate(
                    "INSERT INTO tool_execution_claim"
                            + " (idempotency_key,tool_name,state,created_at,updated_at)"
                            + " VALUES ('k','t','BOGUS',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)"))
                    .as("V7 的 chk_claim_state 必须在建表语句里生效")
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("V7 里幂等键就是主键——这是抢占语义成立的唯一依据")
    void migrationMakesKeyThePrimaryKey() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            String insert = "INSERT INTO tool_execution_claim"
                    + " (idempotency_key,tool_name,state,created_at,updated_at)"
                    + " VALUES ('dup','t','CLAIMED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)";
            st.executeUpdate(insert);
            assertThatThrownBy(() -> st.executeUpdate(insert))
                    .as("没有主键，claim 就退化成先查后写，并发下必然有窗口")
                    .isInstanceOf(SQLException.class);
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 从 {@code db/migration/V7} 抽出建表语句执行。
     *
     * <p>只取 {@code CREATE TABLE}：{@code COMMENT ON} 是 PostgreSQL 方言，H2 不支持，
     * 而它不影响任何被验证的行为。跳过 {@code CREATE INDEX} 同理。
     */
    private void applyMigrationV7() throws IOException, SQLException {
        String sql = readMigration();
        Matcher m = Pattern.compile("CREATE TABLE IF NOT EXISTS tool_execution_claim\\s*\\((.*?)\\n\\);",
                Pattern.DOTALL).matcher(sql);
        assertThat(m.find()).as("V7 里必须能找到 tool_execution_claim 的建表语句").isTrue();
        String ddl = "CREATE TABLE tool_execution_claim (" + m.group(1) + "\n)";
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute(ddl);
        }
    }

    /** 把某行的 updated_at 改到 minutesAgo 分钟前，模拟"很久没更新"。 */
    private void age(String key, int minutesAgo) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE tool_execution_claim SET updated_at = "
                    + "DATEADD('MINUTE', -" + minutesAgo + ", CURRENT_TIMESTAMP)"
                    + " WHERE idempotency_key='" + key + "'");
        }
    }

    private static String readMigration() throws IOException {
        List<String> candidates = List.of(
                "../db/migration/V7__tool_execution_claim.sql",
                "db/migration/V7__tool_execution_claim.sql");
        for (String candidate : candidates) {
            Path p = Path.of(candidate);
            if (Files.isReadable(p)) {
                return Files.readString(p);
            }
        }
        throw new IOException("找不到 V7 迁移文件，尝试过：" + candidates);
    }
}
