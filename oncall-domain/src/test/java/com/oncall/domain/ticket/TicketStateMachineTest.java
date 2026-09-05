package com.oncall.domain.ticket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 状态机测试。
 *
 * <p>这些用例对应评审报告 F6 的验收标准，尤其是"任何等待人工的状态都必须有超时出口"——
 * 原方案全文检索"超时"命中 0 次，是必须补的洞。
 */
class TicketStateMachineTest {

    @Test
    @DisplayName("正常成功路径：NEW → ... → KNOWLEDGE_INDEXED 全程可走通")
    void happyPath() {
        TicketStatus s = TicketStatus.NEW;
        s = TicketStateMachine.next(s, TicketEvent.ACK);
        assertThat(s).isEqualTo(TicketStatus.ACK);
        s = TicketStateMachine.next(s, TicketEvent.START_AI);
        assertThat(s).isEqualTo(TicketStatus.INVESTIGATING);
        s = TicketStateMachine.next(s, TicketEvent.ROOT_CAUSE_FOUND);
        assertThat(s).isEqualTo(TicketStatus.DIAGNOSED);
        s = TicketStateMachine.next(s, TicketEvent.PROPOSE_ACTION);
        assertThat(s).isEqualTo(TicketStatus.PENDING_APPROVAL);
        s = TicketStateMachine.next(s, TicketEvent.APPROVED);
        assertThat(s).isEqualTo(TicketStatus.EXECUTING);
        s = TicketStateMachine.next(s, TicketEvent.EXEC_SUCCESS);
        assertThat(s).isEqualTo(TicketStatus.RESOLVED);
        s = TicketStateMachine.next(s, TicketEvent.START_REVIEW);
        assertThat(s).isEqualTo(TicketStatus.REVIEW);
        s = TicketStateMachine.next(s, TicketEvent.KB_INDEXED);
        assertThat(s).isEqualTo(TicketStatus.KNOWLEDGE_INDEXED);
        assertThat(s.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("失败路径：EXECUTING 失败 → EXEC_FAILED → 补偿 → 转人工")
    void failurePathWithCompensation() {
        TicketStatus s = TicketStateMachine.next(TicketStatus.EXECUTING, TicketEvent.EXEC_FAILURE);
        assertThat(s).isEqualTo(TicketStatus.EXEC_FAILED);

        s = TicketStateMachine.next(s, TicketEvent.COMPENSATED);
        assertThat(s).isEqualTo(TicketStatus.MANUAL_HANDLING);
    }

    @Test
    @DisplayName("关键修正：审批超时必须有出口，不能永久卡在 PENDING_APPROVAL")
    void approvalTimeoutMustEscalate() {
        TicketStatus s = TicketStateMachine.next(TicketStatus.PENDING_APPROVAL, TicketEvent.APPROVAL_TIMEOUT);
        assertThat(s).isEqualTo(TicketStatus.ESCALATED);

        // 且 PENDING_APPROVAL 必须配置了超时上限
        assertThat(TicketStateMachine.timeoutOf(TicketStatus.PENDING_APPROVAL))
                .contains(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("关键修正：EXECUTING 卡住必须有超时出口")
    void executingTimeoutMustFail() {
        assertThat(TicketStateMachine.next(TicketStatus.EXECUTING, TicketEvent.EXEC_TIMEOUT))
                .isEqualTo(TicketStatus.EXEC_FAILED);
        assertThat(TicketStateMachine.timeoutOf(TicketStatus.EXECUTING)).isPresent();
    }

    @Test
    @DisplayName("非法转移必须抛异常（防脏状态）")
    void illegalTransitionThrows() {
        // 已解决的工单不能回到排查中
        assertThatThrownBy(() -> TicketStateMachine.next(TicketStatus.RESOLVED, TicketEvent.START_AI))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("RESOLVED")
                .hasMessageContaining("START_AI");

        // 未审批不能直接执行
        assertThatThrownBy(() -> TicketStateMachine.next(TicketStatus.DIAGNOSED, TicketEvent.APPROVED))
                .isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    @DisplayName("tryNext 不抛异常，供 UI 展示可用动作")
    void tryNextIsNonThrowing() {
        assertThat(TicketStateMachine.tryNext(TicketStatus.RESOLVED, TicketEvent.START_AI)).isEmpty();
        assertThat(TicketStateMachine.tryNext(TicketStatus.NEW, TicketEvent.ACK))
                .contains(TicketStatus.ACK);
        assertThat(TicketStateMachine.canFire(TicketStatus.NEW, TicketEvent.ACK)).isTrue();
        assertThat(TicketStateMachine.canFire(TicketStatus.NEW, TicketEvent.KB_INDEXED)).isFalse();
    }

    @Test
    @DisplayName("架构自检：PENDING_APPROVAL 必须有超时出口")
    void pendingApprovalMustHaveTimeout() {
        // 这是硬要求：审批人不在线时，工单不能永久卡死而告警还在烧。
        assertThat(TicketStateMachine.timeoutOf(TicketStatus.PENDING_APPROVAL))
                .as("PENDING_APPROVAL 没有超时出口会导致工单永久卡死")
                .isPresent();
    }

    @Test
    @DisplayName("状态枚举必须覆盖状态机图里的全部状态（含原方案漏掉的两个）")
    void statusEnumCoversDiagram() {
        assertThat(TicketStatus.valueOf("MANUAL_HANDLING")).isNotNull();
        assertThat(TicketStatus.valueOf("KNOWLEDGE_INDEXED")).isNotNull();
        assertThat(TicketStatus.valueOf("EXEC_FAILED")).isNotNull();
    }
}
