-- 실행 추적 PR1: 도구 사건의 호출 번호와 실패한 도구의 오류 종류, 가린 오류 요약을 담는다(docs/features/run-trace/design.md).
-- 새 표를 만들지 않는다. 옛 행은 모두 NULL 이라 지금과 같다.
ALTER TABLE execution_event ADD COLUMN tool_call_id VARCHAR(128) NULL COMMENT '도구 사건의 Hermes tool_call_id. plugin 의 도구 기록과 맞춘다. 없으면 NULL';
ALTER TABLE execution_event ADD COLUMN error_type VARCHAR(64) NULL COMMENT '실패한 도구의 오류 종류. plugin 이 준 값을 64자로 자른 것. 없으면 NULL';
ALTER TABLE execution_event ADD COLUMN error_summary VARCHAR(255) NULL COMMENT '실패한 도구의 오류 글을 가린 뒤 200자로 자른 요약. 대화를 지우면 비운다';
