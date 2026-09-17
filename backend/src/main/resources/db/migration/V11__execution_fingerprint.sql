ALTER TABLE agent_execution ADD COLUMN runtime_fingerprint VARCHAR(64) NULL;
ALTER TABLE agent_execution ADD COLUMN instructions_hash VARCHAR(64) NULL;

CREATE INDEX idx_agent_execution_fingerprint ON agent_execution (runtime_fingerprint);
