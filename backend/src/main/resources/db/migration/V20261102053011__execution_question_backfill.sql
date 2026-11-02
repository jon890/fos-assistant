-- execution_question 의 질문 메시지를 실행 줄로 옮긴다(ADR-20261102 / table-reduce-r2).
-- 이미 값이 있는 줄은 건드리지 않는다. 실행이 없는 질문 줄은 옮길 곳이 없어 버린다.
UPDATE agent_execution
   SET question_message_id = (SELECT q.message_id FROM execution_question q WHERE q.execution_id = agent_execution.id)
 WHERE question_message_id IS NULL
   AND EXISTS (SELECT 1 FROM execution_question q2 WHERE q2.execution_id = agent_execution.id);
