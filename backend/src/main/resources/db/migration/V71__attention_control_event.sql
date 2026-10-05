-- 사용자가 지금 화면의 한 카드의 한 항목에 건 숨기기와 미루기다. 칸의 뜻은 docs/backend/schema/attention.md 가 갖는다.
-- 한 사용자의 한 카드의 한 항목에 한 줄이다. 새 제어는 그 줄을 고친다.
CREATE TABLE attention_control (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    card_key VARCHAR(16) NOT NULL,
    item_key VARCHAR(80) NOT NULL,
    action VARCHAR(16) NOT NULL,
    state_key VARCHAR(64) NULL,
    until_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_attention_control_item (user_id, card_key, item_key),
    CONSTRAINT fk_attention_control_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 먼저 알리기의 지표 사건이다. 같은 사용자, 항목, 상태, 사건 종류, 판정에 한 줄만 남긴다.
-- 제목과 본문을 담는 칸이 없다. 보관 기간이 지난 줄은 created_at 색인으로 지운다.
CREATE TABLE attention_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    item_key VARCHAR(80) NOT NULL,
    state_key VARCHAR(64) NOT NULL,
    trigger_type VARCHAR(32) NOT NULL,
    attention VARCHAR(16) NOT NULL,
    event_type VARCHAR(16) NOT NULL,
    stale BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_attention_event_once (user_id, item_key, state_key, event_type, attention),
    KEY idx_attention_event_created (created_at),
    CONSTRAINT fk_attention_event_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
