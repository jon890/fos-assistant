-- 같은 주인의 같은 출처가 두 줄이 되지 않게 한다(ADR-058).
-- 출처가 없는 줄은 source_ref 가 NULL 이라 이 제약에 걸리지 않는다.
CREATE UNIQUE INDEX uk_memory_user_source ON memory (owner_user_id, source_type, source_ref);
