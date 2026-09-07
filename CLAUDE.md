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
| `oncall-agent-core/.../llm/CallOutcome.java` | `llm_call_log` 四个「只有 `ResilientChatModel` 能填」的列的载体（`model` / `latency_ms` / `is_retry` 三列 `NOT NULL`）。**自身不写库**——凑齐一行还需要 `prompt_version` 与 `call_type`，那两列的知识在 `agent.prompt` 与编排层，而 F11 禁止本包依赖它们。**落库由 D2-d 的 `JdbcLlmCallLog` 承担**，见下一行。构造期拒绝空模型标识、负延迟、空白 `failoverFrom`（空白串与 `null` 语义不同，前者是数据缺陷）。**`latencyMs` 必须含重试与退避**，否则 P95 测的不是用户感知的延迟 |
| `GuardedToolCallback` / `McpToolRegistrar` 的 `argClamper` | **`null` 启动即失败，不再静默退化成 `ArgClamper.NOOP`**。理由与同一个构造器里 `ledger` 的判空相同：静默兜底会让「忘了接夹紧器」与「这个工具不需要夹紧」在代码里长得一模一样，而前者会让注入生成的 `replicas:0` 原样执行。`readOnly(...)` 仍传 `NOOP`，但那是**显式选择**（只读工具没有需要夹紧的数值参数）。**注意 `JsonScaleArgsAdapter` 的生产引用数仍是 0——真正接上它需要装配层** |
| `oncall-app/.../ToolGatewayAssembly.java` | **装配层（M2）的第一个类**，也是 `JsonScaleArgsAdapter` 的**唯一生产构造点**（D2-a 之前是 0）。`scaleReplicasClamper(replicaState, limits)` 把两层结构写死：`JsonScaleArgsAdapter`（解析 + 改树）→ `ScaleReplicasClamper`（纯算术 + 拒绝）——`ScaleReplicasClamper` **不实现** `ArgClamper`，所以「直接把它传进去」编译不过。`guardedToolCallback(...)` 接收**原料**（存储 / 端口）、在内部构造**策略**（`PollingApprovalGate` + 夹紧链），于是调用方没有机会忘掉其中任何一个。（初版曾写「`ApprovalGate` 生产实现数为 0 所以不装配」——**那是错的**，`PollingApprovalGate` 一直是生产实现；错因是拿 `head` 截断的 grep 断言了全称否定命题） |
| `oncall-agent-core/.../llm/LlmCallRecord.java` · `LlmCallLog.java` · `JdbcLlmCallLog.java` | **`llm_call_log` 的落库三件套（D2-d）**。`of(CallOutcome, …)` 工厂让 `model` / `latency_ms` / `is_retry` / `failover_from` **只能从 `CallOutcome` 传进来**——调用方没有机会自己填，而那四列恰恰是从装饰器外面填不出来的。`JdbcLlmCallLog` 纯 JDK `javax.sql`，`INSERT_SQL` 16 个占位符且**不含 `id`**（identity）。**刻意不持有 `CREATE_TABLE` 常量**：DDL 只存在于 `db/migration/V4__llm_metering.sql`，Java 里复制一份就会与迁移脚本静默分叉。落库失败**必须抛出来**——计量丢失是静默的，成本报表只会偏小而没人怀疑。`latencyMs > Integer.MAX_VALUE` 时抛异常而非截断，截断会留下一个看起来合理的假值 |
| `oncall-agent-core/src/test/.../JdbcLlmCallLogTest.java` | **全仓唯一连真实 PostgreSQL 的测试**（`llm_call_log` 是 `PARTITION BY RANGE`，赌 H2 的分区方言兼容性最坏结果是「测试通过但生产写不进去」）。建表**读 `db/migration/V4` 原文**，不在 Java 里复制 DDL。用 `ONCALL_TEST_PG_URL` 做闸门：**变量一旦设置就必须真的跑，连不上直接失败**——写成「连不上就跳过」会让 CI 在服务容器没起来时全绿而一条没跑（D2-d 真的踩到了，见 DEVLONG §6） |
| `oncall-domain/.../run/AgentRun.java` · `RunStatus.java` | **`agent_run` 的领域载体（D2-e）**，14 个组件对应 V2 的 14 列。三条构造期不变量：① 预算三重护栏（步数/token/成本）**不允许被越过**——允许越界则护栏只是记录；比较用 `compareTo` 不用 `equals`，因为 `NUMERIC(12,6)` 下 `5.0` 与 `5.000000` 相等但不 `equals`。② `finished_at` 与终态**互为充要**。③ `consume()` 让游标与 `usedSteps` 同步推进——游标落后于已花掉的步数，worker 重启后会重跑**已经付过费**的步骤，账面看不出重复。`RunStatus` 五个值此前只活在一条 SQL 注释里，Java 侧无类型承载，拼错不报错只查不到 |
| `oncall-agent-core/.../run/JdbcAgentRunStore.java` | **`agent_run` 的唯一写入方（D2-e 之前是 0）**。★ `UPDATE_SQL` 只有六个占位符，`autonomy_level` / `trace_id` / 三项预算 / `created_at` **刻意不在其中**：放权等级是 RUNTIME_HOT 配置会变，而 V2 要求它固定为「当时的」快照；若 UPDATE 把它写回去，每次进度更新都会用当前配置覆盖当时的授权**且不报错**。少写四列不是省事，是让「快照被覆盖」在语法上做不到。`update` 命中 0 行必须抛——静默返回会让「进度推进了」变成假话。刻意不持有 `CREATE_TABLE`，DDL 只在 `V2__agent_execution.sql` |
| `oncall-domain/.../run/AgentStep.java` · `StepStatus.java` | **`agent_step` 的领域载体（D2-f）**，11 个组件对应 V2 的 11 列。★ `StepStatus` 只有三个值，是**从列结构推导**出来的而非转录自规范：`agent_step.status` 在 DDL 里既无取值注释也无 CHECK 约束（与 `agent_run.status` 不同）。`started_at` 是 NOT NULL ⇒ 不存在「尚未开始」的状态，所以 `PENDING` 与列约束矛盾、刻意不收。`SKIPPED`/`TIMED_OUT`/`DENIED` 也刻意没加——那取决于「被闸门拒掉的一步算不算一步」，是产品决策。构造期四条不变量：`finishedAt` 与终态互为充要；**`FAILED` 必须带 `errorMessage`**（反之成功的一步带错误信息也是数据缺陷）；**`finishedAt` 不得早于 `startedAt`**（NTP 回拨会产生负时长，拒绝而不是记下负数）；列宽一律拒绝而不是截断 |
| `oncall-agent-core/.../run/JdbcAgentStepStore.java` | **`agent_step` 的唯一写入方（D2-f 之前是 0，那条「幂等的物理保证」从未被真正撞过）**。★ `tryInsert` 返回布尔而**不是**抛异常：多实例下「别人已经抢到了」是预期行为，若与真故障混成同一个异常，调用方要么永远撞同一个唯一约束白转到超时，要么吞掉让这一步凭空消失。区分用 **SQLSTATE `23505`** 而不是匹配异常消息——消息是驱动实现细节，换版本或换语言环境就变，SQLSTATE 是标准。`FINISH_SQL` 只写四列：`idempotency_key` 是写入一次的，改了它等于把「这一步做过没有」的答案换掉。唯一约束是**探测器不是阻止器**，所以必须「先插入再执行」 |
| `oncall-domain/.../alert/AlertGroup.java` · `AlertEvent.java` · `AlertStatus.java` | **告警聚合的领域载体（D2-g）**。`AlertStatus` 四值**转录自** `V3` 的列注释（与 `StepStatus` 靠列结构推导不同）。★ `absorb()` 是**唯一**能加计数的入口且必须同时推进 `lastSeenAt`，刻意不提供 `withEventCount(int)`——那样就能造出「计数是 7 但最后出现还停在第一条」的组。`attachRun` **拒绝改绑**，否则事后无法回答「第一次是谁接的」。刻意**不**校验 `rawPayload` 是否合法 JSON（domain 零依赖，无解析器 ⇒ 交给 PostgreSQL 的 `JSONB`）；刻意**不**校验 `receivedAt` 晚于 `firedAt`（源端时钟快是常见故障，拒绝它等于丢告警，而 V3 写明「丢一条告警比多一次 VACUUM 严重得多」）⇒ 改用 `hasClockSkew()` 把倒挂**记下来** |
| `oncall-alert/.../JdbcAlertStore.java` | **`alert_group` / `alert_event` 的唯一写入方（D2-g 之前都是 0）**。★ **没有 `insertGroup`**——第一版有，CI 红过一次：`AlertGroup.open()` 把计数起在 1 但 `insertGroup` 不插事件行，随后 `ingest` 又 `+1`，于是 `event_count=2` 而真实行数是 1，**从第一次调用起就漂移**，而 javadoc 正写着「不可能漂移」。修法是把这个形状删掉：组只能由第一条事件创建，计数**只由事件的存在性驱动、不接受调用方给定**。`ingest` 在**一个事务**里插事件 + `event_count + 1`（用 SQL 的 `+1` 而非写回 Java 算好的值，避免并发丢更新）；`last_seen_at` 用 `GREATEST` 防止乱序事件把「最后出现」往回推。★ `alert_event.group_id` **没有外键**（分区表不支持跨分区外键），所以引用完整性与计数一致性数据库都管不了 |
| `oncall-domain/.../plan/Plan.java` · `PlanStep.java` · `BasisRef.java` · `BasisType.java` | **计划领域模型（D3-a，M3 起步）**。★ `PlanStep.basisRefs` **不得为空**——否则无法区分「Planner 老实交代它没有依据」与「忘了填这个字段」，两者都必须拒绝；且用 `List.copyOf` 做**防御性拷贝**，否则调用方事后 `add` 就能偷偷给高危步骤补一条「可信依据」。★ `Plan` 的 `seq` 必须 **1..n 连续且有序**，否则「第 k 步之前有哪几步」没有确定含义、`PlanValidator` 的顺序约束会**静默失效**；放在构造期而非校验器里，因为**校验器可以被绕过，构造函数不能**。空计划被拒——「无需处置」必须是 Reporter 的显式结论。`BasisType` 把「可信」写成**枚举自带的方法**：`ALERT_RULE`/`RUNBOOK` 可信，**`LOG_TEXT`/`TOOL_OUTPUT` 不可信**（日志是注入攻击的载体；若日志能当高危操作的依据，注入就等于拿到了授权） |
| `oncall-agent-core/.../plan/PlanValidator.java` · `PlanRejectedException.java` | **计划静态校验（Planner 输出后、执行前）——确定性防线，不依赖模型是否听话**。★ 我**改了 `修复方案.md` F2.3 第③条**：原文 `s.step() < minInvestigationSteps` 只看**步骤位置**，于是 `[写,写,写]` 在第 3 步会被放行（`3<3` 为假）而它前面一步信息收集都没有；改成**数前面真正做了几步只读探查**（`risk == READ_ONLY`），且 **LOW 风险的写操作不计入探查预算**（它不是信息收集）。验收断言 `writeOnlyPlanIsRejectedAtTheFirstWriteNotTheThird`。① 白名单：`ToolDeniedException` 转 `PlanRejectedException` 但**保留原因链**（「哪个工具不在白名单」最有诊断价值）。**整份计划一起拒绝、不做部分放行**——计划有因果链，悄悄删掉第 2 步会让第 4 步拿着不存在的前提去执行，那比整份拒绝危险得多。`minInvestigationSteps` 取 0 即拒（那等于取消这条防线） |
| `oncall-agent-core/.../plan/Planner.java` · `PlanProductionException.java` | **把告警变成计划（D3-b）**。★ **刻意没有降级分支**：`IntentClassifier` 可以退回规则层，但「一份兜底计划」不存在——任何固定默认计划都等于「不管什么告警都执行同一套动作」，**那比不产出计划危险得多**。所以模型失败/空响应/解析不了/结构不合契约/校验不过**一律抛出**，调用方应当把排查**交回给人**。★ 两个异常必须是两个类：`PlanProductionException`（没有计划＝模型**可用性**问题，看 failover 链/限流/密钥）vs `PlanRejectedException`（有计划但不许执行＝模型**行为**问题，看 prompt/加评测用例）——混成一个，「模型老是不返回」和「模型老是想跳过探查直接扩容」在面板上就长得一样。**产出即校验**：`validate()` 在内部调用，不交给调用方记得。`seq` **按数组顺序重新编号、不采信模型给的**（模型常给 0 起/跳号/重复）。**不用 Jackson 直接反序列化成 record**（字段缺失会得到 null 一路带到执行期）。**不用 `asText()`**：`NullNode.asText()` 返回字符串 `"null"`，于是 `"action": null` 会被当成名叫 `null` 的工具 |
| `oncall-agent-core/.../llm/ModelOutputJson.java` | **从模型输出里取 JSON 正文（D3-b 从 `IntentClassifier` 抽出）**。抽出来而不是复制的理由：`extractJson` 原本包级私有在 `agent.query`，`Planner` 在 `agent.plan` 跨包调不到，而**同一条解析规则写两处必然分叉**——将来发现模型新的包裹方式只会修一处，于是「意图分类能解析、计划解析不能」会安静地存在很久。是启发式不是解析器：剥 ``` 围栏后取首个 `{` 到末个 `}`，多段 JSON 时会取错，但那种输出本身就是坏的 |
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
