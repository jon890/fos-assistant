-- 사용자에게 대화 밖에서 알리는 줄이다(ADR-070). 칸의 뜻은 docs/backend/schema/notification.md 가 갖는다.
-- 갈 곳(target_*)은 지워져도 알림 줄을 남기므로 user_id 에만 외래 키를 둔다.
CREATE TABLE notification (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id BINARY(16) NOT NULL,
    user_id BIGINT NOT NULL,
    kind VARCHAR(32) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body VARCHAR(500) NOT NULL,
    target_type VARCHAR(20) NULL,
    target_public_id BINARY(16) NULL,
    created_at DATETIME(6) NOT NULL,
    read_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_public_id (public_id),
    KEY idx_notification_user_created (user_id, created_at, id),
    KEY idx_notification_user_read (user_id, read_at),
    CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
