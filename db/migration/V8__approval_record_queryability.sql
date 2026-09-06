-- =============================================================================
-- V8  approval_record 的可查询性
--
-- 背景：轨道 C3 让 approval_record 第一次真正被写入（PollingApprovalGate）。
-- 写进去之后暴露出 V2 的两个问题——都不是「表设计错了」，
-- 而是「按当时的用法想的，现在用法变了」。
-- =============================================================================

-- ── 1. decision 的取值必须包含 PENDING ──────────────────────────────────────
--
-- V2 的列注释写的是「GRANTED / REJECTED / TIMED_OUT」，那是按「审批已结束」想的。
-- 但 ApprovalGate 的契约要求**在等待之前**就把记录写下来，
-- 否则无法回答「现在有哪些操作正卡着等人批」。
--
-- 少一个 PENDING 的代价不是「少一种状态」，而是
-- **卡住的审批在数据库里不可见**——运维只能靠告警还在烧来倒推。
-- 所以这里只改注释（列本身是 VARCHAR，不需要改类型），
-- 并把「不能为空」的理由写进注释，免得后来人以为可以留空。
COMMENT ON COLUMN approval_record.decision IS
  'PENDING=已提交等待审批（此时 approver 与 decided_at 必须为空）|'
  'GRANTED=已批准 | REJECTED=已拒绝（两者都必须有 approver，'
  '这是责任归属的唯一凭据）| '
  'TIMED_OUT=超时（approver 必须为空：没有人做过这个决定，'
  '填人名等于伪造责任归属）';

-- ── 2. args_snapshot 的脱敏要求从「建议」改成「否则审批无效」 ────────────────
--
-- V2 已经写了「必须是夹紧后且脱敏后的值」，这里把后果说清楚：
-- 不是「不够好」，是这次审批本身不成立。
-- 审批人看到密钥不是「多看到一点」——他批的是一个他没看懂的操作。
COMMENT ON COLUMN approval_record.args_snapshot IS
  '必须是**夹紧后且脱敏后**的参数 JSON；否则这次审批无效'
  '（审批人批的是一个他没看懂的操作）。由 ArgMasker 保证';

-- ── 3. 待审批查询的部分索引 ─────────────────────────────────────────────────
--
-- PollingApprovalGate 每 pollInterval 轮询一次 pending()，
-- 超时扫描走 pendingRequestedBefore()。这两个查询的条件都是 decision='PENDING'。
--
-- 这张表**永久保留**（它是责任归属的唯一凭据，DDL 注释写明 永久），
-- 所以行数只增不减，而 PENDING 行在任何时刻都只有个位数到几十条。
-- 用部分索引而不是普通索引：
-- 索引里只有当前待审批的那几行，历史行完全不占索引空间，
-- 而查询恰好只关心这几行。
CREATE INDEX IF NOT EXISTS idx_approval_pending
    ON approval_record (requested_at, id)
    WHERE decision = 'PENDING';

-- ── 4. trace_id 索引：把一次事故相关的所有审批拉出来 ─────────────────────────
--
-- V2 只在 agent_run 与 tool_audit_log 上建了 trace_id 索引。
-- 审批记录现在也落库了，而复盘一次事故时
-- 「这次操作有没有经过审批、谁批的」是必问的一题。
CREATE INDEX IF NOT EXISTS idx_approval_trace
    ON approval_record (trace_id);

-- ── 5. 申请人索引：查某个人/某个 Agent 发起过哪些高危操作 ────────────────────
CREATE INDEX IF NOT EXISTS idx_approval_requester
    ON approval_record (requester, requested_at);
