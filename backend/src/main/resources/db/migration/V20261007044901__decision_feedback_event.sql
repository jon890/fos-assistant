-- 판단 피드백 기록이다. 제안 하나(subject_key)에 대한 사용자 반응과 실행 결과를 사건 한 줄씩 덧붙인다.
-- 칸의 뜻은 docs/backend/schema/feedback.md 가 갖는다. 제목, 본문, 인자, Memory 원문을 담는 칸이 없다.
CREATE TABLE decision_feedback_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    subject_type VARCHAR(24) NOT NULL,
    subject_key VARCHAR(80) NOT NULL,
    event_type VARCHAR(24) NOT NULL,
    actor VARCHAR(16) NOT NULL,
    conversation_id BIGINT NULL,
    origin_execution_id BIGINT NULL,
    source_check_id BIGINT NULL,
    autonomy_decision_id BIGINT NULL,
    subject_version VARCHAR(64) NULL,
    reason_code VARCHAR(64) NULL,
    changed_fields VARCHAR(64) NULL,
    contract_version INT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_decision_feedback_event_user_occurred (user_id, occurred_at),
    KEY idx_decision_feedback_event_subject (user_id, subject_key),
    KEY idx_decision_feedback_event_conversation (conversation_id),
    KEY idx_decision_feedback_event_occurred (occurred_at),
    CONSTRAINT fk_decision_feedback_event_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_decision_feedback_event_conversation FOREIGN KEY (conversation_id)
        REFERENCES conversation(id) ON DELETE CASCADE,
    CONSTRAINT fk_decision_feedback_event_execution FOREIGN KEY (origin_execution_id)
        REFERENCES agent_execution(id) ON DELETE SET NULL,
    CONSTRAINT fk_decision_feedback_event_check FOREIGN KEY (source_check_id)
        REFERENCES proactive_check(id) ON DELETE SET NULL,
    CONSTRAINT fk_decision_feedback_event_autonomy FOREIGN KEY (autonomy_decision_id)
        REFERENCES proactive_autonomy_decision(id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
