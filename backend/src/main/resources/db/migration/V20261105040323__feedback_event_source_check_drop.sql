-- 반응 기록의 점검 참조 칸을 지운다(ADR-20261007 / decision-feedback, docs/features/proactive/design.md 의 DECISION10).
-- 읽는 곳이 없어 2026-11-05 에 쓰기를 끊었다. 살펴보기와는 origin_execution_id 가 살펴보기의 root_execution_id 와 같은지로 잇는다.
-- 이미 쌓인 사건 줄은 지우지 않고 칸만 지운다. 외래 키를 먼저 지운 뒤 칸을 지운다.
ALTER TABLE decision_feedback_event DROP FOREIGN KEY fk_decision_feedback_event_check;
ALTER TABLE decision_feedback_event DROP COLUMN source_check_id;
