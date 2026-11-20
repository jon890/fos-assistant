-- 비서가 그 대화에서 웹 검색과 페이지 읽기로 찾은 주소의 해시다(ADR-20261121 / found-link-image-source).
-- 주소 원문이 아니라 해시 32자를 구분 문자 없이 이어 붙인다. 엔티티는 이 칸을 읽지 않고 전용 질의로만 읽고 쓴다.
ALTER TABLE conversation ADD COLUMN found_link_hashes MEDIUMTEXT NULL;
