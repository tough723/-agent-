# 开发推进说明（DEVELOPMENT.md）

> 配套文档：`架构评审报告.md`（问题）→ `修复方案.md`（方案）→ 本文（代码现状 + 下一步）
> 最后更新：2026-09-04

---

## ⚠️ 首先：本轮代码未经编译验证

**当前沙箱环境无 JDK、无 Maven，且除白名单外无外网**（已实测：`java`/`mvn` 命令不存在，`apt-get` 无权限安装，`repo1.maven.org` 与 `deb.debian.org` 连接失败）。

因此下面所有 Java 代码**一行都没有编译过**。所有 API 签名均对照 Spring AI / Spring AI Alibaba 官方文档逐个核对，但"对照文档正确"和"能编译通过"是两件事。

**你拿到代码后第一件事**：

```bash
mvn -q -pl oncall-domain test          # 纯 Java，零外部依赖，应该立刻能跑
mvn -q -pl oncall-tool-gateway test    # 依赖 Spring AI，可能需要调坐标（见下）
mvn dependency:tree -Dincludes=org.springframework.ai   # 把实际版本记进 ADR-001
```

### 已加 CI 代为验证

`.github/workflows/ci.yml` 会在每次 push 时用 JDK 17 + Maven 真实编译并跑测试，分模块执行以便定位失败点，并把解析到的 Spring AI 版本打印出来（供 ADR-001 使用）。

**所以推上去之后，CI 结果就是编译验证结论** —— 不需要本地有 JDK。查看方式：

```bash
gh run list --branch arena/01a06d8c-agent
gh run watch <run-id>
```

若 `oncall-tool-gateway` 那步失败而 `oncall-domain` 通过，几乎可以确定是 Spring AI 依赖坐标问题（见下表第 1 行），业务逻辑本身没问题。

**已知最可能需要调整的地方**（按概率排序）：

| 位置 | 风险 | 处理 |
|------|------|------|
| `oncall-tool-gateway/pom.xml` 的 `spring-ai-model` | 工具 API 所在 artifact 名未验证 | 解析不到就换成 `spring-ai-alibaba-starter-dashscope` |
| `GuardedToolCallback` 的 `ToolContext` 导入 | 包路径 `org.springframework.ai.chat.model` 未验证 | 按 IDE 提示改 |
| `ToolMetadata.builder()` / `ToolDefinition.name()` | 已对照官方文档核实，但跨 1.1/2.0 有差异 | 以 pin 的版本为准 |
| `MessageChatMemoryAdvisor` 构造方式（`架构设计方案.md` §3.2） | 1.x 有构造器与 builder 两种写法 | 二选一 |

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
```

**这两个模块是纯逻辑，没有 Spring 上下文**——`oncall-domain` 完全零依赖，`oncall-tool-gateway` 只有 `GuardedToolCallback` 一个类碰 Spring AI。这样即使 Spring AI 坐标要调，90% 的代码不受影响。

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
| `GuardedToolCallbackTest` | **关键**：用 fake `ToolCallback` 验证七道关卡逐个生效（需 Spring AI 在 test classpath） |
| `AutonomyLevel` 接入调用链 | 与 `KillSwitch` 取交集：先 `assertAllowed()` 再 `canAutoExecute()` |

**验收**（对应修复方案 F1.7）：
- [ ] 未注册工具 → `ToolDeniedException` + 有拒绝审计
- [ ] MCP 运行期新增工具 → 不暴露 + 告警
- [ ] `scale_replicas` 传 `replicas: 0` → 夹到 `minReplicas` + clamped 审计
- [ ] kill switch 切 `READ_ONLY` → 写工具立即被拒（无需重启）
- [ ] 同一幂等键重复调用 → 只执行一次

### M2 — Flyway 建表（3 天）

7 张表，DDL 已在 `修复方案.md` 写好：
`agent_run` / `agent_step` / `tool_audit_log` / `approval_record` / `alert_group` / `alert_event` / `kb_document_version`

**关键点**：`agent_step.idempotency_key` 必须 UNIQUE——这是幂等的物理保证，不能只靠应用层判断。

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
