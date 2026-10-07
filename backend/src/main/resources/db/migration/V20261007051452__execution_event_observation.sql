ALTER TABLE agent_execution
    ADD COLUMN event_observation VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN';
