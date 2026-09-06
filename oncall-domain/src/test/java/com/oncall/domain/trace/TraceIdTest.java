package com.oncall.domain.trace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TraceId} 的验收测试。
 *
 * <p><b>这里守的两条规则都是安全控制，不是风格约定</b>：
 * <ol>
 *   <li>字符集 —— {@code ToolAuditContext.toString()} 会把 traceId 拼进日志行，
 *       所以允许换行就等于允许<b>伪造日志行</b>（包括伪造一条「操作已审批」）。</li>
 *   <li>长度 ≤64 —— 对齐 {@code trace_id VARCHAR(64)}。<b>超长绝不能静默截断</b>：
 *       截断会让两条不同的链路撞上同一个 trace，
 *       症状是「一次排查里混进了另一次的操作」，几乎不可能查出来。</li>
 * </ol>
 */
class TraceIdTest {

    // ------------------------------------------------------------ 铸造

    @Test
    @DisplayName("铸出来的 id 落在安全字符集内且不超列宽")
    void mintProducesSafeValueWithinColumnWidth() {
        for (int i = 0; i < 200; i++) {
            String v = TraceId.mint().value();
            assertThat(v).matches("[A-Za-z0-9._-]+");
            assertThat(v.length()).isLessThanOrEqualTo(TraceId.MAX_LENGTH);
            assertThat(TraceId.rejectionReason(v)).isEmpty();
        }
    }

    @Test
    @DisplayName("铸出来的 id 带前缀——在一大片 UUID 里要能一眼认出来")
    void mintHasPrefix() {
        assertThat(TraceId.mint().value()).startsWith(TraceId.PREFIX);
    }

    @Test
    @DisplayName("连铸 5000 个不重复")
    void mintIsUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            assertThat(seen.add(TraceId.mint().value())).as("第 %d 个重复了", i).isTrue();
        }
    }

    // ------------------------------------------------------------ 采纳上游

    @Test
    @DisplayName("上游给的合法 id 原样采纳——自己另铸会和上游链路断开")
    void adoptAcceptsUpstreamId() {
        assertThat(TraceId.adopt("4bf92f3577b34da6a3ce929d0e0e4736").value())
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(TraceId.adopt("upstream_svc.request-42").value())
                .isEqualTo("upstream_svc.request-42");
    }

    @Test
    @DisplayName("首尾空白修掉——否则同一个 trace 会分裂成两个")
    void adoptTrimsWhitespace() {
        assertThat(TraceId.adopt("  trace-abc  ").value()).isEqualTo("trace-abc");
    }

    @Test
    @DisplayName("null / 空白 → 拒绝")
    void adoptRejectsNullOrBlank() {
        assertThatThrownBy(() -> TraceId.adopt(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("null");
        assertThatThrownBy(() -> TraceId.adopt("   "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("空白");
    }

    @Test
    @DisplayName("超过 64 字符 → 拒绝，而不是截断")
    void adoptRejectsTooLong() {
        String tooLong = "a".repeat(TraceId.MAX_LENGTH + 1);
        assertThatThrownBy(() -> TraceId.adopt(tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64");
        // 正好 64 是允许的：边界不能差一
        assertThat(TraceId.adopt("a".repeat(TraceId.MAX_LENGTH)).value()).hasSize(64);
    }

    @Test
    @DisplayName("★ 换行 → 拒绝：否则控制告警头的人能伪造日志行")
    void adoptRejectsNewline() {
        assertThatThrownBy(() -> TraceId.adopt("trace-abc\n[INFO] 操作已审批 approver=admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法字符");
        assertThatThrownBy(() -> TraceId.adopt("trace\r\nabc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("引号、空格、分号、井号 → 全部拒绝")
    void adoptRejectsPunctuation() {
        for (String bad : new String[]{"trace abc", "trace\"abc", "trace'abc",
                "trace;abc", "trace#abc", "trace,abc", "trace\\abc", "trace/abc"}) {
            assertThat(TraceId.rejectionReason(bad))
                    .as("%s 必须被拒", bad).isNotEmpty();
        }
    }

    // ------------------------------------------------------------ 降级原因

    @Test
    @DisplayName("合法输入时 rejectionReason 为空")
    void rejectionReasonEmptyForValid() {
        assertThat(TraceId.rejectionReason("trace-abc")).isEmpty();
        assertThat(TraceId.rejectionReason("  trace-abc  ")).isEmpty();
    }

    @Test
    @DisplayName("又长又含非法字符时，先报长度——那条原因对排查更有用")
    void rejectionReasonReportsLengthBeforeCharset() {
        String longAndBad = ("a b".repeat(40));      // 120 字符且含空格
        Optional<String> r = TraceId.rejectionReason(longAndBad);
        assertThat(r).isPresent();
        assertThat(r.get()).contains("长度").doesNotContain("非法字符");
    }

    // ------------------------------------------------------------ 值语义

    @Test
    @DisplayName("equals / hashCode 按值；toString 就是那个字符串")
    void valueSemantics() {
        TraceId a = TraceId.adopt("trace-abc");
        TraceId b = TraceId.adopt("trace-abc");
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(TraceId.adopt("trace-other"));
        assertThat(a).isNotEqualTo("trace-abc");
        assertThat(a.toString()).isEqualTo("trace-abc");
    }
}
