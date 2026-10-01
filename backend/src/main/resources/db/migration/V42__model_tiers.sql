-- 단계 정의와 기본값은 그룹과 사용자 범위에 둔다. 기존 대화의 명시 선택은 CUSTOM 으로 보존한다.
CREATE TABLE model_tier_definition (
    id BIGINT NOT NULL AUTO_INCREMENT,
    group_id BIGINT NOT NULL,
    tier VARCHAR(16) NOT NULL,
    provider VARCHAR(64) NULL,
    model VARCHAR(128) NOT NULL,
    reasoning_effort VARCHAR(16) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_tier_definition_group_tier (group_id, tier)
);

ALTER TABLE app_user ADD COLUMN model_default_tier VARCHAR(16) NULL;
CREATE TABLE model_tier_group_setting (
    group_id BIGINT NOT NULL,
    default_tier VARCHAR(16) NULL,
    PRIMARY KEY (group_id)
);

ALTER TABLE conversation ADD COLUMN model_selection_mode VARCHAR(16) NULL;
ALTER TABLE conversation ADD COLUMN model_tier VARCHAR(16) NULL;
UPDATE conversation
   SET model_selection_mode = 'CUSTOM'
 WHERE model_provider IS NOT NULL OR model IS NOT NULL OR reasoning_effort IS NOT NULL;

ALTER TABLE agent_execution ADD COLUMN model_tier VARCHAR(16) NULL;
ALTER TABLE agent_execution ADD COLUMN reasoning_effort_source VARCHAR(20) NULL;
ALTER TABLE agent_execution ADD COLUMN request_received_at DATETIME(6) NULL;
ALTER TABLE agent_execution ADD COLUMN submitted_at DATETIME(6) NULL;
ALTER TABLE agent_execution ADD COLUMN first_delta_at DATETIME(6) NULL;

-- 최초 단계는 provider 를 비워 요청 에이전트 profile 의 catalog 기본 provider 를 사용한다.
INSERT INTO model_tier_definition (group_id, tier, provider, model, reasoning_effort)
SELECT DISTINCT group_id, 'FAST', NULL, 'gpt-6-luna', 'low' FROM app_user;
INSERT INTO model_tier_definition (group_id, tier, provider, model, reasoning_effort)
SELECT DISTINCT group_id, 'BALANCED', NULL, 'gpt-6-luna', 'medium' FROM app_user;
INSERT INTO model_tier_definition (group_id, tier, provider, model, reasoning_effort)
SELECT DISTINCT group_id, 'DEEP', NULL, 'gpt-6.1-sol', 'high' FROM app_user;
