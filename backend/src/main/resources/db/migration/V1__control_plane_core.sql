CREATE TABLE app_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(320) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    family_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_email (email),
    KEY ix_app_user_family (family_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE hermes_profile_binding (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    profile_name VARCHAR(64) NOT NULL,
    api_base_url VARCHAR(255) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    cost_mode VARCHAR(20) NOT NULL,
    credential_scope VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_binding_user (user_id),
    UNIQUE KEY uk_binding_profile (profile_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE conversation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    hermes_session_id VARCHAR(128) NULL,
    title VARCHAR(200) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_conversation_user_updated (user_id, updated_at DESC)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE chat_message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    content LONGTEXT NOT NULL,
    execution_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_chat_message_conversation (conversation_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE agent_execution (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    profile_name VARCHAR(64) NOT NULL,
    hermes_run_id VARCHAR(128) NULL,
    provider VARCHAR(64) NULL,
    model VARCHAR(128) NULL,
    cost_mode VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_code VARCHAR(64) NULL,
    input_tokens BIGINT NULL,
    cached_input_tokens BIGINT NULL,
    output_tokens BIGINT NULL,
    total_tokens BIGINT NULL,
    latency_ms BIGINT NOT NULL,
    estimated_cost_micros BIGINT NULL,
    cost_currency CHAR(3) NULL,
    pricing_version VARCHAR(32) NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_execution_user_started (user_id, started_at DESC),
    KEY ix_execution_conversation (conversation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
