-- 맡긴 실행의 결과를 부모 대화에 전했는지와, 사람의 질문 없이 연 turn 의 횟수를 적는다.
ALTER TABLE conversation ADD COLUMN auto_turn_count INT NOT NULL DEFAULT 0;

ALTER TABLE agent_execution ADD COLUMN result_delivered_at DATETIME(6) NULL;

CREATE INDEX idx_agent_execution_conversation_delivered
    ON agent_execution (conversation_id, result_delivered_at, status);

-- 이 칸이 생기기 전에 끝난 위임 실행은 이미 전한 것으로 적는다. 비워 두면 배포 뒤 첫 기동이 옛 대화를 모두 깨운다.
-- 아직 RUNNING 인 줄은 비워 둔다. 기동 정리가 FAILED 로 적은 뒤 기동 훑기가 부모 대화에 전한다.
UPDATE agent_execution
SET result_delivered_at = COALESCE(finished_at, CURRENT_TIMESTAMP(6))
WHERE delegation_key IS NOT NULL AND status <> 'RUNNING';
