-- 알림을 만든 원인(승인 줄)의 공개 식별자다. 원인이 끝나면 이 번호로 그 알림만 읽음으로 바꾼다.
-- 원인이 지워져도 알림을 남기므로 외래 키를 걸지 않는다. 옛 알림은 비어 있다.
ALTER TABLE notification ADD COLUMN source_public_id BINARY(16) NULL;
CREATE INDEX idx_notification_user_source ON notification (user_id, source_public_id);
