-- 파일 삭제의 완료와 접근 차단 요청을 구분한다.
ALTER TABLE chat_attachment ADD COLUMN deletion_requested_at DATETIME(6) NULL;
CREATE INDEX idx_attachment_deletion_request ON chat_attachment (deleted_at, deletion_requested_at, id);
