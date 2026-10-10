-- 금융 실행 내용은 승인 줄과 함께 지우고 연결·바인딩은 당시 번호로만 보존한다.
CREATE TABLE connector_action_execution (
    action_id BIGINT NOT NULL,
    connection_id BIGINT NOT NULL,
    binding_id BIGINT NOT NULL,
    connection_updated_at DATETIME(6) NOT NULL,
    binding_updated_at DATETIME(6) NOT NULL,
    execution_args_json MEDIUMTEXT NOT NULL,
    summary_json MEDIUMTEXT NOT NULL,
    scope_json MEDIUMTEXT NOT NULL,
    content_key_id BIGINT NULL,
    execution_args_sha256 VARCHAR(64) NOT NULL,
    scope_sha256 VARCHAR(64) NOT NULL,
    request_key VARCHAR(64) NOT NULL,
    supersedes_unknown_action_id BIGINT NULL,
    protocol VARCHAR(32) NOT NULL,
    ticket_id BINARY(16) NULL,
    ticket_expires_at DATETIME(6) NULL,
    consumed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (action_id),
    CONSTRAINT fk_connector_action_execution_action
        FOREIGN KEY (action_id) REFERENCES connector_action(id) ON DELETE CASCADE,
    CONSTRAINT uk_connector_action_execution_ticket UNIQUE (ticket_id),
    CONSTRAINT uk_connector_action_execution_supersedes UNIQUE (supersedes_unknown_action_id),
    INDEX idx_connector_action_execution_request (request_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
