CREATE TABLE subagent_usage_job (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    execution_id BIGINT NOT NULL,
    child_session_id VARCHAR(128) NOT NULL,
    parent_session_id VARCHAR(128) NULL,
    profile_name VARCHAR(64) NOT NULL,
    api_base_url VARCHAR(512) NOT NULL,
    status VARCHAR(16) NOT NULL,
    unconfirmed_reason VARCHAR(32) NULL,
    created_at DATETIME(6) NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    backoff_attempts INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_subagent_usage_child (execution_id, child_session_id)
);

CREATE INDEX ix_subagent_usage_due ON subagent_usage_job (status, next_attempt_at);

ALTER TABLE execution_event ADD COLUMN completed_child_session_id VARCHAR(128) NULL;

-- 기존 중복 사건을 지우지 않고 마지막 한 줄만 자연키로 잇는다.
UPDATE execution_event
SET completed_child_session_id = hermes_session_id
WHERE id IN (
    SELECT latest.id FROM (
        SELECT MAX(id) AS id FROM execution_event
        WHERE event_type = 'SUBAGENT_COMPLETED' AND hermes_session_id IS NOT NULL
        GROUP BY execution_id, hermes_session_id
    ) latest
);

CREATE UNIQUE INDEX uk_execution_completed_child ON execution_event (execution_id, completed_child_session_id);
