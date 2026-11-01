-- 지금 화면의 숨김, 미루기, 복원을 반응 기록(decision_feedback_event)에서 읽으려고 색인을 더한다(ADR-20261101 / proactive-suggestion 의 순서 5).
-- 카드마다 항목마다 마지막 제어 사건을 (user_id, card_key, subject_key, id) 순서로 읽는다. 칸은 더하지 않는다.
CREATE INDEX idx_decision_feedback_event_control ON decision_feedback_event (user_id, card_key, subject_key, id);
