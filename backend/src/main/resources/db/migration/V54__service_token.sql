-- 다른 서비스가 사용자의 Memory 문서를 읽을 때 쓰는 토큰이다(ADR-056).
-- 원문은 저장하지 않고 SHA-256 해시만 둔다. agent_token 과 달리 사용자 한 사람에 묶인다.
CREATE TABLE service_token (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    label VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    last_used_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_service_token_hash (token_hash),
    CONSTRAINT fk_service_token_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 토큰이 받는 collection 이다. agent_memory_collection 과 같은 모양이고 같은 세 조건으로 판정한다(ADR-053).
CREATE TABLE service_token_collection (
    token_id BIGINT NOT NULL,
    collection VARCHAR(64) NOT NULL,
    allow_sensitive BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (token_id, collection),
    CONSTRAINT fk_service_token_collection_token FOREIGN KEY (token_id) REFERENCES service_token(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
