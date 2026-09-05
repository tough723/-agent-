package com.oncall.tooladmin;

import com.oncall.domain.tool.RiskLevel;
import com.oncall.domain.tool.ToolPolicy;
import com.oncall.domain.tool.ToolSource;
import com.oncall.toolgateway.ToolPolicyEngine;
import com.oncall.toolgateway.governance.ToolPolicyChange;

import java.time.Duration;
import java.util.Locale;

/**
 * 前端提交的工具策略变更请求。
 *
 * <p><b>为什么不直接反序列化成 {@link ToolPolicy}</b>：
 * 那需要 Jackson 处理 {@code Duration}，而 {@code Duration} 的 JSON 形态
 * 取决于有没有注册 JSR310 模块——注册了是 ISO-8601 字符串
 * （{@code "PT15M"}），没注册是数字（秒）。前端不该被迫知道这件事，
 * 而且"少注册一个模块"这种装配差异会让同一个请求体在两个环境里表现不同。
 * 所以这里用 {@code approvalTimeoutSeconds}，由本类负责转成 {@code Duration}。
 *
 * <p>枚举也用字符串接收并显式解析：让 Jackson 直接反序列化枚举的话，
 * 非法值会得到一个 {@code HttpMessageNotReadableException}，
 * 文案是 Jackson 的内部消息，对操作者毫无意义。
 */
public record ToolPolicyChangeRequest(
        String kind,
        String toolName,
        String source,
        String risk,
        Boolean requiresApproval,
        Long approvalTimeoutSeconds,
        Boolean requiresDualApproval,
        String argsJsonSchema,
        String reason
) {

    public ToolPolicyChange toChange() {
        ToolPolicyChange.Kind k = parseKind(kind);
        String name = requireText(toolName, "toolName");

        if (k == ToolPolicyChange.Kind.REVOKE) {
            return ToolPolicyChange.revoke(name);
        }

        ToolSource src = parseSource(source);
        RiskLevel level = parseRisk(risk);
        checkNameMatchesSource(name, src);

        long seconds = approvalTimeoutSeconds == null ? 0L : approvalTimeoutSeconds;
        if (seconds < 0) {
            throw ToolAdminApiException.badRequest("approvalTimeoutSeconds 不能为负数");
        }
        ToolPolicy policy = new ToolPolicy(
                name, src, level,
                Boolean.TRUE.equals(requiresApproval),
                Duration.ofSeconds(seconds),
                Boolean.TRUE.equals(requiresDualApproval),
                (argsJsonSchema == null || argsJsonSchema.isBlank()) ? null : argsJsonSchema);
        return new ToolPolicyChange(k, name, policy);
    }

    // ------------------------------------------------------------------ 解析

    private static ToolPolicyChange.Kind parseKind(String raw) {
        String v = upper(raw, "kind");
        try {
            return ToolPolicyChange.Kind.valueOf(v);
        } catch (IllegalArgumentException e) {
            throw ToolAdminApiException.badRequest(
                    "kind 必须是 GRANT / UPDATE / REVOKE 之一，收到：" + raw);
        }
    }

    private static ToolSource parseSource(String raw) {
        String v = upper(raw, "source");
        try {
            return ToolSource.valueOf(v);
        } catch (IllegalArgumentException e) {
            throw ToolAdminApiException.badRequest(
                    "source 必须是 LOCAL / MCP 之一，收到：" + raw);
        }
    }

    private static RiskLevel parseRisk(String raw) {
        String v = upper(raw, "risk");
        try {
            return RiskLevel.valueOf(v);
        } catch (IllegalArgumentException e) {
            throw ToolAdminApiException.badRequest(
                    "risk 必须是 READ_ONLY / LOW / HIGH 之一，收到：" + raw);
        }
    }

    /**
     * 名字前缀必须与来源一致。
     *
     * <p><b>这是安全边界，不是命名习惯</b>：两个 server 可能都提供 {@code restart}，
     * 前缀是区分"授权了哪一个"的唯一依据。允许 {@code LOCAL} 策略叫
     * {@code mcp:x:y}，就等于用一条本地策略给远端工具背书；
     * 允许 {@code MCP} 策略不带前缀，则纳管结果对不上模型看到的名字，
     * 白名单永远匹配不上，而默认拒绝会让工具静默不可用——
     * 故障现象是"工具不见了"而不是报错。
     */
    private static void checkNameMatchesSource(String name, ToolSource src) {
        boolean prefixed = name.startsWith(ToolPolicyEngine.MCP_PREFIX);
        if (src == ToolSource.MCP && !prefixed) {
            throw ToolAdminApiException.badRequest(
                    "MCP 工具名必须带 " + ToolPolicyEngine.MCP_PREFIX
                            + "<server>: 前缀，收到：" + name);
        }
        if (src == ToolSource.LOCAL && prefixed) {
            throw ToolAdminApiException.badRequest(
                    "LOCAL 工具不得使用 " + ToolPolicyEngine.MCP_PREFIX
                            + " 前缀（那是 MCP 的安全边界），收到：" + name);
        }
    }

    private static String upper(String raw, String field) {
        String v = requireText(raw, field);
        return v.toUpperCase(Locale.ROOT);
    }

    private static String requireText(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw ToolAdminApiException.badRequest(field + " 必填");
        }
        return raw.trim();
    }
}
