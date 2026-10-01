-- turn 이 도는 동안 사용자가 보낸 메시지를 보내기 전까지 둔다(ADR-048).
CREATE TABLE chat_pending_message (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    content LONGTEXT NOT NULL,
    held BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL
);

CREATE INDEX idx_chat_pending_message_conversation ON chat_pending_message (conversation_id, id);
