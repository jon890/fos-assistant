-- 사용자별 가계부 연결 상태를 저장한다.
ALTER TABLE agent ADD COLUMN connector_managed BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE accountbook_connection (
    user_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    family_uuid CHAR(36) NULL,
    token_prefix VARCHAR(8) NULL,
    restart_required BOOLEAN NOT NULL DEFAULT FALSE,
    desired_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    checked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_accountbook_connection_agent (agent_id),
    CONSTRAINT fk_accountbook_connection_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_accountbook_connection_agent FOREIGN KEY (agent_id) REFERENCES agent(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
