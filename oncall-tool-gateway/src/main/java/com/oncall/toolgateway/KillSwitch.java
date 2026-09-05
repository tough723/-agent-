package com.oncall.toolgateway;

import com.oncall.domain.tool.RiskLevel;
import com.oncall.domain.tool.ToolDeniedException;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 全局降级开关。
 *
 * <p>{@code mode} 用 {@link AtomicReference} 持有，配置中心热更新后立即生效——
 * 不能依赖重启，因为出问题时最不能等的就是重启。
 */
public class KillSwitch {

    private final AtomicReference<RunMode> mode = new AtomicReference<>(RunMode.FULL);

    public RunMode mode() {
        return mode.get();
    }

    public void set(RunMode newMode) {
        mode.set(newMode);
    }

    /**
     * 工具执行前的第一道闸。
     *
     * @throws ToolDeniedException 当前模式不允许该风险等级的工具
     */
    public void assertAllowed(String toolName, RiskLevel risk) {
        RunMode current = mode.get();
        if (current == RunMode.OFF) {
            throw new ToolDeniedException(toolName, "agent disabled: mode=OFF");
        }
        if (current == RunMode.READ_ONLY && risk != RiskLevel.READ_ONLY) {
            throw new ToolDeniedException(toolName, "write tool blocked: mode=READ_ONLY");
        }
    }
}
