-- 매일 루프의 사용자 설정과 시도 기록이다. 설정은 사용자와 에이전트마다 하나이고, 시도는 원천 살펴보기마다 하나다.
CREATE TABLE proactive_loop_setting (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL,
    snoozed_until DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_proactive_loop_setting_user_agent (user_id, agent_id),
    CONSTRAINT fk_proactive_loop_setting_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_proactive_loop_setting_agent FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE proactive_loop_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    source_check_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    skipped_reason VARCHAR(32) NULL,
    error_code VARCHAR(64) NULL,
    evaluation_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_proactive_loop_run_source (source_check_id),
    KEY idx_proactive_loop_run_user_created (user_id, created_at),
    KEY idx_proactive_loop_run_status (status),
    CONSTRAINT fk_proactive_loop_run_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_proactive_loop_run_source FOREIGN KEY (source_check_id)
        REFERENCES proactive_check(id) ON DELETE CASCADE,
    CONSTRAINT fk_proactive_loop_run_evaluation FOREIGN KEY (evaluation_id)
        REFERENCES proactive_value_evaluation(id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
