package com.oncall.toolgateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 脱敏的验收测试。
 *
 * <p><b>这一组测试守的是「列名不说谎」</b>：{@code tool_audit_log.args_masked} 的
 * DDL 注释写着「不能成为敏感数据的第二个副本」，而这张表保留 180 天、
 * 查询频率最高、可见范围比业务库宽得多。
 * 一旦脱敏漏了一类键，泄漏就是长期且大范围的。
 *
 * <p><b>误报同样要守</b>：把 {@code author} 当成 {@code auth}、
 * 把 {@code sortKey} 当成密钥，代价是审计内容被无差别涂黑，
 * 而一张全是 {@code ***} 的审计表等于没有审计。
 * 所以下面两侧都有断言。
 */
class ArgMaskerTest {

    @Test
    @DisplayName("★ 敏感键的值被替换——驼峰、下划线、连字符、全大写四种写法都要命中")
    void sensitiveKeysAreMaskedInEveryNamingStyle() {
        assertThat(ArgMasker.mask("{\"apiKey\":\"sk-abc123\"}")).doesNotContain("sk-abc123");
        assertThat(ArgMasker.mask("{\"api_key\":\"sk-abc123\"}")).doesNotContain("sk-abc123");
        assertThat(ArgMasker.mask("{\"API-KEY\":\"sk-abc123\"}")).doesNotContain("sk-abc123");
        assertThat(ArgMasker.mask("{\"apikey\":\"sk-abc123\"}")).doesNotContain("sk-abc123");
        assertThat(ArgMasker.mask("{\"password\":\"hunter2\"}")).doesNotContain("hunter2");
        assertThat(ArgMasker.mask("{\"Authorization\":\"Bearer xyz\"}")).doesNotContain("xyz");
        assertThat(ArgMasker.mask("{\"refreshToken\":\"t-9\"}")).doesNotContain("t-9");
        assertThat(ArgMasker.mask("{\"accessKey\":\"AK1\"}")).doesNotContain("AK1");
        assertThat(ArgMasker.mask("{\"idCard\":\"110101\"}")).doesNotContain("110101");
        assertThat(ArgMasker.mask("{\"sessionId\":\"s-1\"}")).doesNotContain("s-1");
    }

    @Test
    @DisplayName("★ 不误伤：键名含敏感子串但本身无害的字段必须原样保留")
    void innocuousKeysAreNotMasked() {
        // 子串匹配会让这四条全部中招，审计表就废了
        assertThat(ArgMasker.mask("{\"author\":\"alice\"}")).contains("alice");
        assertThat(ArgMasker.mask("{\"keyboard\":\"dvorak\"}")).contains("dvorak");
        assertThat(ArgMasker.mask("{\"sortKey\":\"ts\"}")).contains("ts");
        assertThat(ArgMasker.mask("{\"primaryKey\":\"id\"}")).contains("id");
        // 业务参数是审计的主要内容，绝不能被涂黑
        assertThat(ArgMasker.mask("{\"replicas\":2,\"service\":\"order\"}"))
                .isEqualTo("{\"replicas\":2,\"service\":\"order\"}");
    }

    @Test
    @DisplayName("替换值长度固定——原值长度本身就是信息")
    void maskDoesNotLeakValueLength() {
        String shortSecret = ArgMasker.mask("{\"password\":\"a\"}");
        String longSecret = ArgMasker.mask("{\"password\":\"" + "a".repeat(200) + "\"}");
        assertThat(shortSecret).isEqualTo(longSecret);
    }

    @Test
    @DisplayName("键名与结构保留：审计要能看出「这里有个 password 字段」")
    void keyAndStructureArePreserved() {
        String masked = ArgMasker.mask("{\"user\":\"bob\",\"password\":\"hunter2\"}");
        assertThat(masked).contains("user").contains("bob");
        assertThat(masked).contains("password");
        assertThat(masked).doesNotContain("hunter2");
    }

    @Test
    @DisplayName("非 JSON 的 key=value 形式同样生效")
    void nonJsonKeyValueFormIsMasked() {
        assertThat(ArgMasker.mask("token=abc123&service=order"))
                .doesNotContain("abc123").contains("order");
    }

    @Test
    @DisplayName("★ null 原样返回 null，不转空串——「没有值」与「有值但被遮了」是两件事")
    void nullStaysNull() {
        assertThat(ArgMasker.mask(null)).isNull();
    }

    @Test
    @DisplayName("★ 残缺 JSON 不抛异常——脱敏在审计写入路径上，抛出去就等于这次调用没有审计")
    void malformedInputNeverThrows() {
        assertThatCode(() -> ArgMasker.mask("{\"password\":")).doesNotThrowAnyException();
        assertThatCode(() -> ArgMasker.mask("{\"password")).doesNotThrowAnyException();
        assertThatCode(() -> ArgMasker.mask("}}}{{{{")).doesNotThrowAnyException();
        assertThatCode(() -> ArgMasker.mask("")).doesNotThrowAnyException();
        assertThatCode(() -> ArgMasker.mask("\u0000\u0001binary")).doesNotThrowAnyException();
        // 残缺输入里的密钥仍然不能被放过
        assertThat(ArgMasker.mask("{\"password\":\"hunter2")).doesNotContain("hunter2");
    }

    @Test
    @DisplayName("超长输入截断并标注，而不是静默丢弃")
    void longInputIsTruncatedWithMarker() {
        String masked = ArgMasker.mask("{\"service\":\"" + "x".repeat(ArgMasker.MAX_MASKED_LENGTH * 2) + "\"}");
        assertThat(masked.length()).isLessThanOrEqualTo(ArgMasker.MAX_MASKED_LENGTH);
        assertThat(masked).as("静默截断会让人以为看到了完整参数").endsWith("…<truncated>");
    }

    @Test
    @DisplayName("嵌套对象里的敏感键也会被遮")
    void nestedSensitiveKeysAreMasked() {
        String masked = ArgMasker.mask(
                "{\"conn\":{\"host\":\"db-1\",\"password\":\"hunter2\"},\"replicas\":2}");
        assertThat(masked).doesNotContain("hunter2").contains("db-1").contains("replicas");
    }

    @Test
    @DisplayName("分词：驼峰与分隔符等价，且不会把 author 切成 auth")
    void tokenizationIsStyleInsensitiveButNotSubstringBased() {
        assertThat(ArgMasker.tokenize("apiKey")).containsExactlyInAnyOrder("api", "key");
        assertThat(ArgMasker.tokenize("api_key")).containsExactlyInAnyOrder("api", "key");
        assertThat(ArgMasker.tokenize("API-KEY")).containsExactlyInAnyOrder("api", "key");
        assertThat(ArgMasker.tokenize("author")).containsExactly("author");
        assertThat(ArgMasker.isSensitive("apiKey")).isTrue();
        assertThat(ArgMasker.isSensitive("author")).isFalse();
        assertThat(ArgMasker.isSensitive("")).isFalse();
        assertThat(ArgMasker.isSensitive(null)).isFalse();
    }
}
