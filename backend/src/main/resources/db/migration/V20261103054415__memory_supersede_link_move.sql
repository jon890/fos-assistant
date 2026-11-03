-- 효력 있는 대체 연결(memory_link 의 SUPERSEDES, ACTIVE)을 옛 항목의 superseded_by_memory_id 로 옮긴다(ADR-20261102 / table-reduce-r2).
-- 한 항목을 가리키는 효력 있는 줄이 여럿이면 가장 나중에 맺은(번호가 큰) 줄의 새 항목을 쓴다.
-- 제안 중이거나 끊은 대체 연결과 RELATED 연결은 옮기지 않는다. 옛 줄은 지우지 않고 직전 이미지가 읽도록 남긴다.
UPDATE memory
SET superseded_by_memory_id = (
    SELECT l.from_memory_id
    FROM memory_link l
    WHERE l.to_memory_id = memory.id AND l.type = 'SUPERSEDES' AND l.status = 'ACTIVE'
    ORDER BY l.id DESC
    LIMIT 1)
WHERE EXISTS (
    SELECT 1
    FROM memory_link l
    WHERE l.to_memory_id = memory.id AND l.type = 'SUPERSEDES' AND l.status = 'ACTIVE');
