-- 맡긴 실행의 결과를 부모 대화에 전했는지와, 사람의 질문 없이 연 turn 의 횟수를 적는다.
ALTER TABLE conversation ADD COLUMN auto_turn_count INT NOT NULL DEFAULT 0;

ALTER TABLE agent_execution ADD COLUMN result_delivered_at DATETIME(6) NULL;

CREATE INDEX idx_agent_execution_conversation_delivered
    ON agent_execution (conversation_id, result_delivered_at, status);
