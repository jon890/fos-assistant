-- 대기 메시지를 conversation.pending_messages 로 옮기고 남은 chat_pending_message 를 지운다(ADR-20261102 / table-reduce-r2).
-- 읽고 쓰는 코드는 이미 지웠고 직전 이미지가 쓴 행은 V20261102074329 가 다시 옮겼다. 대화 줄이 없는 행은 보낼 곳이 없어 함께 버린다.
-- 되돌리기 어려운 삭제라 이 파일 하나로 둔다.
DROP TABLE chat_pending_message;
