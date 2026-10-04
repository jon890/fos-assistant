-- 자동 turn 이 부모 대화에 넘긴 결과를 전달 묶음과 항목으로, 그 묶음을 넘긴 한 번 한 번을 전달 시도로 남긴다(ADR-070).
-- 외래 키는 항목과 시도에서 묶음으로만 둔다. 대화, 실행 줄, 알림 줄이 지워져도 전달 기록을 남긴다.
CREATE TABLE result_delivery (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
CREATE INDEX idx_result_delivery_conversation_status ON result_delivery (conversation_id, status);

CREATE TABLE result_delivery_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    delivery_id BIGINT NOT NULL,
    source VARCHAR(40) NOT NULL,
    result_key VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_result_delivery_item_source_key (source, result_key),
    CONSTRAINT fk_result_delivery_item_delivery FOREIGN KEY (delivery_id) REFERENCES result_delivery(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
CREATE INDEX idx_result_delivery_item_delivery ON result_delivery_item (delivery_id, id);

CREATE TABLE result_delivery_attempt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    delivery_id BIGINT NOT NULL,
    attempt_no INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    execution_id BIGINT NULL,
    notice_message_id BIGINT NULL,
    error_code VARCHAR(64) NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_result_delivery_attempt_no (delivery_id, attempt_no),
    CONSTRAINT fk_result_delivery_attempt_delivery FOREIGN KEY (delivery_id) REFERENCES result_delivery(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
CREATE INDEX idx_result_delivery_attempt_status ON result_delivery_attempt (status, started_at);
CREATE INDEX idx_result_delivery_attempt_execution ON result_delivery_attempt (execution_id);
