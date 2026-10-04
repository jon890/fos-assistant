-- 실행 하나에 실은 문맥 항목의 참조다(ADR-071). 칸의 뜻은 docs/backend/schema/execution.md 가 갖는다.
-- 제목과 본문은 남기지 않는다. 실행 줄을 지우지 않으므로 이 줄도 지우지 않는다.
CREATE TABLE execution_context_source (
    execution_id BIGINT NOT NULL,
    position INT NOT NULL,
    source VARCHAR(32) NOT NULL,
    source_ref VARCHAR(80) NOT NULL,
    body_mode VARCHAR(16) NOT NULL,
    freshness VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (execution_id, position)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
