-- 기억의 옛 민감도 칸과 옛 key 칸을 지운다(ADR-20261103 / memory-sensitivity-removal).
-- 코드는 두 칸을 읽고 쓰지 않는다. 옛 key 로 암호화한 줄이 남았는지는 V20261103054416 이 먼저 본다.
-- 되돌리기 어려운 삭제라 표마다 파일을 나눈다. H2 가 한 문장에서 칸 여럿을 지우는 문법을 받지 않아 칸마다 문장을 나눈다.
ALTER TABLE memory DROP COLUMN sensitivity;
ALTER TABLE memory DROP COLUMN content_key_id;
