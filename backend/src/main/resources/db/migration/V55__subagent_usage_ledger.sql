-- native 자식 한 명의 사용량과 금액을 재조회 작업 줄에 적는다(ADR-059).
ALTER TABLE subagent_usage_job ADD COLUMN provider VARCHAR(64) NULL;
ALTER TABLE subagent_usage_job ADD COLUMN model VARCHAR(128) NULL;
ALTER TABLE subagent_usage_job ADD COLUMN input_tokens BIGINT NULL;
ALTER TABLE subagent_usage_job ADD COLUMN cache_read_tokens BIGINT NULL;
ALTER TABLE subagent_usage_job ADD COLUMN cache_write_tokens BIGINT NULL;
ALTER TABLE subagent_usage_job ADD COLUMN output_tokens BIGINT NULL;
ALTER TABLE subagent_usage_job ADD COLUMN estimated_cost_micros BIGINT NULL;
ALTER TABLE subagent_usage_job ADD COLUMN actual_cost_micros BIGINT NULL;
ALTER TABLE subagent_usage_job ADD COLUMN cost_currency CHAR(3) NULL;
ALTER TABLE subagent_usage_job ADD COLUMN pricing_version VARCHAR(32) NULL;
ALTER TABLE subagent_usage_job ADD COLUMN recorded_at DATETIME(6) NULL;

-- 이미 끝난 줄은 사용량을 적지 않고 끝났다. 다시 조회 대기로 넣어 session 에서 사용량을 읽게 한다.
UPDATE subagent_usage_job
SET status = 'WAITING',
    next_attempt_at = created_at,
    expires_at = TIMESTAMPADD(HOUR, 24, CURRENT_TIMESTAMP(6)),
    attempts = 0,
    backoff_attempts = 0
WHERE status = 'DONE';

-- 완료 사건이 먼저 와서 작업 줄 없이 지나간 자식을 넣는다.
-- 같은 profile 의 같은 session 은 실행 번호가 가장 작은 쪽 한 줄만 만든다.
INSERT INTO subagent_usage_job (
    execution_id, child_session_id, parent_session_id, profile_name, api_base_url,
    status, unconfirmed_reason, created_at, next_attempt_at, expires_at, attempts, backoff_attempts
)
SELECT
    parent.id,
    child.child_session_id,
    parent.hermes_session_id,
    parent.profile_name,
    COALESCE(agent.api_base_url, ''),
    CASE
        WHEN agent.id IS NULL OR agent.deleted_at IS NOT NULL THEN 'EXPIRED'
        WHEN agent.hermes_profile <> parent.profile_name THEN 'EXPIRED'
        ELSE 'WAITING'
    END,
    CASE
        WHEN agent.id IS NULL OR agent.deleted_at IS NOT NULL THEN 'AGENT_MISSING'
        WHEN agent.hermes_profile <> parent.profile_name THEN 'PROFILE_CHANGED'
        ELSE NULL
    END,
    child.started_at,
    child.started_at,
    TIMESTAMPADD(HOUR, 24, CURRENT_TIMESTAMP(6)),
    0,
    0
FROM (
    SELECT finished.profile_name AS profile_name,
           started.hermes_session_id AS child_session_id,
           MIN(started.execution_id) AS execution_id
    FROM execution_event started
    JOIN agent_execution finished ON finished.id = started.execution_id
    WHERE started.event_type = 'SUBAGENT_STARTED'
      AND started.hermes_session_id IS NOT NULL
      AND finished.finished_at IS NOT NULL
    GROUP BY finished.profile_name, started.hermes_session_id
) owner
JOIN (
    SELECT started.execution_id AS execution_id,
           started.hermes_session_id AS child_session_id,
           MIN(started.occurred_at) AS started_at
    FROM execution_event started
    WHERE started.event_type = 'SUBAGENT_STARTED'
      AND started.hermes_session_id IS NOT NULL
    GROUP BY started.execution_id, started.hermes_session_id
) child ON child.execution_id = owner.execution_id AND child.child_session_id = owner.child_session_id
JOIN agent_execution parent ON parent.id = owner.execution_id
LEFT JOIN agent ON agent.id = parent.agent_id
WHERE NOT EXISTS (
    SELECT 1 FROM subagent_usage_job job
    WHERE job.profile_name = parent.profile_name AND job.child_session_id = child.child_session_id
);
