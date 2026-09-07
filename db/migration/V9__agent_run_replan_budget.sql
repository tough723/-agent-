-- =============================================================================
-- V9  agent_run 的第四个预算：重规划次数
--
-- 背景：轨道 D3-d 要让 M3 的 Plan-Execute-Replan 循环真正能终止。
--
-- 修复方案.md F3.1 明确要求四个**独立**计数器：
--     步数预算 / 重规划次数 / token 预算 / 成本预算
-- 并特别注明这是为了修掉原方案 `i < 20` 的语义混乱——
-- 那一个循环变量同时承担了「走了多少步」和「重规划了几次」两件事，
-- 于是「一次很长的排查」和「一次反复改主意的排查」在账面上长得一样，
-- 而它们的处置方式完全不同：前者该调步数预算，后者该查 prompt。
--
-- V2 建表时只落了前三项，因为当时 Replanner 还不存在。
-- 现在它要落地了，而**没有这一列，「重规划预算耗尽」这个终止条件
-- 根本无法实现**——循环可以无限改主意，每一步都合法。
-- =============================================================================

-- ── 1. 两个新列 ───────────────────────────────────────────────────────────────
--
-- DEFAULT 0 而不是 DEFAULT 3：**0 是一个有意义的取值**，
-- 它表示「这次排查不允许重规划」。这与前三项预算刻意不同——
-- 步数/token/成本预算为 0 意味着「一步都走不了」，那是配置错误；
-- 而重规划预算为 0 意味着「按最初计划一路走到底」，那是一种合法的策略。
-- 所以 AgentRun 里对它的校验是 >= 0，不是 > 0。
ALTER TABLE agent_run
    ADD COLUMN IF NOT EXISTS budget_replans INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS used_replans  INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN agent_run.budget_replans IS
  '本次排查允许的重规划次数上限；0 表示不允许重规划（合法策略，不是配置错误——'
  '与前三项预算必须为正刻意不同）';
COMMENT ON COLUMN agent_run.used_replans   IS
  '已重规划次数。与 used_steps 刻意分开：一次很长的排查和一次反复改主意的排查'
  '处置方式不同（前者调步数预算，后者查 prompt），合并计数会让两者在账面上无法区分';

-- ── 2. 护栏落到数据库 ─────────────────────────────────────────────────────────
--
-- AgentRun 的紧凑构造器已经校验了这条不变量，但**应用层校验挡不住
-- 绕过应用层的写入**（手工 SQL、数据修复脚本、别的语言的客户端）。
-- 预算护栏一旦被越过，「重规划预算耗尽」这个终止条件就失效了，
-- 而这个失效是静默的：循环看起来每一步都合法。
--
-- 这与 V2 给 approval_record 加 chk_approval_not_self 是同一条原则：
-- 责任/预算这类不变量必须有物理保证，不能只靠应用层自觉。
ALTER TABLE agent_run
    ADD CONSTRAINT chk_agent_run_replan_budget
    CHECK (budget_replans >= 0 AND used_replans >= 0 AND used_replans <= budget_replans);

-- ── 3. 让「反复改主意」的排查可查 ────────────────────────────────────────────
--
-- 复盘时「哪些排查在反复重规划」是判断 prompt 质量的第一手证据。
-- 部分索引：绝大多数排查 used_replans = 0，把它们排除在索引外。
CREATE INDEX IF NOT EXISTS idx_agent_run_replans
    ON agent_run (used_replans, created_at DESC)
    WHERE used_replans > 0;
