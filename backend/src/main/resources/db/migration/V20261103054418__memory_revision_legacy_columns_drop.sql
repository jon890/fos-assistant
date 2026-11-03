-- 기억 변경 기록의 옛 민감도 칸과 옛 key 칸을 지운다(ADR-20261103 / memory-sensitivity-removal).
-- 되돌리기 어려운 삭제라 표마다 파일을 나눈다. H2 가 한 문장에서 칸 여럿을 지우는 문법을 받지 않아 칸마다 문장을 나눈다.
ALTER TABLE memory_revision DROP COLUMN sensitivity;
ALTER TABLE memory_revision DROP COLUMN content_key_id;
