CREATE TABLE content_draft (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    public_id CHAR(36) NOT NULL UNIQUE,
    conversation_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    current_revision BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_content_draft_conversation FOREIGN KEY (conversation_id) REFERENCES conversation(id),
    CONSTRAINT ck_content_draft_revision CHECK (current_revision BETWEEN 1 AND 9007199254740991)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
CREATE INDEX idx_content_draft_conversation ON content_draft(conversation_id, created_at, id);
CREATE INDEX idx_content_draft_expiry ON content_draft(expires_at, id);

CREATE TABLE content_draft_revision (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    draft_id BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    schema_version INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    body LONGTEXT NULL,
    body_key_id BIGINT NULL,
    CONSTRAINT uq_content_draft_revision UNIQUE (draft_id, revision),
    CONSTRAINT fk_content_draft_revision FOREIGN KEY (draft_id) REFERENCES content_draft(id) ON DELETE CASCADE,
    CONSTRAINT ck_content_draft_revision_number CHECK (revision BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_content_draft_schema CHECK (schema_version = 1),
    CONSTRAINT ck_content_draft_body CHECK (
        CASE
            WHEN status = 'VALID' THEN body IS NOT NULL AND body_key_id IS NOT NULL
            WHEN status = 'STALE' THEN body IS NULL AND body_key_id IS NULL
            WHEN status = 'EXPIRED' THEN body IS NULL AND body_key_id IS NULL
            ELSE FALSE
        END = TRUE
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE content_draft_asset (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    revision_id BIGINT NOT NULL,
    position INT NOT NULL,
    attachment_id BIGINT NOT NULL,
    source_fingerprint CHAR(64) NOT NULL,
    observation_id BIGINT NULL,
    observation_revision BIGINT NULL,
    CONSTRAINT uq_content_draft_asset_position UNIQUE (revision_id, position),
    CONSTRAINT fk_content_draft_asset_revision FOREIGN KEY (revision_id) REFERENCES content_draft_revision(id) ON DELETE CASCADE,
    CONSTRAINT fk_content_draft_attachment FOREIGN KEY (attachment_id) REFERENCES chat_attachment(id),
    CONSTRAINT ck_content_draft_asset_position CHECK (position BETWEEN 0 AND 499),
    CONSTRAINT ck_content_draft_observation CHECK (
        (observation_id IS NULL AND observation_revision IS NULL)
        OR (observation_id IS NOT NULL AND observation_id > 0 AND observation_revision IS NOT NULL AND observation_revision BETWEEN 1 AND 9007199254740991)
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
CREATE INDEX idx_content_draft_attachment ON content_draft_asset(attachment_id, revision_id);
CREATE INDEX idx_content_draft_observation ON content_draft_asset(observation_id);
