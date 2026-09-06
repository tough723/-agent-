# CLAUDE.md

给 Claude Code 的项目约定。**通用约定在 [AGENTS.md](AGENTS.md)，这里只写增量。**

---

## 1. 开工前必做

```bash
git fetch origin arena/01a06d8c-agent
git rev-parse HEAD; git rev-parse FETCH_HEAD      # 两者不等说明本地历史被重置了
ls *.md                                            # 确认文档集完整
```

**这个工作区的本地 git 历史会被周期性重置到 `5a4fc36`。**
推送被拒 `(fetch first)` 时不要强推，按 [AGENTS.md §5](AGENTS.md) 用
`git reset --soft` 恢复。

---

## 2. 这个仓库最容易犯的 5 个错

| # | 错 | 后果 |
|---|-----|------|
| 1 | 在 XML 注释里写减号分隔线 | POM 变 `Non-parseable`，**什么都编译不了**，而且错误信息不指向注释 |
| 2 | 给 `oncall-config` 加任何生产依赖 | 破坏"零依赖"约束，所有模块都被 Spring 传染 |
| 3 | 让 `@PathVariable` / `@RequestParam` 不写名字 | 父 POM 有 `-parameters` 所以能跑，但**换构建方式就变运行时 500** |
| 4 | 在测试里用 `System.currentTimeMillis()` 判断过期 | 与注入时钟混用，恒判过期，且**失败信息指向错误的地方** |
| 5 | 把 CI 失败信息逐行发成 annotation | 撞上「每 run 每级别 10 条」上限，把失败栈挤掉 |

---

## 3. 工具使用提示

### 3.1 不要做的事

- **不要尝试安装 JDK 或跑 `mvn`。** 沙箱没有 Java，也没有 Maven Central 出口。
- **不要 `fetch_page` 到 `javadoc.io`** —— 返回空。用 `docs.spring.io`。
- **不要 `curl raw.githubusercontent.com`** —— 返回 `000`。
- **不要用 `mvn … | tee`** —— 吞掉退出码，会把失败伪装成成功（本项目踩过两次）。

### 3.2 可以做的事

- `web_search` 与 `fetch_page` 到 `mvnrepository.com` **都能用**。
  写代码前核实 API 签名与 artifact 版本，比浪费一轮 CI 便宜。
- `python3` 做静态检查（**注意没有 `yaml` 模块**）。
- `gh` 与 `api.github.com` 可用；**但 job 日志下载不可用**，只有 annotation 可读。

### 3.3 批量写文件

优先用 `write_file`（一次一个文件），**不要用一个 heredoc 批量写多个文件**——
这个工作区出现过 heredoc 批量写入静默丢失的情况。批量操作后一定 `find` / `ls` 复核。

---

## 4. 写代码的节奏

1. **先读现有 API，不要猜。** 这个项目里我凭空 import 过一个不存在的
   `ConfigSchemaExporterHolder`，也调用过 `ConfigService` 上不存在的
   `auditHistory()`。读一遍 `grep -nE "public "` 只要几秒。
2. **写完立刻跑静态检查**（包名↔目录、未使用 import、括号、record 工厂同名）。
3. **推送，读 annotation，按真实错误改。** 不要凭猜测连续改多次。
4. **改完同步文档**，特别是计数类的数字。

---

## 5. 报告结果时

- 说清**跑了什么命令、返回了什么**。不要说"应该没问题"。
- **算过发现不重要的，也要说**。例：重试的 token 成本只有 +1.4%（¥11/月）——
  但同一个重试在延迟上是 +8s，直接击穿 P95。**换个维度结论就翻转**，
  这类地方最容易漏。
- **发现自己之前的结论错了要明说**，不要悄悄改掉。
  这个项目里已经修正过：「36 项参数」实际是 39 项（现为 43 项）；
  「四重预算」实际是三重；`#5903` 不是 reranker 的活 issue，`#6524` 才是。
- **不要用"用了 23 种设计模式"这种说法。** 说 5 种组合。
- **不要说"引用幻觉率 = 0"**，要说"**文档级**引用幻觉率 = 0"。

---

## 6. 关键路径速查

| 文件 | 作用 |
|------|------|
| `oncall-tool-gateway/.../GuardedToolCallback.java` | **基石**：七道关卡。**审计必须覆盖每一个出口**——三条拒绝路径曾因审计调用点全写在「放行之后」而一条都没记 |
| `oncall-tool-gateway/.../ToolAuditEvent.java` | 一条审计事件 = `tool_audit_log` 的一行。7 个 `NOT NULL` 列全是 record 组件，**构造期就校验**，少一个就构造不出对象 |
| `oncall-tool-gateway/.../JsonCanonicalizer.java` | 幂等键的唯一输入。键排序 + 数组**保序** + 数字归一。**改它等于改幂等语义**——归一过度会让两个不同操作撞上同一个键（表现为 Agent 说做了但没做），归一不足会导致二次执行 |
| `oncall-tool-gateway/.../clamp/JsonScaleArgsAdapter.java` | `ArgClamper` 的**唯一生产实现**——在它之前网关拿到的一律是 `ArgClamper.NOOP`，「注入让模型生成 `replicas:0` 会被夹住」这句话是假的。三条硬性质：① 解析失败**必须抛，绝不原样放行**（放过就等于给注入留了绕过防线的入口）；② 没夹紧时**必须返回原字符串对象**——`GuardedToolCallback` 用字符串比较判定夹紧，重新序列化会让每次调用都留下一条假的 `CLAMPED` 审计；③ 夹紧时改树而不是重建对象，**未知字段必须保留**。`MAPPER` 上的 `FAIL_ON_TRAILING_TOKENS` **不能关** |
| `oncall-ontology/.../rule/RuleEffect.java` | 规则求值的**保守下限**所在。`markDegraded(ruleId)` 在规则抛异常时记录降级并**立即压下限**（`CONSERVATIVE_MIN_APPROVERS = 2`、`CONSERVATIVE_AUTONOMY_CAP = "S1"`），因为效果全是**收紧**方向的——一条规则没跑成等于它那份收紧凭空消失，结果比真实更宽松。`hasEffect()` 含 `degraded()`，否则「有规则失败但没有规则触发」会读成「无约束，可自动执行」。**下限是取更严不是叠加**（与已触发的两人审批合并后仍是 2） |
| `oncall-ontology/.../rule/RuleEngine.java` 的停用机制 | `disabledRuleIds` 对应 `onto_rule.enabled = FALSE`——**在它之前那个开关没接上任何东西**，运维设成 FALSE 而规则照常在跑。**`markDisabled` 与 `markDegraded` 方向相反**：停用绝不压保守下限（压了停用永远不生效），降级必须压（C6）。跳过发生在求值**之前**，所以一条既被停用又会抛异常的规则记成停用而不是降级。停用列表里的**未知 id 必须报出来**——拼错就等于那条规则根本没被停用。**只有二参构造器，没有 `RuleEngine(Set)` 单参重载**（会与 `RuleEngine(List)` 同元数，`new RuleEngine(null)` 变歧义——C4 的教训） |
| `oncall-agent-core/.../llm/CallOutcome.java` | `llm_call_log` 四个「只有 `ResilientChatModel` 能填」的列的载体（`model` / `latency_ms` / `is_retry` 三列 `NOT NULL`）。**刻意不写库**——凑齐一行还需要 `prompt_version` 与 `call_type`，那两列的知识在 `agent.prompt` 与编排层，而 F11 禁止本包依赖它们。构造期拒绝空模型标识、负延迟、空白 `failoverFrom`（空白串与 `null` 语义不同，前者是数据缺陷）。**`latencyMs` 必须含重试与退避**，否则 P95 测的不是用户感知的延迟 |
| `GuardedToolCallback` / `McpToolRegistrar` 的 `argClamper` | **`null` 启动即失败，不再静默退化成 `ArgClamper.NOOP`**。理由与同一个构造器里 `ledger` 的判空相同：静默兜底会让「忘了接夹紧器」与「这个工具不需要夹紧」在代码里长得一模一样，而前者会让注入生成的 `replicas:0` 原样执行。`readOnly(...)` 仍传 `NOOP`，但那是**显式选择**（只读工具没有需要夹紧的数值参数）。**注意 `JsonScaleArgsAdapter` 的生产引用数仍是 0——真正接上它需要装配层** |
| `oncall-app/.../ToolGatewayAssembly.java` | **装配层（M2）的第一个类**，也是 `JsonScaleArgsAdapter` 的**唯一生产构造点**（D2-a 之前是 0）。`scaleReplicasClamper(replicaState, limits)` 把两层结构写死：`JsonScaleArgsAdapter`（解析 + 改树）→ `ScaleReplicasClamper`（纯算术 + 拒绝）——`ScaleReplicasClamper` **不实现** `ArgClamper`，所以「直接把它传进去」编译不过。**刻意不构造 `GuardedToolCallback`**：那需要 `ApprovalGate`，其生产实现数为 0，硬造假实现只会得到只有测试用的接缝 |
| `oncall-agent-core/.../llm/ResilientChatModel.java` | `FailureListener` 只报失败，**`CallObserver` 报成功**——两个合起来才够填满 `llm_call_log`。`Ticker` 注入单调时钟（用 `nanoTime` 不用 `currentTimeMillis`：墙上时钟被 NTP 回拨一次就能让 `latency_ms` 变负）。`abandoned` 记在内层循环**之外**：同模型上的多次重试是 `is_retry`，不是 failover，DDL 里两列分开。四参构造器委托给六参，**元数不同所以不会重演 C4 的重载歧义** |
| `oncall-ontology/.../rule/RuleEngine.java` | `catch (RuntimeException)` 分支必须调 `effect.markDegraded(rule.id())`。**`warnings()` 在生产代码里零读者**（六个命中全在测试里），所以「记个警告」不构成防线——防线建在效果本身。javadoc 里「跳过时不放宽任何约束」这句话曾是假的，现已由断言钉住 |
| `oncall-domain/.../trace/TraceId.java` | `trace_id` 的**唯一产出方**（三张表的 `NOT NULL` 列）。两条校验都是**安全控制**：字符集 `[A-Za-z0-9._-]` 防日志注入（`ToolAuditContext.toString()` 会把 traceId 拼进日志行，允许换行就等于允许伪造一条「操作已审批」）；长度 ≤64 **绝不静默截断**（截断会让两条链路撞上同一个 trace）。**校验规则只此一份**——`ToolAuditContext` 委托它，不要另写一遍 |
| `oncall-tool-gateway/.../ApprovalRecord.java` | 一条审批记录 = `approval_record` 的一行。**这张表保留期永久，是责任归属的唯一凭据**，所以字段形状在构造期就钉死：`GRANTED`/`REJECTED` 必须有审批人（没有人名的批准等于没有责任人）、`TIMED_OUT` **必须没有**审批人（没有人做过这个决定，填人名等于伪造责任归属）、审批人 ≠ 申请人。它有两条**跨字段**不变量，**校验时不能用凭空构造的对象**——那样只能验到单字段的形状 |
| `oncall-tool-gateway/.../JdbcApprovalRecordStore.java` | `decide` 是**条件更新**（`WHERE decision='PENDING'`）：两个审批人同时点批准只有一个生效。它先读一行做校验，但并发保证仍来自条件更新——**别把「既然读了」改成先查后写** |
| `oncall-tool-gateway/.../ArgMasker.java` | `args_masked` 的唯一来源。三条性质：绝不抛异常（含 `StackOverflowError`）、宁可多遮、不保留原值长度。**改正则前先读类注释里那两个已知 bug 的成因** |
| `oncall-config/.../OnCallConfigRegistry.java` | 43 项配置的唯一声明处 |
| `oncall-config-admin/.../ConfigAdminController.java` | 配置 REST + 双人复核 |
| `oncall-tool-admin/.../ToolPolicyAdminController.java` | 工具白名单 REST + 双人复核 |
| `oncall-tool-gateway/.../ToolPolicyGovernance.java` | **唯一**有权改白名单的生产类 |
| `oncall-agent-core/.../ResilientChatModel.java` | LLM failover 与重试（AI 半边的第一个生产类） |
| `oncall-agent-core/src/test/.../StubChatModel.java` | L2 测试的地基：把编排逻辑从非确定性里摘出来 |
| `oncall-agent-core/.../prompt/PromptRegistry.java` | prompt 的单一事实来源：版本不存在**绝不**回退 |
| `oncall-agent-core/src/main/resources/prompts/` | prompt 正文，文件名 `<name>.<version>.md`，**不可原地修改** |
| `oncall-agent-core/.../query/IntentClassifier.java` | 规则层定安全、LLM 只做路由。**改这里的正则等于改安全边界** |
| `oncall-eval/src/main/resources/golden-set/` | 人工标注集。**动它等于动判据**，加用例可以，删用例要说明理由 |
| `oncall-eval/.../ExecuteRecallGate.java` | `EXECUTE` 召回率硬门槛（规则层单独看） |
| `oncall-eval/.../IntentJudge.java` | 全链路判据：准确率 / 召回率（拆规则侧与模型侧）/ 过度命中 / 降级率。**`false-positive-probe` 组不进准确率分母**，理由写在类注释里，改它之前先读 |
| `oncall-eval/.../IntentRunRunner.java` | 产出一半，模型无关。**刻意不进 CI**——L3 的非确定性不能进每次 push 的门 |
| `db/migration/V1__config_governance.sql` | 已落地的 2 张表 |
| `.github/mvn-test.sh` | CI 失败诊断（annotation 通道） |
| `.github/workflows/ci.yml` | 模块列表是**硬编码**的，加模块要改这里 |
