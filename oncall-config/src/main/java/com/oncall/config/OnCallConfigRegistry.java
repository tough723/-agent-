package com.oncall.config;

import java.util.ArrayList;
import java.util.List;

/**
 * OnCall Agent 的配置声明表——**「哪些参数被冻结在什么值」的唯一事实来源**。
 *
 * <p>逐条对应两份文档：
 * <ul>
 *   <li>{@code 质量与可靠性设计.md §3.1} 的 19 项冻结参数；</li>
 *   <li>{@code 开工前决策冻结与返工风险评估.md §5} 补充的 6 项。</li>
 * </ul>
 *
 * <p><b>关于「冻结」与「可配置」的关系：</b>这两件事不矛盾。
 * 冻结的是**默认值（基线）**，它经过评审签字，改动要走变更流程并留审计；
 * 外置的是**位置**——把值从代码里挪到可治理的配置面。
 * 外置反而降低了返工风险：调参不再需要发版，也就不会因为「改个阈值要发版」
 * 而有人去改代码里的常量。
 *
 * <p><b>但有三类参数物理上或安全上不能做成前端可改</b>，已按
 * {@link ConfigTier#BACKEND_ONLY} 处理，理由见该枚举的注释。
 */
public final class OnCallConfigRegistry {

    private OnCallConfigRegistry() {
    }

    public static ConfigRegistry create() {
        List<ConfigSpec> specs = new ArrayList<>();
        retrieval(specs);
        chunking(specs);
        vector(specs);
        generation(specs);
        memory(specs);
        agent(specs);
        ticket(specs);
        alert(specs);
        autonomy(specs);
        mcp(specs);
        fallback(specs);
        return new ConfigRegistry(specs);
    }

    // ------------------------------------------------------------------ 检索

    private static void retrieval(List<ConfigSpec> out) {
        String g = OnCallConfigKeys.GROUP_RETRIEVAL;
        out.add(ConfigSpec.builder(OnCallConfigKeys.RETRIEVAL_CANDIDATE_SIZE, ConfigType.INT, "20", ConfigTier.RUNTIME_HOT)
                .group(g).bounds(1, 100)
                .description("粗排候选数，交给 reranker 的文档数。调大提升召回但线性增加 rerank 成本与延迟。")
                .build());
        out.add(ConfigSpec.builder(OnCallConfigKeys.RETRIEVAL_TOP_N, ConfigType.INT, "5", ConfigTier.RUNTIME_HOT)
                .group(g).bounds(1, 20)
                .description("最终喂给模型的文档数。调大会挤占上下文预算并推高 token 成本。")
                .build());
        out.add(ConfigSpec.builder(OnCallConfigKeys.RETRIEVAL_RRF_K, ConfigType.INT, "60", ConfigTier.RUNTIME_HOT)
                .group(g).bounds(1, 500)
                .description("RRF 融合常数 k。60 是经验默认值，非特殊场景不要调。")
                .build());
        out.add(ConfigSpec.builder(OnCallConfigKeys.RETRIEVAL_SIMILARITY_THRESHOLD, ConfigType.DOUBLE, "0.5", ConfigTier.RUNTIME_HOT)
                .group(g).bounds(0.0, 1.0)
                .description("相似度阈值。低于此值的结果不进入候选。")
                .build());
        out.add(ConfigSpec.builder(OnCallConfigKeys.RETRIEVAL_SIMILARITY_THRESHOLD_RETRY, ConfigType.DOUBLE, "0.3", ConfigTier.RUNTIME_HOT)
                .group(g).bounds(0.0, 1.0)
                .description("空召回时的重试阈值。必须小于主阈值，否则重试无意义。")
                .build());
        out.add(ConfigSpec.builder(OnCallConfigKeys.RETRIEVAL_RERANK_ENABLED, ConfigType.BOOLEAN, "false", ConfigTier.RUNTIME_HOT)
                .group(g)
                .description("是否启用 reranker。默认关闭——先用 Golden Set 测出无 reranker 的 "
                        + "Chunk-Recall@5 baseline，确认 < 0.85 再开。开了就有按调用计费的成本。")
                .build());
        out.add(ConfigSpec.builder(OnCallConfigKeys.RETRIEVAL_RERANK_MIN_SCORE, ConfigType.DOUBLE, "0.3", ConfigTier.RUNTIME_HOT)
                .group(g).bounds(0.0, 1.0)
                .description("rerank 最低分，低于此值视为噪音不注入上下文。仅在 rerank 开启时生效。")
                .build());
    }

    // ------------------------------------------------------------------ 切片

    private static void chunking(List<ConfigSpec> out) {
        String g = OnCallConfigKeys.GROUP_CHUNKING;
        String hint = "改动后必须触发全量重建索引；重建完成前新旧 chunk 不能混用。";
        out.add(ConfigSpec.builder(OnCallConfigKeys.CHUNKING_RUNBOOK_MODE, ConfigType.ENUM_STRING, "HEADING", ConfigTier.REQUIRES_MIGRATION)
                .group(g).allowedValues(List.of("HEADING", "FIXED")).migrationHint(hint)
                .description("Runbook 切片方式。HEADING = 按 H2/H3 标题切（推荐，语义边界清晰）；FIXED = 固定长度。")
                .build());
        out.add(ConfigSpec.builder(OnCallConfigKeys.CHUNKING_RCA_CHUNK_SIZE, ConfigType.INT, "800", ConfigTier.REQUIRES_MIGRATION)
                .group(g).bounds(100, 4000).migrationHint(hint)
                .description("RCA 报告的 chunk 大小（token）。")
                .build());
        out.add(ConfigSpec.builder(OnCallConfigKeys.CHUNKING_RCA_OVERLAP, ConfigType.INT, "100", ConfigTier.REQUIRES_MIGRATION)
                .group(g).bounds(0, 1000).migrationHint(hint)
                .description("RCA 报告的 chunk 重叠（token）。")
                .build());
        out.add(ConfigSpec.builder(OnCallConfigKeys.CHUNKING_WIKI_CHUNK_SIZE, ConfigType.INT, "1000", ConfigTier.REQUIRES_MIGRATION)
                .group(g).bounds(100, 4000).migrationHint(hint)
                .description("Wiki 类文档的 chunk 大小（token）。")
                .build());
        out.add(ConfigSpec.builder(OnCallConfigKeys.CHUNKING_WIKI_OVERLAP, ConfigType.INT, "150", ConfigTier.REQUIRES_MIGRATION)
                .group(g).bounds(0, 1000).migrationHint(hint)
                .description("Wiki 类文档的 chunk 重叠（token）。")
                .build());
        out.add(ConfigSpec.builder(OnCallConfigKeys.CHUNKING_CHILD_TOKEN_SIZE, ConfigType.INT, "300", ConfigTier.REQUIRES_MIGRATION)
                .group(g).bounds(50, 2000).migrationHint(hint)
                .description("父子检索中子块的 token 大小。子块用于精确命中，父块用于给模型完整上下文。")
                .build());
        out.add(ConfigSpec.builder(OnCallConfigKeys.CHUNKING_MAX_PARENTS, ConfigType.INT, "4", ConfigTier.REQUIRES_MIGRATION)
                .group(g).bounds(1, 10).migrationHint(hint)
                .description("一次最多回溯的父块数。调大会显著增加上下文长度与成本。")
                .build());
    }

    // ------------------------------------------------------------------ 向量存储

    private static void vector(List<ConfigSpec> out) {
        String g = OnCallConfigKeys.GROUP_VECTOR;

        out.add(ConfigSpec.builder(OnCallConfigKeys.VECTOR_EMBEDDING_MODEL, ConfigType.STRING,
                        "text-embedding-v4", ConfigTier.REQUIRES_MIGRATION)
                .group(g)
                .migrationHint("换模型 = 全库重新嵌入 + 重建向量表 + 召回基线作废 + Golden Set 重标。"
                        + "且不同 embedding 版本的向量位于不同语义空间，即使维度相同也不能互相比较。")
                .description("Embedding 模型。这是全系统改动代价最高的一项，默认值需评审签字。"
                        + "API 模型会把知识库内容发到外部服务，选型即合规决策。")
                .build());

        // 下面四项是 DDL 绑定项：值写死在建表语句的列定义/索引定义里，
        // 改了必须重建表。放前端只会诱导误操作，因此 BACKEND_ONLY。
        out.add(ConfigSpec.builder(OnCallConfigKeys.VECTOR_DIMENSION, ConfigType.INT, "1024", ConfigTier.BACKEND_ONLY)
                .group(g).bounds(64, 2000)
                .description("向量维度。写死在 vector(N) 列定义里，改维度必须 DROP 并重建表。"
                        + "上界 2000 是 pgvector HNSW 索引的硬限制——注意上游模型可能提供 2048 维，选它会建索引失败。")
                .build());
        out.add(ConfigSpec.builder(OnCallConfigKeys.VECTOR_DISTANCE_TYPE, ConfigType.ENUM_STRING,
                        "COSINE_DISTANCE", ConfigTier.BACKEND_ONLY)
                .group(g).allowedValues(List.of("COSINE_DISTANCE", "EUCLIDEAN_DISTANCE", "NEGATIVE_INNER_PRODUCT"))
                .description("距离函数。必须与索引算子和查询算子三者一致，任一处不匹配索引静默失效、退化为全表扫描。")
                .build());
        out.add(ConfigSpec.builder(OnCallConfigKeys.VECTOR_INDEX_TYPE, ConfigType.ENUM_STRING,
                        "HNSW", ConfigTier.BACKEND_ONLY)
                .group(g).allowedValues(List.of("HNSW", "IVFFLAT", "NONE"))
                .description("向量索引类型。HNSW 查询更快但建索引慢、占内存多。")
                .build());

        // 这一项是 RUNTIME_HOT，且上界 10 是上游 API 的硬限制。
        // 把它编码成边界，前端就物理上填不出会导致灌库失败的 10000。
        out.add(ConfigSpec.builder(OnCallConfigKeys.VECTOR_EMBEDDING_BATCH_SIZE, ConfigType.INT, "10", ConfigTier.RUNTIME_HOT)
                .group(g).bounds(1, 10)
                .description("单次 embedding 请求的文档数。上界 10 是上游 API 的硬限制，"
                        + "超过会被拒——注意框架默认值是 10000，直接用默认值会在灌库阶段失败。")
                .build());
    }

    // ------------------------------------------------------------------ 生成

    private static void generation(List<ConfigSpec> out) {
        String g = OnCallConfigKeys.GROUP_GENERATION;
        out.add(ConfigSpec.builder(OnCallConfigKeys.GENERATION_MAX_CONTEXT_TOKENS, ConfigType.INT, "12000", ConfigTier.RUNTIME_HOT)
                .group(g).bounds(1000, 128000)
                .description("单次调用的最大上下文 token。超窗时按 rerank 分数从低到高丢弃。")
                .build());
        out.add(ConfigSpec.builder(OnCallConfigKeys.GENERATION_MAX_OUTPUT_TOKENS, ConfigType.INT, "2000", ConfigTier.RUNTIME_HOT)
                .group(g).bounds(200, 16000)
                .description("单次调用的最大输出 token。直接影响成本。")
                .build());
        // 这个参数同时影响三件事：可复现性（不为 0 则评测判据失效）、幻觉率、
        // 结构化输出成功率。运维 Agent 不需要创造性，三个场景都用 0。
        // 设为 RUNTIME_HOT 是为了能做 A/B 对比实验，不是鼓励调高。
        out.add(ConfigSpec.builder(OnCallConfigKeys.GENERATION_TEMPERATURE, ConfigType.DOUBLE, "0.0", ConfigTier.RUNTIME_HOT)
                .group(g).bounds(0.0, 1.0)
                .description("采样温度。默认 0：运维场景不需要创造性，且不为 0 时评测结果不可复现、"
                        + "回归无法判定。评测跑批必须为 0。调高会同时抬高幻觉率与非法 JSON 概率。")
                .build());
    }

    // ------------------------------------------------------------------ 记忆

    private static void memory(List<ConfigSpec> out) {
        String g = OnCallConfigKeys.GROUP_MEMORY;
        out.add(ConfigSpec.builder(OnCallConfigKeys.MEMORY_WINDOW_SIZE, ConfigType.INT, "6", ConfigTier.RUNTIME_HOT)
                .group(g).bounds(1, 50)
                .description("多轮对话的滑动窗口条数。")
                .build());
        out.add(ConfigSpec.builder(OnCallConfigKeys.MEMORY_SUMMARY_THRESHOLD, ConfigType.INT, "20", ConfigTier.RUNTIME_HOT)
                .group(g).bounds(2, 200)
                .description("触发历史摘要的对话条数阈值。")
                .build());
    }

    // ------------------------------------------------------------------ Agent

    private static void agent(List<ConfigSpec> out) {
        String g = OnCallConfigKeys.GROUP_AGENT;
        out.add(ConfigSpec.builder(OnCallConfigKeys.AGENT_MAX_STEPS, ConfigType.INT, "10", ConfigTier.RUNTIME_HOT)
                .group(g).bounds(1, 50)
                .description("单次排查的最大步数。这是成本上限的主护栏之一。")
                .build());
        out.add(ConfigSpec.builder(OnCallConfigKeys.AGENT_MAX_REPLANS, ConfigType.INT, "5", ConfigTier.RUNTIME_HOT)
                .group(g).bounds(0, 20)
                .description("最大重规划次数。")
                .build());
        out.add(ConfigSpec.builder(OnCallConfigKeys.AGENT_TOOL_STEP_TIMEOUT, ConfigType.DURATION, "30s", ConfigTier.RUNTIME_HOT)
                .group(g)
                .description("单个工具调用的超时。")
                .build());
        out.add(ConfigSpec.builder(OnCallConfigKeys.AGENT_PLANNER_STEP_TIMEOUT, ConfigType.DURATION, "60s", ConfigTier.RUNTIME_HOT)
                .group(g)
                .description("Planner 单步的超时。")
                .build());
    }

    // ------------------------------------------------------------------ 工单

    private static void ticket(List<ConfigSpec> out) {
        String g = OnCallConfigKeys.GROUP_TICKET;
        out.add(ConfigSpec.builder(OnCallConfigKeys.TICKET_APPROVAL_TIMEOUT, ConfigType.DURATION, "15m", ConfigTier.RUNTIME_HOT)
                .group(g)
                .description("审批超时。超时不是卡死，而是升级信号——必须触发通知，不能让工单永久停在 PENDING_APPROVAL。")
                .build());
        out.add(ConfigSpec.builder(OnCallConfigKeys.TICKET_EXECUTION_TIMEOUT, ConfigType.DURATION, "10m", ConfigTier.RUNTIME_HOT)
                .group(g)
                .description("执行超时。")
                .build());
    }

    // ------------------------------------------------------------------ 告警

    private static void alert(List<ConfigSpec> out) {
        String g = OnCallConfigKeys.GROUP_ALERT;
        out.add(ConfigSpec.builder(OnCallConfigKeys.ALERT_AGGREGATION_WINDOW, ConfigType.DURATION, "5m", ConfigTier.RUNTIME_HOT)
                .group(g)
                .description("告警聚合窗口。聚合率是 LLM 成本的第一杠杆，调大会省钱但延迟首次结论。")
                .build());
        out.add(ConfigSpec.builder(OnCallConfigKeys.ALERT_STORM_THRESHOLD_PER_MINUTE, ConfigType.INT, "50", ConfigTier.RUNTIME_HOT)
                .group(g).bounds(1, 10000)
                .description("告警风暴熔断阈值（条/分钟）。超过则熔断，避免风暴打爆成本与人力。")
                .build());
    }

    // ------------------------------------------------------------------ 放权

    private static void autonomy(List<ConfigSpec> out) {
        String g = OnCallConfigKeys.GROUP_AUTONOMY;
        // 取值必须与 oncall-domain 的 AutonomyLevel 枚举一致。
        // 这里用 ENUM_STRING 而不是直接引用枚举，是为了让 oncall-config 保持零依赖；
        // 一致性由 OnCallConfigRegistryTest 守住。
        out.add(ConfigSpec.builder(OnCallConfigKeys.AUTONOMY_LEVEL, ConfigType.ENUM_STRING, "SHADOW", ConfigTier.RUNTIME_HOT)
                .group(g).allowedValues(List.of("SHADOW", "SUGGEST", "ASSIST", "BOUNDED_AUTO"))
                .description("AI 放权等级。S0 影子 → S1 建议 → S2 辅助 → S3 限定自动。"
                        + "晋级需满足门槛表；降级不需要审批，出现 1 次误操作即自动降一级。")
                .build());
        // 取值必须与 oncall-tool-gateway 的 RunMode 枚举一致。
        out.add(ConfigSpec.builder(OnCallConfigKeys.AUTONOMY_KILL_SWITCH_MODE, ConfigType.ENUM_STRING, "FULL", ConfigTier.RUNTIME_HOT)
                .group(g).allowedValues(List.of("FULL", "READ_ONLY", "OFF"))
                .description("全局运行模式（kill switch）。READ_ONLY 拒绝一切非只读工具；OFF 完全停用 Agent。")
                .build());
    }

    private static void mcp(List<ConfigSpec> out) {
        String g = OnCallConfigKeys.GROUP_MCP;

        // 这一项对应 Spring AI 的 spring.ai.mcp.client.toolcallback.enabled。
        // 【为什么必须是 BACKEND_ONLY 而不是 RUNTIME_HOT】
        // 框架的这个开关默认是 true —— 也就是"什么都不做"的情况下，
        // MCP client 会自己把远端发现的工具注册给模型，整套工具网关被绕过。
        // 把它放到前端就等于给界面加了一个"关闭全部安全关卡"的按钮。
        // 它属于兜底机制配置，按项目约束只有这类才允许后端固定。
        out.add(ConfigSpec.builder(OnCallConfigKeys.MCP_TOOLCALLBACK_ENABLED, ConfigType.BOOLEAN,
                        "false", ConfigTier.BACKEND_ONLY)
                .group(g)
                .description("是否允许框架自动把 MCP 工具注册给模型。必须为 false："
                        + "MCP 工具只能经 McpToolRegistrar 显式纳管后进入（不变量 I14）。")
                .build());

        // 刻意【没有】mcp.allowed-servers：那会与工具白名单形成两个事实来源。
        // 允许连接哪些 server 由已注册的 MCP 工具策略反推
        // （ToolPolicyEngine.mcpServers()），一份清单不可能与自己矛盾。

        // 刻意不做成"自动纳管新工具"的开关：默认拒绝是不变量，不是可调参数。
        // 这个键只控制"多久重新看一眼清单以便发现异常"，不控制"发现了要不要放行"。
        out.add(ConfigSpec.builder(OnCallConfigKeys.MCP_DISCOVERY_REFRESH_SECONDS, ConfigType.INT,
                        "300", ConfigTier.RUNTIME_HOT)
                .group(g).bounds(60, 86400)
                .description("重新拉取 MCP 工具清单的间隔（秒）。用途是发现 server 悄悄新增了工具"
                        + "并告警，不是自动放行——未纳管的工具永远进不来。")
                .build());
    }

    // ------------------------------------------------------------------ 兜底

    /**
     * 兜底机制配置。按架构约束**显式排除在前端之外**（BACKEND_ONLY）。
     *
     * <p>理由：兜底是系统最后一道防线。如果它本身可以在前端被改坏，
     * 那「兜底」这个概念就不成立了——一个能把兜底关掉的界面，
     * 恰恰在最需要兜底的时刻最危险。
     */
    private static void fallback(List<ConfigSpec> out) {
        String g = OnCallConfigKeys.GROUP_FALLBACK;
        out.add(ConfigSpec.builder(OnCallConfigKeys.FALLBACK_RULE_BASED_ENABLED, ConfigType.BOOLEAN, "true", ConfigTier.BACKEND_ONLY)
                .group(g)
                .description("LLM 全挂时是否启用规则兜底（产出信息聚合包，不做判断）。")
                .build());
        out.add(ConfigSpec.builder(OnCallConfigKeys.FALLBACK_EMBEDDING_DEGRADE_TO_BM25, ConfigType.BOOLEAN, "true", ConfigTier.BACKEND_ONLY)
                .group(g)
                .description("embedding 服务不可用时是否降级为纯 BM25 关键词检索。")
                .build());
        out.add(ConfigSpec.builder(OnCallConfigKeys.FALLBACK_RERANKER_DEGRADE_TO_ORIGINAL_ORDER, ConfigType.BOOLEAN, "true", ConfigTier.BACKEND_ONLY)
                .group(g)
                .description("reranker 调用失败时是否返回原始顺序前 N（用户无感降级）。")
                .build());
        out.add(ConfigSpec.builder(OnCallConfigKeys.FALLBACK_MODEL_FAILOVER_CHAIN, ConfigType.STRING_LIST,
                        "deepseek-v4-flash,deepseek-v4-pro", ConfigTier.BACKEND_ONLY)
                .group(g)
                .description("模型 failover 链，按顺序尝试。全部耗尽后进入规则兜底。")
                .build());
        out.add(ConfigSpec.builder(OnCallConfigKeys.FALLBACK_INJECTION_BLOCK_WRITE, ConfigType.BOOLEAN, "true", ConfigTier.BACKEND_ONLY)
                .group(g)
                .description("检测到提示注入时是否阻断一切写操作。这个开关没有任何理由被关闭。")
                .build());
    }
}
