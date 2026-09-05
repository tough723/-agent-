package com.oncall.toolgateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 幂等键生成（SHA-256）。
 *
 * <p>键 = {@code runId | step | toolName | canonical(args)} 的 SHA-256。
 *
 * <p><b>为什么必须规范化参数</b>：模型可能生成 {@code {"replicas":5}} 或 {@code { "replicas": 5 }}，
 * 语义相同但字面量不同。不规范化会产生不同幂等键，导致重试时二次执行——
 * 在运维场景就是二次扩容、二次重启，是会出真事故的。
 *
 * <p>当前 {@code canonical} 只做去空白（够用但不完美：JSON key 顺序不同仍会产生不同键）。
 * 生产建议换成 Jackson 读成 {@code TreeMap} 再序列化，彻底消除 key 顺序影响。
 * 该改进已记入 DEVELOPMENT.md 的 M1 待办。
 */
public class Sha256IdempotencyStore implements IdempotencyStore {

    @Override
    public String keyFor(String runId, int step, String toolName, String canonicalArgs) {
        String raw = nullToEmpty(runId) + "|" + step + "|" + nullToEmpty(toolName)
                + "|" + GuardedToolCallback.canonical(canonicalArgs);
        return sha256(raw);
    }

    static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制支持的算法，理论上不会走到这里
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
