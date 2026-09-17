CREATE TABLE workspace (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    source_path VARCHAR(255) NOT NULL,
    visibility VARCHAR(20) NOT NULL,
    owner_user_id BIGINT NULL,
    enabled BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_workspace_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

ALTER TABLE conversation
    ADD COLUMN workspace_id BIGINT NULL AFTER user_id;

ALTER TABLE agent_execution
    ADD COLUMN workspace_id BIGINT NULL AFTER conversation_id;
