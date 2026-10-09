CREATE TABLE agent_toolset_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    public_id BINARY(16) NOT NULL UNIQUE,
    group_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    requester_user_id BIGINT NOT NULL,
    toolset VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    pending_slot INT NULL,
    requested_at TIMESTAMP(6) NOT NULL,
    decided_at TIMESTAMP(6) NULL,
    decided_by_user_id BIGINT NULL,
    reason VARCHAR(200) NULL,
    CONSTRAINT uq_toolset_request_pending UNIQUE (group_id, agent_id, requester_user_id, toolset, pending_slot),
    CONSTRAINT fk_toolset_request_agent FOREIGN KEY (agent_id) REFERENCES agent(id),
    CONSTRAINT fk_toolset_request_user FOREIGN KEY (requester_user_id) REFERENCES app_user(id),
    CONSTRAINT fk_toolset_request_decider FOREIGN KEY (decided_by_user_id) REFERENCES app_user(id),
    CONSTRAINT ck_toolset_request_pending CHECK (
        (status = 'PENDING' AND pending_slot IS NOT NULL AND pending_slot = 1)
        OR (status <> 'PENDING' AND pending_slot IS NULL)
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
