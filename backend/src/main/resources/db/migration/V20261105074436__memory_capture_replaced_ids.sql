-- 합치기 요청에서 새 기억이 대신할 옛 기억 번호를 기록에 둔다(docs/features/memory/spec.md 의 REQ6).
-- 바로 저장은 실제로 대신한 번호, 제안은 받아들일 때 대신할 번호다. 합치기가 아니면 NULL 이다.
ALTER TABLE memory_capture ADD COLUMN replaced_memory_ids VARCHAR(512) NULL COMMENT '대신한(제안이면 대신할) 옛 기억 번호의 JSON 배열. 합치기가 아니면 NULL';
-- 제안을 받아들일 때 그 항목의 PROPOSED 기록을 찾는다(MemoryCaptureRepository.findFirstByMemoryIdAndKindOrderByIdDesc).
CREATE INDEX idx_memory_capture_memory ON memory_capture (memory_id, kind, id);
