-- 비서가 그 대화에서 웹 검색과 페이지 읽기로 찾은 주소의 해시다(ADR-20261121 / found-link-image-source).
-- 주소 원문이 아니라 해시 32자를 구분 문자 없이 이어 붙인다. 엔티티는 이 칸을 읽지 않고 전용 질의로만 읽고 쓴다.
ALTER TABLE conversation ADD COLUMN found_link_hashes MEDIUMTEXT NULL COMMENT '비서가 이 대화에서 웹 검색과 페이지 읽기로 찾은 주소의 해시. 정해진 꼴로 바꾼 주소의 SHA-256 앞 16바이트를 16진수 32자로 적어 구분 문자 없이 이어 붙인다. 주소 원문은 두지 않는다. 대화를 지우거나 정리하면 비운다';
