-- 가치 평가의 입력과 축별 판단이다. 원문 본문과 전체 개인 문맥은 복제하지 않는다.
CREATE TABLE proactive_value_evaluation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    check_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    replay_of_id BIGINT NULL,
    outcome VARCHAR(32) NOT NULL,
    evidence_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_proactive_value_evaluation_user_created (user_id, created_at),
    KEY idx_proactive_value_evaluation_outcome (outcome),
    CONSTRAINT fk_proactive_value_evaluation_check FOREIGN KEY (check_id) REFERENCES proactive_check(id) ON DELETE CASCADE,
    CONSTRAINT fk_proactive_value_evaluation_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
