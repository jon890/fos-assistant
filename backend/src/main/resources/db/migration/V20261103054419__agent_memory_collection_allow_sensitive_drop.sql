-- 에이전트가 받는 collection 의 옛 민감 허용 칸을 지운다(ADR-20261103 / memory-sensitivity-removal).
-- 되돌리기 어려운 삭제라 표마다 파일을 나눈다.
ALTER TABLE agent_memory_collection DROP COLUMN allow_sensitive;
