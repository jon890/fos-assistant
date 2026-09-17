CREATE TABLE memory (
    id BIGINT NOT NULL AUTO_INCREMENT,
    scope VARCHAR(20) NOT NULL,
    owner_user_id BIGINT NULL,
    family_id BIGINT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    always_inject BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL,
    proposed_by_execution_id BIGINT NULL,
    accepted_by_user_id BIGINT NULL,
    accepted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_memory_owner ON memory (owner_user_id, status);
CREATE INDEX idx_memory_family ON memory (family_id, status);
