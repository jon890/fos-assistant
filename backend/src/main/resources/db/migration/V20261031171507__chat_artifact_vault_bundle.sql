-- 사진과 스타일을 부르는 HTML 을 묶음으로 보관한다(#530 PR 2, 새 표를 늘리지 않는다).
-- 묶음은 뿌리 행 하나와 그 뿌리를 가리키는 보관 사본 행들이다. 뿌리 행과 묶음이 아닌 보관 사본 행은 비어 있다.
ALTER TABLE chat_artifact ADD COLUMN vault_bundle_id BIGINT NULL COMMENT '이 보관 사본이 들어 있는 묶음의 뿌리 행 chat_artifact.id. 뿌리 행과 대화 결과물 행은 비어 있다';
CREATE INDEX ix_chat_artifact_vault_bundle ON chat_artifact (vault_bundle_id, path);
