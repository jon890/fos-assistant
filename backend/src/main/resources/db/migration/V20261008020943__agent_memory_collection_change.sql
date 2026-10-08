-- 관리자가 에이전트의 받는 collection 을 붙이고 떼고 민감 허용을 바꾼 기록이다(ADR-20261008 / agent-memory-grants-admin).
CREATE TABLE agent_memory_collection_change (
    id BIGINT NOT NULL AUTO_INCREMENT,
    agent_id BIGINT NOT NULL,
    collection VARCHAR(64) NOT NULL,
    change_type VARCHAR(20) NOT NULL,
    allow_sensitive BOOLEAN NOT NULL,
    changed_by_user_id BIGINT NOT NULL,
    changed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_agent_memory_collection_change_agent (agent_id, id),
    CONSTRAINT fk_agent_memory_collection_change_agent FOREIGN KEY (agent_id) REFERENCES agent(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
