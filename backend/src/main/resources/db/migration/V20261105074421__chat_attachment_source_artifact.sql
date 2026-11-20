-- 만든 그림을 블로그 원고에 넣을 때 만드는 사진 첨부 사본을 표시한다(ADR-20261120 / generated-image-draft, 스펙 generated-image REQ18, REQ19).
-- 새 표를 만들지 않는다. 사본은 message_id 가 비어 있고 이 칸에 그림 행(chat_artifact.id) 번호가 있다. 옛 행은 모두 NULL 이라 지금과 같다.
ALTER TABLE chat_attachment ADD COLUMN source_artifact_id BIGINT NULL COMMENT '원고에 넣으려고 만든 그림 사본이면 그 그림 행(chat_artifact.id). 사용자가 올린 사진이면 NULL';
