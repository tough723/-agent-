# 开发推进说明（DEVELOPMENT.md）

> 配套文档：`架构评审报告.md`（问题）→ `修复方案.md`（方案）→ 本文（代码现状 + 下一步）
> 最后更新：2026-09-04

---

## ⚠️ 首先：本轮代码未经编译验证

**当前沙箱环境无 JDK、无 Maven，且除白名单外无外网**（已实测：`java`/`mvn` 命令不存在，`apt-get` 无权限安装，`repo1.maven.org` 与 `deb.debian.org` 连接失败）。因此本地无法编译，改为用 GitHub Actions 代为验证。

### CI 已经验证通过（不是"待你验证"）

`.github/workflows/ci.yml` 在每次 push 时用 JDK 17 + Maven 3.9.16 真实编译并跑测试。
**最近一次全绿**：run `33952866933` / conclusion `success`。

| 步骤 | 结果 |
|------|------|
| Check all POM files are well-formed XML | ✅ |
| Validate POM model (all modules) | ✅ |
| Record resolved Spring AI version | ✅ |
| Build & test `oncall-domain`（纯 Java，零外部依赖） | ✅ |
| Build & test `oncall-config`（配置治理，纯 Java，零外部依赖） | ✅ |
| Build & test `oncall-tool-gateway`（依赖 Spring AI） | ✅ |
| Assert tests actually ran | ✅ `报告文件=12 测试总数=148 失败=0 错误=0 跳过=0` |

**测试数字对得上**：12 个报告文件对应 12 个测试类，148 个测试对应本地 148 个 `@Test`。
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
│       ├── OnCallConfigRegistry.java    39 项参数声明（23 热改 / 8 迁移 / 8 后端专属）
│       ├── schema/ConfigSchemaExporter.java  前端 JSON schema 导出（手写，零依赖）
│       └── store/
│           ├── JdbcConfigStore.java     配置覆盖值持久化（墓碑行 + 方言无关 upsert）
│           └── JdbcConfigAuditLog.java  配置变更审计持久化
│   └── src/test/java/...                5 个测试类（JDBC 用 H2 内存库跑真 SQL）
├── oncall-config-admin/                 ✅ 配置治理的 REST 接入层（本轮新增）
│   └── src/main/java/com/oncall/config/admin/
│       ├── ConfigAdminController.java   读/写/双人复核/重载
│       ├── Operator.java                操作人 + 三级角色（刻意没有超级管理员）
│       ├── ConfigAccessPolicy.java      权限判定 + 高危键清单（硬编码，不可配置）
│       ├── PendingChange.java           待复核单：带 TTL 与"期间被改过"检测
│       ├── PendingChangeStore.java      端口 + InMemory 实现
│       ├── ConfigItemView.java          前端视图 DTO（敏感值掩码）
│       └── AdminApiException.java       统一异常 → HTTP 状态码
│   └── src/test/java/...                3 个测试类（MockMvc standaloneSetup）
└── oncall-tool-gateway/                 ✅ P0 安全核心
    └── src/main/java/com/oncall/toolgateway/
        ├── ToolPolicyEngine.java        默认拒绝 + 拒绝事件上报
        ├── GuardedToolCallback.java     Decorator：七道关卡
        ├── KillSwitch.java + RunMode.java
        ├── ApprovalGate.java + Approval.java
        ├── ToolAuditLog.java            审计 + 幂等
        ├── IdempotencyStore.java
        └── ArgClamper.java              Strategy：参数夹紧
    └── src/test/java/.../ToolPolicyEngineTest.java       10 个用例
        └── GuardedToolCallbackTest.java  24 个用例：七道关卡 + 关卡顺序 + 三条安全性质
```

**这三个模块都是纯逻辑，没有 Spring 上下文**——`oncall-domain` 与 `oncall-config` 完全零依赖，`oncall-tool-gateway` 只有 `GuardedToolCallback` 一个类碰 Spring AI。这样即使 Spring AI 坐标要调，90% 的代码不受影响。

---

## 二、下一步：按模块推进（M1 → M7）

### M1 — 完成 tool-gateway 的落地实现（1 周）⭐ 当前在这

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
| `JdbcToolAuditLog` | 落 `tool_audit_log` 表，`idempotency_key` 加 UNIQUE 约束。**内存版在多实例下幂等会失效** |
| 规范化升级 | `canonical()` 现在只去空白；应改为 Jackson 读 `TreeMap` 再序列化，消除 key 顺序影响 |
| `WecomApprovalGate` | 企微卡片 + `expires_at` + 超时升级 + 双人复核 |
| `McpToolRegistrar` | 关掉自动注册后显式纳管 + 打 `mcp:<server>:` 前缀 + 启动快照比对 |
| `AutonomyLevel` 接入调用链 | 与 `KillSwitch` 取交集：先 `assertAllowed()` 再 `canAutoExecute()` |

**已完成**：`GuardedToolCallbackTest`（24 个用例，见下）。

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

**39 项参数**逐条对应 `质量与可靠性设计.md §3.1` 的 19 项冻结参数 +
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
