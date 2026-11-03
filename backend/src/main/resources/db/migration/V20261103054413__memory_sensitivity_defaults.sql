-- 기억의 민감 구분을 없앤다(ADR-20261103 / memory-sensitivity-removal). 코드는 아래 칸을 읽고 쓰지 않는다.
-- 칸은 배포를 되돌릴 때 직전 이미지가 읽도록 남겨 두고, 배포를 확인한 뒤 따로 지운다.
-- 새 코드가 칸을 비운 채 넣는 줄이 NOT NULL 에 걸리지 않게 기본값만 둔다. memory.sensitivity 와
-- agent_memory_collection.allow_sensitive 는 이미 기본값이 있다.
ALTER TABLE memory_revision ALTER COLUMN sensitivity SET DEFAULT 'NORMAL';
ALTER TABLE agent_memory_collection_change ALTER COLUMN allow_sensitive SET DEFAULT FALSE;
