# 开发推进说明（DEVELOPMENT.md）

> 配套文档：`架构评审报告.md`（问题）→ `修复方案.md`（方案）→ 本文（代码现状 + 下一步）
> 最后更新：2026-09-04

---

## ⚠️ 首先：本轮代码未经编译验证

**当前沙箱环境无 JDK、无 Maven，且除白名单外无外网**（已实测：`java`/`mvn` 命令不存在，`apt-get` 无权限安装，`repo1.maven.org` 与 `deb.debian.org` 连接失败）。因此本地无法编译，改为用 GitHub Actions 代为验证。

### CI 已经验证通过（不是"待你验证"）

`.github/workflows/ci.yml` 在每次 push 时用 JDK 17 + Maven 3.9.16 真实编译并跑测试，
另有一个独立的 job 用真实 PostgreSQL 16 + pgvector 跑 DDL。
**最近一次全绿**：run `33986641697` / conclusion `success`（两个 job 都是），
commit `90bd81e`。下表是那次实测的数字（读自 check-run annotations，不是预测值）。

| 步骤 | 结果 |
|------|------|
| Check all POM files are well-formed XML | ✅ 7 个 POM |
| Validate POM model (all modules) | ✅ |
| Record resolved Spring AI version | ✅ `spring-ai-model:1.1.6` |
| Build & test `oncall-domain`（纯 Java，零外部依赖） | ✅ |
| Build & test `oncall-config`（配置治理，纯 Java，零外部依赖） | ✅ |
| Build & test `oncall-config-admin`（REST 接入层，依赖 Spring Web） | ✅ |
| Build & test `oncall-tool-gateway`（依赖 Spring AI） | ✅ |
| Build & test `oncall-ontology`（轻量本体，纯 Java，零外部依赖） | ✅ |
| Build & test `oncall-archtest`（架构约束 F1–F4、F9） | ✅ `archunit:1.4.2` |
| Assert tests actually ran | ✅ `报告文件=25 测试总数=365 失败=0 错误=0 跳过=0` |
| **DDL on PostgreSQL 16 + pgvector** | ✅ V1–V7 各 7/7，**重复执行也 7/7**，表数 **19** |

**测试数字对得上**：25 个报告文件对应 25 个测试类，365 =
domain 35 + config 82 + config-admin 35 + tool-gateway 146 + ontology 59 + archtest 8。
其中 tool-gateway 的 146 = 104 + 工具策略治理 42（见 §1.7）。

**DDL job 的断言**：`information_schema` 表数必须恰好命中期望值
（本轮起是 19 = 16 张逻辑表 + 3 个 DEFAULT 分区），`uq_agent_step_idem`、
`chk_approval_not_self`、`chk_claim_state`、`tool_execution_claim_pkey`
必须各存在一条。数字变了说明有人动了 schema。

`GuardedToolCallback` 编译通过，意味着 `ToolCallback` / `ToolDefinition` /
`ToolMetadata.builder()` / `ToolContext` 这套签名在 Spring AI **1.1.6** 上是对的——
F1 那块最关键的装饰器代码不再是"对照文档推测"。

**ADR-001 版本基线（CI 实测解析结果）**：

```
spring-ai-alibaba-bom : 1.1.2.3
spring-ai-bom         : 1.1.6
  org.springframework.ai:spring-ai-model:jar:1.1.6
  org.springframework.ai:spring-ai-commons:jar:1.1.6
  org.springframework.ai:spring-ai-template-st:jar:1.1.6
```

> 注意：`spring-ai-alibaba-bom` **不**管理 `org.springframework.ai` 的 artifact。
> 只引 SAA BOM 会让 `spring-ai-model` 缺 version，导致 POM 模型校验失败——
> 而且是整个 reactor 一起失败，连零依赖的 `oncall-domain` 都构建不了。
> 两个 BOM 必须同时引入（见父 `pom.xml` 的 `dependencyManagement`）。

查看方式：

```bash
gh run list --branch arena/01a06d8c-agent
gh run watch <run-id>
```

### CI 抓到的三个真实缺陷（已全部修复，留档防再犯）

按发现顺序。三个都不是"代码写错了"这么简单，其中两个会让 CI 撒谎。

1. **POM 不是合法 XML** —— 注释里写了 `----` 分隔线。XML 规范禁止注释内出现
   连续两个减号，整个 `pom.xml` 直接 `Non-parseable`。
   → CI 新增 `Check all POM files are well-formed XML` 前置步骤，用 Python
   `xml.etree` 先解析一遍，把"XML 语法错"与"依赖/编译错"彻底分开。

2. **`mvn ... 2>&1 | tee log` 会吞掉失败** —— 不带 `pipefail` 时管道退出码取的是
   最后一环 `tee` 的 `0`，Maven 报错被完全掩盖，步骤显示"成功"。
   这就是中途出现过一次**假全绿**的原因：实际一个 class 都没编译。
   → 已加 `defaults.run.shell: bash -e -o pipefail {0}`，并去掉 mvn 步骤的 `| tee`。
   **教训**：管道后的构建命令等于没有退出码检查。

3. **record 静态工厂顶掉了访问器** —— `record Approval(boolean approved, ..., boolean expired)`
   会隐式生成 `boolean approved()` / `boolean expired()`，而类里又声明了
   `static Approval expired()`（同名同参、返回值不同），编译报
   `invalid accessor method in record`，并让所有 `approval.approved()` 的布尔用法
   一起失效。→ 静态工厂改为动词命名 `granted` / `rejected` / `timedOut`。
   **规约**：record 的静态工厂名不得与任一组件名相同。

另外顺手修的一个隐患：Maven super-POM 把 `maven-surefire-plugin` 默认钉在 **2.12.4**，
该版本不支持 JUnit 5，会"找不到测试"然后**静默成功**。已在父 POM 钉住
surefire **3.2.5** + compiler **3.13.0**，并显式声明 `junit-jupiter-engine` 与
`junit-platform-launcher`。CI 里的 `Assert tests actually ran` 会进一步断言
"报告文件数 > 0 且测试总数 > 0 且失败/错误 = 0"——**不接受空跑冒充通过**。

### 仍需你本地验证的部分

CI 只覆盖到 `oncall-domain` + `oncall-config` + `oncall-tool-gateway` 三个模块。下面这些还**没有代码**，
自然也没被验证，不要当成已完成：

| 位置 | 状态 |
|------|------|
| `MessageChatMemoryAdvisor` 构造方式（`架构设计方案.md` §3.2） | 仅有文档，1.x 有构造器与 builder 两种写法，写代码时二选一 |
| `CitationVerifier` / `RerankPostProcessor` / `HybridRetriever` / `UsageTrackingAdvisor` | 仅在 `质量与可靠性设计.md` 中设计，未落地 |
| M1 剩余：`JsonScaleArgsAdapter`、`JdbcToolAuditLog`、`WecomApprovalGate`、`McpToolRegistrar`、`GuardedToolCallbackTest` | 未落地 |


---

## 一、已完成

### 1.1 文档修正（22 项，已全部应用并验证）

`架构设计方案.md` 已就地修正。验证方式：原来 14 个错误模式全部检索为 0。

```
TokenTextSplitter(500, 50)                 0    ✓ 已改为 builder
Document doc = new MarkdownDocumentReader  0    ✓ get() 返回 List
vectorStore.accept                         0    ✓ 改为 add
filterBuilder.and                          0    ✓ 改为 FilterExpressionBuilder
rag.topK}}                                 0    ✓ 占位符修正
chatClient(chatClient) / maxSteps(8)       0    ✓ ReactAgent 正确 API
      servers: / auth: bearer-token-xxx    0    ✓ MCP 前缀修正 + 关掉自动注册
data: " + chunk                            0    ✓ 去掉双重 SSE 前缀
```

残留的 `subList(-6`、`i < 20`、`JsonUtils.parse` 各 1 处，**都在"原写法问题说明"的注释里**，是刻意保留的对照，不是遗漏。

4 项设计层修订也已插入：§5.1（失败态 + 超时出口）、§5.2（缺失的 6 张表）、§7/§10 文档定位标注、§8.3 风险表补 4 行（知识陈旧升级为"高"、新增提示注入/数据出域/单厂商依赖）。

### 1.2 代码骨架

```
oncall-agent/
├── pom.xml                              父 POM：版本基线 + BOM
├── oncall-domain/                       ✅ 零外部依赖（纯 Java 17）
│   └── src/main/java/com/oncall/domain/
│       ├── ticket/
│       │   ├── TicketStatus.java        12 状态（补齐 EXEC_FAILED / MANUAL_HANDLING / KNOWLEDGE_INDEXED）
│       │   ├── TicketEvent.java         15 事件（含 APPROVAL_TIMEOUT / EXEC_TIMEOUT）
│       │   ├── TicketStateMachine.java  State 模式：显式转移表 + 超时上限
│       │   └── IllegalTransitionException.java
│       └── tool/
│           ├── RiskLevel.java
│           ├── ToolSource.java          LOCAL / MCP（策略按来源寻址）
│           ├── ToolPolicy.java          策略 record，与代码解耦
│           └── ToolDeniedException.java
│   └── src/test/java/.../TicketStateMachineTest.java     8 个用例
├── oncall-config/                       ✅ 配置治理（本轮新增，零外部依赖）
│   └── src/main/java/com/oncall/config/
│       ├── ConfigTier.java              三级：RUNTIME_HOT / REQUIRES_MIGRATION / BACKEND_ONLY
│       ├── ConfigType.java              类型 + 时长/列表/枚举解析（严格布尔，拼错即拒）
│       ├── ConfigSpec.java              单项声明：默认值/分级/边界/说明/迁移提示
│       ├── ConfigRegistry.java          声明表 + 启动自检（坏声明直接启动失败）
│       ├── ConfigValidator.java         四层校验，写入时拒绝而非读取时炸
│       ├── ConfigStore.java             持久化端口
│       ├── InMemoryConfigStore.java     内存实现（并发安全）
│       ├── ConfigService.java           读取/变更/快照/前端视图
│       ├── ConfigSnapshot.java          一次请求内配置一致 + revision 标注
│       ├── ConfigChange.java            变更审计记录
│       ├── ConfigAuditLog.java          审计端口 + InMemory 实现
│       ├── OnCallConfigKeys.java        39 个配置键常量
│       ├── OnCallConfigRegistry.java    41 项参数声明（24 热改 / 8 迁移 / 9 后端专属）
│       ├── schema/ConfigSchemaExporter.java  前端 JSON schema 导出（手写，零依赖）
│       └── store/
│           ├── JdbcConfigStore.java     配置覆盖值持久化（墓碑行 + 方言无关 upsert）
│           └── JdbcConfigAuditLog.java  配置变更审计持久化
│   └── src/test/java/...                5 个测试类（JDBC 用 H2 内存库跑真 SQL）
├── oncall-config-admin/                 ✅ 配置治理的 REST 接入层（本轮新增）
│   └── src/main/java/com/oncall/config/admin/
│       ├── ConfigAdminController.java   读/写/双人复核/重载（复核判定委托领域层，见 §1.6）
│       ├── ConfigAccessPolicy.java      权限判定 + 高危键清单（硬编码，不可配置）
│       ├── PendingChange.java           待复核单：带 TTL 与"期间被改过"检测
│       ├── PendingChangeStore.java      端口 + InMemory 实现
│       ├── ConfigItemView.java          前端视图 DTO（敏感值掩码）
│       └── AdminApiException.java       统一异常 → HTTP 状态码
│   └── src/test/java/...                3 个测试类（MockMvc standaloneSetup）
│       （Operator 与双人复核判定已移到 oncall-domain，见 §1.6）
└── oncall-tool-gateway/                 ✅ P0 安全核心（17 个生产类）
    └── src/main/java/com/oncall/toolgateway/
        ├── ToolPolicyEngine.java        默认拒绝 + 拒绝事件上报 + mcpServers() 反推
        ├── GuardedToolCallback.java     Decorator：七道关卡 + MCP 改名
        ├── KillSwitch.java + RunMode.java
        ├── ApprovalGate.java + Approval.java
        ├── ToolAuditLog.java            审计（只追加）+ InMemoryToolAuditLog
        ├── IdempotencyStore.java        幂等键生成 + Sha256IdempotencyStore
        ├── ArgClamper.java              Strategy：参数夹紧
        ├── clamp/                       ScaleReplicasClamper + ReplicaStatePort
        └── mcp/                         MCP 工具显式纳管（见 §1.4）
    └── src/test/java/...                            6 个测试类，80 个用例
```

> 上面的 17 / 6 / 80 是本轮之前（commit `e7b596a`）的数字，见 §1.5 的增量表。

**这些模块都是纯逻辑，没有 Spring 上下文**——`oncall-domain`、`oncall-config`、
`oncall-ontology` 完全零依赖（JDBC 只用 JDK 自带的 `javax.sql`，H2 只在测试作用域），
`oncall-tool-gateway` 只有 `GuardedToolCallback` 一个类碰 Spring AI。
这样即使 Spring AI 坐标要调，绝大部分代码不受影响。

### 1.3 本轮新增：轻量本体 + 架构约束 + M2 建表

```
oncall-ontology/                         ✅ 零外部依赖（纯 Java 17）
└── src/main/java/com/oncall/ontology/
    ├── Criticality.java                 属性而非子类（OntoClean 的结论）
    ├── ConceptKind.java                 5 类，每类对应一组 Competency Question
    ├── OntoConcept.java                 SKOS 命名：pref/alt/hidden label + broader
    ├── OntoRelation.java                带类型的边（6 种 predicate）
    ├── OntologyStore.java               端口：概念层与关系层
    ├── InMemoryOntologyStore.java       内存实现（返回值按 id 排序，见类注释）
    ├── JdbcOntologyStore.java           只用 javax.sql，在真实 H2 上测过
    ├── EntityLink.java                  RESOLVED / AMBIGUOUS / UNRESOLVED
    ├── Ontology.java                    门面：有界遍历 + 实体链接
    └── rule/                            4 条规则 + RuleEngine（刻意不用规则引擎）
└── src/test/java/...                    3 个测试类，59 个用例

oncall-archtest/                         ✅ 无生产代码，只有测试
├── ArchitectureRuleTest.java            F1–F4 + F9，8 个用例
└── fixture/UnguardedToolCallbackFixture.java   故意违规，用来证明 F9 会红
```

**本体不引入 OWL / 推理机 / SPARQL**，三个语义层面的理由（不是成本理由）见
[本体论方法论评估.md](本体论方法论评估.md)：OWL 的开放世界假设与运维要的闭世界冲突；
OWL 与 SWRL 都单调、无否定即失败，表达不了「除非…否则…」；
形式本体要求领域十年尺度稳定，而服务拓扑每周变。

| 组件 | 关键设计 |
|------|---------|
| `Criticality` | **属性不是子类**。「核心」是非刚性的，做成子类会导致服务升级要迁实例类型 |
| `Ontology.traverse` | 跳数上限 `MAX_HOPS=3`，**调用方传再大的数也会被夹回来** |
| `Ontology.link` | 多义时返回候选**而不是猜**。别名表给不了这个能力 |
| `Ontology.isA` | 遇环终止，脏数据不能挂死请求线程 |
| `JdbcOntologyStore` | upsert 先 UPDATE 再 INSERT，**不用方言语法**（H2 的 `MERGE` 与 PG 的 `ON CONFLICT` 不通用） |
| `onto_concept_label` | 归一化标签表而非 `TEXT[]`：热路径是等值查询，且 `TEXT[]` 会让 H2 验证不可能 |
| `RuleEngine` | 顺序执行，效果**取更严格的一档**（不是叠加也不是覆盖）；单条规则异常不连带其余约束失效 |

**M2 建表**：`db/migration/` 从 1 个脚本增至 6 个，表从 2 张增至 **15 张**。
数据库级不变量落到了约束上（**注意粒度，别把两条幂等约束混为一谈**）：

| 约束 | 粒度 | 保证的不变量 |
|------|------|-------------|
| `tool_execution_claim` 主键（V7） | **工具调用**（`runId\|step\|toolName\|canonicalArgs`） | **I8 幂等的物理保证**。见 §1.5 |
| `uq_agent_step_idem UNIQUE (idempotency_key)` | **步**（`agent_step` 一行） | 同一个步不会被插入两次（消息重投 / 整步重放）。**约束不到工具调用**——一步内可以有多次不同的工具调用 |
| `chk_claim_state CHECK (state IN ('CLAIMED','COMPLETED'))` | 幂等行状态 | 账本不会进入无法判定的第三态 |
| `chk_approval_not_self CHECK (approver IS NULL OR approver <> requester)` | 审批单 | I3 不能复核自己。放应用层等于让人改一行代码就绕过 |

> **✅ V1–V7 已在真实 PostgreSQL 16 + pgvector 上执行通过，重复执行也通过。**
> CI 里有独立的 `DDL on PostgreSQL 16 + pgvector` job（service 容器 `pgvector/pgvector:pg16`），
> `information_schema` 报 19 张 = 16 张逻辑表 + 3 个 DEFAULT 分区，
> 上表四条约束的存在性都是硬断言（run `33984843271`，`DDL-约束 4/4`）。
> 加这个 job 之前它们从未被任何数据库执行过——H2 不支持 `PARTITION BY RANGE`
> 与 `vector(1024)`。详见 [5.数据库设计文档.md](5.数据库设计文档.md) §8.2。


### 1.4 MCP 工具显式纳管（M1 收尾，堵住不变量 I14）

```
oncall-tool-gateway/
└── src/main/java/com/oncall/toolgateway/mcp/
    ├── McpToolCatalog.java          端口：某个 server 当前提供哪些工具
    ├── StaticMcpToolCatalog.java    静态目录实现（S0 阶段它就是正确实现，不只是替身）
    ├── McpToolRegistrar.java        纳管器：白名单过滤 + 改名 + 交给守卫
    └── McpRegistrationResult.java   接受/拒绝及原因
└── src/test/java/.../mcp/           22 个用例 + StubToolCallback
```

**堵的洞**：原方案给工具方法打 `@RiskLevel` 注解，但 MCP 工具是运行期从远端
server 发现的对象，不是本项目的 Java 类，打不了注解 —— 于是 MCP 工具绕过整套
风险分级。更糟的是 Spring AI 的 MCP client **默认会**自动把发现的工具注册给模型
（`toolcallback.enabled` 默认 `true`），也就是"什么都不做"的情况下
远端 server 就能让模型获得任意工具。

| 关键设计 | 为什么 |
|---------|--------|
| `mcp.toolcallback-enabled` 默认 `false`，且 `BACKEND_ONLY` | 与框架默认值相反。放前端等于给界面加一个"关闭全部安全关卡"的按钮 |
| 未纳管的工具**拒绝并记录**，不静默丢弃 | "server 悄悄多了个工具"正是工具投毒最典型的信号，静默丢弃等于没人知道发生过 |
| 改名 `mcp:<server>:<tool>` 放在 `GuardedToolCallback` 里，不另做装饰器 | 模型看到的名字与策略判定用的名字必须是同一个值。放同一个类里由同一字段同一方法产出，不可能被改岔；拆两层装饰器就多一处包装顺序出错的机会 |
| 前缀格式**不做成配置项** | 可配置就等于允许把两个 server 的名字空间合并，从而用 A 的纳管结果授权 B 的工具 |
| server 集合从工具策略反推（`ToolPolicyEngine.mcpServers()`），不另配名单 | 两份清单必然出现互相矛盾的状态，且两种都不报错。单一事实来源不可能与自己矛盾 |
| catalog 抛异常时返回 `unavailable` 而不是上抛 | 一个 MCP server 挂掉不该让整个 Agent 起不来；但必须记审计，否则运维只会看到"工具变少了" |

**前缀是安全边界，不是命名习惯** —— 有测试直接证明：只注册原始名的策略不能给
MCP 工具背书；纳管 `cmdb` 的 `restart` 不等于纳管 `billing` 的 `restart`；
工具名含 `:` 被拒（否则可伪造 `mcp:other:tool` 冒用别人的纳管结果）；
`LOCAL` 策略不能给 MCP 工具背书。

> **本轮暴露出一个更严重的缺口**（已记入 [DEVLONG.md](DEVLONG.md) §9 第四项）：
> 工具白名单 `ToolPolicy` 是整个安全模型的事实来源，但它**没有变更治理**——
> 没有双人复核，没有"谁在什么时候放行了什么"的可查记录。
> 配置治理那一套只覆盖 `OnCallConfigRegistry`。
> 这条的优先级高于任何新功能。

---

### 1.5 幂等账本独立成表（给不变量 I8 补上物理保证）

```
db/migration/
└── V7__tool_execution_claim.sql     tool_execution_claim：幂等键就是主键

oncall-tool-gateway/src/main/java/com/oncall/toolgateway/
├── ToolExecutionLedger.java             端口：claim / isCompleted / resultOf / complete / release
├── InMemoryToolExecutionLedger.java     单实例与测试用
└── JdbcToolExecutionLedger.java         多实例必须用这个；只用 javax.sql，零方言语法

oncall-tool-gateway/src/test/java/.../
├── InMemoryToolExecutionLedgerTest.java  6 个用例
└── JdbcToolExecutionLedgerTest.java     14 个用例（真 H2，建表语句从 V7 文件里抽）
```

#### 先更正一句我此前说错的话

我在早前的说明里讲过「I8 的物理保证已经由 `uq_agent_step_idem` 提供」。**这句是错的。**
`uq_agent_step_idem` 是 `agent_step` 上的唯一约束，粒度是**步**；
而 `GuardedToolCallback` 的幂等键是 `runId|step|toolName|canonicalArgs`，粒度比一步细
（一步里可以有多次不同的工具调用）。它约束不到工具执行，I8 当时**没有**物理保证。

#### 原先的实现为什么在并发下不成立

第④关原先是这样：

```java
if (auditLog.has(key)) { return auditLog.resultOf(key); }
... 执行 ...
auditLog.recordSuccess(key, toolName, args, result);
```

两个独立的问题：

| 问题 | 后果 |
|------|------|
| 状态存在 `InMemoryToolAuditLog` 的 map 里 | 多实例各有一份 map。同一个重试请求被负载均衡打到另一个实例，就是**第二次真执行** |
| `has()` 与 `recordSuccess()` 之间是"先查后写" | 即使单实例，两个线程也能同时通过 `has()` 检查，然后都执行 |

**唯一能真正防住的写法是让"抢占"本身成为一次原子插入**：内存版用 `putIfAbsent`，
JDBC 版靠主键冲突。应用层的 `SELECT` 再 `INSERT` 之间永远有窗口，
而"两个实例同时扩容"正是这个窗口会造成真实事故的地方。

#### 关键设计

| 关键设计 | 为什么 |
|---------|--------|
| 独立一张表，不给 `tool_audit_log` 加 `UNIQUE` | 审计一次调用可以有多条事件（审批 / 夹紧 / 成功或失败），加唯一约束直接冲突；而幂等需要一行**可变**且失败后**可删**的记录 |
| 从只追加的审计表里删行 = 篡改审计 | 「失败要能重试」要求删掉抢占行。放在审计表里，这个删除就是审计最不能容忍的操作 |
| 幂等键就是主键，不再另建唯一索引 | 键本身就是一行的身份。`claim` 用 INSERT 抢占，主键冲突即"别人已经抢到了" |
| 抢到手但没结果时**报错**，不返回 null | `claim=false` 且 `resultOf=null` 意味着另一次执行正在进行。此时返回空结果会被模型当成"执行成功了"，比失败危险得多 |
| `release` 必须带 `state='CLAIMED'` 条件 | 少了它，一次迟到的失败回调会删掉已经 `COMPLETED` 的行 —— 成功结果丢失，下次重试**真的**再执行一遍 |
| `complete` 先于 `recordSuccess` | 反过来，审计写入一失败，这次成功的执行就没有可重放记录，重试会真执行一遍。调换后即使审计抛异常，`release` 也只删 `CLAIMED`，已完成记录保得住 |
| 审批被拒 / 审批超时也要 `release` | 否则这个键永久停在 `CLAIMED`，之后所有重试都被判"重复调用"。症状是"这个操作再也做不了了"，而且**没有任何报错指向原因** |
| 重复键异常按正常分支处理，其余 SQL 异常上抛 | 把连接故障当成"已被抢占"，工具会静默不执行 —— "Agent 什么都不做"比抛异常难查得多 |
| 不用方言语法（`MERGE INTO` / `ON CONFLICT`） | H2 与 PostgreSQL 不通用；捕获重复键异常是两边都成立的写法，因此这个类能在 H2 上真跑 |
| `releaseStale(olderThanMillis)` | 实例在 `CLAIMED` 与 `complete` 之间被 kill，那一行就是永久残留，必须能被清理任务释放 |
| `complete` 在抢占行已被清理时改用 INSERT | 执行了 30 分钟才被清理任务判定为残留、随后才成功 —— 结果仍必须落库，否则下次重试真的再扩容一次 |
| 测试从 `db/migration/V7` 里抽建表语句，不用类里的 `CREATE_TABLE_SQL` 常量 | 用常量等于测一份 DDL 副本，生产上真正执行的 V7 没人验证；主键或 CHECK 写漏了测试照样绿 |

#### 有测试直接证明的性质

- 并发抢占（内存 16 线程 / JDBC 8 线程，`CyclicBarrier` 同时冲）→ **恰好一个赢家**；
- `GuardedToolCallback` 16 线程并发调用同一幂等键 → 工具**只被真正执行一次**。
  注意这里**不能**断言"只有 1 个线程成功"：抢不到执行权的线程会走 `resultOf()`，
  若赢家此刻已 `complete()`，它们会拿到上次结果并原样返回——那是**重放**，是期望行为。
  成功线程数落在 1..16 之间，取决于时序；确定的只有「执行次数 = 1」
  与「每个线程要么拿到结果、要么拿到 `ToolDeniedException`」。
  这条断言我第一版写成了 `== 1`，CI 上炸成 `expected: 1 but was: 16`；
- 执行失败 / 审批被拒 → 账本行数归零，同一请求可重试；
- 迟到的 `release` **不能**删掉 `COMPLETED` 的行（两个实现各测一遍）；
- V7 的 `chk_claim_state` 与主键真的在库里（直接对 H2 插非法状态 / 重复键，必须报错）。

#### 本轮改动的预期数字（CI 跑完前不写成"已验证"）

| 指标 | `e7b596a` 实测 | 本轮预测 | **run `33984843271` 实测** |
|------|---------------|---------|------------------------------|
| 测试类 / 报告文件 | 20 | 22 | ✅ **22** |
| 测试用例 | 280 | 304 | ✅ **304**（失败 0 / 错误 0 / 跳过 0） |
| `oncall-tool-gateway` 用例 | 80 | 104 | ✅ **104** |
| 生产类合计 | 75 | 78 | 本地清点 78（CI 不统计此项） |
| DDL 表数 | 18 | 19 | ✅ **19** |
| DDL 约束断言 | 2 条 | 4 条 | ✅ 4/4（合并为一条 notice 输出，见下） |

> 预测与实测逐项吻合。中间红过两次，都是**我的断言或声明写错，不是生产代码错**：
> run `33984616442` 编译失败（`completeSurvivesStaleCleanupRace` 调 `age()`
> 漏了 `throws`）；run `33984722772` 断言失败
> （`concurrentCallsExecuteExactlyOnce` 期望 1 实得 16，见上）。
>
> **DDL 约束断言为什么要合并成一条 notice**：GitHub 对单个 check run
> 每个级别最多保留 **10 条** annotation，本 job 已有 7 条 `DDL-OK` + 1 条幂等，
> 逐条发约束 notice 会被截断——run `33984843271` 就只留下了前两条约束，
> 后两条只能靠「没有 error 所以通过了」反推。断言通过与否必须**看得见**，
> 不能靠 absence 推断。现在输出 `DDL-约束 4/4 全部存在: ...` 一条。

#### ⚠️ 本轮查出但**没有**顺手改的一件事

`tool_audit_log`（V2）要求 `trace_id` / `tool_source` / `risk_level` /
`args_masked` / `gate_outcome` 全部 `NOT NULL`，而 `ToolAuditLog` 的方法签名是

```java
void recordSuccess(String idempotencyKey, String toolName, String args, String result);
```

**四个必填列一个都拿不到**：没有 `trace_id`，没有 `tool_source`（LOCAL / MCP），
没有 `risk_level`，`gate_outcome` 也没传（只能靠方法名反推，`recordClamped` 与
`recordApproval` 之间还分不清 `PASSED` / `CLAMPED`）。
`args` 也还是未脱敏的原文，而列名是 `args_masked`。

也就是说 **`JdbcToolAuditLog` 现在写不出来**——写出来只能往必填列塞假值，
而一张字段造假的审计表比没有审计更糟（它让人相信查过了）。
这条已记入 [DEVLONG.md](DEVLONG.md) §9，改接口会动到 `GuardedToolCallback`
全部审计调用点，属于独立一轮的工作，本轮不夹带。

### 1.6 双人复核决策核心下沉到领域层（轨道 A 第一步）

```
oncall-domain/src/main/java/com/oncall/domain/governance/
├── Operator.java            从 com.oncall.config.admin **移过来**：操作人 + 三级角色
├── ReviewVerdict.java       五个封闭值：ALLOWED / EXPIRED / NOT_AUTHORIZED / SELF_APPROVAL / STALE
├── ReviewRequest.java       判定输入（纯数据，不碰存储）
├── ReviewOutcome.java       判定 + 给人看的话
└── TwoPersonReview.java     四条规则与它们的先后顺序

oncall-domain/src/test/java/.../governance/
└── TwoPersonReviewTest.java  19 个用例

oncall-config-admin
├── ConfigAdminController    confirm() 改为委托 TwoPersonReview，本地只做 HTTP 状态码映射
└── Operator.java            **已删除**（移到领域层）
```

#### 为什么这一步要先做

`ToolPolicy`（工具白名单）是整个安全模型的事实来源——加一条 MCP 工具策略
等于放行一个远端工具——但它**没有变更治理**：没有双人复核，
也没有"谁在什么时候放行了什么"的可查记录（[DEVLONG.md](DEVLONG.md) §9 第四项）。

配置侧那一套（待复核单 / 双人 / 不能自审 / 过期失效 / 期间被改过则失效）
已经写好且测过。**直接复制一份到工具侧是最省事也最错的做法**：
两份复核逻辑一定会分叉，而分叉不会报错——
「配置侧不允许自审、工具侧忘了这一条」在所有功能测试里都是绿的，
只有真的有人自审自批时才会暴露，而那时事故已经发生。
所以先把规则抽成一份，两边都只当调用方。

#### 关键设计

| 关键设计 | 为什么 |
|---------|--------|
| 判定是**纯函数**，不访问存储 | 四条规则能被穷举单测；读当前值、删待复核单、写审计都是调用方的事。规则与存储混在一起时，测一条规则就要搭一套存储 |
| 「需要 ADMIN」**不做成参数** | 写成 `Predicate<Operator>` 会让调用方有机会传恒真判断。守卫自己的东西不能交给被守卫的对象——与 `HIGH_RISK_KEYS` 刻意硬编码同一个理由 |
| 五个判定值而不是布尔 | 四种拒绝要映射到不同状态码（410/403/409/409），前端处置也不同：过期要重走流程，自审要换人。合成布尔就在 UI 上变成同一句"操作失败" |
| 没有 `force` 入口，没有超级管理员角色 | 高危变更需要的是「另一个人也同意」，不是「某人权限更大」 |
| 过期判定**从 `ConfigAdminController` 移进核心** | 原先过期检查在 `livePendingOrThrow` 里，属于第二条实现。工具策略那侧只会有一个核心，两处不一致就没人发现 |

#### 顺序是承重的，而且有测试守着

```
① 过期  →  ② 角色不足  →  ③ 不能自审  →  ④ 期间已被改过
```

**② 必须排在 ④ 之前**：④ 的提示语里含**当前生效值**
（`发起时 X，现在 Y`）。若顺序颠倒，一个 VIEWER 就能靠反复发起复核，
从错误消息里把当前生效值一条条读出来——`BACKEND_ONLY` 的键对前端是 404
（连存在性都不泄露），却会从这条错误消息里漏出值，**比 404 更糟**。

这条不是靠读代码时的自觉，而是两条直接断言：构造一个
「VIEWER + 值已被改成 `BOUND_AUTO_SECRET`」的输入，
断言返回 `NOT_AUTHORIZED` **且消息里不含那个值**；自审同理。

#### 行为未变，只是搬了家

`ConfigAdminController` 的 HTTP 语义逐条保持：自审 409、EDITOR 复核 403、
过期 410、期间被改过 409 且清掉单子、复核单只能用一次、
不存在的单 404。**`ConfigAdminControllerTest` 的 24 个用例一字未改**，
全靠新核心通过——这就是"行为没变、只是搬了家"的证据。
`ConfigAccessPolicyTest` 只改了 import 行（`Operator` 换包），8 个断言未动。

#### 本轮改动的预期数字（CI 跑完前不写成"已验证"）

| 指标 | `a5553e5` 实测 | 本轮预测 | **run `33985929578` 实测** |
|------|---------------|---------|-------------------------------|
| 测试类 / 报告文件 | 22 | 23 | ✅ **23** |
| 测试用例 | 304 | 323 | ✅ **323**（失败 0 / 错误 0 / 跳过 0） |
| `oncall-domain` | 11 类 / 16 用例 | 16 类 / 35 用例 | 本地清点 16 / 35（CI 不分模块报数） |
| `oncall-config-admin` | 12 类 / 35 用例 | 11 类 / 35 用例 | 本地清点 11 / 35，用例数不变 |
| 生产类合计 | 78 | 82 | 本地清点 82（CI 不统计此项） |

> 预测与实测逐项吻合。中间红过一次，是**我踩了同一个坑第二次**：
> run `33985838506` 编译失败，`ReviewOutcome` 里既有实例方法 `boolean allowed()`
> 又写了 `static ReviewOutcome allowed()` —— 同名同参数个数、只有返回类型不同，
> Java 不允许。`Approval` 里踩过一次（那次是与 record 组件同名）。
> 已在 [DEVLONG.md](DEVLONG.md) §6 记成一般规则：**record 的静态工厂一律用动词命名**。

### 1.7 工具白名单变更治理（轨道 A 第二步）

```
oncall-tool-gateway/src/main/java/com/oncall/toolgateway/governance/
├── PolicyRiskDelta.java                  风险方向判定：这次变更是放宽还是收紧
├── ToolPolicyChange.java                 一次拟议变更（GRANT / UPDATE / REVOKE）
├── ToolPolicyChangeTicket.java           待复核单 + 策略签名
├── ToolPolicyChangeTicketStore.java      端口（多实例下 A 发起的单子 B 必须能复核）
├── InMemoryToolPolicyChangeTicketStore.java
├── ToolPolicyChangeAudit.java            端口：谁在什么时候放行/收紧了什么
├── InMemoryToolPolicyChangeAudit.java
├── GovernanceException.java              携带 ReviewVerdict，供接入层映射状态码
└── ToolPolicyGovernance.java             propose / confirm / reject / preview

oncall-tool-gateway/src/test/java/.../governance/
├── PolicyRiskDeltaTest.java              17 个用例
└── ToolPolicyGovernanceTest.java         24 个用例
```

#### 堵的洞

`ToolPolicy` 是整个安全模型的事实来源——**加一条 MCP 工具策略等于放行一个远端工具**。
但在此之前加策略就是调一次 `ToolPolicyEngine.register()`：
没有第二人复核，也没有"谁在什么时候放行了什么"的可查记录。
配置治理那一套只覆盖 `OnCallConfigRegistry`。

这相当于给「连接」（`mcp.allowed-servers`）装了锁，
而真正承重的「授权」是敞开的。

#### 核心判据：治理要求跟着风险方向走，不是跟着"有没有改动"走

| 方向 | 是否需要双人 | 为什么 |
|------|-------------|--------|
| **放宽**（让 AI 能做更多） | ✅ 必须 | 加一条策略就是放行一个工具 |
| **收紧**（让 AI 能做更少） | ❌ 不需要 | 默认拒绝之下，撤掉一条策略让系统**严格变安全** |

这条不对称是刻意的：**如果撤掉一个危险工具也要凑齐两个人，
结果就是那个危险工具继续留在白名单里**——
把安全改进卡在双人流程后面，恰好保护了它本该消除的风险。

算「放宽」的五个维度（**任一**放宽即整体放宽，刻意不打分）：
`risk` 下调 · 取消 `requiresApproval` · 取消 `requiresDualApproval` ·
去掉 `argsJsonSchema` · `approvalTimeout` 变长。

不打分是因为打分会让一处收紧去抵一处放宽，
而「关掉审批」和「把风险从 HIGH 降到 READ_ONLY」不是可以互相抵消的东西。

**两个刻意的例外**：
- **新增工具无条件算放宽，包括 `READ_ONLY`**。加进白名单就等于允许模型调用它，
  而「只读」这个标签本身正是发起人的断言——复核人要做的事就是核对这个断言。
  数据泄露不需要写权限。
- **`source` 变化（LOCAL ↔ MCP）不算宽窄**：换的是信任边界，不是权限大小。
  但它会记进理由里，复核人需要知道。

#### 共享什么、不共享什么

| | 内容 |
|---|---|
| **共享** | 判定规则 `TwoPersonReview`（领域层，只有一份，见 §1.6） |
| **不共享** | 单据形状：配置侧载荷是两个 String，工具侧是一个 `ToolPolicy` 加变更类型 |
| **不共享** | 哪些变更要复核：配置侧是硬编码的 5 个键，工具侧是 `PolicyRiskDelta` 算出来的方向 |

**规则分叉才会出事故，单据形状分叉不会**——所以只共享规则。
硬把两边塞进同一个记录，只会让两边都长出一堆「这个字段在我这儿没意义」。

#### ⚠️ 这一轮**没有**堵上的一半（明确登记，不假装已封死）

`ToolPolicyEngine.register()` / `revoke()` **仍然是 `public`**。
也就是说这个治理层是「应当走的路」，还不是「唯一能走的路」——
拿到引擎引用的代码仍然可以直接改白名单。

要真正封死得把这两个方法降为包级可见，而 `McpToolRegistrarTest` 在
`com.oncall.toolgateway.mcp` 子包里用了它们（13 处），需要一并调整。
这是独立一轮的事，本轮不夹带。
`ToolPolicyGovernanceTest` 里的 `staleTicketIsRejectedAndRemoved`
甚至**直接利用了**这个缺口来构造"期间被别人改过"的场景。

另外**还没有 REST 接入点**：这一层是领域服务，
`GovernanceException` 携带 `ReviewVerdict` 就是为了让接入层能正确映射状态码，
但接入层本身属于 A3。

#### 本轮改动的预期数字（CI 跑完前不写成"已验证"）

| 指标 | `de5ae41` 实测 | 本轮预测 | **run `33986641697` 实测** |
|------|---------------|---------|-------------------------------|
| 测试类 / 报告文件 | 23 | 25 | ✅ **25** |
| 测试用例 | 323 | 365 | ✅ **365**（失败 0 / 错误 0 / 跳过 0） |
| `oncall-tool-gateway` | 20 类 / 104 用例 | 29 类 / 146 用例 | 本地清点 29 / 146（CI 不分模块报数） |
| 生产类合计 | 82 | 91 | 本地清点 91（CI 不统计此项） |

> 预测与实测逐项吻合。中间红过一次（run `33986494127`），
> 见下节——**那一次是生产代码的缺陷，不是断言写错**。

#### 第一次 CI 红出来的东西：审计排序不确定

run `33986494127` 只红了一条：`requesterCanReject` 期望 `REJECTED`、实得 `PROPOSED`。
**这不是测试写错，是生产代码的缺陷。**

审计条目原先只按 `System.currentTimeMillis()` 排序。发起与驳回落在
**同一毫秒**是正常操作而不是边缘情况，两条记录时间戳相同 ⇒ 顺序不确定 ⇒
`recent(1)` 可能返回先发生的那条。

后果不只是测试随机红：**「先发起还是先被拒」是事后追责时要回答的问题，
它必须有唯一答案。** 一个顺序不确定的审计日志，在真正需要它的时候是不可用的。

两条修法都做了：

| 改动 | 为什么 |
|------|--------|
| `Entry` 增加 `seq`（插入序号），排序改为 `(atMillis, seq)`，比较器抽成 `Entry.order()` | 给同一毫秒内的事件定序。这是审计的必备属性，不是实现细节 |
| 事件时刻由 `ToolPolicyGovernance` 传入，审计实现不再自己取时钟 | 治理服务持有可注入时钟用于测过期；审计若另取 `System.currentTimeMillis()`，同一流程里就有两个时钟，测试推进了一个另一个不动。**这个"两个时钟"的坑本项目已经踩过一次** |

并补了一条专门断言：整段流程不推进时钟，三条审计事件仍必须按发生顺序排列。

---

## 二、下一步：按模块推进（M1 → M7）

### M1 — 完成 tool-gateway 的落地实现（1 周）🟡 安全部分已完成，剩 5 项工程收尾

**已有**：接口 + 策略引擎 + 装饰器骨架 + **3 个纯 Java 落地实现（本轮新增）**。

本轮新增（均为纯 Java，无外部依赖）：

| 类 | 说明 |
|----|------|
| `Sha256IdempotencyStore` | SHA-256 幂等键，含参数规范化 |
| `InMemoryToolAuditLog` | 内存审计，供单测/本地/S0 影子模式起步 |
| `clamp/ScaleReplicasClamper` + `ReplicaStatePort` | **第一个真实夹紧器**：上限 `current+maxDelta`、下限 `minReplicas`、查不到当前值即拒绝 |
| `domain/autonomy/*` | `AutonomyLevel`（四阶段放权）+ `AutonomyGate` + `AlertSeverity` |

测试从 18 个增至 **40 个**，新增覆盖：注入攻击生成 `replicas=0` 被夹到 `minReplicas`、爆炸半径限制、查不到副本数即拒绝、S0-S2 绝不允许自动执行、P0/P1 永不自动执行。

**仍待补**：

| 待办 | 说明 |
|------|------|
| `JsonScaleArgsAdapter` | 把 `String` JSON 转成 `ScaleRequest`（需 Jackson），并实现 `ArgClamper` 接口 |
| `JdbcToolAuditLog` | 落 `tool_audit_log` 表。**原先的写法（"`idempotency_key` 加 UNIQUE，因为内存版在多实例下幂等会失效"）已作废**：幂等已由 `tool_execution_claim`（V7）+ `JdbcToolExecutionLedger` 独立解决，审计表不该也不能承担它（一次调用多条事件、且失败要能删行）。剩下的问题是审计自身的持久性——内存版重启即丢，出事后无从追责。**且当前被卡住**：接口签名给不出表的必填列，见 §1.5 末尾与 [DEVLONG.md](DEVLONG.md) §9 第五项 |
| 规范化升级 | `canonical()` 现在只去空白；应改为 Jackson 读 `TreeMap` 再序列化，消除 key 顺序影响 |
| `WecomApprovalGate` | 企微卡片 + `expires_at` + 超时升级 + 双人复核 |
| `AutonomyLevel` 接入调用链 | 与 `KillSwitch` 取交集：先 `assertAllowed()` 再 `canAutoExecute()` |

**已完成**：`GuardedToolCallbackTest`（30 个用例，见下）、
**`McpToolRegistrar`**（22 个用例，见 §1.4，堵住 I14）、
**幂等账本三件套**（24 个用例，见 §1.5，堵住 I8）——M1 的两个**安全**缺口都已堵。

上面 5 项剩余的都是工程收尾，不是安全缺口。
`JdbcToolAuditLog` 仍然带安全含义，但**理由已经变了**：原先写的是
「内存版在多实例下幂等会失效」——那句话现在不成立了，幂等已由
`tool_execution_claim`（V7）+ `JdbcToolExecutionLedger` 独立解决，与审计表无关；
而 `uq_agent_step_idem` 的粒度是「步」，本来就管不到工具调用。
它真正的安全含义是**审计自身的持久性**：内存版重启即丢，出事后无从追责。
而且它现在**被卡住**——`ToolAuditLog` 的签名给不出 `tool_audit_log` 的必填列
（`trace_id` / `tool_source` / `risk_level` / `gate_outcome`），
见 §1.5 末尾与 [DEVLONG.md](DEVLONG.md) §9 第五项。

**验收**（对应修复方案 F1.7）：
- [ ] 未注册工具 → `ToolDeniedException` + 有拒绝审计
- [ ] MCP 运行期新增工具 → 不暴露 + 告警
- [ ] `scale_replicas` 传 `replicas: 0` → 夹到 `minReplicas` + clamped 审计
- [ ] kill switch 切 `READ_ONLY` → 写工具立即被拒（无需重启）
- [x] 同一幂等键重复调用 → 只执行一次

**`GuardedToolCallbackTest` 已落地（24 个用例）**——F1 的验收点。
此前 `GuardedToolCallback` 只是"编译通过"，七道关卡一个测试都没有。
测试不只验证每道关卡单独生效，还验证**关卡之间的先后关系**（顺序不可调换）：

| 顺序断言 | 为什么顺序重要 |
|---------|--------------|
| ① 默认拒绝 在 ② kill switch 之前 | 否则未注册工具的存在性会被错误信息泄露 |
| ② kill switch 在 ③ 参数夹紧之前 | 否则被禁的工具还会留下夹紧审计，制造噪音与误导 |
| ④ 幂等 在 ⑤ 审批之前 | 否则重放会二次打扰审批人 |

另外三条安全性质也做了断言：
- 执行失败时**异常必须继续抛出**（不能被审计逻辑吞掉），且失败结果**不能**被当成可重放的成功结果；
- 审批人看到的必须是**夹紧后**的参数——否则批的不是真正会执行的东西；
- kill switch 是**热生效**的，切档后同一个装饰器实例的下一次调用立即改变行为，无需重建。

### M1.5 — 配置治理与前端可配置化（本轮新增，✅ 已完成）

对应架构约束：**能做成前端可交互配置的一律不得硬编码在后端，兜底机制除外。**
设计说明见 `配置外置与前端可配置设计.md`。

核心机制：

| 类 | 作用 |
|----|------|
| `ConfigTier` | 三级分类：`RUNTIME_HOT`（前端可改热生效）/ `REQUIRES_MIGRATION`（可改但需重建索引）/ `BACKEND_ONLY`（前端不展示不可改） |
| `ConfigSpec` | 一份声明同时服务三个消费方：前端渲染、后端校验、评审审计 |
| `ConfigRegistry` | **启动自检**——重复键、缺迁移提示、默认值不可解析一律启动失败 |
| `ConfigValidator` | 写入时四层校验；`BACKEND_ONLY` 从前端写入时**不确认该键是否存在** |
| `ConfigSnapshot` | 一次请求内配置一致，并标明基于哪一版 revision |
| `ConfigSchemaExporter` | 导出前端可直接渲染的 JSON schema，后端加配置项前端自动多一个表单项 |

**41 项参数**逐条对应 `质量与可靠性设计.md §3.1` 的 19 项冻结参数 +
`开工前决策冻结与返工风险评估.md §5` 补充的 6 项，并用测试锁住默认值一致性。

两个上游硬限制被**编码成校验边界**，前端物理上填不出会导致故障的值：

| 参数 | 上界 | 依据 |
|------|-----|------|
| `vector.dimension` | 2000 | pgvector HNSW 索引上限；上游模型提供 2048 维，选它会建索引失败 |
| `vector.embedding-batch-size` | 10 | 上游 embedding API 单批上限；框架默认 10000 会在灌库阶段失败 |

**留在 `BACKEND_ONLY` 的三类**（理由见设计文档 §2）：兜底机制配置（约束的例外）、
DDL 绑定项（向量维度/距离函数/索引类型——改了是数据迁移不是配置变更）、凭据。

测试 64 个，其中三类守的不是业务逻辑而是**架构约束本身**：
兜底项必须 `BACKEND_ONLY`、热改数值项必须有边界、后端专属键名不得出现在前端 schema 里。
另有跨模块的 `ConfigEnumAlignmentTest`（在 `oncall-tool-gateway`），
守配置里的字符串取值与 `AutonomyLevel` / `RunMode` 枚举逐一对应——这类错位没有编译期保护。

**已完成**：`JdbcConfigStore` + `JdbcConfigAuditLog`（`com.oncall.config.store`）
+ `db/migration/V1__config_governance.sql`。此前只有内存实现，配置重启即丢。

JDBC 实现有三个不显眼但会出事的决定：

| 决定 | 不这么做会怎样 |
|------|--------------|
| `remove` 用**墓碑行**（值置 NULL）而非 DELETE | `revision()` 取 `MAX(revision)`，真删行会让修订号倒退，`ConfigService` 的快照缓存会误判"没变化"而**读到旧值** |
| upsert 先 UPDATE 再按影响行数 INSERT | H2 的 `MERGE INTO` 与 PostgreSQL 的 `ON CONFLICT` 不通用，用任一方言都只能跑在一个库上 |
| 只用 JDK 的 `javax.sql`/`java.sql` | 引 Spring JDBC 会破坏本模块"生产代码零外部依赖"的约束（H2 只在 test scope） |

测试用 **H2 内存库跑真 SQL**（`2.3.232`，test scope）。mock `Connection` 只能证明
"我以为的 SQL 是我以为的 SQL"，证明不了语句真能执行——列名拼错、方言不兼容只有真库能抓到。

**已完成**：管理 REST 接口 + RBAC + 高危项双人复核（`oncall-config-admin` 模块）。

这一层单独成模块，是为了不破坏 `oncall-config` 的"生产代码零外部依赖"——
一旦在里面放 `@RestController`，这条约束就破了，而且破得毫无必要：
HTTP 绑定本来就该是可替换的适配器。依赖方向是 admin → config，不反向。

四个不显眼但会出事的决定：

| 决定 | 不这么做会怎样 |
|------|--------------|
| BACKEND_ONLY 返回 **404 而不是 403** | 403 泄露"这个键存在但你不能碰"，攻击者可据此枚举出系统有哪些兜底开关 |
| 高危键清单**硬编码，不做成配置项** | 若能配置，提权者可以先把该键移出高危清单再从容修改，双人复核被自己保护的机制绕过 |
| 待复核单**有 15 分钟 TTL** | 隔三天再复核，复核人面对的是完全不同的系统状态，他的"同意"不再是对当前状态的判断 |
| 复核前检查**该键期间是否被改过** | 否则复核人判断的依据已经失效，却照样能批准 |

角色刻意只有三级且**没有超级管理员**：高危配置需要的是"另一个人也同意"，
不是"某个人权限更大"——加一个超级角色只会让双人复核形同虚设。

**待做**：把业务代码接到 `ConfigService` 并用 ArchUnit 禁止模块外出现 `@Value` →
`X-Operator` 头必须由网关鉴权后写入并剥掉客户端自带的同名头（接入时必须核对）→
`PendingChangeStore` 换数据库实现（多实例下 A 发起的变更要能被 B 复核）→
Prompt 模板配置化（必须连带双人复核）。

### M2 — Flyway 建表（3 天）

7 张表，DDL 已在 `修复方案.md` 写好：
`agent_run` / `agent_step` / `tool_audit_log` / `approval_record` / `alert_group` / `alert_event` / `kb_document_version`

**关键点**：`agent_step.idempotency_key` 必须 UNIQUE——这是幂等的物理保证，不能只靠应用层判断。

**本轮追加 3 张**（配置治理与向量库带来的）：

| 表 | 用途 | 关键点 |
|----|------|-------|
| `app_config` | 配置覆盖值（`JdbcConfigStore` 的落地） | 只存**被改过**的键，默认值留在代码声明里，这样「哪些参数被改离基线」一目了然 |
| `config_audit_log` | 配置变更审计 | 与 `tool_audit_log` 同级保留期，建议 180 天 |
| `kb_chunk_vector` | 向量表 | DDL 见 `开工前决策冻结与返工风险评估.md §1.3`。`id` **不要**用 `DEFAULT uuid_generate_v4()`——chunk ID 必须确定性生成，否则 chunk 级引用与增量索引都做不了 |

### M3 — Plan-Execute-Replan 编排（2 周）

Spring AI Alibaba **没有内置 `planexecute`**（Eino 有），要用 `StateGraph` 条件边自己搭：

```
START → planner → plan_validator → executor → should_continue? ─yes→ replanner → executor
                                                    └─no──→ reporter → END
```

**待补**：`OpsAgentWorker`（`@KafkaListener` + 行锁 + `step_cursor` 续跑）、`RunBudget`（步数/重规划/token/成本四个独立计数器）、`PlanValidator`、补偿栈。

**验收**：杀掉 worker 重启后从 `step_cursor` 续跑；同一事件投递 3 次只执行一次。

### M4 — 只读工具接入（1.5 周）

先只做只读的 5 个：`query_prometheus_alerts` / `query_logs` / `query_metrics` / `query_runbook` / `query_cmdb_service`。

每个都走 **Adapter 模式**：`PrometheusAlertAdapter implements AlertSourcePort`，Agent 只依赖 Port。

**七道关卡**逐个过（凭据/超时重试/熔断/限流/契约隔离/不可信边界/审计）。

### M5 — RAG 修复（2 周）

- 挂 `RetrievalAugmentationAdvisor`（`架构设计方案.md` §3.2 已改好）
- 混合检索：dense + BM25 + RRF
- 版本化索引：`vectorStore.delete("doc_id == 'x'")` 后再 `add`
- 评测集 v1（≥100 条）+ 命中率基线

### M6 — 告警接入 + 状态机联动（1.5 周）

Alertmanager Webhook（**200ms 内 ACK**）→ 归一化 → `alert_group` 聚合 → 风暴熔断 → 发事件 → 状态机 `NEW → ACK`。

配 60s 超时扫描器（`TicketStateMachine.timeoutOf` 已就绪）。

### M7 — 写操作 + 审批 + 降级（2 周）

**必须在 M1-M6 全部验收后才开始。** 注入测试集 20/20 是硬门槛。

---

## 三、设计模式落点对照

已实现的：

| 模式 | 位置 |
|------|------|
| **State** | `TicketStateMachine`（显式转移表，非法转移抛异常） |
| **Decorator** | `GuardedToolCallback`（可再叠限流/追踪层） |
| **Strategy** | `ArgClamper`、`IdempotencyStore`、`ApprovalGate` |
| **Chain of Responsibility** | `GuardedToolCallback` 内的七道关卡 |
| **Builder** | 跟随 Spring AI 的 `*.builder()` 风格 |

待实现的：

| 模式 | 落点 | 模块 |
|------|------|------|
| **Adapter** | 各第三方系统适配器 | M4 |
| **Command** | `PlanStep` / `ToolInvocation` 可序列化命令 | M3 |
| **Memento** | `agent_run.step_cursor` + 操作栈 | M3 |
| **Factory Method** | `AlertSourceAdapterFactory` | M4/M6 |
| **Observer** | 状态变更事件 → 通知/复盘/索引 | M6 |
| **Facade** | `ToolGateway` 对 Agent 的统一入口 | M1 |

---

## 四、还没解决的三件事

1. **知识库盘点没做**。RAG 的效果上限由 Runbook 的数量和质量决定，而不是由 topK 和 threshold 决定。建议 M5 之前先花两天盘一遍：有多少篇、覆盖哪些告警、多久没更新。
2. **CMDB 的"告警 → service"映射没设计**。按 `service` 过滤召回的前提是知道告警属于哪个服务，查不到时的降级路径也还没定。
3. **成本模型仍是空的**。等 M3 的 token 埋点上线后，用真实数据反推单告警成本，替换掉原方案那个没有假设支撑的"3000-5000 元/月"。
