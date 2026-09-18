-- 에이전트가 쓸 모델을 순서 있는 목록으로 갖는다. 1 이 1순위다.
-- 칸 이름을 rank 로 두지 않는다. MySQL 8 이 RANK 를 예약어로 갖는다.
CREATE TABLE agent_model_option (
    id BIGINT NOT NULL AUTO_INCREMENT,
    agent_id BIGINT NOT NULL,
    option_rank INT NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_model_option (agent_id, option_rank)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 지금 있는 에이전트의 provider 와 model 을 1순위로 옮긴다.
-- 옮기지 않으면 배포 직후 모든 실행이 쓸 모델을 찾지 못한다.
INSERT INTO agent_model_option (agent_id, option_rank, provider, model, created_at)
SELECT id, 1, provider, model, CURRENT_TIMESTAMP(6)
FROM agent;
