-- 사용자가 한 대화에서 커넥터 도구 하나를 계속 허용한 기록이다. 거두면 줄을 지우지 않고 시각을 적는다.
CREATE TABLE connector_conversation_grant (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    connector_id VARCHAR(64) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    action_id BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_connector_conversation_grant_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_connector_conversation_grant_conversation FOREIGN KEY (conversation_id) REFERENCES conversation(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_connector_conversation_grant_lookup
    ON connector_conversation_grant (conversation_id, agent_id, connector_id, tool_name);
CREATE INDEX idx_connector_conversation_grant_connector ON connector_conversation_grant (user_id, connector_id);
