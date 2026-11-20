-- 비서가 링크의 사진을 참고해 만든 그림에 그 링크 주소를 남긴다(ADR-20261120 / link-image-source, 스펙 generated-image REQ22).
-- 새 표를 만들지 않는다. 지시문 칸과 같은 방식으로 대화 요청자의 데이터 key 로 암호화한다.
ALTER TABLE chat_artifact ADD COLUMN generation_source_links LONGTEXT NULL COMMENT '그림을 만들 때 참고한 링크 주소를 줄바꿈으로 이은 글. generation_source_links_key_id 가 있으면 암호문이다. 링크를 쓰지 않은 그림은 NULL';
ALTER TABLE chat_artifact ADD COLUMN generation_source_links_key_id BIGINT NULL COMMENT 'generation_source_links 를 암호화한 데이터 key(user_data_key.id). 비어 있으면 평문이다';
