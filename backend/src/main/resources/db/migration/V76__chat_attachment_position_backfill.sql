UPDATE chat_attachment
SET position = (
    SELECT ordered.position
    FROM (
        SELECT id, ROW_NUMBER() OVER (PARTITION BY message_id ORDER BY id) - 1 AS position
        FROM chat_attachment
        WHERE message_id IS NOT NULL
    ) ordered
    WHERE ordered.id = chat_attachment.id
)
WHERE message_id IS NOT NULL;
