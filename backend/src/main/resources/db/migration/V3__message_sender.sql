ALTER TABLE chat_message
    ADD COLUMN sender_user_id BIGINT NULL AFTER content;

UPDATE chat_message
SET sender_user_id = (
    SELECT conversation.user_id
    FROM conversation
    WHERE conversation.id = chat_message.conversation_id
)
WHERE role <> 'ASSISTANT';
