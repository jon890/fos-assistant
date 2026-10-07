-- 먼저 살펴보기 결과 버전 3의 문제 후보(ADR-092)다. 받아들인 것과 버린 것을 모두 남긴다.
-- 칸의 뜻은 docs/backend/schema/proactive.md 가 갖는다. 근거는 발견의 주제 키, 원문 주소, 확인 시각만 참조로 둔다.
CREATE TABLE proactive_check_problem (
    id BIGINT NOT NULL AUTO_INCREMENT,
    check_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    drop_reason VARCHAR(32) NULL,
    problem_key VARCHAR(120) NOT NULL,
    problem VARCHAR(300) NULL,
    related_goal VARCHAR(200) NULL,
    action_type VARCHAR(16) NULL,
    action_text VARCHAR(200) NULL,
    confidence VARCHAR(16) NULL,
    expected_benefit VARCHAR(300) NULL,
    side_effect VARCHAR(16) NULL,
    risk VARCHAR(200) NULL,
    change_since_last VARCHAR(300) NULL,
    evidence_json JSON NOT NULL,
    evidence_checked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_proactive_check_problem_conversation_status_created (conversation_id, status, created_at),
    CONSTRAINT fk_proactive_check_problem_check FOREIGN KEY (check_id) REFERENCES proactive_check(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
