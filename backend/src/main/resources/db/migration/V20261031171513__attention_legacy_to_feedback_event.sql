-- 옛 지금 화면 표(attention_control, attention_event)의 살아 있는 값을 반응 기록(decision_feedback_event)으로 복사한다(ADR-20261101 / proactive-suggestion 의 순서 5).
-- 옛 표는 지우지 않는다. 읽고 쓰는 곳만 반응 기록으로 옮겼고, 표를 지우는 일은 따로 한다.
-- 같은 파일을 다시 돌려도 줄이 늘지 않도록 event_key 로 (user_id, event_key) 유일 제약에 걸리는 줄을 건너뛴다.

-- 숨김과 미루기의 현재값 한 줄은 처음 제어 사건 하나가 된다. 사용자가 숨기거나 미룬 항목은 옮긴 뒤에도 그대로 숨고 미뤄진다.
-- 숨김은 숨긴 상태의 지문을 subject_version 에, 미루기는 끝나는 시각을 until_at 에 둔다. 미루기가 걸린 때의 상태는 옛 표에 없어 비운다.
INSERT INTO decision_feedback_event (
    user_id, subject_type, subject_key, event_type, actor, subject_version, reason_code,
    card_key, until_at, event_key, contract_version, occurred_at)
SELECT
    c.user_id,
    'ATTENTION_ITEM',
    c.item_key,
    CASE c.action WHEN 'HIDE' THEN 'DISMISSED' ELSE 'POSTPONED' END,
    'USER',
    CASE c.action WHEN 'HIDE' THEN c.state_key ELSE NULL END,
    CASE c.action WHEN 'HIDE' THEN 'ATTENTION_HIDE' ELSE 'ATTENTION_SNOOZE' END,
    c.card_key,
    CASE c.action WHEN 'SNOOZE' THEN c.until_at ELSE NULL END,
    CONCAT('legacy:attention_control:', c.id),
    1,
    c.updated_at
FROM attention_control c
WHERE c.action IN ('HIDE', 'SNOOZE')
  AND NOT EXISTS (
      SELECT 1 FROM decision_feedback_event e
      WHERE e.user_id = c.user_id AND e.event_key = CONCAT('legacy:attention_control:', c.id));

-- 노출, 열기, 동작 사건을 복사한다. 판정(attention 칸)과 신선도는 옮기지 않고, 지표가 묶는 데 쓰는 계기(trigger_type)만 reason_code 에 둔다.
-- 숨김과 미루기 사건은 옮기지 않는다. 걸려 있는 것은 위에서 제어 사건이 되었다.
INSERT INTO decision_feedback_event (
    user_id, subject_type, subject_key, event_type, actor, subject_version, reason_code,
    card_key, until_at, event_key, contract_version, occurred_at)
SELECT
    v.user_id,
    'ATTENTION_ITEM',
    v.item_key,
    v.event_type,
    CASE v.event_type WHEN 'SHOWN' THEN 'SYSTEM' ELSE 'USER' END,
    v.state_key,
    v.trigger_type,
    NULL,
    NULL,
    CONCAT('legacy:attention_event:', v.id),
    1,
    v.created_at
FROM attention_event v
WHERE v.event_type IN ('SHOWN', 'OPENED', 'ACTED')
  AND NOT EXISTS (
      SELECT 1 FROM decision_feedback_event e
      WHERE e.user_id = v.user_id AND e.event_key = CONCAT('legacy:attention_event:', v.id));
