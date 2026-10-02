-- native 자식 한 명의 사용량과 금액을 재조회 작업 줄에 적는다(ADR-062).
-- 칸만 더한다. 지난 줄을 다시 넣는 DML 은 V57 이 갖는다.
-- MySQL 의 DDL 은 되돌려지지 않아, 한 파일에 섞으면 DML 이 실패했을 때 칸만 더해진 채 남는다.
ALTER TABLE subagent_usage_job ADD COLUMN provider VARCHAR(64) NULL;
ALTER TABLE subagent_usage_job ADD COLUMN model VARCHAR(128) NULL;
ALTER TABLE subagent_usage_job ADD COLUMN input_tokens BIGINT NULL;
ALTER TABLE subagent_usage_job ADD COLUMN cache_read_tokens BIGINT NULL;
ALTER TABLE subagent_usage_job ADD COLUMN cache_write_tokens BIGINT NULL;
ALTER TABLE subagent_usage_job ADD COLUMN output_tokens BIGINT NULL;
ALTER TABLE subagent_usage_job ADD COLUMN estimated_cost_micros BIGINT NULL;
ALTER TABLE subagent_usage_job ADD COLUMN actual_cost_micros BIGINT NULL;
ALTER TABLE subagent_usage_job ADD COLUMN cost_currency CHAR(3) NULL;
ALTER TABLE subagent_usage_job ADD COLUMN pricing_version VARCHAR(32) NULL;
ALTER TABLE subagent_usage_job ADD COLUMN recorded_at DATETIME(6) NULL;
