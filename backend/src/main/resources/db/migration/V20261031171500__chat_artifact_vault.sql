-- 사용자가 골라 보관한 결과물 사본을 chat_artifact 의 행으로 둔다(#530, 새 표를 늘리지 않는다).
-- 보관 사본 행은 vault_user_id 가 채워지고 message_id 가 비어 있다. 대화를 지워도 남고 본문은 그 사용자의 데이터 key 로 암호화한다.
-- 보관 사본 행의 byte_size 는 평문 바이트 수이고 사용자당 용량을 셀 때 쓴다.
ALTER TABLE chat_artifact MODIFY COLUMN message_id BIGINT NULL COMMENT '이 파일을 만든 turn 의 답 메시지. 보관 사본 행은 비어 있다';
ALTER TABLE chat_artifact ADD COLUMN vault_user_id BIGINT NULL COMMENT '보관 사본의 주인(대화 요청자). 비어 있으면 대화 결과물 행이고, 채워져 있으면 대화와 상관없이 남는 보관 사본이다';
ALTER TABLE chat_artifact ADD COLUMN vault_key_id BIGINT NULL COMMENT '보관 본문을 암호화한 user_data_key.id. 보관 사본 행에만 있다';
ALTER TABLE chat_artifact ADD COLUMN vault_body LONGTEXT NULL COMMENT '암호화한 보관 본문(v1.IV.암호문과 태그). 평문은 저장하지 않는다. 보관 사본 행에만 있다';
ALTER TABLE chat_artifact ADD COLUMN vault_fingerprint VARCHAR(64) NULL COMMENT '대화 번호, 경로, 본문 바이트로 만든 SHA-256 16진수. 같은 사용자의 같은 보관 요청을 한 건으로 만든다. 다른 사용자와 비교하지 않는다';
CREATE UNIQUE INDEX uk_chat_artifact_vault_fingerprint ON chat_artifact (vault_user_id, vault_fingerprint);
CREATE INDEX ix_chat_artifact_vault_user ON chat_artifact (vault_user_id, id);
