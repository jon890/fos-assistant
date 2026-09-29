-- 추천 질문을 사람이 적지 않고 모델이 만들어 backend 메모리에만 둔다(ADR-036).
-- 추천을 만드는 실행은 속한 대화가 없어 실행 줄의 conversation_id 가 비어도 되게 한다.
-- 사람이 적던 추천 질문 표와 에이전트의 한 줄 소개 칸은 지운다.
-- 여러 칸을 한 문장으로 바꾸는 문법이 MySQL 과 H2 의 MySQL 모드에서 서로 달라 문장마다 따로 쓴다.

ALTER TABLE agent_execution MODIFY COLUMN conversation_id BIGINT NULL;

DROP TABLE agent_starter_prompt;

ALTER TABLE agent DROP COLUMN tagline;
