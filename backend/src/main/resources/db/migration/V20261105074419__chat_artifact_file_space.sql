-- 「내 파일」 에 넣은 파일의 제목과 설명, 들어온 길, 내준 답을 보관 줄에 둔다(ADR-20261119 / file-space-put, 스펙 workspace REQ3, REQ6).
-- 새 표를 만들지 않는다. artifact_write 가 제목을 받은 HTML 은 답에 묶이기 전의 결과물 줄에 vault_meta 를 먼저 둔다.
ALTER TABLE chat_artifact ADD COLUMN vault_meta LONGTEXT NULL COMMENT '제목과 설명의 JSON 을 주인의 데이터 key 로 암호화한 글. 보관 뿌리 줄과 artifact_write 가 제목을 받은 결과물 줄에 있다. 없으면 NULL';
ALTER TABLE chat_artifact ADD COLUMN vault_meta_key_id BIGINT NULL COMMENT 'vault_meta 를 암호화한 데이터 key(user_data_key.id)';
ALTER TABLE chat_artifact ADD COLUMN vault_origin VARCHAR(16) NULL COMMENT '보관 줄이 들어온 길. PUT 은 넣기 도구가 대화 폴더에서, PUT_WORKSPACE 는 넣기 도구가 실행 공간에서 넣었다. NULL 이면 자동 보관이나 보관 단추다';
ALTER TABLE chat_artifact ADD COLUMN vault_message_id BIGINT NULL COMMENT '넣기 도구가 넣은 보관 뿌리 줄을 내준 답 메시지(chat_message.id). turn 이 끝날 때 채운다';
CREATE INDEX idx_chat_artifact_vault_message ON chat_artifact (vault_message_id);
