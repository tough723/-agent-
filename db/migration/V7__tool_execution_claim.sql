-- =====================================================================
-- 工具执行幂等账本
--
-- 【为什么需要这张表】
-- GuardedToolCallback 的第④关原先只查内存：
--     if (auditLog.has(key)) return resultOf(key);
-- 内存版在单实例下正确，多实例下必然失效——两个实例各有一份 map，
-- 同一个重试请求打到不同实例就会被执行两次。二次扩容、二次重启是会出真事故的。
-- 不变量 I8 的物理保证必须由数据库提供。
--
-- 【为什么是独立一张表，而不是给 tool_audit_log 加 UNIQUE】
-- 两者是不同的东西，生命周期也不同：
--   审计日志 = 只追加的事件流，保留 180 天，一次调用可以有多条
--              （审批 / 夹紧 / 成功 或 失败 各一条）
--   幂等账本 = 可变状态，一次调用只有一行，且失败后要能被删除以允许重试
-- 给审计表加 UNIQUE 会直接和「一次调用多条事件」冲突；
-- 把可变状态塞进只追加的日志表，则会让「删掉一行以允许重试」这种操作
-- 变成篡改审计记录。
--
-- 【为什么主键就够了，不需要额外的唯一索引】
-- 幂等键本身就是一行的身份。claim 用 INSERT 抢占，
-- 主键冲突即表示「别人已经抢到了」——这是数据库层面唯一能真正防止
-- 两个实例同时执行同一操作的机制（应用层的先查后写在并发下必然有窗口）。
--
-- 方言：PostgreSQL 10+
-- =====================================================================

CREATE TABLE IF NOT EXISTS tool_execution_claim (
    idempotency_key  VARCHAR(128)  NOT NULL PRIMARY KEY,
    tool_name        VARCHAR(191)  NOT NULL,
    state            VARCHAR(16)   NOT NULL,
    result           TEXT,
    created_at       TIMESTAMP     NOT NULL,
    updated_at       TIMESTAMP     NOT NULL,
    CONSTRAINT chk_claim_state CHECK (state IN ('CLAIMED','COMPLETED'))
);

COMMENT ON TABLE  tool_execution_claim                 IS '工具执行幂等账本；主键冲突就是「别人已经抢到执行权」的信号';
COMMENT ON COLUMN tool_execution_claim.state           IS 'CLAIMED 已抢占未结束 / COMPLETED 已完成可重放';
COMMENT ON COLUMN tool_execution_claim.result          IS 'COMPLETED 时的执行结果，重放时直接返回，不再真正执行';

-- 清理任务用：CLAIMED 且长时间未更新的行是崩溃残留，
-- 应当释放（删除）以允许重试，否则这个幂等键会永久卡死。
CREATE INDEX IF NOT EXISTS idx_claim_stale ON tool_execution_claim (state, updated_at);
