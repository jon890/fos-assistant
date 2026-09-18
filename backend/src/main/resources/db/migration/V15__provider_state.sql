-- provider 하나가 막힌 상태를 기억한다. 에이전트마다 두지 않는다.
-- credential 묶음이 provider 마다 하나이고 그 묶음이 막힌 것이다.
CREATE TABLE provider_state (
    provider VARCHAR(64) NOT NULL,
    blocked_until DATETIME(6) NOT NULL,
    blocked_reason VARCHAR(255) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (provider)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 막혀서 다음 모델로 넘어갈 때 만든 실행이 직전 실행을 가리킨다.
-- 자식이 아니라 다시 시도한 것이라 parent_execution_id 를 쓰지 않는다.
ALTER TABLE agent_execution ADD COLUMN retry_of_execution_id BIGINT NULL;
