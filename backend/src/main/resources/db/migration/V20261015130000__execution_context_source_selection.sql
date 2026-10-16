-- 실은 Memory 항목을 어떤 규칙이 몇 번째로 골랐는지 남긴다(ADR-20261015 / memory-eval-gate).
-- 칸의 뜻은 backend/docs/data-schema.md 의 「execution_context_source」 가 갖는다. 제목과 본문, 사용 신호는 담지 않는다.
-- 이 칸을 더하기 전의 줄과 Memory 가 아닌 줄은 비어 있다.
ALTER TABLE execution_context_source ADD COLUMN selector VARCHAR(32) NULL;
ALTER TABLE execution_context_source ADD COLUMN selection_rank INT NULL;
ALTER TABLE execution_context_source ADD COLUMN keyword_score DOUBLE NULL;
ALTER TABLE execution_context_source ADD COLUMN semantic_score DOUBLE NULL;
ALTER TABLE execution_context_source ADD COLUMN selection_score DOUBLE NULL;
