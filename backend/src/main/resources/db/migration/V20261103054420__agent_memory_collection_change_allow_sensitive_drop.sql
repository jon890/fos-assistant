-- collection 허용 변경 기록의 옛 민감 허용 칸을 지운다(ADR-20261103 / memory-sensitivity-removal).
-- 옛 SENSITIVE_CHANGED 기록의 줄은 남고, 그 줄이 바꾼 값만 사라진다.
-- 되돌리기 어려운 삭제라 표마다 파일을 나눈다.
ALTER TABLE agent_memory_collection_change DROP COLUMN allow_sensitive;
