package com.oncall.archtest;

import com.oncall.toolgateway.GuardedToolCallback;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 架构约束测试。
 *
 * <p><b>为什么这些约束必须是测试而不是文档</b>：
 * 安全模型里几乎每一层控制都<b>假设 GuardedToolCallback 一定会被执行</b>——
 * 幂等假设它、审批假设它、审计假设它、预算假设它。
 * 只要有任何一条代码路径绕过它直接实现 {@link ToolCallback} 并注册出去，
 * 那七道关卡就全部失效，而且<b>编译通过、启动正常、单元测试全绿</b>。
 * 这种失效只有架构测试能拦住：它检查的是「有哪些类实现了这个接口」，
 * 而不是「某次调用的行为对不对」。
 *
 * <p>这就是 F9 被列为不可事后补的三项之一的原因。
 * 另外两项是 {@code OnCallConfigRegistry}（配置项一旦散落就收不回来）
 * 与数据库 schema（迁移一旦上线就改不了）。
 */
class ArchitectureRuleTest {

    /** 生产代码所在的包。测试代码与 fixture 刻意排除在外。 */
    private static final String[] PRODUCTION_PACKAGES = {
            "com.oncall.domain",
            "com.oncall.config",
            "com.oncall.toolgateway",
            "com.oncall.tooladmin",
            "com.oncall.agent",
            "com.oncall.ontology",
            "com.oncall.eval",
            "com.oncall.app"
    };

    /**
     * 必须保持零外部依赖的包。
     *
     * <p><b>这里的排除项是踩过坑之后加的</b>：{@code oncall-config-admin} 的包名是
     * {@code com.oncall.config.admin}，<b>不是</b>一个独立的顶层包。
     * 只写 {@code com.oncall.config..} 会把 REST 控制器一起圈进来，
     * 而它理所当然地依赖 Spring Web——第一次跑就红了 58 处。
     *
     * <p>这个错误有价值：它证明 F2 真的在检查东西。
     * 一条规则第一次跑就报出大量违规，先怀疑范围写错，而不是先怀疑代码。
     */
    private static final String[] ZERO_DEP_PACKAGES = {
            "com.oncall.domain..",
            "com.oncall.config..",
            "com.oncall.ontology.."
    };

    /** 上面的范围里必须排除的部分。 */
    private static final String[] ZERO_DEP_EXCLUDED = {
            "com.oncall.config.admin.."
    };

    /**
     * 零依赖模块<b>不得</b>引入的库清单。
     *
     * <p><b>为什么是黑名单而不是「只允许 java./javax.」的白名单</b>：
     * ArchUnit 的 {@code onlyDependOnClassesThat()} 会把对基本类型（{@code int} 等）
     * 的依赖也算进去，而基本类型的「包名」是空串，白名单写法会误报。
     * 当前环境无法本地编译验证，一条会误报的规则比没有规则更糟
     * ——它会在 CI 里制造一个没人看得懂的红灯，然后被人删掉。
     * 所以这里改成点名具体库：这些库一旦出现在零依赖模块里，
     * 要么是设计漂移，要么是有人为了省事加依赖。
     */
    private static final String[] FORBIDDEN_EXTERNAL_PACKAGES = {
            "org.springframework..",
            "com.fasterxml..",
            "org.slf4j..",
            "io.micrometer..",
            "jakarta..",
            "org.junit..",
            "org.assertj..",
            "org.h2..",
            "com.tngtech.archunit.."
    };

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter().importPackages(PRODUCTION_PACKAGES);
    }

    // ------------------------------------------------------------ F9 基石

    /**
     * F9：{@link GuardedToolCallback} 是<b>唯一</b>允许存在的 {@link ToolCallback} 实现。
     *
     * <p>将来若要加 {@code RateLimitedToolCallback}、{@code TracingToolCallback} 这类装饰器，
     * 必须把类名加进 {@code orShould} 白名单。<b>加这个名字的动作本身就是评审</b>——
     * 它会在 code review 里显式出现，而不是藏在某个新文件的 {@code implements} 后面。
     *
     * <p>用 {@code areAssignableTo} 而不是 {@code implement}：
     * 前者覆盖间接实现（继承了一个实现类也算），后者只看直接实现。
     * 绕过一层继承就失效的规则不算规则。
     */
    private static final ArchRule F9_SINGLE_GUARDED_TOOL_CALLBACK =
            classes().that().areAssignableTo(ToolCallback.class)
                    .and().areNotInterfaces()
                    .should().haveFullyQualifiedName(GuardedToolCallback.class.getName())
                    .because("所有工具调用必须经过 GuardedToolCallback 的七道关卡"
                            + "（默认拒绝/急停/夹取/幂等/审批/超时熔断/审计）；"
                            + "新增装饰器必须在此处登记，登记动作本身即评审")
                    .as("F9 除 GuardedToolCallback 外不得有 ToolCallback 实现");

    @Test
    @DisplayName("F9 通过：当前只有 GuardedToolCallback 实现 ToolCallback")
    void f9OnlyGuardedToolCallbackImplementsToolCallback() {
        assertRulePasses(F9_SINGLE_GUARDED_TOOL_CALLBACK, production);
    }

    @Test
    @DisplayName("F9 非空转：GuardedToolCallback 确实被扫到了")
    void f9IsNotVacuouslyTrue() {
        // 规则通过有两种可能：真的守住了，或者扫描路径写错、什么都没扫到。
        // 后者在 CI 里长得一模一样，所以必须单独断言被测对象存在。
        assertTrue(production.size() > 0,
                "生产包扫描结果为空，PRODUCTION_PACKAGES 可能写错了");
        assertTrue(production.contain(GuardedToolCallback.class.getName()),
                "生产包里没扫到 GuardedToolCallback，F9 的通过是空转的");
        assertTrue(production.contain("com.oncall.config.OnCallConfigRegistry"),
                "没扫到配置注册表，说明 com.oncall.config 不在扫描范围内");
        assertTrue(production.contain("com.oncall.ontology.Ontology"),
                "没扫到本体门面，说明 com.oncall.ontology 不在扫描范围内");
        assertTrue(production.contain("com.oncall.app.ToolGatewayAssembly"),
                "没扫到装配层，说明 com.oncall.app 不在扫描范围内"
                        + "（或 oncall-archtest 没有依赖 oncall-app）");
    }

    @Test
    @DisplayName("F9 自证：故意违规的 fixture 必须被规则拦下")
    void f9RejectsUnguardedImplementation() {
        // 一条从没红过的规则等于没写。这里证明它在遇到真实违规时会失败。
        JavaClasses fixture = new ClassFileImporter().importPackages("com.oncall.archtest.fixture");
        assertTrue(fixture.contain("com.oncall.archtest.fixture.UnguardedToolCallbackFixture"),
                "fixture 没被扫到，自证失效");
        assertThrows(AssertionError.class,
                () -> F9_SINGLE_GUARDED_TOOL_CALLBACK.check(fixture),
                "F9 对绕过 GuardedToolCallback 的实现没有报错——规则已失效");
    }

    // ------------------------------------------------------------ F1 领域层纯净

    private static final ArchRule F1_DOMAIN_HAS_NO_SPRING =
            noClasses().that().resideInAnyPackage("com.oncall.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
                    .because("领域层是依赖图的最底层，一旦引入 Spring 就无法在"
                            + "没有容器的情况下被任何模块复用或单测")
                    .as("F1 领域层不得依赖 Spring");

    @Test
    @DisplayName("F1 通过：领域层零 Spring 依赖")
    void f1DomainStaysFrameworkFree() {
        assertRulePasses(F1_DOMAIN_HAS_NO_SPRING, production);
    }

    // ------------------------------------------------------------ F2 零依赖模块

    private static final ArchRule F2_ZERO_DEP_MODULES =
            noClasses().that().resideInAnyPackage(ZERO_DEP_PACKAGES)
                    .and().resideOutsideOfPackages(ZERO_DEP_EXCLUDED)
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(FORBIDDEN_EXTERNAL_PACKAGES)
                    .because("domain / config / ontology 是生产代码零外部依赖的模块，"
                            + "JDBC 只用 JDK 自带的 javax.sql；H2 与 JUnit 只允许出现在测试作用域")
                    .as("F2 零依赖模块不得引入外部库");

    @Test
    @DisplayName("F2 通过：domain / config / ontology 未引入外部库")
    void f2ZeroDependencyModulesStayClean() {
        assertRulePasses(F2_ZERO_DEP_MODULES, production);
    }

    @Test
    @DisplayName("F2 非空转：JDBC 实现确实在扫描范围内（否则规则等于没写）")
    void f2CoversTheJdbcImplementations() {
        // 零依赖约束最有价值的地方就是 JDBC 实现——它们最容易为了省事引第三方连接池或 ORM。
        // 如果这两个类不在扫描范围里，F2 通过了也说明不了任何事。
        assertTrue(production.contain("com.oncall.config.store.JdbcConfigStore"),
                "没扫到 JdbcConfigStore，F2 的通过是空转的");
        assertTrue(production.contain("com.oncall.ontology.JdbcOntologyStore"),
                "没扫到 JdbcOntologyStore，F2 的通过是空转的");
    }

    // ------------------------------------------------------------ F3 依赖方向

    private static final ArchRule F3_NO_UPWARD_DEPENDENCY_FROM_CONFIG =
            noClasses().that().resideInAnyPackage("com.oncall.config..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("com.oncall.toolgateway..", "com.oncall.ontology..")
                    .because("依赖方向必须是 config-admin -> config，"
                            + "配置层反向依赖上层会形成环，且让配置层无法独立测试")
                    .as("F3 配置层不得反向依赖上层模块");

    @Test
    @DisplayName("F3 通过：配置层不反向依赖 tool-gateway / ontology")
    void f3ConfigDoesNotDependUpward() {
        assertRulePasses(F3_NO_UPWARD_DEPENDENCY_FROM_CONFIG, production);
    }

    private static final ArchRule F4_DOMAIN_IS_LEAF =
            noClasses().that().resideInAnyPackage("com.oncall.domain..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("com.oncall.config..", "com.oncall.toolgateway..",
                            "com.oncall.tooladmin..", "com.oncall.ontology..")
                    .because("领域层是叶子：被所有人依赖，不依赖任何人。"
                            + "已核实的依赖方向为 config-admin -> config、"
                            + "tool-gateway -> domain、ontology -> domain")
                    .as("F4 领域层不得依赖其他模块");

    @Test
    @DisplayName("F4 通过：领域层是依赖图的叶子")
    void f4DomainIsLeaf() {
        assertRulePasses(F4_DOMAIN_IS_LEAF, production);
    }

    // ------------------------------------------ F10 两个接入层互不依赖

    /**
     * F10：{@code com.oncall.tooladmin} 不得依赖 {@code com.oncall.config}，
     * 且 {@code com.oncall.toolgateway} 不得依赖 {@code com.oncall.tooladmin}。
     *
     * <p><b>为什么这条规则值得单独写</b>：两个 REST 接入层（配置治理、工具治理）
     * 的错误响应体长得几乎一样，"复用一下"的诱惑很实在——
     * 而一旦 {@code tool-admin} 引了 {@code config-admin} 的 {@code ApiError}，
     * 两个模块就在编译期绑死了：配置侧改一次错误码，工具侧跟着变，
     * 而两边的前端分支逻辑是分开演进的。
     * 更实际的问题是它们各带一个 {@code @RestControllerAdvice}，
     * 同时在 classpath 上时匹配顺序不确定，共用类型会让故障更难定位。
     *
     * <p>第二条（gateway 不得依赖 tool-admin）是常规的适配器方向约束：
     * 内核不知道 HTTP 的存在，换掉传输层不该动到内核。
     */
    private static final ArchRule F10_ADMIN_ADAPTERS_ARE_DECOUPLED =
            noClasses().that().resideInAnyPackage("com.oncall.tooladmin..")
                    .should().dependOnClassesThat().resideInAnyPackage("com.oncall.config..")
                    .because("工具治理与配置治理是两个独立适配器，"
                            + "共用类型会让它们绑死，而两边的错误码是分开演进的")
                    .as("F10a 工具接入层不得依赖配置层");

    private static final ArchRule F10B_GATEWAY_IS_ADAPTER_FREE =
            noClasses().that().resideInAnyPackage("com.oncall.toolgateway..")
                    .should().dependOnClassesThat().resideInAnyPackage("com.oncall.tooladmin..")
                    .because("内核不知道传输层的存在：换掉 HTTP 绑定不该动到网关")
                    .as("F10b 工具网关不得依赖其接入层");

    @Test
    @DisplayName("F10 通过：两个接入层互不依赖，网关不依赖适配器")
    void f10AdminAdaptersAreDecoupled() {
        assertRulePasses(F10_ADMIN_ADAPTERS_ARE_DECOUPLED, production);
        assertRulePasses(F10B_GATEWAY_IS_ADAPTER_FREE, production);
    }

    @Test
    @DisplayName("F10 非空转：工具接入层的类确实在扫描范围内")
    void f10CoversTheToolAdminAdapter() {
        // 与 F2 的非空转检查同理：漏掉一个包，规则会一路绿灯却什么都没查。
        // CI 是显式枚举模块的，新模块被漏掉时「测试数 > 0」这道保险也拦不住。
        assertTrue(production.contain("com.oncall.tooladmin.ToolPolicyAdminController"),
                "没扫到 ToolPolicyAdminController，F10 的通过是空转的");
        assertTrue(production.contain("com.oncall.tooladmin.ToolAdminExceptionHandler"),
                "没扫到 ToolAdminExceptionHandler，F10 的通过是空转的");
    }

    // ------------------------------------------ F11 可靠性层是叶子

    /**
     * F11：{@code com.oncall.agent.llm} 与 {@code com.oncall.agent.prompt}
     * 不得依赖本项目任何其它模块。
     *
     * <p><b>为什么范围只圈这两个子包，而不是整个 {@code com.oncall.agent}</b>：
     * 编排层将来理所当然要依赖 domain 与 toolgateway，
     * 一条"agent 不得依赖 domain"的规则迟早会被人为了让编译过而删掉。
     * 圈定子包才能长期成立：
     * <ul>
     *   <li>{@code .llm} —— 重试与 failover 是<b>传输层的可靠性问题</b>，
     *       与"工单""风险等级""白名单"这些领域概念没有任何关系；</li>
     *   <li>{@code .prompt} —— prompt 的装载与渲染只认「名字 + 版本 + 变量」，
     *       它连"这段 prompt 是给 Planner 还是给意图分类用的"都不需要知道。
     *       一旦它引了 domain，加一个 prompt 就要动领域层。</li>
     * </ul>
     *
     * <p>收益是具体的：{@code oncall-agent-core} 保持零内部依赖，
     * knowledge 模块将来才能拿同一个 {@code ResilientChatModel} 去包装
     * embedding / reranker 的调用，也能拿同一个 {@code PromptRegistry}
     * 管自己的 prompt——而不会顺带把 domain / config 拖进依赖图。
     */
    private static final ArchRule F11_AGENT_FOUNDATIONS_ARE_LEAVES =
            noClasses().that().resideInAnyPackage("com.oncall.agent.llm..", "com.oncall.agent.prompt..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.oncall.domain..", "com.oncall.config..",
                            "com.oncall.toolgateway..", "com.oncall.tooladmin..",
                            "com.oncall.ontology..",
                            // 也禁止依赖同模块内的编排层：
                            // PromptRegistry 只认「名字 + 版本 + 变量」，
                            // 它连"这段 prompt 是给意图分类还是给 Planner 用的"都不该知道。
                            // 一旦它引了 Intent，加一个 prompt 就要动编排层，
                            // 而"叶子"这个性质也就没了。
                            "com.oncall.agent.query..")
                    .because("LLM 可靠性与 prompt 装载都是与领域无关的基础件；"
                            + "保持零内部依赖，其它模块才能直接复用")
                    .as("F11 agent 基础件不得依赖其它业务模块");

    @Test
    @DisplayName("F11 通过：agent 的基础件（llm / prompt）是依赖图的叶子")
    void f11AgentFoundationsAreLeaves() {
        assertRulePasses(F11_AGENT_FOUNDATIONS_ARE_LEAVES, production);
    }

    @Test
    @DisplayName("F11 非空转：两个子包的类都确实在扫描范围内")
    void f11CoversTheAgentFoundations() {
        assertTrue(production.contain("com.oncall.agent.llm.ResilientChatModel"),
                "没扫到 ResilientChatModel，F11 的通过是空转的");
        assertTrue(production.contain("com.oncall.agent.prompt.PromptRegistry"),
                "没扫到 PromptRegistry，F11 的通过是空转的");
    }

    // ------------------------------------------------------------ F12 评测层

    /**
     * F12：评测代码不得被任何生产模块依赖。
     *
     * <p>{@code oncall-eval} 的产物是<b>判据</b>，不是能力。
     * 一旦生产代码 import 了它，就会出现两类很难发现的坏事：
     * <ul>
     *   <li>标注集被打进运行时依赖，评测数据跟着应用一起发布；</li>
     *   <li>更糟的是有人为了"顺便复用"某个指标计算，
     *       把评测逻辑接进请求路径——那等于让线上行为依赖一份 Git 里的 YAML。</li>
     * </ul>
     *
     * <p>这条约束的成本极低（评测层本来就没有理由被别人依赖），
     * 而它挡住的是"依赖方向悄悄反过来"这类退化。
     */
    private static final ArchRule F12_EVAL_IS_NOT_A_PRODUCTION_DEPENDENCY =
            noClasses().that().resideOutsideOfPackage("com.oncall.eval..")
                    .should().dependOnClassesThat().resideInAnyPackage("com.oncall.eval..")
                    .because("评测层的产物是判据而不是能力；被生产代码依赖会让"
                            + "标注集进运行时、甚至让线上行为依赖一份 Git 里的 YAML")
                    .as("F12 评测层不得被生产代码依赖");

    @Test
    @DisplayName("F12 通过：没有生产代码依赖评测层")
    void f12EvalIsNotAProductionDependency() {
        assertRulePasses(F12_EVAL_IS_NOT_A_PRODUCTION_DEPENDENCY, production);
    }

    @Test
    @DisplayName("F12 非空转：评测层的类确实在扫描范围内")
    void f12CoversTheEvalLayer() {
        assertTrue(production.contain("com.oncall.eval.ExecuteRecallGate"),
                "没扫到 ExecuteRecallGate，F12 的通过是空转的");
    }

    // ------------------------------------------------------------ 工具方法

    /**
     * 执行规则，失败时把异常信息截断后再抛出。
     *
     * <p><b>为什么要截断</b>：ArchUnit 会把每一条违规都列进 message，
     * 一个失控的规则能产出几万字符。GitHub Actions 每一级注解上限 10 条，
     * 超长输出会把真正的违规项从可读区域里挤出去——
     * 这个坑在配置模块的 CI 排查里已经踩过一次。
     */
    private static void assertRulePasses(ArchRule rule, JavaClasses target) {
        try {
            rule.check(target);
        } catch (AssertionError e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.length() > 2000) {
                msg = msg.substring(0, 2000) + "\n...（违规项过多，已截断；共 "
                        + msg.length() + " 字符）";
            }
            fail(rule.getDescription() + " 失败：\n" + msg);
        }
    }
}
