package com.oncall.toolgateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内存账本的语义测试。
 *
 * <p><b>为什么内存实现也要单独测</b>：它与 {@link JdbcToolExecutionLedger}
 * 必须逐条语义一致，否则「本地跑通了、上了多实例就出事」。
 * 尤其是两条容易被写歪的：
 * <ul>
 *   <li>{@code claim} 必须是原子的（{@code putIfAbsent}，不是 get-then-put）；</li>
 *   <li>{@code release} 必须只删 {@code CLAIMED} 的行。</li>
 * </ul>
 * 第二条如果写成无条件 remove，一次迟到的失败回调就会删掉已完成的记录，
 * 下次重试真的会再执行一遍 —— 而这在单实例的正常路径上完全看不出来。
 */
class InMemoryToolExecutionLedgerTest {

    private static final String KEY = "run-1|1|scale_replicas|a1b2";
    private static final String TOOL = "scale_replicas";

    private final InMemoryToolExecutionLedger ledger = new InMemoryToolExecutionLedger();

    @Test
    @DisplayName("同一键只能抢占一次，抢占期间没有可重放的结果")
    void claimIsExclusiveAndHasNoResultUntilCompleted() {
        assertThat(ledger.claim(KEY, TOOL)).isTrue();
        assertThat(ledger.claim(KEY, TOOL)).isFalse();
        assertThat(ledger.isCompleted(KEY)).isFalse();
        assertThat(ledger.resultOf(KEY)).as("未完成的行不能返回结果").isNull();
        assertThat(ledger.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("完成后结果可重放")
    void completedResultIsReplayable() {
        ledger.claim(KEY, TOOL);
        ledger.complete(KEY, "scaled");

        assertThat(ledger.isCompleted(KEY)).isTrue();
        assertThat(ledger.resultOf(KEY)).isEqualTo("scaled");
    }

    @Test
    @DisplayName("release 只删 CLAIMED：迟到的失败回调不能删掉已完成的记录")
    void releaseKeepsCompletedRows() {
        ledger.claim(KEY, TOOL);
        ledger.complete(KEY, "scaled");

        ledger.release(KEY);

        assertThat(ledger.size()).as("与 JDBC 版的 state='CLAIMED' 条件保持一致").isEqualTo(1);
        assertThat(ledger.resultOf(KEY)).isEqualTo("scaled");
    }

    @Test
    @DisplayName("release 之后可以重新抢占（失败必须可重试）")
    void releaseAllowsRetry() {
        ledger.claim(KEY, TOOL);
        ledger.release(KEY);

        assertThat(ledger.size()).isZero();
        assertThat(ledger.claim(KEY, TOOL)).isTrue();
    }

    @Test
    @DisplayName("complete 在抢占行不存在时仍然记录结果")
    void completeWithoutPriorClaimStillRecords() {
        // 对应 JDBC 版「UPDATE 影响 0 行时改用 INSERT」的分支：
        // 抢占行可能已被清理任务当作崩溃残留删掉，此时结果仍必须落库。
        ledger.complete(KEY, "scaled");

        assertThat(ledger.isCompleted(KEY)).isTrue();
        assertThat(ledger.resultOf(KEY)).isEqualTo("scaled");
    }

    @Test
    @DisplayName("并发抢占：恰好一个赢家")
    void concurrentClaimHasExactlyOneWinner() throws Exception {
        int threads = 16;
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
            assertThat(winners).as("putIfAbsent 必须保证唯一赢家").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
