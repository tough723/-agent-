package com.oncall.ontology.rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一条规则触发后产生的效果。
 *
 * <p>效果是**累加**的，不是覆盖的：多条规则同时触发时，
 * 取更严格的那一档。规则之间不做优先级仲裁——
 * 优先级需要有人维护仲裁表，而「更严格优先」不需要。
 */
public final class RuleEffect {

    private final List<String> firedRuleIds = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private int minApprovers = 1;
    private String maxAutonomy = null;

    public static RuleEffect none() {
        return new RuleEffect();
    }

    public void requireApprovers(int n) {
        minApprovers = Math.max(minApprovers, n);
    }

    /** 放权级别上限，形如 {@code S1}。多条规则时取更严格（字母序更小）的那个。 */
    public void capAutonomy(String level) {
        if (level == null) {
            return;
        }
        if (maxAutonomy == null || level.compareTo(maxAutonomy) < 0) {
            maxAutonomy = level;
        }
    }

    public void warn(String message) {
        warnings.add(message);
    }

    void markFired(String ruleId) {
        firedRuleIds.add(ruleId);
    }

    public int minApprovers() {
        return minApprovers;
    }

    /** 放权上限；{@code null} 表示无限制。 */
    public String maxAutonomy() {
        return maxAutonomy;
    }

    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    /** 哪些规则触发了。审计里必须能看到「为什么要求两人审批」。 */
    public List<String> firedRuleIds() {
        return Collections.unmodifiableList(firedRuleIds);
    }

    public boolean hasEffect() {
        return !firedRuleIds.isEmpty();
    }
}
