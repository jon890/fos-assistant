ALTER TABLE execution_event ADD COLUMN model VARCHAR(128) NULL;
ALTER TABLE execution_event ADD COLUMN input_tokens BIGINT NULL;
ALTER TABLE execution_event ADD COLUMN output_tokens BIGINT NULL;
