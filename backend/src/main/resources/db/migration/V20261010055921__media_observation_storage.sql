-- 불변 관찰 revision과 수락된 모든 요청의 alias는 첨부 수명 안에서 함께 지운다.
CREATE TABLE media_observation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    attachment_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    source_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    provenance_kind VARCHAR(32) NOT NULL,
    schema_version INT NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    origin_execution_id BIGINT NULL,
    provider VARCHAR(128) NULL,
    provider_version VARCHAR(128) NULL,
    model VARCHAR(128) NULL,
    model_version VARCHAR(128) NULL,
    body LONGTEXT NULL,
    body_key_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_media_observation_revision UNIQUE (attachment_id, revision),
    CONSTRAINT uk_media_observation_attachment_id UNIQUE (attachment_id, id),
    CONSTRAINT fk_media_observation_attachment FOREIGN KEY (attachment_id)
        REFERENCES chat_attachment(id) ON DELETE CASCADE,
    KEY ix_media_observation_conversation (conversation_id, attachment_id, revision)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE media_observation_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    attachment_id BIGINT NOT NULL,
    request_id CHAR(36) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    observation_id BIGINT NOT NULL,
    CONSTRAINT uk_media_observation_request UNIQUE (attachment_id, request_id),
    CONSTRAINT fk_media_observation_request FOREIGN KEY (attachment_id, observation_id)
        REFERENCES media_observation(attachment_id, id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
