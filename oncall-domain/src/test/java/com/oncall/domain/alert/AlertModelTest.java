package com.oncall.domain.alert;

import com.oncall.domain.autonomy.AlertSeverity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 告警领域模型的构造期不变量。纯领域测试，不碰数据库。
 */
@DisplayName("AlertGroup / AlertEvent：聚合计数与接入延迟")
class AlertModelTest {

    private static final Instant T0 = Instant.parse("2026-09-07T03:00:00Z");

    private static AlertGroup group() {
        return AlertGroup.open("grp-1", "fp-abc", "order-service", AlertSeverity.P2, T0);
    }

    private static AlertEvent event(String id, String groupId, Instant fired) {
        return new AlertEvent(id, groupId, "prometheus",
                "{\"alertname\":\"HighLatency\"}", null, AlertSeverity.P2,
                fired, fired.plusSeconds(2));
    }

    @Test
    @DisplayName("open() 产出 OPEN、计数 1、首末出现同刻")
    void openProducesAFreshGroup() {
        AlertGroup g = group();
        assertThat(g.status()).isEqualTo(AlertStatus.OPEN);
        assertThat(g.eventCount()).isEqualTo(1);
        assertThat(g.firstSeenAt()).isEqualTo(T0);
        assertThat(g.lastSeenAt()).isEqualTo(T0);
        assertThat(g.needsAttention()).isTrue();
    }

    @Test
    @DisplayName("★ absorb() 是唯一能加计数的入口，且必须同时推进最后出现")
    void absorbMovesCountAndLastSeenTogether() {
        AlertGroup after = group().absorb(T0.plusSeconds(60));
        assertThat(after.eventCount()).isEqualTo(2);
        assertThat(after.lastSeenAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(after.firstSeenAt()).as("第一次出现是历史事实，不能被后来的事件改掉")
                .isEqualTo(T0);
        assertThat(group().absorb(T0.plusSeconds(60)).absorb(T0.plusSeconds(120)).eventCount())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("★ 乱序到达的事件不得把「最后出现」往回推")
    void absorbRefusesOutOfOrderEvent() {
        AlertGroup g = group().absorb(T0.plusSeconds(60));
        assertThatThrownBy(() -> g.absorb(T0.plusSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("往回推");
    }

    @Test
    @DisplayName("计数至少为 1——不含任何事件的聚合组不该存在")
    void rejectsZeroEventCount() {
        assertThatThrownBy(() -> new AlertGroup("g", "fp", null, AlertSeverity.P3,
                AlertStatus.OPEN, 0, T0, T0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少为 1");
    }

    @Test
    @DisplayName("lastSeenAt 不得早于 firstSeenAt")
    void rejectsLastBeforeFirst() {
        assertThatThrownBy(() -> new AlertGroup("g", "fp", null, AlertSeverity.P3,
                AlertStatus.OPEN, 1, T0, T0.minusSeconds(1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("早于");
    }

    @Test
    @DisplayName("withStatus 只改状态，不动计数与时刻")
    void withStatusKeepsAggregation() {
        AlertGroup acked = group().absorb(T0.plusSeconds(30)).withStatus(AlertStatus.ACKED);
        assertThat(acked.status()).isEqualTo(AlertStatus.ACKED);
        assertThat(acked.eventCount()).isEqualTo(2);
        assertThat(acked.lastSeenAt()).isEqualTo(T0.plusSeconds(30));
        assertThat(acked.needsAttention()).isTrue();
        assertThat(acked.withStatus(AlertStatus.RESOLVED).needsAttention()).isFalse();
    }

    @Test
    @DisplayName("★ attachRun 拒绝改绑——否则事后无法回答「第一次是谁接的」")
    void attachRunRefusesRebinding() {
        AlertGroup bound = group().attachRun("run-1");
        assertThat(bound.runId()).isEqualTo("run-1");
        assertThatThrownBy(() -> bound.attachRun("run-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不允许改绑");
    }

    @Test
    @DisplayName("列宽一律拒绝而不是截断——截断会让 fingerprint 把两个告警聚成一组")
    void rejectsOversizedColumns() {
        assertThatThrownBy(() -> AlertGroup.open("g".repeat(65), "fp", null, AlertSeverity.P3, T0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("绝不截断");
        assertThatThrownBy(() -> AlertGroup.open("g", "f".repeat(129), null, AlertSeverity.P3, T0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fingerprint");
        assertThatThrownBy(() -> AlertGroup.open("g", "fp", "s".repeat(192), AlertSeverity.P3, T0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("service");
    }

    @Test
    @DisplayName("AlertStatus 四个值转录自 DDL 列注释；只有 OPEN/ACKED 需人处理")
    void alertStatusMatchesDdlComment() {
        assertThat(AlertStatus.values()).hasSize(4);
        assertThat(AlertStatus.OPEN.needsAttention()).isTrue();
        assertThat(AlertStatus.ACKED.needsAttention()).isTrue();
        assertThat(AlertStatus.RESOLVED.needsAttention()).isFalse();
        assertThat(AlertStatus.SUPPRESSED.needsAttention()).isFalse();
    }

    @Test
    @DisplayName("rawPayload 空白即拒绝——它能过 NOT NULL 却会在重放时失败")
    void rejectsBlankRawPayload() {
        assertThatThrownBy(() -> new AlertEvent("e", "grp-1", "prometheus", "   ",
                null, AlertSeverity.P2, T0, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重放");
        assertThatThrownBy(() -> new AlertEvent("e", "grp-1", "prometheus", "{}",
                "  ", AlertSeverity.P2, T0, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("labels");
    }

    @Test
    @DisplayName("★ 源端时钟倒挂要被记下来，而不是拒绝这条告警")
    void clockSkewIsRecordedNotRejected() {
        // 本地收到时刻早于源头发生时刻：源端时钟快了几分钟。
        AlertEvent skewed = new AlertEvent("e", "grp-1", "prometheus", "{}",
                null, AlertSeverity.P2, T0, T0.minusSeconds(180));
        assertThat(skewed.hasClockSkew()).isTrue();
        assertThat(skewed.ingestionLag()).isEqualTo(Duration.ofSeconds(-180));

        AlertEvent normal = event("e2", "grp-1", T0);
        assertThat(normal.hasClockSkew()).isFalse();
        assertThat(normal.ingestionLag()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("事件的列宽同样拒绝截断——截断的 group_id 会把事件挂到别的组")
    void eventRejectsOversizedColumns() {
        assertThatThrownBy(() -> new AlertEvent("e".repeat(65), "grp-1", "prometheus",
                "{}", null, AlertSeverity.P2, T0, T0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("绝不截断");
        assertThatThrownBy(() -> new AlertEvent("e", "g".repeat(65), "prometheus",
                "{}", null, AlertSeverity.P2, T0, T0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("groupId");
    }
}
