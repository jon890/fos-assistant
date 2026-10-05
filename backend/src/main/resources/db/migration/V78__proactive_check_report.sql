ALTER TABLE proactive_check ADD COLUMN report_json JSON NULL;
ALTER TABLE proactive_check ADD COLUMN tree_input_tokens BIGINT NULL;
ALTER TABLE proactive_check ADD COLUMN tree_cached_input_tokens BIGINT NULL;
ALTER TABLE proactive_check ADD COLUMN tree_output_tokens BIGINT NULL;
ALTER TABLE proactive_check ADD COLUMN skipped_reason VARCHAR(32) NULL;
ALTER TABLE proactive_check ADD COLUMN report_opened_at DATETIME(6) NULL;

CREATE INDEX idx_proactive_check_unopened_report ON proactive_check (user_id, agent_id, report_opened_at);
