-- 먼저 살펴보기(ADR-080)의 점검 대화를 가리는 칸과, 살펴보기 한 번과 그 발견을 저장하는 표 둘이다.
-- 칸의 뜻은 docs/backend/schema/proactive.md 와 docs/backend/schema/chat.md 가 갖는다.
ALTER TABLE conversation ADD COLUMN purpose VARCHAR(16) NOT NULL DEFAULT 'CHAT';

CREATE INDEX idx_conversation_user_agent_purpose ON conversation (user_id, agent_id, purpose);

-- trigger 는 MySQL 의 예약어라 칸 이름을 trigger_type 으로 둔다.
CREATE TABLE proactive_check (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    root_execution_id BIGINT NULL,
    hermes_root_session_id VARCHAR(128) NULL,
    trigger_type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    outcome VARCHAR(16) NULL,
    error_code VARCHAR(64) NULL,
    tool_calls INT NOT NULL DEFAULT 0,
    delegations INT NOT NULL DEFAULT 0,
    new_findings INT NOT NULL DEFAULT 0,
    reference_findings INT NOT NULL DEFAULT 0,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_proactive_check_root_execution (root_execution_id),
    KEY idx_proactive_check_user_agent_started (user_id, agent_id, started_at),
    KEY idx_proactive_check_conversation_session (conversation_id, hermes_root_session_id),
    CONSTRAINT fk_proactive_check_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_proactive_check_agent FOREIGN KEY (agent_id) REFERENCES agent(id),
    CONSTRAINT fk_proactive_check_conversation FOREIGN KEY (conversation_id) REFERENCES conversation(id),
    CONSTRAINT fk_proactive_check_root_execution FOREIGN KEY (root_execution_id) REFERENCES agent_execution(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 발견의 이유, 사실, 추정은 대화 메시지에만 남기고 이 표에 두지 않는다.
CREATE TABLE proactive_check_finding (
    id BIGINT NOT NULL AUTO_INCREMENT,
    check_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    reason VARCHAR(32) NULL,
    area VARCHAR(40) NOT NULL,
    topic_key VARCHAR(120) NULL,
    title VARCHAR(120) NOT NULL,
    source_url VARCHAR(2000) NULL,
    checked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_proactive_check_finding_conversation_kind_created (conversation_id, kind, created_at),
    CONSTRAINT fk_proactive_check_finding_check FOREIGN KEY (check_id) REFERENCES proactive_check(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
