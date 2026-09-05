package com.oncall.toolgateway;

import com.oncall.domain.tool.ToolPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 白名单变更入口的<b>可见性</b>断言。
 *
 * <h2>为什么需要这条测试</h2>
 *
 * 白名单是整个安全模型的事实来源。{@code ToolPolicyGovernance} 给白名单变更
 * 加了双人复核与审计，但只要 {@code ToolPolicyEngine.register()} 是 public，
 * 它就只是"应当走的路"而不是"唯一能走的路"——
 * 任何拿到引擎引用的代码都能直接改白名单，<b>而且不会有任何报错</b>，
 * 它看起来就是一次正常的方法调用。
 *
 * <p>把可见性降为包级是编译器的强制，比任何规则都硬。但可见性是<b>会被无意放宽的</b>：
 * 有人为了让某个测试能跑，随手加个 {@code public}，编译通过、测试全绿、
 * review 时那一行 diff 混在别的改动里。这条测试就是拦这个的。
 *
 * <h2>为什么它不是"永远不会失败的规则"</h2>
 *
 * 本项目的一条纪律是：<b>一个不会失败的断言等于没写</b>。
 * 这条断言天然非空自证——它读的是运行期真实的 {@code Modifier}，
 * 谁把可见性放宽，它当场就红，不需要额外的违规样本。
 *
 * <p>也正是因为它守的是可见性，ArchUnit 在这里帮不上忙：
 * 方法一旦降为包级，任何跨包的违规样本<b>根本编译不过</b>，
 * 造不出能让规则变红的 fixture。可见性检查比规则更强，代价是它不能被规则复述。
 */
class ToolPolicyEngineVisibilityTest {

    private static Method method(String name, Class<?>... params) throws NoSuchMethodException {
        return ToolPolicyEngine.class.getDeclaredMethod(name, params);
    }

    @Test
    @DisplayName("register 不是 public——运行期改白名单只能通过治理层")
    void registerIsNotPublic() throws Exception {
        assertThat(Modifier.isPublic(method("register", ToolPolicy.class).getModifiers()))
                .as("一旦放宽，双人复核与变更审计就可以被无声绕过")
                .isFalse();
    }

    @Test
    @DisplayName("revoke 不是 public")
    void revokeIsNotPublic() throws Exception {
        assertThat(Modifier.isPublic(method("revoke", String.class).getModifiers()))
                .isFalse();
    }

    @Test
    @DisplayName("两个变更方法都是包级可见，不是 private——治理层要用它们")
    void mutatorsArePackagePrivateNotPrivate() throws Exception {
        for (String name : List.of("register", "revoke")) {
            Method m = method(name, name.equals("register") ? ToolPolicy.class : String.class);
            int mod = m.getModifiers();
            assertThat(Modifier.isPrivate(mod)).as("%s 不能是 private", name).isFalse();
            assertThat(Modifier.isProtected(mod)).as("%s 不能是 protected", name).isFalse();
            assertThat(Modifier.isPublic(mod)).as("%s 不能是 public", name).isFalse();
        }
    }

    @Test
    @DisplayName("治理层与引擎同包——这是包级可见能成立的前提")
    void governanceLivesInTheSamePackageAsTheEngine() {
        assertThat(ToolPolicyGovernance.class.getPackage().getName())
                .as("换了包，包级可见就调不到了；若为此把方法改回 public，整个防线就没了")
                .isEqualTo(ToolPolicyEngine.class.getPackage().getName());
    }

    @Test
    @DisplayName("构造器仍然是 public：启动时从配置/DB 灌策略是初始化，不是变更")
    void constructorStaysPublicSoStartupLoadingStillWorks() throws Exception {
        // 刻意用 getDeclaredConstructors()：getConstructors() 按契约只返回 public 的，
        // 对它再断言"全都是 public"是恒真的废断言。
        var ctors = ToolPolicyEngine.class.getDeclaredConstructors();
        assertThat(Arrays.stream(ctors).anyMatch(c -> Modifier.isPublic(c.getModifiers())))
                .as("至少要有一个 public 构造器，否则启动时没法灌策略")
                .isTrue();
    }

    @Test
    @DisplayName("读方法仍然是 public：封的是写，不是读")
    void readsRemainPublic() throws Exception {
        assertThat(Modifier.isPublic(method("resolve", String.class).getModifiers())).isTrue();
        assertThat(Modifier.isPublic(method("find", String.class).getModifiers())).isTrue();
        assertThat(Modifier.isPublic(method("size").getModifiers())).isTrue();
        assertThat(Modifier.isPublic(method("mcpServers").getModifiers())).isTrue();
    }
}
