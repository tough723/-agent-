package com.oncall.config;

/**
 * 全部配置键的常量声明。
 *
 * <p>业务代码引用常量而不是字符串字面量，拼错的键在编译期就暴露，
 * 而不是运行时才发现读不到值。
 *
 * <p>键名与 {@code 质量与可靠性设计.md §3.1} 冻结清单、
 * {@code 开工前决策冻结与返工风险评估.md §5} 补充清单逐条对应。
 */
public final class OnCallConfigKeys {

    private OnCallConfigKeys() {
    }

    // ---- 分组名 ----
    public static final String GROUP_RETRIEVAL = "retrieval";
    public static final String GROUP_CHUNKING = "chunking";
    public static final String GROUP_VECTOR = "vector";
    public static final String GROUP_GENERATION = "generation";
    public static final String GROUP_MEMORY = "memory";
    public static final String GROUP_AGENT = "agent";
    public static final String GROUP_TICKET = "ticket";
    public static final String GROUP_ALERT = "alert";
    public static final String GROUP_AUTONOMY = "autonomy";
    public static final String GROUP_MCP = "mcp";
    public static final String GROUP_FALLBACK = "fallback";

    // ---- 检索 ----
    public static final String RETRIEVAL_CANDIDATE_SIZE = "retrieval.candidate-size";
    public static final String RETRIEVAL_TOP_N = "retrieval.top-n";
    public static final String RETRIEVAL_RRF_K = "retrieval.rrf-k";
    public static final String RETRIEVAL_SIMILARITY_THRESHOLD = "retrieval.similarity-threshold";
    public static final String RETRIEVAL_SIMILARITY_THRESHOLD_RETRY = "retrieval.similarity-threshold-retry";
    public static final String RETRIEVAL_RERANK_ENABLED = "retrieval.rerank-enabled";
    public static final String RETRIEVAL_RERANK_MIN_SCORE = "retrieval.rerank-min-score";

    // ---- 切片 ----
    public static final String CHUNKING_RUNBOOK_MODE = "chunking.runbook-mode";
    public static final String CHUNKING_RCA_CHUNK_SIZE = "chunking.rca-chunk-size";
    public static final String CHUNKING_RCA_OVERLAP = "chunking.rca-overlap";
    public static final String CHUNKING_WIKI_CHUNK_SIZE = "chunking.wiki-chunk-size";
    public static final String CHUNKING_WIKI_OVERLAP = "chunking.wiki-overlap";
    public static final String CHUNKING_CHILD_TOKEN_SIZE = "chunking.child-token-size";
    public static final String CHUNKING_MAX_PARENTS = "chunking.max-parents";

    // ---- 向量存储 ----
    public static final String VECTOR_EMBEDDING_MODEL = "vector.embedding-model";
    public static final String VECTOR_DIMENSION = "vector.dimension";
    public static final String VECTOR_DISTANCE_TYPE = "vector.distance-type";
    public static final String VECTOR_INDEX_TYPE = "vector.index-type";
    public static final String VECTOR_EMBEDDING_BATCH_SIZE = "vector.embedding-batch-size";

    // ---- 生成 ----
    public static final String GENERATION_MAX_CONTEXT_TOKENS = "generation.max-context-tokens";
    public static final String GENERATION_MAX_OUTPUT_TOKENS = "generation.max-output-tokens";
    public static final String GENERATION_TEMPERATURE = "generation.temperature";

    // ---- 记忆 ----
    public static final String MEMORY_WINDOW_SIZE = "memory.window-size";
    public static final String MEMORY_SUMMARY_THRESHOLD = "memory.summary-threshold";

    // ---- Agent ----
    public static final String AGENT_MAX_STEPS = "agent.max-steps";
    public static final String AGENT_MAX_REPLANS = "agent.max-replans";
    public static final String AGENT_TOOL_STEP_TIMEOUT = "agent.tool-step-timeout";
    public static final String AGENT_PLANNER_STEP_TIMEOUT = "agent.planner-step-timeout";

    // ---- 工单状态机 ----
    public static final String TICKET_APPROVAL_TIMEOUT = "ticket.approval-timeout";
    public static final String TICKET_EXECUTION_TIMEOUT = "ticket.execution-timeout";

    // ---- 告警 ----
    public static final String ALERT_AGGREGATION_WINDOW = "alert.aggregation-window";
    public static final String ALERT_STORM_THRESHOLD_PER_MINUTE = "alert.storm-threshold-per-minute";

    // ---- 放权 ----
    public static final String AUTONOMY_LEVEL = "autonomy.level";
    public static final String AUTONOMY_KILL_SWITCH_MODE = "autonomy.kill-switch-mode";

    // ---- MCP 工具纳管 ----
    //
    // 刻意【没有】mcp.tool-name-prefix 这个键：
    // 前缀格式 mcp:<server>:<tool> 是安全边界的一部分（它让"哪个 server 的哪个工具"
    // 可寻址），做成可配置就等于允许有人把两个 server 的工具名空间合并，
    // 从而用 A 的纳管结果授权 B 的工具。这类东西属于不变量，不属于配置。
    public static final String MCP_TOOLCALLBACK_ENABLED = "mcp.toolcallback-enabled";
    public static final String MCP_ALLOWED_SERVERS = "mcp.allowed-servers";
    public static final String MCP_DISCOVERY_REFRESH_SECONDS = "mcp.discovery-refresh-seconds";

    // ---- 兜底（按架构约束仅后端可见）----
    public static final String FALLBACK_RULE_BASED_ENABLED = "fallback.rule-based.enabled";
    public static final String FALLBACK_EMBEDDING_DEGRADE_TO_BM25 = "fallback.embedding.degrade-to-bm25";
    public static final String FALLBACK_RERANKER_DEGRADE_TO_ORIGINAL_ORDER = "fallback.reranker.degrade-to-original-order";
    public static final String FALLBACK_MODEL_FAILOVER_CHAIN = "fallback.model.failover-chain";
    public static final String FALLBACK_INJECTION_BLOCK_WRITE = "fallback.injection.block-write";
}
