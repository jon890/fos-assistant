-- 추천 질문은 에이전트마다 넷까지 순서를 갖는 여러 값이라 agent 의 한 칸에 이어 붙이지 않고 자식 표로 둔다.
-- 순서를 바꾸는 것도 그 에이전트의 줄을 모두 지우고 새로 넣는 것으로 다룬다.
ALTER TABLE agent ADD COLUMN tagline VARCHAR(200) NULL;

CREATE TABLE agent_starter_prompt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    agent_id BIGINT NOT NULL,
    position INT NOT NULL,
    text VARCHAR(300) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_starter_prompt (agent_id, position)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
