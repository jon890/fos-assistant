-- 에이전트와 연결을 다대다로 잇는 바인딩 표를 만든다. 한 연결을 여러 에이전트에, 한 에이전트에 여러 연결을 붙인다(ADR-083).
-- 연결에 보관 파일 상태 칸을 더하고, 에이전트 없이 연결을 만들 수 있게 agent_id 를 비워도 되게 한다. 유일 제약과 FK 는 남긴다.
CREATE TABLE agent_connector_binding (
    id BIGINT NOT NULL AUTO_INCREMENT,
    agent_id BIGINT NOT NULL,
    connection_id BIGINT NOT NULL,
    mcp_server VARCHAR(64) NULL,
    status VARCHAR(20) NOT NULL,
    restart_required BOOLEAN NOT NULL DEFAULT FALSE,
    restart_required_since DATETIME(6) NULL,
    desired_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    checked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_connector_binding (agent_id, connection_id),
    CONSTRAINT fk_agent_connector_binding_agent FOREIGN KEY (agent_id) REFERENCES agent(id),
    CONSTRAINT fk_agent_connector_binding_connection FOREIGN KEY (connection_id) REFERENCES connector_connection(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_agent_connector_binding_connection ON agent_connector_binding (connection_id);

ALTER TABLE connector_connection ADD COLUMN vault_stored BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE connector_connection MODIFY COLUMN agent_id BIGINT NULL;
