CREATE TABLE execution_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    execution_id BIGINT NOT NULL,
    sequence INT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    tool_name VARCHAR(128) NULL,
    subagent_name VARCHAR(128) NULL,
    hermes_session_id VARCHAR(128) NULL,
    duration_ms BIGINT NULL,
    detail VARCHAR(500) NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_execution_event_seq (execution_id, sequence)
);
