-- 답이 도는 중 도는 실행에 끼워 넣은 사용자 글(steer 말풍선)의 상태를 사용자 메시지 줄에 둔다(ADR-20261116 / run-steer).
-- 세 칸 모두 비면 보통 메시지다. 끝난 turn 에 남은 「전달됨」 말풍선을 실행으로 찾는 질의를 위해 색인을 둔다.
ALTER TABLE chat_message ADD COLUMN steer_state VARCHAR(20) NULL COMMENT 'DELIVERED, CARRIED_OVER, UNCONFIRMED. 비면 steer 말풍선이 아니다';
ALTER TABLE chat_message ADD COLUMN steer_execution_id BIGINT NULL COMMENT '끼워 넣은 turn 의 루트 실행(agent_execution.id). execution_id 와 같은 까닭으로 외래 키를 두지 않는다';
ALTER TABLE chat_message ADD COLUMN steer_child VARCHAR(20) NULL COMMENT '받은 순간 돈 자식의 종류. SUBAGENT, DELEGATION, 비면 없음';
CREATE INDEX idx_chat_message_steer ON chat_message (steer_state, steer_execution_id);
