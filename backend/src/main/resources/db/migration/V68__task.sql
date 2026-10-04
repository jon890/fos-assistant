-- 예약 작업과 그 시각, 발화 한 번을 저장한다(ADR-076, ADR-077). 칸의 뜻은 docs/backend/schema/task.md 가 갖는다.
-- 에이전트와 대화는 지워도 행이 남는 표라 task 는 owner_user_id 에만 외래 키를 둔다.
CREATE TABLE task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id BINARY(16) NOT NULL,
    owner_user_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    instruction TEXT NOT NULL,
    state VARCHAR(20) NOT NULL,
    conversation_mode VARCHAR(20) NOT NULL,
    conversation_id BIGINT NULL,
    notify VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    archived_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_public_id (public_id),
    KEY idx_task_owner_state (owner_user_id, state),
    CONSTRAINT fk_task_owner_user FOREIGN KEY (owner_user_id) REFERENCES app_user(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 작업의 시각이다. 지금은 작업 하나에 하나다. 시각을 고치면 같은 줄을 고친다.
CREATE TABLE task_trigger (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    type VARCHAR(20) NOT NULL,
    cron_expr VARCHAR(100) NULL,
    fire_at DATETIME(6) NULL,
    time_zone VARCHAR(64) NOT NULL,
    missed_policy VARCHAR(20) NOT NULL,
    next_fire_at DATETIME(6) NULL,
    last_fired_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_trigger_task (task_id),
    KEY idx_task_trigger_next_fire (next_fire_at),
    CONSTRAINT fk_task_trigger_task FOREIGN KEY (task_id) REFERENCES task(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 발화 한 번이다. 같은 trigger 의 같은 예정 시각은 한 줄뿐이다(ADR-077).
CREATE TABLE task_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id BINARY(16) NOT NULL,
    task_id BIGINT NOT NULL,
    trigger_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    scheduled_for DATETIME(6) NOT NULL,
    status VARCHAR(20) NOT NULL,
    reason VARCHAR(32) NULL,
    conversation_id BIGINT NULL,
    execution_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_run_public_id (public_id),
    UNIQUE KEY uk_task_run_trigger_scheduled (trigger_id, scheduled_for),
    KEY idx_task_run_status_scheduled (status, scheduled_for),
    KEY idx_task_run_owner_created (owner_user_id, created_at),
    KEY idx_task_run_task_scheduled (task_id, scheduled_for),
    CONSTRAINT fk_task_run_task FOREIGN KEY (task_id) REFERENCES task(id),
    CONSTRAINT fk_task_run_trigger FOREIGN KEY (trigger_id) REFERENCES task_trigger(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 이 대화를 만든 예약 작업이다(ADR-078). 사용자가 연 대화는 비어 있다. conversation.agent_id 처럼 외래 키를 두지 않는다.
ALTER TABLE conversation ADD COLUMN task_id BIGINT NULL;
CREATE INDEX idx_conversation_task ON conversation (task_id);
