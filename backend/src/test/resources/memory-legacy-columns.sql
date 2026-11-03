-- 테스트 DB 는 엔티티로 스키마를 만든다. 기억의 민감 구분 칸과 옛 key 칸은 코드가 읽고 쓰지 않아 엔티티가 매핑하지 않으므로 여기서 만든다.
-- 운영 스키마에는 배포를 되돌릴 때 직전 이미지가 읽도록 칸이 남아 있다. 칸을 지우는 PR 이 이 파일을 지운다.
-- 기본값은 Flyway 의 V47__memory_v2.sql, V53__memory_content_key.sql, V20261103054413__memory_sensitivity_defaults.sql 을 따른다.
ALTER TABLE memory ADD COLUMN sensitivity VARCHAR(20) NOT NULL DEFAULT 'NORMAL';
ALTER TABLE memory ADD COLUMN content_key_id VARCHAR(32) NULL;
ALTER TABLE memory_revision ADD COLUMN sensitivity VARCHAR(20) NOT NULL DEFAULT 'NORMAL';
ALTER TABLE memory_revision ADD COLUMN content_key_id VARCHAR(32) NULL;
ALTER TABLE agent_memory_collection ADD COLUMN allow_sensitive BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE agent_memory_collection_change ADD COLUMN allow_sensitive BOOLEAN NOT NULL DEFAULT FALSE;
