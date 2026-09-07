package com.oncall.alert;

import com.oncall.domain.alert.AlertEvent;
import com.oncall.domain.alert.AlertGroup;
import com.oncall.domain.alert.AlertStatus;
import com.oncall.domain.autonomy.AlertSeverity;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JdbcAlertStore} 对真实 PostgreSQL 的验证。
 *
 * <p>必须连真实库，有三个理由：
 * ① 两张表都是 {@code PARTITION BY RANGE}，H2 的方言兼容性是赌注；
 * ② {@code raw_payload} 是 {@code JSONB}，「非法 JSON 会被拒绝」这件事
 *    只有真库能证明——领域层刻意不做这个校验（零依赖，没有解析器）；
 * ③ 事务原子性要在真的会回滚的地方才算验过。
 */
@DisplayName("JdbcAlertStore：分区表落库、事务原子性与计数不漂移")
class JdbcAlertStoreTest {

    private static final String PG_URL_ENV = "ONCALL_TEST_PG_URL";
    private static final Instant T0 = Instant.parse("2026-09-07T03:00:00Z");

    private PGSimpleDataSource dataSource;
    private JdbcAlertStore store;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv(PG_URL_ENV);
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "未设置 " + PG_URL_ENV + "，跳过真实数据库验证（本地正常；CI 里必须设置）");

        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(url);
        applyMigrationV3(dataSource);
        store = new JdbcAlertStore(dataSource);
    }

    /** 两张表都是分区表，DROP 父表用 CASCADE 一并带走 default 分区。 */
    private static void applyMigrationV3(javax.sql.DataSource ds) throws Exception {
        String ddl = readV3();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS alert_event CASCADE");
            st.execute("DROP TABLE IF EXISTS alert_group CASCADE");
            for (String stmt : ddl.split(";")) {
                if (!stmt.isBlank()) {
                    st.execute(stmt);
                }
            }
        }
    }

    private static String readV3() throws Exception {
        for (String p : new String[] {"db/migration/V3__alert.sql",
                "../db/migration/V3__alert.sql"}) {
            if (Files.exists(Path.of(p))) {
                return Files.readString(Path.of(p));
            }
        }
        throw new IllegalStateException("找不到 V3__alert.sql——"
                + "本测试必须用迁移脚本原文建表，不接受在 Java 里复制一份 DDL");
    }

    private static AlertGroup group(String id) {
        return AlertGroup.open(id, "fp-" + id, "order-service", AlertSeverity.P2, T0);
    }

    private static AlertEvent event(String id, String groupId, Instant fired) {
        return new AlertEvent(id, groupId, "prometheus",
                "{\"alertname\":\"HighLatency\",\"value\":0.97}",
                "{\"team\":\"trade\"}", AlertSeverity.P2, fired, fired.plusSeconds(2));
    }

    @Test
    @DisplayName("★ ingest 一次：事件落库 + 计数 +1 + 最后出现推进，三者同时发生")
    void ingestBumpsCountAndLastSeenAtomically() {
        AlertGroup g = group("grp-1");
        store.insertGroup(g);

        assertThat(store.ingest(g, event("ev-1", "grp-1", T0.plusSeconds(60)))).isTrue();

        AlertGroup back = store.findGroup("grp-1", T0).orElseThrow();
        // 组本身的 event_count 是 1（open 时那条），ingest 又加一条 → 2
        assertThat(back.eventCount()).isEqualTo(2);
        assertThat(back.lastSeenAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(back.firstSeenAt()).as("第一次出现不被后来的事件改掉").isEqualTo(T0);
        // ★ 计数器与真实行数必须一致——聚合率就压在这个相等上
        assertThat(store.countEventsInGroup("grp-1")).isEqualTo(back.eventCount());
    }

    @Test
    @DisplayName("★★ 重复投递返回 false 且计数不动——否则聚合率会被算低，与真实情况相反")
    void duplicateDeliveryDoesNotBumpCount() {
        AlertGroup g = group("grp-1");
        store.insertGroup(g);
        assertThat(store.ingest(g, event("ev-1", "grp-1", T0.plusSeconds(60)))).isTrue();

        // 同一条告警被上游重投：id 相同。
        assertThat(store.ingest(g, event("ev-1", "grp-1", T0.plusSeconds(60))))
                .as("重复投递必须返回 false")
                .isFalse();

        AlertGroup back = store.findGroup("grp-1", T0).orElseThrow();
        assertThat(back.eventCount()).as("计数不得因重复投递而增加").isEqualTo(2);
        assertThat(store.countEventsInGroup("grp-1")).isEqualTo(2);
    }

    @Test
    @DisplayName("★★ 组不存在时必须回滚——没有外键，否则会留下孤儿事件")
    void missingGroupRollsBackTheEvent() {
        AlertGroup ghost = group("grp-ghost");
        // 刻意不 insertGroup：alert_event.group_id 没有外键，数据库不会替我们拦。
        assertThatThrownBy(() -> store.ingest(ghost, event("ev-1", "grp-ghost", T0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("接入告警事件失败");

        // ★ 事务必须真的回滚：不能留下一条挂在不存在组上的事件。
        assertThat(store.countEventsInGroup("grp-ghost"))
                .as("回滚后不该留下孤儿事件")
                .isZero();
    }

    @Test
    @DisplayName("★ 非法 JSON 由 PostgreSQL 拒绝——领域层刻意不校验（零依赖，无解析器）")
    void invalidJsonIsRejectedByTheDatabase() {
        AlertGroup g = group("grp-1");
        store.insertGroup(g);

        AlertEvent bad = new AlertEvent("ev-bad", "grp-1", "prometheus",
                "{这不是合法的 JSON", null, AlertSeverity.P2, T0, T0.plusSeconds(1));
        assertThatThrownBy(() -> store.ingest(g, bad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("接入告警事件失败");

        // 回滚后组不该被递增
        assertThat(store.findGroup("grp-1", T0).orElseThrow().eventCount()).isEqualTo(1);
        assertThat(store.countEventsInGroup("grp-1")).isZero();
    }

    @Test
    @DisplayName("事件与组不一致时立刻拒绝——没有外键，这条只能由代码守")
    void mismatchedGroupAndEventIsRejected() {
        AlertGroup g = group("grp-1");
        store.insertGroup(g);
        assertThatThrownBy(() -> store.ingest(g, event("ev-1", "grp-OTHER", T0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有外键");
        assertThat(store.countEventsInGroup("grp-1")).isZero();
    }

    @Test
    @DisplayName("★ 组 9 列与事件 8 列逐列往返；labels 与 run_id 可空写 SQL NULL")
    void roundTripsAllColumns() {
        AlertGroup g = group("grp-1");
        store.insertGroup(g);
        store.ingest(g, event("ev-1", "grp-1", T0.plusSeconds(5)));

        AlertGroup back = store.findGroup("grp-1", T0).orElseThrow();
        assertThat(back.id()).isEqualTo("grp-1");
        assertThat(back.fingerprint()).isEqualTo("fp-grp-1");
        assertThat(back.service()).isEqualTo("order-service");
        assertThat(back.severity()).isEqualTo(AlertSeverity.P2);
        assertThat(back.status()).isEqualTo(AlertStatus.OPEN);
        assertThat(back.runId()).isNull();
        assertThat(store.findGroup("grp-1", T0.plusSeconds(1)))
                .as("主键是 (id, first_seen_at)，时刻不对就查不到")
                .isEmpty();

        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT source, raw_payload::text, labels::text, severity,"
                             + " fired_at, received_at FROM alert_event WHERE id='ev-1'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("source")).isEqualTo("prometheus");
            assertThat(rs.getString("raw_payload")).contains("HighLatency");
            assertThat(rs.getString("labels")).contains("trade");
            assertThat(rs.getString("severity")).isEqualTo("P2");
            assertThat(rs.getTimestamp("received_at").toInstant())
                    .isEqualTo(T0.plusSeconds(7));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("updateGroupStatus 只改状态；命中 0 行必须抛出来")
    void updateStatusOnlyTouchesStatus() {
        AlertGroup g = group("grp-1");
        store.insertGroup(g);
        store.ingest(g, event("ev-1", "grp-1", T0.plusSeconds(60)));

        store.updateGroupStatus(g.withStatus(AlertStatus.ACKED));
        AlertGroup back = store.findGroup("grp-1", T0).orElseThrow();
        assertThat(back.status()).isEqualTo(AlertStatus.ACKED);
        assertThat(back.eventCount()).as("状态流转不得动计数").isEqualTo(2);
        assertThat(back.lastSeenAt()).isEqualTo(T0.plusSeconds(60));

        assertThatThrownBy(() -> store.updateGroupStatus(group("grp-nope")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("命中 0 行");
    }
}
