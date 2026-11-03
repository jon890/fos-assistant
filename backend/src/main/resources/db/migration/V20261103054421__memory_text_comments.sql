-- 옛 content_key_id 컬럼을 지웠으므로(V20261103054417, V20261103054418) 그 컬럼을 가리키던 컬럼 설명을 지금 뜻으로 다시 적는다.
-- 타입과 NULL 여부는 V20261103024541 의 정의 그대로다. 설명만 바뀐다.
ALTER TABLE memory MODIFY COLUMN content LONGTEXT NOT NULL COMMENT '저장한 본문. data_key_id 가 있으면 그 데이터 key 의 암호문이고, 비어 있으면 평문. 암호문은 평문보다 약 4/3 배 길어 TEXT 를 넘을 수 있다';
ALTER TABLE memory MODIFY COLUMN data_key_id BIGINT NULL COMMENT '제목과 본문을 암호화한 user_data_key.id. NULL 이면 평문인 줄';
ALTER TABLE memory_revision MODIFY COLUMN content LONGTEXT NOT NULL COMMENT '변경 전 본문. data_key_id 가 있으면 그 데이터 key 의 암호문이고, 비어 있으면 평문';
ALTER TABLE memory_revision MODIFY COLUMN data_key_id BIGINT NULL COMMENT '제목과 본문을 암호화한 user_data_key.id. NULL 이면 평문인 줄';
