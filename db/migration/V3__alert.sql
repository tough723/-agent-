-- =====================================================================
-- 告警接入与聚合（对应模块 oncall-alert，规划中）
--
-- 关键：聚合率是这个系统的第一成本杠杆，不是可靠性优化。
-- 聚合率从 15% 恶化到 40%，月 LLM 成本涨 2.7 倍——
-- 比模型选型调优（最多 3 倍但牺牲规划质量）的影响更大且无副作用。
-- 所以 event_count 必须能算出聚合率并作为一级监控指标：
--     aggregation_ratio = 1 - (事件数 / 原始告警数)
--
-- raw_payload 保留原文：聚合规则会调整，需要能重放历史告警验证新规则。
--
-- 大表按时间分区，清理用 DROP PARTITION 而不是 DELETE
-- （DELETE 会产生大量死元组并触发 VACUUM 压力）。
--
-- 方言：PostgreSQL 10+
-- =====================================================================

CREATE TABLE IF NOT EXISTS alert_group (
    id             VARCHAR(64)   NOT NULL,
    fingerprint    VARCHAR(128)  NOT NULL,
    service        VARCHAR(191),
    severity       VARCHAR(32)   NOT NULL,
    status         VARCHAR(32)   NOT NULL,
    event_count    INT           NOT NULL DEFAULT 1,
    first_seen_at  TIMESTAMP     NOT NULL,
    last_seen_at   TIMESTAMP     NOT NULL,
    run_id         VARCHAR(64),
    PRIMARY KEY (id, first_seen_at)
) PARTITION BY RANGE (first_seen_at);

COMMENT ON TABLE  alert_group             IS '告警聚合组；按 first_seen_at 月分区';
COMMENT ON COLUMN alert_group.status      IS 'OPEN / ACKED / RESOLVED / SUPPRESSED';
COMMENT ON COLUMN alert_group.event_count IS '聚合率的数据来源，一级监控指标';

CREATE INDEX IF NOT EXISTS idx_alert_group_open ON alert_group (status, last_seen_at DESC);


CREATE TABLE IF NOT EXISTS alert_event (
    id            VARCHAR(64)  NOT NULL,
    group_id      VARCHAR(64)  NOT NULL,
    source        VARCHAR(64)  NOT NULL,
    raw_payload   JSONB        NOT NULL,
    labels        JSONB,
    severity      VARCHAR(32)  NOT NULL,
    fired_at      TIMESTAMP    NOT NULL,
    received_at   TIMESTAMP    NOT NULL,
    PRIMARY KEY (id, received_at)
) PARTITION BY RANGE (received_at);

COMMENT ON TABLE  alert_event             IS '单条原始告警；按 received_at 月分区，保留 30 天';
COMMENT ON COLUMN alert_event.source      IS 'prometheus / zabbix / nightingale';
COMMENT ON COLUMN alert_event.raw_payload IS '保留原文，用于重放验证新聚合规则';

CREATE INDEX IF NOT EXISTS idx_alert_event_group  ON alert_event (group_id, fired_at);
CREATE INDEX IF NOT EXISTS idx_alert_event_labels ON alert_event USING GIN (labels);


-- ---------------------------------------------------------------------
-- 分区维护
--
-- DEFAULT 分区是安全网：月度分区应由定时任务提前创建，
-- 但漏建时数据落进 default 而不是「插入失败丢告警」。
-- 丢一条告警比多一次 VACUUM 严重得多。
--
-- 定时任务应每月创建下两个月的分区，并 DROP 超过 30 天的分区。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS alert_group_default   PARTITION OF alert_group   DEFAULT;
CREATE TABLE IF NOT EXISTS alert_event_default   PARTITION OF alert_event   DEFAULT;
