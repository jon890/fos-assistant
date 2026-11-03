-- 테스트 DB 는 엔티티로 스키마를 만든다. 기억 항목의 벡터 칸은 엔티티가 매핑하지 않아 생기지 않으므로 여기서 만든다(ADR-20261102 / table-reduce-r2).
-- 칸의 모양은 Flyway 의 V20261102074336__memory_embedding_columns.sql 과 같다.
ALTER TABLE memory ADD COLUMN embedding_model VARCHAR(128) NULL;
ALTER TABLE memory ADD COLUMN embedding_fingerprint BINARY(32) NULL;
ALTER TABLE memory ADD COLUMN embedding_vector BLOB NULL;
