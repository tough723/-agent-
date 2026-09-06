# DEVLONG.md — 长期开发上下文与不变量

这份文档是给**未来的开发会话**（人或 AI）的。它记录的不是"怎么做"，
而是**"哪些事已经定死了，不要再讨论"**，以及**"哪些路已经走过并且被否决了"**。

没有这份文档，每一轮都会重新争论一遍已经解决的问题。

---

## 1. 不变量（Invariants）

这些是系统的**结构性保证**。任何改动如果破坏了其中任何一条，
不管它多有道理，都必须先修改这份文档并说明理由。

| # | 不变量 | 靠什么保证 |
|---|-------|----------|
| **I1** | 所有 AI 发起的写操作必须穿过 `GuardedToolCallback` | 代码结构 + ArchUnit 规则 F9（`oncall-archtest`，**已实现且自证会失败**） |
| **I2** | 未注册的工具一律拒绝，且**不泄露其存在性** | `ToolPolicyEngine` 默认拒绝 + 关卡①在②之前 |
| **I3** | 高危配置变更必须两个不同的人同意 | `ConfigAccessPolicy` + 待复核单 + "不能复核自己"断言 |
| **I4** | `BACKEND_ONLY` 的键对外表现与"不存在"完全一致 | 一律 404，不是 403 |
| **I5** | 高危配置清单不可被配置修改 | 硬编码在 `ConfigAccessPolicy.HIGH_RISK_KEYS` |
| **I6** | `oncall-domain` / `oncall-config` 生产代码零外部依赖 | 只用 JDK；H2 仅 test scope |
| **I7** | `app_config` 只有墓碑行，没有真删除 | `JdbcConfigStore.remove()` 写 NULL |
| **I8** | 幂等的最终保证在数据库唯一约束，不在应用层 | `tool_execution_claim` 主键（V7，**已落地**）+ `JdbcToolExecutionLedger.claim()` 靠主键冲突抢占。**不是** `uq_agent_step_idem`——那条约束的粒度是「步」，比工具调用粗，管不住它 |
| **I9** | 答案必须通过 `CitationVerifier` 才能下发，因此**不流式吐 token** | SSE 承载阶段事件，`answer` 原子下发 |
| **I10** | 评测跑批 `temperature` 必须为 0 | 配置默认 0.0 + 说明文案 |
| **I11** | AI 永远不是责任主体 | 放权模型 S0–S3 每级都有明确的人 |
| **I12** | 绝不提供可执行任意 SQL 的工具 | 架构约束，无代码 |
| **I13** | PromQL 只能是参数化模板，模型只能填参数 | 架构约束，无代码 |
| **I14** | MCP 工具必须显式纳管，不自动注册 | `toolcallback.enabled=false`（**默认是 true**） |

---

## 2. 已冻结的决策（改这些 = 返工）

| 决策 | 冻结值 | 改了的代价 |
|------|-------|----------|
| embedding 模型 | `text-embedding-v4` | **跨版本向量不可比**。换模型 = 全量重嵌 + 召回基线全部作废 + Golden Set 重新标注 |
| 向量维度 | **1024** | 改维度要重建 `kb_chunk_vector` 表并全量重嵌 |
| 距离类型 | `COSINE_DISTANCE` + `vector_cosine_ops` | 不匹配**不会报错**，静默退化成全表扫描 |
| 索引类型 | HNSW | **上限 2000 维**，选 2048 会直接建不了索引 |
| embedding 批大小 | **10** | 百炼 API 每次最多 10 行；Spring AI 默认批 10000，直接对接必炸 |
| `initialize-schema` | `false` | 为 `true` 会在建表时钉死维度，之后无法演进 |
| chunk ID | `UUID.nameUUIDFromBytes(docId\|version\|headingPath\|ordinal)` | 用随机 UUID 会重复索引产生重复切片，且无法做版本安全 upsert |
| 版本安全 upsert | 先 delete 再 add | `VectorStore` 没有 upsert 语义 |
| Spring Boot | 3.5.16 | 3.3.5 已停止维护；4.x 大版本不跟进 |
| Spring AI | 1.1.6 | 与 SAA 1.1.2.3（2026-05-12）时间最贴近 |
| JUnit | 5.10.2 | `junit-bom` 刻意放在 BOM 首位，防止 Boot BOM 换成 5.11 |

**重新评估的触发条件**见
[2.项目整体架构和技术栈选型分析设计文档.md](2.项目整体架构和技术栈选型分析设计文档.md) §6。

---

## 3. 已否决的方案（不要再提）

| 方案 | 否决理由 |
|------|---------|
| **微服务架构** | 小团队运维成本远超收益；OnCall 系统可用性要求**高于它服务的系统**，跨进程调用是净损失。只把 agent-worker 和 kb-indexer 独立出来 |
| **S4 完全自动放权** | 收益边际递减，风险不可控 |
| **流式吐答案 token** | 与结构化输出 + 引用校验物理冲突（不变量 I9） |
| **Milvus 作为向量库** | 参考项目用它，但复用既有 PostgreSQL 少一个组件；HNSW 在预期规模下够用 |
| **通用 SQL 查询工具** | 参考项目就是这么做的（DSN + 任意 SQL 含 `delete`，只靠"执行前需用户确认"）。那是事故源 |
| **模型生成 PromQL** | 不可预测、不可审计 |
| **把"自动处置率"当 SLO** | 会诱导团队为了让数字好看而放宽放权标准 |
| **给配置界面加"超级管理员"角色** | 只会让双人复核形同虚设。高危配置需要的是"另一个人也同意" |
| **高危配置清单做成配置项** | 双人复核会被自己保护的机制绕过 |
| **F10 引用校验失败后"重试 1 次"** | 同模型同上下文重试期望收益极低，代价确定 +8s，直接击穿 P95。改为直接降级为返回原文片段 |
| **`retrieval.top-n` 与 `retrieval.parent-top-n` 并存** | 同一个东西的两个名字，一定会打架。应合并 |
| **prompt 写进 Java 字符串常量** | 同时失去灰度、回滚、归因、评审四种能力 |
| ~~**Ontology / 本体**~~ | **此条已作废。** 当时把 Ontology 等同于「OWL + 推理机」并整体否定，是过度否定。**轻量本体（概念层 + 带类型关系 + 规则集，无推理机）是应该做的**，见 [轻量本体设计.md](轻量本体设计.md)。仍然否决的是 L2 完整本体 |
| **在 CI 里调真模型** | CI 会间歇性变红，而间歇性变红的 CI 等于没有 CI |

---

## 4. 已知的、刻意保留的不一致

| 项 | 现状 | 计划 |
|----|------|------|
| `架构设计方案.md` 与其他文档冲突 | **它是原始方案，已被修订**，保留作对照 | 不删除。以 `2.项目整体架构…` 与 `修复方案.md` 为准 |
| `架构设计方案.md §6.4` "Prompt 工程迭代（面试亮点）" | 是 60%→95% 的话术，无版本机制 | 用 `PromptRegistry` 替代（未实现） |
| `retrieval.top-n = 5` 仍在配置表里 | 与 `parent-top-n = 4` 语义重叠 | 属于**修改已冻结参数**，需评审签字后改 |
| `chunking.parent-max-tokens` 未冻结 | 导致 `最大上下文 12000` 无法被强制执行 | 建议 1200，封顶后总上下文 9700 < 12000 |
| ~~ArchUnit 规则（F9）未实现~~ | **已实现**（`oncall-archtest`） | 规则的表述从「禁止在网关外调 `ToolCallback.call`」改成「除 `GuardedToolCallback` 外不得实现 `ToolCallback`」，理由见 `ArchitectureRuleTest` 的类注释 |

---

## 4.5 原始方案 vs 修复后：防护覆盖的实际差距

有人问过"设计之初是否就全方位考虑了参数校验、数据清洗、防幻觉、兜底、安全"。
**答案是没有**，下面是 grep 出的实际命中数，可以复核：

| 防护维度 | 原始 `架构设计方案.md` | 修复后各文档合计 |
|---------|---------------------|---------------|
| 参数校验 / 参数夹紧 | **0 处** | 6 处 |
| 数据清洗 / 脱敏 | 2 处，**且都是我加的修订**（原文标着"原方案缺失"） | 18 处 |
| 幻觉 / 引用校验 | 2 处，都是一句话愿望 | 39 处 |
| 兜底 / 降级 | **1 处**（一个表格单元格） | 54 处 |
| 提示注入 | **0 处** | 27 处 |
| 幂等 | 3 处 | 15 处 |

两个容易看错的点：

1. **原始方案里 7 处"注入"，6 处是依赖注入 / 上下文注入**（`@Autowired`、
   "结果注入下一轮 Prompt"）。唯一提到**提示注入**的那一行是我加的修订，
   原文自己标着"（原方案缺失）"。**原始设计对提示注入的覆盖是 0。**
2. **原始方案里 2 处"幻觉"都是愿望，不是设计**：
   "严格 RAG 引用 + 高危操作人工审批"、
   "三道闸门：RAG 引用依据 / 高危操作人工审批 / 工具调用前置校验"。
   没有校验器、没有引用 schema、没有粒度定义。
   "严格 RAG 引用"这句话**不可实现也不可验证**。

**这条记录的意义**：它说明了为什么 `修复方案.md` 和 `质量与可靠性设计.md`
不是"锦上添花"，而是把"能演示"变成"能上线"的那部分工作。



## 5. 已核实为**正确**的（不要"修"它们）

这些曾经被怀疑是 bug，核实后确认没问题。**不要再报一遍。**

| 疑点 | 结论 |
|------|------|
| `vectorStore.accept(chunks)` | ✅ 可用（`accept` 是 default 方法） |
| `splitter.split(doc)` | ✅ `TextSplitter.split()` 存在 |
| `plannerModel.call(prompt)` | ✅ 可编译（`ChatModel` 有 `default String call(String)`） |
| `SearchRequest.builder()…filterExpression()` | ✅ 链式用法正确 |
| `gofish2020/OncallAgent` 参考项目 | ✅ 存在 |
| "Spring AI Alibaba 1.0+" | ⚠️ 表述过时但不算错 |
| `DeepSeek-V3 Quick` | ✅ 是 `deepseek-chat` 的配置别名，不是虚构产品名 |
| `ConfigSchemaExporter` 括号不配对 | ✅ 误报。它含 `'{'` `'}'` `'"'` 字符字面量，静态检查要先剥离字符串 |

---

## 6. 我自己犯过并修正的错误

记下来是为了不重复。**这类"文档里的数字"最容易错，因为没人会去核对。**

| 错误 | 实际 |
|------|------|
| 文档写"36 项参数 / RUNTIME_HOT 20 项" | **39 项 / 23 热改** / 8 迁移 / 8 后端专属（新增 mcp 组后为 **41 项 / 24 热改 / 9 后端专属**）。少数了 2 项，很久没人发现。现在 `tierDistribution` 用精确断言拦 |
| 文档写"F3.2 四重预算" | 实际是**三重**（步数 / token / 成本） |
| 说"没有内置 reranker（issue #5903）" | **#5903 已被关为重复**，活 issue 是 **#6524** |
| 说"引用幻觉率 = 0" | 应为"**文档级**引用幻觉率 = 0"，`CitationVerifier` 只到 `doc_id` 粒度 |
| 说"三份文档已充分覆盖质量维度" | 错。**关键词计数不等于设计覆盖**。第一次核查时"幻觉"那一节只有一行配置 + 一句 prompt |
| 说"DeepSeek-V3 Quick 无法核实" | 错，它是配置别名 |
| 把 CI 的 success 当成"测试通过" | 错，当时 surefire 2.12.4 一个测试都没跑 |
| 加两个 `@SuppressWarnings("unused")` 常量来"消化"未使用 import | 错。**未使用 import 是代码没用这个类型的信号**，应该删 import |
| 凭空 import 了 `ConfigSchemaExporterHolder`，调用了不存在的 `service.auditHistory()` | 读一遍 `grep -nE "public "` 就能避免 |
| 测试辅助方法里混用两个时钟 | `PendingChangeStore.size()` 用真实时钟过滤注入时钟算出的过期时间，恒返回 0 |
| **把「Ontology」等同于「OWL + 推理机」并整体否定** | 用户追问后才发现是过度否定。轻量本体（概念层 + 带类型关系 + 规则集）是应该做的，而且它能解决 MCP 工具风险分级这个 P0 缺口。**教训：否定一个方案前，先确认否定的是它的哪个层次** |
| **修正后又只给了成本理由，且概念模型有错** | 被追问"是否真的掌握这套方法论"后用 CQ 与 OntoClean 重做，查出 `CoreService` 不该是子类（非刚性），已改为 `criticality` 属性。否决 OWL 的真正理由是**语义不匹配**（OWA / 单调性 / 变更速度），不是成本 |
| **F9 的原始表述根本不可静态判定** | 文档里写的是"用 ArchUnit 禁止在网关之外直接调用 `ToolCallback.call`"。但编排层与 Spring AI 内部**必须**调 `call`，ArchUnit 无法区分"调的是被包装过的实例"还是"裸实例"——那是运行时属性。改成约束**实现类的集合**：只要 `GuardedToolCallback` 是唯一实现，编排层手里的任何 `ToolCallback` 就必然是被包装过的。**教训：写架构约束前先问"这个性质静态可见吗"** |
| **F2 的包范围把 REST 层圈了进去** | `oncall-config-admin` 的包名是 `com.oncall.config.admin`，不是独立顶层包。只写 `com.oncall.config..` 会把控制器一起算进"零依赖模块"，第一次跑红了 58 处。加 `resideOutsideOfPackages` 排除。**规则第一次跑就报出大量违规时，先怀疑范围写错，不是先怀疑代码** |
| **审计日志只按毫秒时间戳排序** | 发起与驳回落在同一毫秒是正常操作，两条记录时间戳相同 ⇒ 顺序不确定 ⇒ `recent(1)` 返回哪条看运气。后果不只是测试随机红：**「先发起还是先被拒」是事后追责要回答的问题，必须有唯一答案**。修法是给条目加插入序号 `seq`，排序改为 `(atMillis, seq)`。**教训：凡是"给人看的有序列表"，都要问"排序键有并列时怎么办"** |
| **同一个流程里混用两个时钟**（第二次踩） | `ToolPolicyGovernance` 持有可注入时钟用于测过期，而审计实现自己调 `System.currentTimeMillis()`。测试推进了一个，另一个不动。**规则：谁拥有流程，谁负责传时刻；被调用方不自己取时钟** |
| **record 里声明了与零参方法同名的静态工厂**（第二次踩） | `record ReviewOutcome(verdict, message)` 里既有实例方法 `boolean allowed()`，又写了 `static ReviewOutcome allowed()` —— 同名同参数个数、只有返回类型不同，Java 直接编译不过（`method allowed() is already defined`）。`Approval` 里踩过一次同样的坑。**规则：record 的静态工厂一律用动词命名（`allow` / `granted` / `rejected`），永远不要用形容词或与组件同名的词。括号平衡自检抓不到，只有真编译能抓** |
| **record 加了字段，漏改直接 `new` 的调用点** | `RuleContext` 把 `riskLevel` 插到第 4 位，测试里那个绕过工厂方法直接 `new` 的调用点没跟着改，编译失败。**括号平衡、括号配对这类自检抓不到参数个数错误——只有真编译能抓** |
| **在文档里写"未验证风险清单"，而不是加个数据库容器** | V2–V6 写完后我列了四条"未经验证的风险"。CI 里加一个 `pgvector/pgvector:pg16` 的 service 容器实测，**四条全部证伪**，一次通过。风险清单只能让人知道有风险，容器能让人知道没有风险 |
| **为了塞进一个配置项，差点去放宽全类型共用的默认值校验** | `mcp.allowed-servers` 用 `STRING_LIST` + 空默认值，被 `ConfigRegistry` 的自检拒绝（空串对任何类型都判为不可解析）。第一反应是"校验器太严"，但那条校验是对的：**默认值本身必须合法，否则"用默认值启动"这条路就是坏的**。真正的错误是这个键不该存在——它与工具白名单是两个事实来源。删键，不改校验器 |
| **给"连接"加了治理，却没发现"授权"根本没有治理** | 把 `mcp.allowed-servers` 放进高危清单，看起来"加 server 要两人同意"了。但决定远端工具能不能用的是 `ToolPolicy`，而工具白名单至今没有双人复核与审计。锁装在了不承重的门上。已记为 §9 第四个未解决问题 |
| **说"I8 的物理保证已经由 `uq_agent_step_idem` 提供"** | **错**。那是 `agent_step` 上的唯一约束，粒度是**步**；幂等键是 `runId\|step\|toolName\|canonicalArgs`，粒度比一步细（一步内可以有多次工具调用）。它约束不到工具执行。真正的保证是 V7 的 `tool_execution_claim` 主键。**教训：说"某条约束保证了 X"之前，先比对约束的粒度和 X 的粒度** |
| **第④关写成了"先查后写"，还以为它是幂等的** | 原实现 `if (auditLog.has(key)) return resultOf(key);` 再执行、再 `recordSuccess`。两个线程可以同时通过 `has()` 检查然后都执行；多实例下连 `has()` 都不共享。**唯一成立的写法是让抢占本身成为一次原子插入**（内存版 `putIfAbsent`，JDBC 版主键冲突）。数据库的唯一约束是**探测器**，不是**阻止器**——除非冲突发生在执行之前 |
| **并发测试里断言"只有一个线程成功"** | CI 炸成 `expected: 1 but was: 16`。抢不到执行权的线程会走 `resultOf()`，若赢家已 `complete()`，它们拿到的是上次结果并原样返回——**重放，期望行为**。成功线程数是 1..N 的时序函数。真正确定的不变量是「工具只被真正执行一次」。**教训：并发测试里只断言不变量，不断言时序的副产品** |
| **`release` 一开始写成了无条件 DELETE** | 那样一次迟到的失败回调会删掉已经 `COMPLETED` 的行，成功结果丢失，下次重试真的再执行一遍。必须带 `state='CLAIMED'` 条件。**凡是"删除以允许重试"的操作，都要先问"它会不会删掉已经成功的记录"** |

---

## 7. 环境事实（省得每次重新试）

| 事项 | 结论 |
|------|------|
| Java 工具链 | **没有**。`which javac`、`find / -name javac` 都为空 |
| Maven Central 出口 | **没有**。`apt-get update` 与 3 次 `curl` 均失败 |
| job 日志 | **取不到**。`gh run view --log` 空；`jobs/<id>/logs` SSL 失败；`gh run download` blob EOF |
| 可读的 CI 输出 | **只有 check-run annotation 与 `::notice::` / `::error::`** |
| annotation 上限 | **每个 run 每级别 10 条** |
| `python3` | 可用，但**没有 `yaml` 模块**；没有 `jq` |
| `web_search` / `mvnrepository.com` | ✅ 可用。**核实 API 签名与版本最便宜的途径** |
| `javadoc.io` / `raw.githubusercontent.com` | ❌ 返回空 / `000` |
| 本地 git 历史 | **会被周期性重置到 `5a4fc36`**。用 `git reset --soft FETCH_HEAD` 恢复，**绝不强推** |
| heredoc 批量写文件 | 出现过静默丢失。优先 `write_file` 一次一个，批量后 `ls` 复核 |

---

## 8. 成本基线（用于判断"这个改动贵不贵"）

| 项 | 值 |
|----|-----|
| 单事件 token | 72,000 输入 / 7,900 输出 |
| 单事件成本 | 谷时 ¥0.18 / 峰时 ¥0.36 |
| 月成本（150 事件/天） | **谷时 ¥803 / 峰时 ¥1,607** |
| 重试的 token 成本 | **+1.4%（¥11/月）**，敏感度到 40% 失败率也只 +4.4% ⇒ **可忽略** |
| 重试的延迟成本 | **+8s，直接击穿 P95 < 10s** ⇒ **不可忽略** |
| LLM 占总成本 | 只有 **15–40%**，其余是向量库、日志、人力 |
| 第一成本杠杆 | **告警聚合率**。15% → 40% 时成本涨 2.7 倍 |

> **记住这一条**：同一个设计决策，在成本维度可能无所谓，在延迟维度可能是硬伤。
> 评估不能只问"贵不贵"，要逐维度问一遍。

---

## 9. 还没解决的五个问题

前三个不解决，M4 之后会卡住。**不是技术问题，是信息缺口。**
第四个是实现 MCP 纳管时暴露出来的**治理缺口**；
第五个是做幂等账本时对着 V2 的 DDL 查出来的**接口与表结构不匹配**。

| 问题 | 影响 | 需要什么 |
|------|------|---------|
| **知识库清单** | 不知道有多少文档、什么格式、谁维护 ⇒ 无法估算索引成本与冷启动周期 | 找知识库的实际负责人盘点 |
| **CMDB 的告警→服务映射** | 没有映射就无法自动定位影响面，也无法 @ 对人 | 确认 CMDB 是否有这个关系，覆盖率多少 |
| **成本模型的真实聚合率** | 现在的 15% 是**假设值**，成本承诺全是空的 | W3 用真实数据修正 |
| **工具白名单（`ToolPolicy`）没有变更治理路径**（🟡 进行中） | 白名单是整个安全模型的事实来源——加一条 MCP 工具策略就等于放行一个远端工具。但配置治理那套（可见性分级 / 双人复核 / 待复核单 / 审计）只覆盖 `OnCallConfigRegistry`，**不覆盖 `ToolPolicyEngine`**。现在加策略是改代码或改 DB，没有第二人复核，也没有"谁在什么时候放行了什么"的可查记录 | 把工具策略纳入与配置同等的治理：变更走待复核单 + 双人 + 审计。这一条的优先级高于任何新功能。**A1 已完成**：双人复核的决策核心（`TwoPersonReview` + `Operator`）已从 `oncall-config-admin` 下沉到 `oncall-domain`，两侧共用一份规则（§1.6）。**A2 已完成**：`ToolPolicyGovernance` 落地，工具策略变更走待复核单 + 双人 + 审计，判据是 `PolicyRiskDelta` 算出的风险方向（§1.7）。**A4 已完成**：`register()/revoke()` 降为包级可见，治理层成为唯一入口（§1.8）。**A3 也已完成**：`oncall-tool-admin` 提供 REST 接入点（8 类 / 25 用例），四种拒绝映射到 410/403/409/409 四个状态码（§1.9）。**轨道 A 全部收口**，实测 398 用例 / 27 报告文件 / 99 生产类 |
| **`ToolAuditLog` 的方法签名喂不满 `tool_audit_log` 的必填列** | V2 的 `tool_audit_log` 要求 `trace_id` / `tool_source` / `risk_level` / `args_masked` / `gate_outcome` 全部 `NOT NULL`，而 `recordSuccess(idempotencyKey, toolName, args, result)` **一个都给不出**：没有 trace，分不清 LOCAL 与 MCP，没有风险级，`gate_outcome` 只能靠方法名反推（`recordClamped` 与 `recordApproval` 之间还分不清 `PASSED` / `CLAMPED`），`args` 还是未脱敏原文而列名叫 `args_masked`。⇒ **`JdbcToolAuditLog` 现在写不出来**；硬写只能往必填列塞假值，而一张字段造假的审计表比没有审计更糟 | 把审计上下文（trace / source / riskLevel / gateOutcome / 脱敏后的参数）作为显式参数传进来，而不是让实现去猜。会动到 `GuardedToolCallback` 全部审计调用点，属独立一轮 |

> 第四条是这么被发现的：我本来加了一个 `mcp.allowed-servers` 配置项并把它放进
> 高危清单，以为这样就"加 server 要两人同意"了。但那管的是**连接**，
> 不是**授权**——真正决定一个远端工具能不能用的是工具策略。
> 给连接加治理而授权没有治理，是把锁装在了不承重的门上。

---

## 10. 下一步

见 [DEVELOPMENT.md](DEVELOPMENT.md) 的 M2 起。

> **M1 的安全部分已完成，但不等于 M1 全部完成。**
> I14（MCP 显式纳管）与 I8（幂等的物理保证）都已堵上。
> 剩余 5 项是工程收尾：`JsonScaleArgsAdapter`、`JdbcToolAuditLog`、
> `canonical()` 升级为 Jackson `TreeMap` 规范化、`WecomApprovalGate`、
> `AutonomyLevel` 接入调用链。
>
> **更正一处此前写错的话**：我曾说这 5 项里只有 `JdbcToolAuditLog` 带安全含义、
> 理由是"内存版在多实例下幂等会失效"，并说它依赖 `uq_agent_step_idem`。
> 两点都不对——幂等的多实例问题已由 `tool_execution_claim`（V7）+
> `JdbcToolExecutionLedger` 独立解决，与审计表无关；而 `uq_agent_step_idem`
> 的粒度是"步"，本来就管不到工具调用。
> `JdbcToolAuditLog` 仍然带安全含义，但理由变成了**审计本身在多实例下的持久性**
> （内存版重启即丢，出事后无从追责），而且它现在**被 §9 第五项卡住**：
> 接口签名给不出表的必填列，必须先改接口。

当前建议顺序分两条独立的轨道，可以并行：

### 轨道 A：补安全治理的缺口（优先级高于任何新功能）

1. **把工具白名单（`ToolPolicy`）纳入变更治理** —— 见 §9 第四项。🟡 第一步已完成。

   白名单是整个安全模型的事实来源，加一条 MCP 工具策略就等于放行一个远端工具，
   但现在既没有双人复核也没有"谁在什么时候放行了什么"的可查记录。
   配置治理那一套（可见性分级 / 待复核单 / 双人 / 审计）已经写好且测过，
   把它复用到工具策略上，是**已有机制的复用**而不是新造轮子。
   > 这一条是实现 MCP 纳管时暴露出来的：我当时给"连接"加了双人复核，
   > 却发现真正承重的"授权"根本没有治理。

   **A1 已完成**：`TwoPersonReview` + `Operator` 下沉到 `oncall-domain`，
   `ConfigAdminController.confirm()` 改为委托它，本地只做 HTTP 状态码映射。
   刻意不复制一份复核逻辑到工具侧——两份一定会分叉，而分叉不会报错。
   19 个用例，其中两条守的是**判定顺序**：角色判定必须在"期间已被改过"之前，
   否则 VIEWER 能从错误消息里读出当前生效值。

   **A2 已完成**：`com.oncall.toolgateway.governance` 共 9 个类。
   判据不是「有没有改动」而是**风险方向**——放宽必须双人，收紧不需要
   （默认拒绝之下，撤掉一个危险工具让系统严格变安全；
   把它卡在双人流程后面，恰好保护了它本该消除的风险）。
   41 个用例。

   **A3 已完成**：新模块 `oncall-tool-admin`（8 个类 / 25 个用例）。
   `GovernanceException` 携带的 `ReviewVerdict` 被用掉了：
   四种拒绝映射到 410 / 403 / 409 / 409 四个状态码，
   机器码分别是 `EXPIRED` / `NOT_AUTHORIZED` / `SELF_APPROVAL` / `STALE`——
   前端靠它区分"换人"和"重新发起"。
   **没有并进 `oncall-config-admin`**：F3 禁止 `com.oncall.config..`
   依赖 `com.oncall.toolgateway..`，且 F3 刻意没有 `.admin..` 例外
   （F2 有，因为排的是零外部依赖；F3 排的是依赖方向，理由不同）。
   新增 ArchUnit **F10** 守住两个接入层互不依赖。
   **A4 已完成**：`register()/revoke()` 降为**包级可见**，
   `ToolPolicyGovernance` 移到与引擎同包（它是唯一有权改白名单的生产类）。
   用编译器的可见性而不是 ArchUnit 规则——规则可以被削弱，
   而且方法一旦包级可见就造不出违规样本，没法证明规则会红。
   可见性由 `ToolPolicyEngineVisibilityTest` 守住（6 个用例，非空自证）。
   构造器与读方法仍是 public：封的是运行期变更，不是启动装配也不是读。

### 轨道 B：AI 那一半（`ChatModel` 已落地 1 个装饰器，其余 5 个组件仍是 0）

2. **`oncall-agent-core` 模块 + `StubChatModel` + 第一个 L2 场景** —— ✅ **已完成**。
   这是 AI 那一半的**前置条件**而不是其中一项：没有 `StubChatModel`，
   任何编排逻辑都无法进 CI（真实模型非确定，进了 CI 就是随机红）。
   先有它，后面每一项才可测。

   落地内容：`ResilientChatModel`（failover + 退避重试）、`RetryPolicy`、
   `ModelExhaustedException`，加 `StubChatModel` 与 27 个用例（§1.10）。
   实测 427 用例 / 29 报告文件 / 102 生产类，一次跑绿。
   五个 L2 场景里只做了「模型抛 429 → 是否切 failover」这一个，
   因为它是**唯一不需要任何新领域决策**的——其余四个都要先定下
   计划格式或引用契约，那些决定不该被一个可靠性组件顺手做掉。

   **三个待接的口子**（都不是遗漏，是刻意留的）：
   - **真实的 429 分类器**：`RetryPolicy.retryable` 现在由调用方传入，
     默认是「不重试、直接 failover」。接上 DashScope 后要按**实际抛出的异常类型**
     写这个谓词——判得太严会导致"偶发失败"，判得太松会对 401 带着退避重试三次。
     在那之前不猜。
   - **流式重试**：`stream(Prompt)` 现在沿用接口默认实现直接抛。
     要支持就得在吐出第一个 token 之前决定是否重试（缓冲 + 阈值），是另一套设计。
   - **配置接线**：`fallback.model-failover-chain` 已存在但没人读。
     刻意**没有**在本轮加"最大重试次数"这个配置项——
     加一个没有读取者的键，界面上会显示"可配置"而改了什么都不会发生，
     那比写死的常量更糟。等应用模块接上时，键与读取者一起落地。
3. **`PromptRegistry`** —— prompt 作为带版本的配置而不是 Java 字符串常量。
   **不可事后补**：prompt 一旦散进代码，抽出来时版本历史与归因能力就没了。
4. **`IntentClassifier`** —— `EXECUTE` 意图必须在任何 LLM 之前用正则确定性判定。
   LLM 的分类结果是**路由**，不是**安全**；`EXECUTE` 召回率必须是 1.0 硬门槛。

**已完成**：

- **ArchUnit 规则 F1–F4 + F9**（`oncall-archtest`）—— 不变量 I1 从"自觉"变成编译期强制。
  F9 的表述与文档里原先写的不一样，见 `ArchitectureRuleTest` 的类注释：
  「禁止在网关外调 `ToolCallback.call`」在静态分析下不可判定，
  能判定的是实现类的集合。
- **M2 的 9 张表 DDL**（V2–V5）+ **轻量本体 4 张表**（V6）
  + **幂等账本 1 张**（V7），共 16 张。
  V1–V7 **全部在真实 PostgreSQL 16 + pgvector 上执行通过，重复执行也通过**
  （CI job `DDL on PostgreSQL 16 + pgvector`，run `33984843271`；
  `information_schema` 报 **19** 张 = 16 张逻辑表 + 3 个 DEFAULT 分区）。
  数据库级不变量已落到约束上：`chk_approval_not_self`（I3 "不能复核自己"）、
  `tool_execution_claim` 的主键（**I8 幂等的物理保证**）、`chk_claim_state`，
  外加 `uq_agent_step_idem`（步级幂等，**不覆盖工具调用**），
  这四条的存在性已写成 CI 断言。
  > 刚写完时它们从未被任何数据库执行过（沙箱无 PostgreSQL，H2 不支持
  > `PARTITION BY RANGE` 与 `vector(1024)`）。**与其在文档里写风险清单，
  > 不如加一个数据库 service 容器**——清单只能让人知道有风险，
  > 容器能让人知道没有风险。这个 job 现在是硬失败。
- **`oncall-ontology` 模块** —— 概念层 / 关系层 / 4 条规则（R1–R4），
  有界遍历 + 实体链接，JDBC 实现在真实 H2 上测过。
- **白名单变更入口封死** —— `ToolPolicyEngine.register()/revoke()` 降为包级可见，
  `ToolPolicyGovernance` 与引擎同包，是**唯一**有权改白名单的生产类。
  用编译器强制而不是规则，见 [DEVELOPMENT.md](DEVELOPMENT.md) §1.8。
- **`ToolPolicyGovernance`（`oncall-tool-gateway`）** —— 工具白名单变更
  终于有了双人复核与「谁在什么时候放行了什么」的审计。
  判据是风险方向而不是改动本身，见 [DEVELOPMENT.md](DEVELOPMENT.md) §1.7。
- **`TwoPersonReview` 决策核心（`oncall-domain`）** —— 双人复核的规则
  （过期 / 角色 / 不能自审 / 期间已被改过）**只有一份**，
  配置侧与工具侧共用。含判定顺序的两条安全断言，见 [DEVELOPMENT.md](DEVELOPMENT.md) §1.6。
- **幂等账本 `tool_execution_claim`（V7）+ `ToolExecutionLedger` 三件套** ——
  给不变量 I8 补上真正的物理保证。原先第④关是"先查后写"+ 内存态，
  多实例与并发下都不成立（详见 [DEVELOPMENT.md](DEVELOPMENT.md) §1.5）。
  24 个新用例，其中并发抢占在内存与 H2 上各测一遍，
  H2 的建表语句直接从 V7 文件里抽——测的是生产要执行的那份 DDL，不是副本。
- **`McpToolRegistrar`** —— 堵住不变量 I14（M1 里唯一的安全缺口）。
  MCP 工具只能显式纳管后进入，统一改名 `mcp:<server>:<tool>` 再走完整七道关卡；
  `mcp.toolcallback-enabled` 默认 false 且是 BACKEND_ONLY。22 个用例。
