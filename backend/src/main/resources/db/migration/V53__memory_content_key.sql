-- 민감 본문을 암호화한 key 의 id 다(ADR-055). 비어 있으면 content 는 평문이다.
-- 지운 항목의 본문도 판에 남으므로 두 표에 함께 둔다.
ALTER TABLE memory ADD COLUMN content_key_id VARCHAR(32) NULL;
ALTER TABLE memory_revision ADD COLUMN content_key_id VARCHAR(32) NULL;
