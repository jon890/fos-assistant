-- 실행 하나에서 스킬 하나가 쓰인 것을 한 행으로 남기는 표를 만든다(ADR-034).
-- 사용자, 에이전트, 대화는 agent_execution 과 이어 얻으므로 여기 다시 적지 않는다.
-- 외래 키는 두지 않는다. 실행 줄은 지우지 않고, 스킬을 지워도 이 행은 이름으로 남는다.
-- 한 실행에서 모델이 같은 스킬을 여러 번 읽어도 한 행이라 (execution_id, skill_name, source) 가 유일하다.
CREATE TABLE execution_skill_use (
    id BIGINT NOT NULL AUTO_INCREMENT,
    execution_id BIGINT NOT NULL,
    skill_name VARCHAR(64) NOT NULL,
    source VARCHAR(20) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_execution_skill_use_execution_skill_source UNIQUE (execution_id, skill_name, source)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_execution_skill_use_skill_name ON execution_skill_use (skill_name);
