-- 사용자별 커넥터 연결 상태를 커넥터 번호로 구분해 저장한다. 가계부 전용 표의 행을 옮기고 그 표를 지운다.
CREATE TABLE connector_connection (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    connector_id VARCHAR(64) NOT NULL,
    agent_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    fields VARCHAR(4000) NOT NULL,
    restart_required BOOLEAN NOT NULL DEFAULT FALSE,
    desired_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    checked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_connector_connection_user_connector (user_id, connector_id),
    UNIQUE KEY uk_connector_connection_agent (agent_id),
    CONSTRAINT fk_connector_connection_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_connector_connection_agent FOREIGN KEY (agent_id) REFERENCES agent(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- fields 는 JSON 텍스트다. 비어 있는 칸은 키를 넣지 않는다.
-- MySQL 의 CONCAT 은 인자 하나가 NULL 이면 전체가 NULL 이므로 NULL 을 CASE 로 먼저 나눈다.
-- 옮기는 값은 UUID 와 토큰 앞 8자뿐이라 JSON 에서 따로 바꿔 써야 하는 문자가 없다.
INSERT INTO connector_connection (
    user_id, connector_id, agent_id, status, fields,
    restart_required, desired_enabled, checked_at, created_at, updated_at
)
SELECT
    user_id,
    'fos-accountbook',
    agent_id,
    status,
    CONCAT(
        '{"values":{',
        CASE
            WHEN family_uuid IS NULL OR TRIM(family_uuid) = '' THEN ''
            ELSE CONCAT('"family":"', TRIM(family_uuid), '"')
        END,
        '},"secretPrefixes":{',
        CASE
            WHEN token_prefix IS NULL OR token_prefix = '' THEN ''
            ELSE CONCAT('"token":"', token_prefix, '"')
        END,
        '}}'
    ),
    restart_required,
    desired_enabled,
    checked_at,
    created_at,
    updated_at
FROM accountbook_connection;

DROP TABLE accountbook_connection;
