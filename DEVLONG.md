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
| **I1** | 所有 AI 发起的写操作必须穿过 `GuardedToolCallback` | 代码结构 + ArchUnit 规则（F9，待实现） |
| **I2** | 未注册的工具一律拒绝，且**不泄露其存在性** | `ToolPolicyEngine` 默认拒绝 + 关卡①在②之前 |
| **I3** | 高危配置变更必须两个不同的人同意 | `ConfigAccessPolicy` + 待复核单 + "不能复核自己"断言 |
| **I4** | `BACKEND_ONLY` 的键对外表现与"不存在"完全一致 | 一律 404，不是 403 |
| **I5** | 高危配置清单不可被配置修改 | 硬编码在 `ConfigAccessPolicy.HIGH_RISK_KEYS` |
| **I6** | `oncall-domain` / `oncall-config` 生产代码零外部依赖 | 只用 JDK；H2 仅 test scope |
| **I7** | `app_config` 只有墓碑行，没有真删除 | `JdbcConfigStore.remove()` 写 NULL |
| **I8** | 幂等的最终保证在数据库唯一约束，不在应用层 | `agent_step.idempotency_key UNIQUE`（待落地） |
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
| **在 CI 里调真模型** | CI 会间歇性变红，而间歇性变红的 CI 等于没有 CI |

---

## 4. 已知的、刻意保留的不一致

| 项 | 现状 | 计划 |
|----|------|------|
| `架构设计方案.md` 与其他文档冲突 | **它是原始方案，已被修订**，保留作对照 | 不删除。以 `2.项目整体架构…` 与 `修复方案.md` 为准 |
| `架构设计方案.md §6.4` "Prompt 工程迭代（面试亮点）" | 是 60%→95% 的话术，无版本机制 | 用 `PromptRegistry` 替代（未实现） |
| `retrieval.top-n = 5` 仍在配置表里 | 与 `parent-top-n = 4` 语义重叠 | 属于**修改已冻结参数**，需评审签字后改 |
| `chunking.parent-max-tokens` 未冻结 | 导致 `最大上下文 12000` 无法被强制执行 | 建议 1200，封顶后总上下文 9700 < 12000 |
| ArchUnit 规则（F9）未实现 | 不变量 I1 目前靠自觉 | M2 或 M3 补上 |

---

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
| 文档写"36 项参数 / RUNTIME_HOT 20 项" | **39 项 / 23 热改** / 8 迁移 / 8 后端专属。少数了 2 项，很久没人发现。现在 `tierDistribution` 用精确断言拦 |
| 文档写"F3.2 四重预算" | 实际是**三重**（步数 / token / 成本） |
| 说"没有内置 reranker（issue #5903）" | **#5903 已被关为重复**，活 issue 是 **#6524** |
| 说"引用幻觉率 = 0" | 应为"**文档级**引用幻觉率 = 0"，`CitationVerifier` 只到 `doc_id` 粒度 |
| 说"三份文档已充分覆盖质量维度" | 错。**关键词计数不等于设计覆盖**。第一次核查时"幻觉"那一节只有一行配置 + 一句 prompt |
| 说"DeepSeek-V3 Quick 无法核实" | 错，它是配置别名 |
| 把 CI 的 success 当成"测试通过" | 错，当时 surefire 2.12.4 一个测试都没跑 |
| 加两个 `@SuppressWarnings("unused")` 常量来"消化"未使用 import | 错。**未使用 import 是代码没用这个类型的信号**，应该删 import |
| 凭空 import 了 `ConfigSchemaExporterHolder`，调用了不存在的 `service.auditHistory()` | 读一遍 `grep -nE "public "` 就能避免 |
| 测试辅助方法里混用两个时钟 | `PendingChangeStore.size()` 用真实时钟过滤注入时钟算出的过期时间，恒返回 0 |

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

## 9. 还没解决的三个问题

这三个不解决，M4 之后会卡住。**不是技术问题，是信息缺口。**

| 问题 | 影响 | 需要什么 |
|------|------|---------|
| **知识库清单** | 不知道有多少文档、什么格式、谁维护 ⇒ 无法估算索引成本与冷启动周期 | 找知识库的实际负责人盘点 |
| **CMDB 的告警→服务映射** | 没有映射就无法自动定位影响面，也无法 @ 对人 | 确认 CMDB 是否有这个关系，覆盖率多少 |
| **成本模型的真实聚合率** | 现在的 15% 是**假设值**，成本承诺全是空的 | W3 用真实数据修正 |

---

## 10. 下一步

见 [DEVELOPMENT.md](DEVELOPMENT.md) 的 M2 起。当前建议顺序：

1. **`McpToolRegistrar`** —— M1 最后一项，堵住不变量 I14
2. **ArchUnit 规则（F9）** —— 让不变量 I1 从"自觉"变成"编译期强制"
3. **M2 Flyway 建表** —— 特别是 `agent_step.idempotency_key` 的 UNIQUE（不变量 I8）
4. **`StubChatModel` + L2 测试** —— 覆盖 AI 编排逻辑又能进 CI 的唯一一层
