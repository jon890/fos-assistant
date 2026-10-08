-- 메시지 본문을 암호화한 데이터 key(ADR-20261008 / data-encryption). 비어 있으면 content 는 평문이다.
-- 외래 키를 두지 않는다. 데이터 key 를 지워 본문을 읽지 못하게 하는 길을 막지 않는다.
ALTER TABLE chat_message ADD COLUMN content_key_id BIGINT NULL;
