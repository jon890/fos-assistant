-- 직전 이미지가 execution_question 에 쓴 질문 줄을 실행 줄의 question_message_id 로 다시 옮긴다(ADR-20261102 / table-reduce-r2).
-- execution_question 을 지우는 V20261102074322 앞 단계다. 이미 값이 있는 실행은 건드리지 않고, 실행이 없는 질문 줄은 옮길 곳이 없어 버린다.
-- 정리한 대화(conversation.purged_at 이 있는)의 실행은 옮기지 않는다. 직전 이미지로 되돌린 동안 옛 코드가 대화를 정리했다면
-- V20261102053011 이 옮긴 값이 정리한 대화의 메시지를 가리킬 수 있어, 그 실행의 질문 번호는 비운다.
UPDATE agent_execution
   SET question_message_id = (SELECT q.message_id FROM execution_question q WHERE q.execution_id = agent_execution.id)
 WHERE question_message_id IS NULL
   AND EXISTS (SELECT 1 FROM execution_question q2 WHERE q2.execution_id = agent_execution.id)
   AND (conversation_id IS NULL
        OR NOT EXISTS (SELECT 1 FROM conversation c WHERE c.id = agent_execution.conversation_id AND c.purged_at IS NOT NULL));

UPDATE agent_execution
   SET question_message_id = NULL
 WHERE question_message_id IS NOT NULL
   AND conversation_id IN (SELECT c.id FROM conversation c WHERE c.purged_at IS NOT NULL);
