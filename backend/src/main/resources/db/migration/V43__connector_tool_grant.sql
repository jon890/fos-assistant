-- 사용자가 커넥터 도구 하나에 준 상시 허락이다(ADR-048). 무기한은 없고, 거두면 줄을 지우지 않고 시각을 적는다.
CREATE TABLE connector_tool_grant (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    connector_id VARCHAR(64) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_connector_tool_grant_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_connector_tool_grant_lookup ON connector_tool_grant (user_id, connector_id, tool_name);
