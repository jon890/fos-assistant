ALTER TABLE chat_message ADD COLUMN replaces_message_id BIGINT NULL;
ALTER TABLE chat_message ADD CONSTRAINT fk_chat_message_replaces
    FOREIGN KEY (replaces_message_id) REFERENCES chat_message (id);
