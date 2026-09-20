-- 로그인할 수 있는 사람의 목록이다. 여기 없는 주소는 토큰을 받지 못한다.
--
-- app_user 와 나누어 둔다. 허용한 시점에는 app_user 가 아직 없고,
-- 목록에서 빼도 실행 기록이 가리키는 app_user 는 남아야 한다.
-- 그래서 둘을 잇는 것은 email 하나이고 외래 키를 두지 않는다.
--
-- hermes_profile 이 유일한 이유는 두 사람이 같은 profile 을 쓰면 격리가 깨지기 때문이다.
-- enabled 에 기본값을 두지 않는다. 행을 넣는 쪽이 정한다.
--
-- 이 표가 비어 있으면 아무도 들어오지 못한다.
-- 지금 쓰는 주소를 넣는 절차는 비공개 저장소가 소유한다. 이 파일에 주소를 적지 않는다.
CREATE TABLE allowed_person (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(320) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    hermes_profile VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_allowed_person_email (email),
    UNIQUE KEY uk_allowed_person_hermes_profile (hermes_profile)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
