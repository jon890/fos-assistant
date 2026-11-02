-- 반응 기록의 옛 행동 정책 판정 연결을 끊는다(ADR-20261101 / proactive-suggestion 의 옮기는 순서 7).
-- 판정 테이블을 지우기 전에 외래 키와 칸을 먼저 지운다. 이미 쌓인 사건 줄은 지우지 않는다.
-- 문제 후보에 붙었던 판정 번호는 판정 테이블과 함께 의미를 잃으므로 옮기지 않는다.
-- subject_type 이 AUTONOMY_DECISION 인 옛 줄은 그대로 둔다. 저장된 enum 이름이라 읽는 쪽이 계속 알아야 한다.
ALTER TABLE decision_feedback_event DROP FOREIGN KEY fk_decision_feedback_event_autonomy;
ALTER TABLE decision_feedback_event DROP COLUMN autonomy_decision_id;
