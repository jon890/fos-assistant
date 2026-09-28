-- 사용자들이 모인 단위를 family 에서 group 으로 부른다. 컬럼 이름과 저장 값을 한 번에 바꾼다.
-- 색인 이름을 바꾸는 문법이 MySQL 과 H2 의 MySQL 모드에 함께 있지 않아 지우고 다시 만든다.
-- 이 버전을 배포한 뒤 이전 이미지로 되돌리려면 DB 도 배포 전 백업으로 되돌려야 한다.
-- 이전 이미지는 family_id 컬럼을 찾아 Schema validation 에서 실패하고, 저장된 GROUP 값을 읽지 못한다.

DROP INDEX ix_app_user_family ON app_user;
ALTER TABLE app_user RENAME COLUMN family_id TO group_id;
CREATE INDEX ix_app_user_group ON app_user (group_id);

DROP INDEX idx_memory_family ON memory;
ALTER TABLE memory RENAME COLUMN family_id TO group_id;
CREATE INDEX idx_memory_group ON memory (group_id, status);

UPDATE agent SET visibility = 'GROUP' WHERE visibility = 'FAMILY';
UPDATE memory SET scope = 'GROUP' WHERE scope = 'FAMILY';
