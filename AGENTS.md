# AGENTS.md — AI 编码代理工作约定

给在这个仓库里工作的 AI 编码代理。**读完再动手**，这里的每一条都对应一次真实的返工。

---

## 1. 这个项目是什么

一个**能改生产环境**的运维 Agent 系统。它的全部价值取决于"它不会做错事"这个信任，
而这个信任**不能靠 prompt 保证，只能靠代码结构保证**。

所以：**任何让某个安全关卡变得可绕过的改动，都是破坏性改动**，
即使它让代码更简洁、更"优雅"。

---

## 2. 当前状态（动手前先核对）

| 项 | 值 |
|----|-----|
| 分支 | `arena/01a06d8c-agent`（**只在这个分支上工作**） |
| 模块 | `oncall-domain`、`oncall-config`、`oncall-config-admin`、`oncall-tool-gateway`、`oncall-ontology`、`oncall-archtest`（仅测试） |
| 测试 | **323 个用例 / 23 个测试类**（CI run `33985929578` 实测，失败 0） |
| 配置项 | 41 项（24 `RUNTIME_HOT` / 8 `REQUIRES_MIGRATION` / 9 `BACKEND_ONLY`） |
| 数据库 | 15 张表的 DDL 已写入，全部在真实 PostgreSQL 16 + pgvector 上执行通过（含重复执行） |
| 阶段 | M1 / M1.5 完成，M2 起 |

**这些数字会变。** 改完之后要同步 `DEVELOPMENT.md`，
并且注意 `OnCallConfigRegistryTest.tierDistribution` 用的是**精确断言**——
加一个配置项就会红，那是故意的。

---

## 3. 硬性约束（违反就是 bug）

### 3.1 依赖

- **`oncall-domain` 与 `oncall-config` 生产代码零外部依赖。**
  只能用 JDK。JDBC 实现只用 `javax.sql` / `java.sql`；JSON 手写不引 Jackson。
  H2 只在 test scope。
- 新增依赖必须说明：为什么不能用已有的、是否 test-scope、版本是否已确认在 Maven Central。
- **`spring-ai-bom` 与 `spring-ai-alibaba-bom` 必须同时存在。**
  只有前者管 `org.springframework.ai:*`；缺了它整个 reactor 都构建不了。

### 3.2 安全

- **不得在网关之外直接调用 `ToolCallback.call`。** 所有工具调用必须穿过 `GuardedToolCallback`。
- **不得做一个能执行任意 SQL 的工具。** 参考项目就是这么做的，那是事故源。
- **不得让模型生成 PromQL。** 必须是参数化模板，模型只能填参数。
- **`BACKEND_ONLY` 的键对外一律 404，不是 403。** 403 泄露存在性。
- **高危配置清单不得做成配置项。** 否则双人复核被自己保护的机制绕过。
- **prompt 不得写进 Java 字符串常量。** 会同时失去灰度、回滚、归因、评审四种能力。

### 3.3 不要"顺手优化"的东西

| 别动 | 理由 |
|------|------|
| `GuardedToolCallback` 的关卡顺序 | 顺序错了不报错，只会静默改变安全语义。有 3 条测试专门守着 |
| `app_config` 的墓碑行逻辑 | 真删行会让 `revision()` 倒退，快照缓存会读到旧值 |
| `agent_step.idempotency_key` 的 UNIQUE 约束 | 多实例下应用层幂等会失效，这是唯一兜底 |
| `temperature` 默认 0 | 不为 0 则评测结果不可复现，回归无法判定 |
| `vector(1024)` 与 `vector_cosine_ops` | 写错不报错，静默退化成全表扫描 |
| `mcp.client.toolcallback.enabled=false` | **默认是 true**。开着的话运行时拉取的工具绕过所有注解式管控 |

---

## 4. 构建与验证

### 4.1 本地没有 JDK

这个沙箱**没有 Java 工具链，也没有 Maven Central 的网络出口**。
`which javac`、`find / -name javac`、`apt-get update`、`curl` 到 Central 全部失败。
**不要尝试安装 JDK，那是浪费时间。**

能做的静态检查（每次提交前都跑）：

```python
# XML 合法性 / 包名↔目录一致 / 未使用 import / 括号配对 / record 静态工厂同名
```

注意：**判断未使用 import 前必须先剥离注释与字符串**。
`ConfigSchemaExporter` 手写了 JSON，含 `'{'` `'}'` `'"'` 字符字面量，
不剥离会误报括号不配对。

### 4.2 唯一的真实验证通道是 CI

推上去，然后读 **check-run annotation**：

```bash
SHA=$(git rev-parse HEAD)
CR=$(curl -s -H "Authorization: Bearer $GH_TOKEN" \
  "https://api.github.com/repos/tough723/-agent-/commits/$SHA/check-runs" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['check_runs'][0]['id'])")
curl -s -H "Authorization: Bearer $GH_TOKEN" \
  "https://api.github.com/repos/tough723/-agent-/check-runs/$CR/annotations?per_page=100"
```

**⚠️ job 日志取不到**：`gh run view --log` 是空的，
`api.github.com/.../jobs/<id>/logs` 下载 SSL 失败，`gh run download` blob EOF。
**annotation 是唯一可读通道。**

**⚠️ GitHub annotation 上限是每个 run 每级别 10 条。**
发 10 条 `[ERROR]` 首行会把真正需要的失败栈挤掉。
要压成少量多行（换行用 `%0A`）。`.github/mvn-test.sh` 已经处理好了。

### 4.3 绿色的构建不能证明任何关于测试的事

surefire 2.12.4（Maven super-POM 的默认值）不支持 JUnit 5，
会"找不到测试"然后**静默成功**。必须看 `测试统计` annotation 里的数字。

---

## 5. Git

**⚠️ 这个工作区的本地 git 历史会被周期性重置到 `5a4fc36`**（工作区文件完好，
远程也完好）。表现是推送被拒 `(fetch first)`。

正确处置：

```bash
git fetch origin arena/01a06d8c-agent
git log --oneline HEAD..FETCH_HEAD        # 确认远程提交都在
git reset --soft FETCH_HEAD               # 保留 index + 工作区
git status --short                        # 确认差异只有本轮改动
git commit && git push
```

**永远不要强推。** 强推会真的丢掉远程历史。

---

## 6. 文档

改代码要同步文档。特别是：

| 改动 | 要同步 |
|------|-------|
| 加/删配置项 | `DEVELOPMENT.md` 的计数、`配置外置与前端可配置设计.md`、`tierDistribution` 测试 |
| 加模块 | 父 POM `<modules>`、`.github/workflows/ci.yml` 的步骤、`DEVELOPMENT.md` 的模块树 |
| 加表 | `db/migration/`、`5.数据库设计文档.md` |
| 加端点 | `3.接口设计api规范.md` |

**文档里的数字要能核对。** 曾经文档写"36 项参数 / RUNTIME_HOT 20 项"而实际是
39 / 23，差了 2 项很久没人发现。现在 `tierDistribution` 用精确断言拦这个。

---

## 7. 汇报

- **不要说"用了 23 种设计模式"。** 说 5 种组合，并说清各自解决什么问题。
- **不要说"引用幻觉率 = 0"**，要说"**文档级**引用幻觉率 = 0"——
  `CitationVerifier` 只校验到 `doc_id` 粒度。
- **不要把"自动处置率"当 SLO。** 那是业务指标，当 SLO 会诱导放宽放权。
- **不要凭记忆断言 API 签名。** 用 `web_search` 或 `mvnrepository.com` 核实。
  这个项目里已经因为猜错签名浪费过 CI 轮次。
- **算过发现不重要的事，也要说。** 例如重试的 token 成本只有 +1.4%（¥11/月），
  我算完发现可以忽略——但同一个重试在延迟上是 +8s，直接击穿 SLA。
  **同一个设计决策在不同维度上结论可以完全相反。**

---

## 8. 关键文档索引

| 想知道 | 看 |
|-------|-----|
| 现在做到哪了 | [DEVELOPMENT.md](DEVELOPMENT.md) |
| 为什么是这个架构 | [2.项目整体架构和技术栈选型分析设计文档.md](2.项目整体架构和技术栈选型分析设计文档.md) |
| 施工依据 | [修复方案.md](修复方案.md) |
| 哪些决定不能改 | [开工前决策冻结与返工风险评估.md](开工前决策冻结与返工风险评估.md) |
| 踩过的坑 | [4.开发编码规范.md](4.开发编码规范.md) §3 |
| 长期上下文与不变量 | [DEVLONG.md](DEVLONG.md) |
