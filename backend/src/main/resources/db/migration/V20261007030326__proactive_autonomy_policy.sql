-- 행동 정책의 판정과 사용자의 자동 실행 동의다. 판정은 후보 하나에 한 줄이고 실행 키는 원천 살펴보기마다 하나다.
CREATE TABLE proactive_autonomy_decision (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    evaluation_id BIGINT NOT NULL,
    candidate_id BIGINT NOT NULL,
    source_check_id BIGINT NOT NULL,
    action_level VARCHAR(16) NOT NULL,
    reasons_json JSON NOT NULL,
    inputs_json JSON NOT NULL,
    policy_version INT NOT NULL,
    execution_key VARCHAR(64) NULL,
    execution_status VARCHAR(16) NULL,
    execution_check_id BIGINT NULL,
    execution_error VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_proactive_autonomy_decision_execution_key (execution_key),
    KEY idx_proactive_autonomy_decision_user_created (user_id, created_at),
    KEY idx_proactive_autonomy_decision_evaluation (evaluation_id),
    CONSTRAINT fk_proactive_autonomy_decision_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_proactive_autonomy_decision_evaluation FOREIGN KEY (evaluation_id)
        REFERENCES proactive_value_evaluation(id) ON DELETE CASCADE,
    CONSTRAINT fk_proactive_autonomy_decision_source FOREIGN KEY (source_check_id)
        REFERENCES proactive_check(id) ON DELETE CASCADE,
    CONSTRAINT fk_proactive_autonomy_decision_execution FOREIGN KEY (execution_check_id)
        REFERENCES proactive_check(id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE user_autonomy_preference (
    user_id BIGINT NOT NULL,
    read_only_execution BOOLEAN NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_autonomy_preference_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
