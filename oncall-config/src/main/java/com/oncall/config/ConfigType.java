package com.oncall.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置值类型。前端据此渲染对应的控件（数字框 / 开关 / 下拉 / 时长选择器），
 * 后端据此做类型校验——**类型不对就拒绝写入，而不是读的时候再炸**。
 */
public enum ConfigType {

    STRING,
    INT,
    LONG,
    DOUBLE,
    BOOLEAN,

    /** 时长，字面量形如 {@code 500ms} / {@code 30s} / {@code 15m} / {@code 2h}。 */
    DURATION,

    /** 逗号分隔的字符串列表，用于 failover 链、服务白名单等。 */
    STRING_LIST,

    /**
     * 取值受限于固定集合的字符串。前端渲染成下拉框，
     * 合法值由 {@link com.oncall.config.ConfigSpec#allowedValues()} 给出。
     */
    ENUM_STRING;

    /**
     * 校验字面量能否解析成本类型。解析失败返回 {@code null}，
     * 由调用方转成校验错误——这里不抛异常，因为「用户输入不合法」是正常业务分支。
     */
    public Object parse(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        if (v.isEmpty()) {
            return null;
        }
        try {
            switch (this) {
                case STRING:
                case ENUM_STRING:
                    return v;
                case INT:
                    return Integer.valueOf(v);
                case LONG:
                    return Long.valueOf(v);
                case DOUBLE:
                    return Double.valueOf(v);
                case BOOLEAN:
                    return parseBooleanStrict(v);
                case DURATION:
                    return parseDuration(v);
                case STRING_LIST:
                    return parseList(v);
                default:
                    return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 刻意不用 {@link Boolean#parseBoolean}——它把任何非 "true" 的字符串都当 false，
     * 会把拼写错误（"ture"）静默变成「关闭」，这在开关类配置上是危险的。
     */
    private static Boolean parseBooleanStrict(String v) {
        if ("true".equalsIgnoreCase(v)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(v)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static Duration parseDuration(String v) {
        String s = v.toLowerCase();
        long n;
        long unitMillis;
        if (s.endsWith("ms")) {
            n = Long.parseLong(s.substring(0, s.length() - 2).trim());
            unitMillis = 1L;
        } else if (s.endsWith("s")) {
            n = Long.parseLong(s.substring(0, s.length() - 1).trim());
            unitMillis = 1000L;
        } else if (s.endsWith("m")) {
            n = Long.parseLong(s.substring(0, s.length() - 1).trim());
            unitMillis = 60_000L;
        } else if (s.endsWith("h")) {
            n = Long.parseLong(s.substring(0, s.length() - 1).trim());
            unitMillis = 3_600_000L;
        } else {
            // 裸数字按秒解释，但这是有歧义的写法，前端应当总是带单位
            n = Long.parseLong(s);
            unitMillis = 1000L;
        }
        if (n < 0) {
            return null;
        }
        return Duration.ofMillis(n * unitMillis);
    }

    private static List<String> parseList(String v) {
        List<String> out = new ArrayList<>();
        for (String part : v.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
