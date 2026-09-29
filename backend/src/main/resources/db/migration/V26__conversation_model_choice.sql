-- 대화마다 고른 provider, 모델, reasoning effort 를 둔다. 셋 다 비면 그 profile 의 기본값으로 돈다.
-- 실행 기록에는 그 실행에 요청한 effort 를 둔다. 이 칸이 생기기 전의 실행은 비어 있다.

ALTER TABLE conversation ADD COLUMN model_provider VARCHAR(64) NULL;
ALTER TABLE conversation ADD COLUMN model VARCHAR(128) NULL;
ALTER TABLE conversation ADD COLUMN reasoning_effort VARCHAR(16) NULL;

ALTER TABLE agent_execution ADD COLUMN reasoning_effort VARCHAR(16) NULL;
