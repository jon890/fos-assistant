-- 서버 비밀값을 KEK 로 감싸 두는 테이블이다(ADR-20261120 / server-secret).
-- 줄이 있고 값 컬럼이 NULL 이면 「비어 있음, 옛 설정 가져오기 끝남」 이다. 지운 값도 줄은 남긴다.
CREATE TABLE server_secret (
    name VARCHAR(160) NOT NULL COMMENT '비밀값 이름. browser_gateway 처럼 종류를 접두로 나눈다',
    kek_id VARCHAR(32) NULL COMMENT '값을 감싼 KEK 의 id. 값이 비었으면 NULL',
    sealed_value VARCHAR(4096) NULL COMMENT 'KEK 로 감싼 값(v1.<IV>.<암호문과 태그>). 원문이 아니다. 비었으면 NULL',
    prefix VARCHAR(16) NULL COMMENT '화면에 보이는 앞부분. 값이 16자 미만이거나 비었으면 NULL',
    meta_json TEXT NULL COMMENT '비밀이 아닌 부가 값의 JSON. 공개 키, 별칭 같은 것. 없으면 NULL',
    updated_by BIGINT NULL COMMENT '마지막으로 바꾼 사용자 번호. 기동 때 가져온 값이면 NULL. 외래 키 없음',
    created_at DATETIME(6) NOT NULL COMMENT '줄을 처음 만든 시각(UTC)',
    updated_at DATETIME(6) NULL COMMENT '마지막으로 바꾼 시각(UTC)',
    PRIMARY KEY (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '서버 비밀값. 값은 KEK 로 감싼 암호문이다';
