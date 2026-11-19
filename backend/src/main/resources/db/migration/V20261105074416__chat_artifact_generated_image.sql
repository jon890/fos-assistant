-- 비서가 만든 그림의 지시문과 되풀이 올리기 판정 값을 결과물 줄에 둔다(ADR-20261119 / generated-image, 스펙 generated-image REQ5).
-- 그림 줄은 fos-ctx 가 그림을 올릴 때 message_id 가 빈 채 만들어지고 turn 이 끝날 때 답에 묶인다.
ALTER TABLE chat_artifact ADD COLUMN generation_prompt LONGTEXT NULL COMMENT '비서가 이미지 도구에 넘긴 그림 지시문. generation_prompt_key_id 가 있으면 암호문이다. 만든 그림 줄이 아니면 NULL';
ALTER TABLE chat_artifact ADD COLUMN generation_prompt_key_id BIGINT NULL COMMENT '지시문을 암호화한 데이터 key(user_data_key.id). NULL 이면 지시문은 평문이다';
ALTER TABLE chat_artifact ADD COLUMN generation_call_key CHAR(64) NULL COMMENT '올린 호출의 session, tool_call_id, 그림 SHA-256 을 묶은 SHA-256 16진수. 같은 올리기가 다시 와도 한 줄이다';
CREATE UNIQUE INDEX uk_chat_artifact_generation_call_key ON chat_artifact (generation_call_key);
