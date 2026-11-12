-- 결과 도착 줄에 맡은 에이전트의 이름표를 그리려고 묶음과 위임 결과 알림 줄을 잇는다(docs/features/agent-team/spec.md 의 REQ4).
-- 묶음은 마지막 시도의 마지막 알림 줄만 기억하고, 첫 시도는 승인 결과마다 알림 줄을 더 남겨 위임 알림 줄을 따로 적는다.
ALTER TABLE result_delivery ADD COLUMN delegation_notice_message_id BIGINT NULL COMMENT '첫 시도가 저장한 위임 결과 알림 줄(chat_message.id). 묶음에 위임 결과가 없거나 이 칸 전에 만든 묶음이면 NULL';
