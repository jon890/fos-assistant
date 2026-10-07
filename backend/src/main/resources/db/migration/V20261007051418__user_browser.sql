-- 사용자마다 하나씩 두는 브라우저의 상태다. 쿠키와 저장소, 열린 주소는 넣지 않는다.
-- 칸의 뜻은 docs/backend/schema/browser.md, 상태 전이는 docs/backend/user-browser.md 가 갖는다.
CREATE TABLE user_browser (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    profile_key CHAR(64) NOT NULL,
    container_id VARCHAR(80) NULL,
    last_error VARCHAR(40) NULL,
    last_active_at DATETIME(6) NULL,
    started_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_browser_user (user_id),
    KEY idx_user_browser_status_active (status, last_active_at),
    CONSTRAINT fk_user_browser_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
