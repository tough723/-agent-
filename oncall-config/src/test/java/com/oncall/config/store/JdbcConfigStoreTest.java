package com.oncall.config.store;

import com.oncall.config.ConfigChange;
import com.oncall.config.ConfigService;
import com.oncall.config.ConfigSnapshot;
import com.oncall.config.ConfigTier;
import com.oncall.config.InMemoryConfigAuditLog;
import com.oncall.config.OnCallConfigKeys;
import com.oncall.config.OnCallConfigRegistry;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JDBC 持久化的真实数据库测试。
 *
 * <p>用 H2 内存库跑真 SQL，而不是 mock {@code Connection}——
 * mock 出来的 JDBC 测试只能证明"我以为的 SQL 是我以为的 SQL"，
 * 证明不了语句真的能执行。SQL 写错、列名拼错、方言不兼容，只有真库能抓到。
 */
class JdbcConfigStoreTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private DataSource dataSource;
    private JdbcConfigStore store;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        // 每个用例一个独立内存库，避免用例间相互污染
        ds.setURL("jdbc:h2:mem:cfg" + SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        this.dataSource = ds;
        this.store = new JdbcConfigStore(ds);
        this.store.createSchemaIfMissing();
    }

    // ------------------------------------------------------------ JdbcConfigStore

    @Test
    @DisplayName("建表是幂等的，重复调用不报错")
    void createSchemaIsIdempotent() {
        store.createSchemaIfMissing();
        store.createSchemaIfMissing();
        assertThat(store.revision()).isZero();
    }

    @Test
    @DisplayName("空库读取返回 empty")
    void emptyStoreReturnsEmpty() {
        assertThat(store.get(OnCallConfigKeys.RETRIEVAL_TOP_N)).isEmpty();
        assertThat(store.getAll()).isEmpty();
        assertThat(store.overriddenCount()).isZero();
    }

    @Test
    @DisplayName("写入后可读回")
    void putThenGet() {
        store.put(OnCallConfigKeys.RETRIEVAL_TOP_N, "8");
        assertThat(store.get(OnCallConfigKeys.RETRIEVAL_TOP_N)).contains("8");
        assertThat(store.overriddenCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("重复写同一个键是 upsert，不产生第二行")
    void repeatedPutIsUpsert() {
        store.put(OnCallConfigKeys.RETRIEVAL_TOP_N, "8");
        store.put(OnCallConfigKeys.RETRIEVAL_TOP_N, "9");

        assertThat(store.get(OnCallConfigKeys.RETRIEVAL_TOP_N)).contains("9");
        assertThat(store.getAll()).hasSize(1);
        assertThat(store.overriddenCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("getAll 只返回被覆盖的键，不含默认值")
    void getAllReturnsOnlyOverrides() {
        store.put(OnCallConfigKeys.RETRIEVAL_TOP_N, "8");
        store.put(OnCallConfigKeys.RETRIEVAL_CANDIDATE_SIZE, "30");

        assertThat(store.getAll()).containsOnlyKeys(
                OnCallConfigKeys.RETRIEVAL_TOP_N, OnCallConfigKeys.RETRIEVAL_CANDIDATE_SIZE);
    }

    @Test
    @DisplayName("修订号随每次写入递增")
    void revisionAdvancesOnWrite() {
        long r0 = store.revision();
        store.put(OnCallConfigKeys.RETRIEVAL_TOP_N, "8");
        long r1 = store.revision();
        store.put(OnCallConfigKeys.RETRIEVAL_CANDIDATE_SIZE, "30");
        long r2 = store.revision();

        assertThat(r1).isGreaterThan(r0);
        assertThat(r2).isGreaterThan(r1);
    }

    @Test
    @DisplayName("关键性质：remove 之后修订号不倒退——否则快照缓存会读到旧值")
    void revisionNeverDecreasesAfterRemove() {
        store.put(OnCallConfigKeys.RETRIEVAL_TOP_N, "8");
        long afterPut = store.revision();

        store.remove(OnCallConfigKeys.RETRIEVAL_TOP_N);
        long afterRemove = store.revision();

        assertThat(store.get(OnCallConfigKeys.RETRIEVAL_TOP_N)).isEmpty();
        assertThat(afterRemove).as("墓碑行必须让修订号继续前进").isGreaterThan(afterPut);
    }

    @Test
    @DisplayName("remove 之后该键不再计入覆盖项")
    void removeClearsOverrideCount() {
        store.put(OnCallConfigKeys.RETRIEVAL_TOP_N, "8");
        store.remove(OnCallConfigKeys.RETRIEVAL_TOP_N);
        assertThat(store.overriddenCount()).isZero();
    }

    @Test
    @DisplayName("写回同一个键（墓碑复活）后仍能正确读取")
    void keyCanBeRevivedAfterRemove() {
        store.put(OnCallConfigKeys.RETRIEVAL_TOP_N, "8");
        store.remove(OnCallConfigKeys.RETRIEVAL_TOP_N);
        store.put(OnCallConfigKeys.RETRIEVAL_TOP_N, "12");

        assertThat(store.get(OnCallConfigKeys.RETRIEVAL_TOP_N)).contains("12");
        assertThat(store.getAll()).hasSize(1);
    }

    @Test
    @DisplayName("拒绝 null 写入")
    void rejectsNull() {
        assertThatThrownBy(() -> store.put(null, "v")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.put("k", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.remove(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------ 与 ConfigService 集成

    @Test
    @DisplayName("配置持久化后，新建的 ConfigService 实例能读到——重启不丢配置")
    void configSurvivesRestart() {
        ConfigService before = new ConfigService(OnCallConfigRegistry.create(), store, new InMemoryConfigAuditLog());
        before.set(OnCallConfigKeys.RETRIEVAL_TOP_N, "8", "alice", "召回不足", true);

        // 模拟进程重启：同一个库，全新的 store 与 service 实例
        JdbcConfigStore reopened = new JdbcConfigStore(dataSource);
        ConfigService after = new ConfigService(OnCallConfigRegistry.create(), reopened, new InMemoryConfigAuditLog());

        assertThat(after.getInt(OnCallConfigKeys.RETRIEVAL_TOP_N)).isEqualTo(8);
        assertThat(after.viewsForUi())
                .filteredOn(v -> v.spec().key().equals(OnCallConfigKeys.RETRIEVAL_TOP_N))
                .singleElement()
                .satisfies(v -> assertThat(v.overridden()).isTrue());
    }

    @Test
    @DisplayName("未被覆盖的参数在重启后仍是声明的基线值")
    void untouchedKeysFallBackToDeclaredDefault() {
        store.put(OnCallConfigKeys.RETRIEVAL_TOP_N, "8");
        ConfigService service = new ConfigService(OnCallConfigRegistry.create(), store, new InMemoryConfigAuditLog());

        assertThat(service.getInt(OnCallConfigKeys.RETRIEVAL_CANDIDATE_SIZE)).isEqualTo(20);
        assertThat(service.getDuration(OnCallConfigKeys.TICKET_APPROVAL_TIMEOUT).toMinutes()).isEqualTo(15);
    }

    @Test
    @DisplayName("JDBC store 的修订号变化能让快照缓存正确失效")
    void snapshotInvalidatesOnJdbcWrite() {
        ConfigService service = new ConfigService(OnCallConfigRegistry.create(), store, new InMemoryConfigAuditLog());
        ConfigSnapshot first = service.snapshot();

        service.set(OnCallConfigKeys.RETRIEVAL_TOP_N, "8", "alice", "调大", true);
        ConfigSnapshot second = service.snapshot();

        assertThat(second.revision()).isGreaterThan(first.revision());
        assertThat(second.getInt(OnCallConfigKeys.RETRIEVAL_TOP_N)).isEqualTo(8);
    }

    @Test
    @DisplayName("reset 在 JDBC 上同样恢复基线值")
    void resetRestoresDefaultOnJdbc() {
        ConfigService service = new ConfigService(OnCallConfigRegistry.create(), store, new InMemoryConfigAuditLog());
        service.set(OnCallConfigKeys.RETRIEVAL_TOP_N, "8", "alice", "调大", true);
        service.reset(OnCallConfigKeys.RETRIEVAL_TOP_N, "bob", "回归基线", true);

        assertThat(service.getInt(OnCallConfigKeys.RETRIEVAL_TOP_N)).isEqualTo(5);
        assertThat(store.overriddenCount()).isZero();
    }

    // ------------------------------------------------------------ JdbcConfigAuditLog

    @Test
    @DisplayName("审计变更可落库并按时间正序读回，字段完整")
    void auditRoundTrips() {
        JdbcConfigAuditLog log = newAuditLog();
        ConfigService service = new ConfigService(OnCallConfigRegistry.create(), store, log);

        service.set(OnCallConfigKeys.RETRIEVAL_TOP_N, "8", "alice", "召回不足", true);
        service.set(OnCallConfigKeys.RETRIEVAL_CANDIDATE_SIZE, "30", "bob", "候选太少", true);

        List<ConfigChange> history = log.history(OnCallConfigKeys.RETRIEVAL_TOP_N);
        assertThat(history).hasSize(1);
        ConfigChange c = history.get(0);
        assertThat(c.oldValue()).isEqualTo("5");
        assertThat(c.newValue()).isEqualTo("8");
        assertThat(c.operator()).isEqualTo("alice");
        assertThat(c.reason()).isEqualTo("召回不足");
        assertThat(c.tier()).isEqualTo(ConfigTier.RUNTIME_HOT);
        assertThat(c.timestampMillis()).isPositive();
    }

    @Test
    @DisplayName("最近变更按时间倒序返回，且受 limit 限制")
    void recentIsDescendingAndLimited() {
        JdbcConfigAuditLog log = newAuditLog();
        ConfigService service = new ConfigService(OnCallConfigRegistry.create(), store, log);

        service.set(OnCallConfigKeys.RETRIEVAL_TOP_N, "8", "alice", "1", true);
        service.set(OnCallConfigKeys.RETRIEVAL_CANDIDATE_SIZE, "30", "bob", "2", true);
        service.set(OnCallConfigKeys.RETRIEVAL_RRF_K, "70", "carol", "3", true);

        List<ConfigChange> recent = log.recent(2);
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).key()).isEqualTo(OnCallConfigKeys.RETRIEVAL_RRF_K);
        assertThat(recent.get(1).key()).isEqualTo(OnCallConfigKeys.RETRIEVAL_CANDIDATE_SIZE);
        assertThat(log.recent(0)).isEmpty();
    }

    @Test
    @DisplayName("reset 在审计里表现为 newValue 为 null，可据此还原「谁把它改回了基线」")
    void resetIsAuditedWithNullNewValue() {
        JdbcConfigAuditLog log = newAuditLog();
        ConfigService service = new ConfigService(OnCallConfigRegistry.create(), store, log);

        service.set(OnCallConfigKeys.RETRIEVAL_TOP_N, "8", "alice", "调大", true);
        service.reset(OnCallConfigKeys.RETRIEVAL_TOP_N, "bob", "回归基线", true);

        List<ConfigChange> history = log.history(OnCallConfigKeys.RETRIEVAL_TOP_N);
        assertThat(history).hasSize(2);
        assertThat(history.get(1).newValue()).isNull();
        assertThat(history.get(1).oldValue()).isEqualTo("8");
        assertThat(history.get(1).operator()).isEqualTo("bob");
        assertThat(history.get(1).isReset()).isTrue();
    }

    @Test
    @DisplayName("审计表的建表语句也是幂等的")
    void auditSchemaIsIdempotent() {
        JdbcConfigAuditLog log = newAuditLog();
        log.createSchemaIfMissing();
        assertThat(log.recent(10)).isEmpty();
    }

    private JdbcConfigAuditLog newAuditLog() {
        JdbcConfigAuditLog log = new JdbcConfigAuditLog(dataSource);
        log.createSchemaIfMissing();
        return log;
    }
}
