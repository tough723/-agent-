package com.oncall.toolgateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等键测试。
 *
 * <p>幂等失效在运维场景等于二次扩容/二次重启，所以键的稳定性必须逐条验证。
 */
class Sha256IdempotencyStoreTest {

    private final IdempotencyStore store = new Sha256IdempotencyStore();

    @Test
    @DisplayName("相同输入产生相同键（幂等的前提）")
    void sameInputSameKey() {
        String a = store.keyFor("run-1", 3, "scale_replicas", "{\"replicas\":5}");
        String b = store.keyFor("run-1", 3, "scale_replicas", "{\"replicas\":5}");
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("空白差异不影响键（规范化生效）")
    void whitespaceIsNormalized() {
        String a = store.keyFor("run-1", 3, "t", "{ \"replicas\": 5 }");
        String b = store.keyFor("run-1", 3, "t", "{\"replicas\":5}");
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("不同 run / 不同步 / 不同工具 / 不同参数 必须产生不同键")
    void differentInputsDifferentKeys() {
        String base = store.keyFor("run-1", 3, "t", "{\"a\":1}");
        assertThat(store.keyFor("run-2", 3, "t", "{\"a\":1}")).isNotEqualTo(base);
        assertThat(store.keyFor("run-1", 4, "t", "{\"a\":1}")).isNotEqualTo(base);
        assertThat(store.keyFor("run-1", 3, "other", "{\"a\":1}")).isNotEqualTo(base);
        assertThat(store.keyFor("run-1", 3, "t", "{\"a\":2}")).isNotEqualTo(base);
    }

    @Test
    @DisplayName("键格式为 64 位十六进制（SHA-256）")
    void keyIsSha256Hex() {
        String k = store.keyFor("r", 1, "t", "{}");
        assertThat(k).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("null 入参不抛异常")
    void toleratesNulls() {
        assertThat(store.keyFor(null, 0, null, null)).hasSize(64);
    }
}
