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
 *
 * <h2>★ 本类的核心断言是一条不变量，不是一个数字</h2>
 * <p>{@code event_count == countEventsInGroup(...)} 在<b>每一步之后</b>都要成立。
 * 上一版实现第一次跑就红在这条上（计数 2、真实行数 1）：
 * 当时的 {@code insertGroup} 会建一个计数为 1 却没有对应事件行的组。
 * <b>是这条断言抓到了实现，不是断言写错了</b>——所以它被刻意保留成
 * 「计数器 vs 真实行数」的形式，而不是「计数应当等于 2」这种写死的期望值。
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

    /** 组描述：首末出现都取本条事件的 firedAt，计数 1。 */
    private static AlertGroup descriptor(String id, Instant firedAt) {
        return AlertGroup.open(id, "fp-" + id, "order-service", AlertSeverity.P2, firedAt);
    }

    private static AlertEvent event(String id, String groupId, Instant fired) {
        return new AlertEvent(id, groupId, "prometheus",
                "{\"alertname\":\"HighLatency\",\"value\":0.97}",
                "{\"team\":\"trade\"}", AlertSeverity.P2, fired, fired.plusSeconds(2));
    }

    /** ★ 核心不变量：计数器必须等于真实行数。每一步之后都要成立。 */
    private void assertCountMatchesRealRows(String groupId) {
        AlertGroup g = store.findGroup(groupId).orElseThrow();
        assertThat(g.eventCount())
                .as("event_count 必须等于真实事件行数——聚合率就压在这个相等上")
                .isEqualTo(store.countEventsInGroup(groupId));
    }

    @Test
    @DisplayName("★ 第一条事件创建组：计数 1、首末出现同为该事件时刻，且计数 == 真实行数")
    void firstEventCreatesTheGroup() {
        Instant fired = T0.plusSeconds(60);
        assertThat(store.ingest(descriptor("grp-1", fired), event("ev-1", "grp-1", fired)))
                .isTrue();

        AlertGroup back = store.findGroup("grp-1").orElseThrow();
        assertThat(back.eventCount()).isEqualTo(1);
        assertThat(back.firstSeenAt()).isEqualTo(fired);
        assertThat(back.lastSeenAt()).isEqualTo(fired);
        assertThat(back.status()).isEqualTo(AlertStatus.OPEN);
        assertThat(store.countEventsInGroup("grp-1")).isEqualTo(1);
        assertCountMatchesRealRows("grp-1");
    }

    @Test
    @DisplayName("★ 后续事件让计数与最后出现一起推进，且计数始终 == 真实行数")
    void subsequentEventsBumpCountAndLastSeen() {
        Instant f1 = T0.plusSeconds(60);
        Instant f2 = T0.plusSeconds(180);
        store.ingest(descriptor("grp-1", f1), event("ev-1", "grp-1", f1));
        // 第二条事件时组已存在，描述里的时刻不会被采用——调用方通常不知道组最初何时出现。
        assertThat(store.ingest(descriptor("grp-1", f2), event("ev-2", "grp-1", f2))).isTrue();

        AlertGroup back = store.findGroup("grp-1").orElseThrow();
        assertThat(back.eventCount()).isEqualTo(2);
        assertThat(back.firstSeenAt()).as("第一次出现是历史事实，不被后来的事件改掉").isEqualTo(f1);
        assertThat(back.lastSeenAt()).isEqualTo(f2);
        assertCountMatchesRealRows("grp-1");
    }

    @Test
    @DisplayName("★★ 重复投递返回 false 且计数不动——否则聚合率会被算低，与真实情况相反")
    void duplicateDeliveryDoesNotBumpCount() {
        Instant fired = T0.plusSeconds(60);
        store.ingest(descriptor("grp-1", fired), event("ev-1", "grp-1", fired));

        assertThat(store.ingest(descriptor("grp-1", fired), event("ev-1", "grp-1", fired)))
                .as("同一条告警被上游重投，必须返回 false")
                .isFalse();

        assertThat(store.findGroup("grp-1").orElseThrow().eventCount())
                .as("计数不得因重复投递而增加").isEqualTo(1);
        assertThat(store.countEventsInGroup("grp-1")).isEqualTo(1);
        assertCountMatchesRealRows("grp-1");
    }

    @Test
    @DisplayName("★ 非法 JSON 由 PostgreSQL 拒绝，且回滚后组也不会被创建")
    void invalidJsonIsRejectedByTheDatabase() {
        AlertEvent bad = new AlertEvent("ev-bad", "grp-1", "prometheus",
                "{这不是合法的 JSON", null, AlertSeverity.P2, T0, T0.plusSeconds(1));
        assertThatThrownBy(() -> store.ingest(descriptor("grp-1", T0), bad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("接入告警事件失败");

        // ★ 事务必须真的回滚：既不留事件，也不留一个空组。
        assertThat(store.countEventsInGroup("grp-1")).isZero();
        assertThat(store.findGroup("grp-1")).as("回滚后不该留下空组").isEmpty();
    }

    @Test
    @DisplayName("事件与组不一致时立刻拒绝——没有外键，这条只能由代码守")
    void mismatchedGroupAndEventIsRejected() {
        assertThatThrownBy(() -> store.ingest(descriptor("grp-1", T0),
                event("ev-1", "grp-OTHER", T0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有外键");
        assertThat(store.countEventsInGroup("grp-1")).isZero();
        assertThat(store.findGroup("grp-1")).isEmpty();
    }

    @Test
    @DisplayName("★ 调用方给的计数不被采用——计数只能由事件的存在性驱动")
    void callerSuppliedCountIsRejected() {
        // 造一个 eventCount=3 的「组描述」：上一版会照单全收，于是漂移。
        AlertGroup lying = new AlertGroup("grp-1", "fp-1", "order-service", AlertSeverity.P2,
                AlertStatus.OPEN, 3, T0, T0, null);
        assertThatThrownBy(() -> store.ingest(lying, event("ev-1", "grp-1", T0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("计数只能由事件的存在性驱动");
        assertThat(store.countEventsInGroup("grp-1")).isZero();
    }

    @Test
    @DisplayName("组 9 列与事件 8 列逐列往返；labels 与 run_id 可空写 SQL NULL")
    void roundTripsAllColumns() {
        Instant fired = T0.plusSeconds(5);
        store.ingest(descriptor("grp-1", fired), event("ev-1", "grp-1", fired));

        AlertGroup back = store.findGroup("grp-1").orElseThrow();
        assertThat(back.id()).isEqualTo("grp-1");
        assertThat(back.fingerprint()).isEqualTo("fp-grp-1");
        assertThat(back.service()).isEqualTo("order-service");
        assertThat(back.severity()).isEqualTo(AlertSeverity.P2);
        assertThat(back.runId()).isNull();

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
                    .isEqualTo(fired.plusSeconds(2));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertCountMatchesRealRows("grp-1");
    }

    @Test
    @DisplayName("updateGroupStatus 只改状态；命中 0 行必须抛出来")
    void updateStatusOnlyTouchesStatus() {
        Instant fired = T0.plusSeconds(60);
        store.ingest(descriptor("grp-1", fired), event("ev-1", "grp-1", fired));
        store.ingest(descriptor("grp-1", fired), event("ev-2", "grp-1", fired.plusSeconds(30)));

        store.updateGroupStatus("grp-1", AlertStatus.ACKED);
        AlertGroup back = store.findGroup("grp-1").orElseThrow();
        assertThat(back.status()).isEqualTo(AlertStatus.ACKED);
        assertThat(back.eventCount()).as("状态流转不得动计数").isEqualTo(2);
        assertThat(back.lastSeenAt()).isEqualTo(fired.plusSeconds(30));
        assertCountMatchesRealRows("grp-1");

        assertThatThrownBy(() -> store.updateGroupStatus("grp-nope", AlertStatus.RESOLVED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("命中 0 行");
    }
}
