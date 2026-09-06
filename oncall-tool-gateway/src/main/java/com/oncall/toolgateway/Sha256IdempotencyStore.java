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
 * <p>规范化由 {@link JsonCanonicalizer} 承担（轨道 C2 落地）：
 * 键递归排序 + 数组保序 + 去非结构性空白 + 数字归一
 * （{@code 8} / {@code 8.0} / {@code 8.00} 同值）。
 *
 * <p><b>本类的 javadoc 曾写着「canonical 只做去空白，JSON key 顺序不同仍会产生不同键」</b>，
 * 那句话在 C2 之后就过期了。它过期得很危险：读到的人会以为幂等还有已知缺口，
 * 从而在自己的代码里额外加一层防重——而那层防重是多余的。
 * <b>改实现之后，指向旧行为的所有注释都要一起改，包括别的文件里的。</b>
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
