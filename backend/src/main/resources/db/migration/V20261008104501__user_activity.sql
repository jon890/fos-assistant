ALTER TABLE allowed_person ADD COLUMN last_login_at DATETIME(6) NULL;

CREATE INDEX ix_chat_message_sender_role_created_at
    ON chat_message (sender_user_id, role, created_at);
