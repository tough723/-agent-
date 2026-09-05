package com.oncall.config.schema;

import com.oncall.config.ConfigService;
import com.oncall.config.ConfigSpec;

import java.util.List;
import java.util.Map;

/**
 * 把配置声明导出成前端可直接渲染表单的 JSON schema。
 *
 * <p>这是「配置不再藏在后端」的落地形态：前端**不需要知道有哪些配置项**，
 * 它拉这份 schema，按 type 渲染控件、按 min/max 做前端校验、按 tier 决定是否可编辑、
 * 按 description 显示帮助文案。后端加一个配置项，前端自动多一个表单项，不用改前端代码。
 *
 * <p>刻意手写 JSON 而不引 Jackson：产物是结构固定的扁平 schema，
 * 为它背一个依赖不值得，而且这样 {@code oncall-config} 能保持零外部依赖、
 * 与 {@code oncall-domain} 同一条架构约束。
 *
 * <p><b>BACKEND_ONLY 的项不会出现在输出里</b>——连键名都不暴露。
 */
public final class ConfigSchemaExporter {

    private final ConfigService service;

    public ConfigSchemaExporter(ConfigService service) {
        this.service = service;
    }

    /** 导出前端可见的全部配置项，按分组组织。 */
    public String exportForUi() {
        List<ConfigService.ConfigView> views = service.viewsForUi();
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\n");
        sb.append("  \"revision\": ").append(service.snapshot().revision()).append(",\n");
        sb.append("  \"editable\": true,\n");
        sb.append("  \"groups\": [\n");

        List<String> groups = distinctGroups(views);
        for (int gi = 0; gi < groups.size(); gi++) {
            String group = groups.get(gi);
            sb.append("    {\n");
            sb.append("      \"name\": ").append(jsonString(group)).append(",\n");
            sb.append("      \"items\": [\n");
            boolean first = true;
            for (ConfigService.ConfigView v : views) {
                if (!group.equals(v.spec().group())) {
                    continue;
                }
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                appendItem(sb, v);
            }
            sb.append("\n      ]\n");
            sb.append("    }");
            if (gi < groups.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static void appendItem(StringBuilder sb, ConfigService.ConfigView v) {
        ConfigSpec s = v.spec();
        sb.append("        {\n");
        sb.append("          \"key\": ").append(jsonString(s.key())).append(",\n");
        sb.append("          \"type\": ").append(jsonString(s.type().name())).append(",\n");
        sb.append("          \"tier\": ").append(jsonString(s.tier().name())).append(",\n");
        sb.append("          \"description\": ").append(jsonString(s.description())).append(",\n");
        sb.append("          \"defaultValue\": ").append(jsonString(s.defaultValue())).append(",\n");
        sb.append("          \"effectiveValue\": ").append(jsonString(v.effectiveValue())).append(",\n");
        sb.append("          \"overridden\": ").append(v.overridden()).append(",\n");
        sb.append("          \"hotReloadable\": ").append(s.tier().hotReloadable()).append(",\n");
        sb.append("          \"min\": ").append(numberOrNull(s.min())).append(",\n");
        sb.append("          \"max\": ").append(numberOrNull(s.max())).append(",\n");
        sb.append("          \"allowedValues\": ").append(jsonArray(s.allowedValues())).append(",\n");
        sb.append("          \"migrationHint\": ").append(jsonString(s.migrationHint())).append(",\n");
        sb.append("          \"sensitive\": ").append(s.sensitive()).append("\n");
        sb.append("        }");
    }

    private static List<String> distinctGroups(List<ConfigService.ConfigView> views) {
        List<String> out = new java.util.ArrayList<>();
        for (ConfigService.ConfigView v : views) {
            if (!out.contains(v.spec().group())) {
                out.add(v.spec().group());
            }
        }
        return out;
    }

    private static String numberOrNull(Double d) {
        if (d == null) {
            return "null";
        }
        if (d == Math.rint(d) && !Double.isInfinite(d)) {
            return String.valueOf((long) (double) d);
        }
        return String.valueOf(d);
    }

    private static String jsonArray(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(jsonString(items.get(i)));
        }
        return sb.append("]").toString();
    }

    /** JSON 字符串字面量转义。null 输出为 JSON 的 null 而不是 "null"。 */
    static String jsonString(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** 便于测试：把导出的 JSON 粗略校验一遍括号与引号是否配平。 */
    static boolean looksLikeBalancedJson(String json) {
        int braces = 0;
        int brackets = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') braces++;
            else if (c == '}') braces--;
            else if (c == '[') brackets++;
            else if (c == ']') brackets--;
            if (braces < 0 || brackets < 0) {
                return false;
            }
        }
        return braces == 0 && brackets == 0 && !inString;
    }

    /** 供上层做健康检查用：确认导出的键集合与注册表可见集合一致。 */
    public Map<String, String> exportedKeys() {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (ConfigService.ConfigView v : service.viewsForUi()) {
            out.put(v.spec().key(), v.effectiveValue());
        }
        return out;
    }
}
