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
    private final List<String> degradedRuleIds = new ArrayList<>();
    private final List<String> disabledRuleIds = new ArrayList<>();
    private int minApprovers = 1;
    private String maxAutonomy = null;

    /**
     * 有规则求值失败时假定的最严审批人数。
     *
     * <p>为什么是 2：那是本规则集里最严的一档要求。
     * 求值失败意味着<b>不知道这条规则本来会要求什么</b>，
     * 而「不知道」在安全判定里只能往严的方向猜。
     */
    public static final int CONSERVATIVE_MIN_APPROVERS = 2;

    /**
     * 有规则求值失败时假定的放权上限。
     *
     * <p>为什么是 S1（建议模式，决策权 100% 在人）：
     * 那正是「系统不确定时该落到哪一档」的语义本身。
     */
    public static final String CONSERVATIVE_AUTONOMY_CAP = "S1";

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

    /**
     * 标记一条规则求值失败，并<b>立即把效果压到保守下限</b>。
     *
     * <p><b>为什么不能只是记一笔警告</b>：本类的效果全是<b>收紧</b>方向的
     * （{@code requireApprovers} 取更大、{@code capAutonomy} 取更严）。
     * 所以一条规则没跑成，等于它那份收紧<b>凭空消失</b>——
     * 结果比真实情况更宽松，而调用方从数值上完全看不出来。
     *
     * <p>只加一条警告字符串是无效的：警告需要有人读，
     * 而「谁来读」在类型上没有任何约束。
     * 把保守下限直接压进效果，调用方就算完全不看警告也拿不到过宽的结论。
     */
    void markDegraded(String ruleId) {
        degradedRuleIds.add(ruleId);
        requireApprovers(CONSERVATIVE_MIN_APPROVERS);
        capAutonomy(CONSERVATIVE_AUTONOMY_CAP);
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

    /** 是否有规则求值失败。<b>true 时本效果是保守下限，不是精确结论。</b> */
    /**
     * 记录一条<b>被人为停用</b>的规则。
     *
     * <p><b>与 {@link #markDegraded(String)} 的区别是这个类里最关键的一条设计：</b>
     * 两者都意味着「这条规则的约束没有施加」，但方向相反——
     * <ul>
     *   <li><b>降级</b>是系统不知道答案，所以<b>压保守下限</b>；</li>
     *   <li><b>停用</b>是人明确说了不要这条约束，所以<b>绝不能压下限</b>——
     *       压了就等于停用功能永远不生效。</li>
     * </ul>
     *
     * <p>反过来也成立：一条求值失败的规则绝不能被记成「已停用」，
     * 否则轨道 C6 堵上的洞会重新打开——失败会伪装成「运维关掉了」。
     * 所以这是两个独立的方法、两个独立的列表，不是一个布尔标志。
     */
    void markDisabled(String ruleId) {
        disabledRuleIds.add(ruleId);
        // 记进警告是为了让「这次求值少了哪条约束」在审计里可见。
        // 但它不构成防线——真正的差别是不压保守下限。
        warn("规则 " + ruleId + " 已被停用，本次求值未施加其约束");
    }

    public boolean degraded() {
        return !degradedRuleIds.isEmpty();
    }

    /** 哪些规则求值失败了。审计里必须能看到「这个约束是猜的」。 */
    /**
     * 哪些规则是被<b>人为停用</b>的。与 {@link #degradedRuleIds()} 严格分开——
     * 前者是运维的决定，后者是系统的故障，混在一起就再也分不出来了。
     */
    public List<String> disabledRuleIds() {
        return Collections.unmodifiableList(disabledRuleIds);
    }

    public List<String> degradedRuleIds() {
        return Collections.unmodifiableList(degradedRuleIds);
    }

    /**
     * 是否产生了任何需要调用方处理的结果。
     *
     * <p><b>降级也算有效果</b>：一条规则失败而没有任何规则触发时，
     * 旧写法返回 false，调用方会读成「没有约束，可以自动执行」——
     * 而那恰恰是最危险的误读。
     */
    public boolean hasEffect() {
        return !firedRuleIds.isEmpty() || degraded();
    }
}
