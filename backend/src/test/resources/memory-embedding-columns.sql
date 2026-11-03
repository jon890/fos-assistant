-- 테스트 DB 는 엔티티로 스키마를 만든다. 기억 항목의 벡터 칸은 엔티티가 매핑하지 않아 생기지 않으므로 여기서 만든다(ADR-20261102 / table-reduce-r2).
-- 칸의 모양은 Flyway 의 V20261102074336__memory_embedding_columns.sql 과 같다.
ALTER TABLE memory ADD COLUMN embedding_model VARCHAR(128) NULL;
ALTER TABLE memory ADD COLUMN embedding_fingerprint BINARY(32) NULL;
ALTER TABLE memory ADD COLUMN embedding_vector BLOB NULL;
-- 옛 벡터 테이블이다. 항목 삭제와 사용자 정리, 정리 미리보기만 native 질의로 지우고 센다. 옛 테이블을 지우는 PR 이 이 문장을 지운다.
CREATE TABLE IF NOT EXISTS memory_embedding (memory_id BIGINT NOT NULL PRIMARY KEY, model VARCHAR(128) NOT NULL, dimensions INT NOT NULL, text_fingerprint BINARY(32) NOT NULL, vector BLOB NOT NULL, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL);
