-- 커넥터 도구 호출 하나의 판정을 한 줄씩 남긴다(ADR-049). 승인이 필요했던 호출의 승인 줄도 이 표가 갖는다(ADR-050).
-- 외래 키는 사용자와 에이전트에만 둔다. 실행과 대화는 지워져도 이 줄을 남긴다.
CREATE TABLE connector_action (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id BINARY(16) NOT NULL,
    user_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    connector_id VARCHAR(64) NOT NULL,
    tool_name VARCHAR(128) NULL,
    hermes_tool VARCHAR(128) NOT NULL,
    risk VARCHAR(16) NULL,
    approval_mode VARCHAR(16) NULL,
    decision VARCHAR(20) NOT NULL,
    deny_reason VARCHAR(40) NULL,
    passed BOOLEAN NOT NULL,
    status VARCHAR(20) NULL,
    origin_execution_id BIGINT NOT NULL,
    conversation_id BIGINT NULL,
    dedupe_key VARCHAR(64) NOT NULL,
    args_json MEDIUMTEXT NULL,
    args_sha256 VARCHAR(64) NOT NULL,
    expires_at DATETIME(6) NULL,
    decided_at DATETIME(6) NULL,
    executed_at DATETIME(6) NULL,
    result_text MEDIUMTEXT NULL,
    error_code VARCHAR(64) NULL,
    result_delivered_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_connector_action_public_id (public_id),
    UNIQUE KEY uk_connector_action_dedupe_key (dedupe_key),
    CONSTRAINT fk_connector_action_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_connector_action_agent FOREIGN KEY (agent_id) REFERENCES agent(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_connector_action_conversation_status ON connector_action (conversation_id, status);
CREATE INDEX idx_connector_action_user_created ON connector_action (user_id, created_at);
