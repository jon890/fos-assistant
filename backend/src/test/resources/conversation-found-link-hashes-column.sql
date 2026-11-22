-- 테스트 DB 는 엔티티로 스키마를 만든다. 찾은 주소의 해시 칸은 엔티티가 매핑하지 않아 생기지 않으므로 여기서 만든다(ADR-20261121 / found-link-image-source).
-- 칸의 모양은 Flyway 의 V20261105074439__conversation_found_link_hashes.sql 과 같다.
ALTER TABLE conversation ADD COLUMN found_link_hashes MEDIUMTEXT NULL;
