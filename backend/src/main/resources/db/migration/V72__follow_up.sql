-- 사용자 한 사람의 할 일이다. 칸의 뜻은 docs/backend/schema/attention.md 의 「follow_up」 이 갖는다.
-- open_marker 는 열린 줄만 1 이고 끝난 줄은 NULL 이다. 유일 제약이 NULL 을 서로 다르다고 보므로 끝난 줄은 걸리지 않는다.
-- 외래 키는 사용자에만 둔다. 대화와 실행은 지워져도 이 줄을 남긴다.
CREATE TABLE follow_up (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id BINARY(16) NOT NULL,
    user_id BIGINT NOT NULL,
    conversation_id BIGINT NULL,
    proposed_by_execution_id BIGINT NULL,
    title VARCHAR(200) NOT NULL,
    title_key CHAR(64) NOT NULL,
    due_at DATETIME(6) NULL,
    waiting BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL,
    open_marker TINYINT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    accepted_at DATETIME(6) NULL,
    closed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_follow_up_public_id (public_id),
    UNIQUE KEY uk_follow_up_open_title (user_id, title_key, open_marker),
    CONSTRAINT fk_follow_up_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_follow_up_user_status ON follow_up (user_id, status);
CREATE INDEX idx_follow_up_conversation_status ON follow_up (conversation_id, status);
