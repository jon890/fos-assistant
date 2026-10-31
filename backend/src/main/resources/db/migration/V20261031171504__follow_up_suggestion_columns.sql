-- 먼저 살펴보기의 제안을 할 일(follow_up)의 PROPOSED 줄로 저장하려고 칸을 더한다(ADR-20261101 / proactive-suggestion).
-- 새 테이블을 만들지 않는다. 기존 줄은 모두 비어 있고 사람이 직접 더한 할 일도 비어 있다.
-- detail_key_id 에는 외래 키를 두지 않는다. 데이터 key 를 지워 본문을 읽지 못하게 하는 길을 막지 않는다.
ALTER TABLE follow_up ADD COLUMN detail_json LONGTEXT NULL COMMENT '제안의 글, 근거, 모델이 낸 자유 출력을 사용자 데이터 key 로 암호화한 JSON. detail_key_id 가 비어 있으면 평문. 사람이 직접 더한 할 일은 NULL';
ALTER TABLE follow_up ADD COLUMN detail_key_id BIGINT NULL COMMENT 'detail_json 을 암호화한 user_data_key.id. NULL 이면 detail_json 이 평문이거나 비어 있다';
ALTER TABLE follow_up ADD COLUMN dedupe_key CHAR(64) NULL COMMENT '같은 실행의 같은 결과 항목을 다시 받아도 제안이 하나이게 하는 서버 계산 열쇠. 실행 번호와 항목 식별자의 SHA-256 16진수. NULL 이면 중복 열쇠 없음';
ALTER TABLE follow_up ADD COLUMN expires_at DATETIME(6) NULL COMMENT '받아들이지 않은 제안이 지금 화면에서 빠지는 시각(UTC). NULL 이면 만료 없음. 받아들인 할 일에는 쓰지 않는다';
ALTER TABLE follow_up ADD CONSTRAINT uk_follow_up_user_dedupe UNIQUE (user_id, dedupe_key);

-- 반응 기록에 카드 범위, 미루기 끝, 재전송 합치기 열쇠를 더한다. 기존 줄은 모두 비어 있다.
ALTER TABLE decision_feedback_event ADD COLUMN card_key VARCHAR(40) NULL COMMENT '같은 항목이 다른 카드에 있을 때 숨김과 미루기의 범위를 나누는 카드 열쇠. NULL 이면 카드와 무관한 사건';
ALTER TABLE decision_feedback_event ADD COLUMN until_at DATETIME(6) NULL COMMENT '미루기가 끝나는 시각(UTC). 미루기 사건이 아니면 NULL';
ALTER TABLE decision_feedback_event ADD COLUMN event_key VARCHAR(120) NULL COMMENT '서버가 검증한 요청 키나 노출 키. 같은 사용자의 같은 키는 한 번만 저장해 재전송을 하나로 합친다. NULL 이면 합치지 않음';
ALTER TABLE decision_feedback_event ADD CONSTRAINT uk_decision_feedback_event_user_event_key UNIQUE (user_id, event_key);
