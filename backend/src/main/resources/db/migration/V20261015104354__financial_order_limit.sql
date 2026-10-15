-- 계좌 주인이 운영 최대치 안에서 정한 주문 상한이다. 사용자마다 한 줄이고, 빈 컬럼은 운영 최대치를 따른다.
CREATE TABLE financial_order_limit (
    user_id BIGINT NOT NULL,
    order_amount_krw DECIMAL(20, 6) NULL,
    order_amount_usd DECIMAL(20, 6) NULL,
    order_quantity DECIMAL(20, 6) NULL,
    daily_amount_krw DECIMAL(20, 6) NULL,
    daily_amount_usd DECIMAL(20, 6) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_financial_order_limit_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
