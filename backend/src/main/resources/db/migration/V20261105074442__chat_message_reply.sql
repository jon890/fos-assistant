-- 사용자가 메시지나 그림을 집어 보낸 답장을 질문 줄에 적는다(스펙 chat-reference REQ6, REQ7, 설계 DECISION1).
-- 새 표를 만들지 않는다. 한 메시지에 하나만 집으므로 칸 셋으로 충분하다. 옛 행은 모두 NULL 이라 지금과 같다.
-- 외래 키를 두지 않는다. 대화를 지우면 줄이 함께 지워지고, 원래 줄이 사라져도 답장 줄은 남아야 한다.
ALTER TABLE chat_message ADD COLUMN reply_message_id BIGINT NULL COMMENT '사용자가 집어 답장한 메시지 줄(chat_message.id). 답장이 아니면 NULL';
ALTER TABLE chat_message ADD COLUMN reply_attachment_id BIGINT NULL COMMENT '사진 한 장을 집었으면 그 첨부(chat_attachment.id). 아니면 NULL';
ALTER TABLE chat_message ADD COLUMN reply_artifact_id BIGINT NULL COMMENT '그림 한 장을 집었으면 그 그림 행(chat_artifact.id). 아니면 NULL';
