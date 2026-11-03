-- 모든 기억의 제목과 본문을 주인의 데이터 key 로 암호화한다(ADR-20261103 / memory-encryption).
-- 그룹 기억의 데이터 key 를 user_data_key 의 줄로 둔다. 새 테이블은 만들지 않는다.
-- data_key_id 에는 외래 키를 두지 않는다. 데이터 key 를 지워 본문을 읽지 못하게 하는 길을 막지 않는다.
-- 이미 있는 줄은 기동 작업이 데이터 key 로 다시 쓴다. key 가 DB 밖에 있어 SQL 로 옮기지 않는다.
ALTER TABLE user_data_key MODIFY COLUMN user_id BIGINT NULL COMMENT '데이터 key를 소유한 사용자 식별자이며 사용자마다 하나다. 그룹 key 줄이면 NULL이다.';
ALTER TABLE user_data_key ADD COLUMN group_id BIGINT NULL COMMENT '데이터 key를 소유한 그룹 식별자이며 그룹마다 하나다. 사용자 key 줄이면 NULL이다.';
ALTER TABLE user_data_key ADD CONSTRAINT uk_user_data_key_group UNIQUE (group_id);
ALTER TABLE user_data_key ADD CONSTRAINT ck_user_data_key_owner CHECK ((user_id IS NULL AND group_id IS NOT NULL) OR (user_id IS NOT NULL AND group_id IS NULL));

ALTER TABLE memory MODIFY COLUMN title VARCHAR(1200) NOT NULL COMMENT 'Memory 또는 문서의 제목. data_key_id 가 있으면 암호문';
ALTER TABLE memory ADD COLUMN data_key_id BIGINT NULL COMMENT '제목과 본문을 암호화한 user_data_key.id. NULL 이면 평문이거나 content_key_id 의 옛 key 로 암호화한 줄';
ALTER TABLE memory_revision MODIFY COLUMN title VARCHAR(1200) NOT NULL COMMENT '변경 전 제목. data_key_id 가 있으면 암호문';
ALTER TABLE memory_revision ADD COLUMN data_key_id BIGINT NULL COMMENT '제목과 본문을 암호화한 user_data_key.id. NULL 이면 평문이거나 content_key_id 의 옛 key 로 암호화한 줄';
