CREATE TABLE agent (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    hermes_profile VARCHAR(64) NOT NULL,
    api_base_url VARCHAR(255) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    model_synced_at DATETIME(6) NULL,
    cost_mode VARCHAR(20) NOT NULL,
    credential_scope VARCHAR(20) NOT NULL,
    visibility VARCHAR(20) NOT NULL,
    owner_user_id BIGINT NULL,
    enabled BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_code (code),
    UNIQUE KEY uk_agent_hermes_profile (hermes_profile)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO agent (
    code, name, hermes_profile, api_base_url, provider, model, model_synced_at,
    cost_mode, credential_scope, visibility, owner_user_id, enabled, created_at
)
SELECT
    profile_name, profile_name, profile_name, api_base_url, provider, model, NULL,
    cost_mode, credential_scope, 'PRIVATE', user_id, status = 'ACTIVE', created_at
FROM hermes_profile_binding;

ALTER TABLE conversation
    ADD COLUMN agent_id BIGINT NULL AFTER workspace_id;

ALTER TABLE agent_execution
    ADD COLUMN agent_id BIGINT NULL AFTER workspace_id;

UPDATE conversation
SET agent_id = (
    SELECT agent.id
    FROM agent
    INNER JOIN hermes_profile_binding ON hermes_profile_binding.profile_name = agent.hermes_profile
    WHERE hermes_profile_binding.user_id = conversation.user_id
);

UPDATE agent_execution
SET agent_id = (
    SELECT agent.id
    FROM agent
    WHERE agent.hermes_profile = agent_execution.profile_name
);

DROP TABLE hermes_profile_binding;
