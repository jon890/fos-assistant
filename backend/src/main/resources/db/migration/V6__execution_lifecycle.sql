ALTER TABLE agent_execution ADD COLUMN parent_execution_id BIGINT NULL;
ALTER TABLE agent_execution ADD COLUMN root_execution_id BIGINT NULL;
ALTER TABLE agent_execution ADD COLUMN context_chars BIGINT NULL;
ALTER TABLE agent_execution ADD COLUMN actual_cost_micros BIGINT NULL;
ALTER TABLE agent_execution MODIFY COLUMN finished_at DATETIME(6) NULL;
ALTER TABLE agent_execution MODIFY COLUMN latency_ms BIGINT NULL;

CREATE INDEX idx_agent_execution_root ON agent_execution (root_execution_id);
CREATE INDEX idx_agent_execution_parent ON agent_execution (parent_execution_id);
CREATE INDEX idx_agent_execution_status ON agent_execution (status);
