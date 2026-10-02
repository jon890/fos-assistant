-- V56 이 더한 사용량 칸을 지난 자식에게도 채우게 한다(ADR-062). DML 만 둔다.
--
-- 서로 다른 표의 문자열 칸은 CAST(... AS BINARY) 로 바꿔 바이트로 비교한다.
-- 운영 MySQL 은 표마다 정렬 규칙이 다르다. 서버 기본값은 utf8mb4_unicode_ci 인데
-- `DEFAULT CHARSET = utf8mb4` 를 적은 표는 utf8mb4_0900_ai_ci 가 된다.
-- 두 정렬 규칙의 칸을 그대로 비교하면 오류 1267(Illegal mix of collations)로 실패한다.
-- subagent_usage_job.profile_name 과 agent_execution.profile_name 이 실제로 그랬다.
-- `COLLATE` 는 H2 가 받지 않아 쓰지 않는다. 마이그레이션 검사는 H2 에서도 이 파일을 돌린다.
-- 바이트 비교는 대소문자를 구분한다. profile 이름과 session 번호는 식별자라 그쪽이 맞다.

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
        WHEN CAST(agent.hermes_profile AS BINARY) <> CAST(parent.profile_name AS BINARY) THEN 'EXPIRED'
        ELSE 'WAITING'
    END,
    CASE
        WHEN agent.id IS NULL OR agent.deleted_at IS NOT NULL THEN 'AGENT_MISSING'
        WHEN CAST(agent.hermes_profile AS BINARY) <> CAST(parent.profile_name AS BINARY) THEN 'PROFILE_CHANGED'
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
-- 두 쪽 모두 execution_event.hermes_session_id 에서 온 값이라 정렬 규칙이 같다.
) child ON child.execution_id = owner.execution_id AND child.child_session_id = owner.child_session_id
JOIN agent_execution parent ON parent.id = owner.execution_id
LEFT JOIN agent ON agent.id = parent.agent_id
WHERE NOT EXISTS (
    SELECT 1 FROM subagent_usage_job job
    WHERE CAST(job.profile_name AS BINARY) = CAST(parent.profile_name AS BINARY)
      AND CAST(job.child_session_id AS BINARY) = CAST(child.child_session_id AS BINARY)
);
