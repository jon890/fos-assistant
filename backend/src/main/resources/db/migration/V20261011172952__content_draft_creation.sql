CREATE TABLE content_draft_creation_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    creation_key BINARY(32) NOT NULL,
    identity_key BINARY(32) NOT NULL,
    owner_user_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    profile_name VARCHAR(255) NOT NULL,
    origin_execution_id BIGINT NOT NULL,
    root_execution_id BIGINT NOT NULL,
    root_session_id LONGTEXT NOT NULL,
    request_id CHAR(36) NOT NULL,
    request_hash BINARY(32) NULL,
    draft_public_id CHAR(36) NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_content_creation_key UNIQUE (creation_key),
    CONSTRAINT uq_content_creation_identity UNIQUE (identity_key),
    CONSTRAINT ck_content_creation_status CHECK (
        (status = 'CREATED' AND request_hash IS NOT NULL AND draft_public_id IS NOT NULL)
        OR (status = 'GONE' AND request_hash IS NULL AND draft_public_id IS NULL)
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
CREATE INDEX idx_content_creation_conversation ON content_draft_creation_request (conversation_id, id);
CREATE INDEX idx_content_creation_draft ON content_draft_creation_request (draft_public_id);
CREATE TABLE content_draft_creation_call (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    call_key BINARY(32) NOT NULL,
    identity_key BINARY(32) NOT NULL,
    creation_request_id BIGINT NOT NULL,
    session_id LONGTEXT NOT NULL,
    tool_call_id LONGTEXT NOT NULL,
    CONSTRAINT uq_content_creation_call_key UNIQUE (call_key),
    CONSTRAINT uq_content_creation_call_identity UNIQUE (identity_key),
    CONSTRAINT fk_content_creation_call_request FOREIGN KEY (creation_request_id)
        REFERENCES content_draft_creation_request (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
