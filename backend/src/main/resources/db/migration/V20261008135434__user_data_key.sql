-- 사용자마다 하나인 데이터 key(DEK)를 KEK 로 감싸 둔다(ADR-20261008 / data-encryption).
-- 원문 key 는 저장하지 않는다. KEK 는 데이터베이스 밖에 있고 kek_id 로 어느 KEK 로 감쌌는지만 적는다.
-- 이 줄을 지우면 그 사용자의 암호문은 누구도 풀지 못한다.
CREATE TABLE user_data_key (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    kek_id VARCHAR(32) NOT NULL,
    wrapped_key VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    rewrapped_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_data_key_user (user_id),
    KEY ix_user_data_key_kek (kek_id),
    CONSTRAINT fk_user_data_key_user FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
