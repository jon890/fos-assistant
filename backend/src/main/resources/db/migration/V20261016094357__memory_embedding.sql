-- 질문과 관련된 기억을 의미로 고르는 데 쓰는 항목별 벡터다(ADR-20261015 / memory-hybrid-selection).
-- 한 항목에 한 줄이고 한 번에 한 모델만 쓴다. 항목을 지우면 서비스가 같은 트랜잭션에서 지운다.
CREATE TABLE memory_embedding (
    memory_id BIGINT NOT NULL PRIMARY KEY COMMENT '벡터를 만든 기억 항목 번호. 외래 키 없이 서비스가 항목과 함께 지운다',
    model VARCHAR(128) NOT NULL COMMENT '벡터를 만든 임베딩 모델 이름',
    dimensions INT NOT NULL COMMENT '벡터의 차원 수',
    text_fingerprint BINARY(32) NOT NULL COMMENT '모델 이름과 임베딩한 제목·본문의 SHA-256. 지금 글과 다르면 낡은 벡터다',
    vector BLOB NOT NULL COMMENT 'float32 값을 little-endian 으로 이어 붙인 벡터. 길이는 차원 수의 4배 바이트다',
    created_at DATETIME(6) NOT NULL COMMENT '줄을 처음 넣은 시각',
    updated_at DATETIME(6) NOT NULL COMMENT '벡터를 마지막으로 바꾼 시각'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '임베딩 대상 기억 항목마다 한 줄인 의미 검색용 벡터';
