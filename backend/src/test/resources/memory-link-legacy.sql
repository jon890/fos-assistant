-- 테스트 DB 는 엔티티로 스키마를 만든다. 기억 연결 테이블은 코드가 읽고 쓰지 않아 엔티티가 매핑하지 않으므로 여기서 만든다(ADR-20261102 / table-reduce-r2).
-- 항목 삭제와 사용자 정리가 직전 이미지가 남긴 옛 줄을 지우는지 보려는 것이다. 운영 스키마에는 배포를 되돌릴 때 직전 이미지가 읽도록 테이블이 남아 있다.
-- 테이블을 지우는 PR 이 이 파일을 지운다. 칸의 모양은 Flyway 의 V20261016224028__memory_link.sql 과 같다. 문장은 한 줄에 하나씩 쓴다.
CREATE TABLE memory_link (id BIGINT AUTO_INCREMENT PRIMARY KEY, from_memory_id BIGINT NOT NULL, to_memory_id BIGINT NOT NULL, type VARCHAR(20) NOT NULL, label VARCHAR(200) NULL, origin VARCHAR(20) NOT NULL, status VARCHAR(20) NOT NULL, score DOUBLE NULL, created_by_execution_id BIGINT NULL, created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL, revoked_at TIMESTAMP(6) NULL, CONSTRAINT uk_memory_link_pair UNIQUE (from_memory_id, to_memory_id, type));
