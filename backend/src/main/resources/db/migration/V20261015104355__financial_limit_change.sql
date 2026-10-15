-- 대화로 올리기를 요청한 주문 상한 변경의 내용이다. 승인 줄 하나에 한 줄이고, 승인하면 financial_order_limit 에 반영한다.
CREATE TABLE financial_limit_change (
    action_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    limit_kind VARCHAR(32) NOT NULL,
    requested_value DECIMAL(20, 6) NOT NULL,
    previous_value DECIMAL(20, 6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (action_id),
    CONSTRAINT fk_financial_limit_change_action FOREIGN KEY (action_id) REFERENCES connector_action(id) ON DELETE CASCADE,
    CONSTRAINT fk_financial_limit_change_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
