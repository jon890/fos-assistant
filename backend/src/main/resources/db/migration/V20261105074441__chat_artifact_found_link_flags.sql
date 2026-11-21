-- 그림이 참고한 링크마다 비서가 찾은 것인지 적는다(ADR-20261121 / found-link-image-source). 화면의 「비서가 찾음」 이 된다.
ALTER TABLE chat_artifact ADD COLUMN generation_found_link_flags VARCHAR(16) NULL COMMENT 'generation_source_links 의 링크마다 비서가 찾은 것이면 1, 사용자가 보낸 것이면 0 을 같은 순서로 이은 글. 찾은 링크가 없으면 NULL';
