-- Hermes 하위 에이전트 session 이 어느 FOS 실행(origin 실행)에서 시작됐는지 적는 표를 만든다(ADR-037).
-- 한 번 적은 줄은 바꾸지 않는다. 한 profile 안에서 한 session 은 한 origin 에만 속한다.
-- 외래 키는 두지 않는다. 실행 줄과 사용자는 지우지 않고, 등록은 서버가 방금 읽은 실행에서 옮겨 적는다.
CREATE TABLE hermes_session_binding (
    id BIGINT NOT NULL AUTO_INCREMENT,
    profile_name VARCHAR(64) NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    user_id BIGINT NOT NULL,
    origin_execution_id BIGINT NOT NULL,
    root_session_id VARCHAR(128) NOT NULL,
    parent_session_id VARCHAR(128) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_hermes_session_binding_session UNIQUE (profile_name, session_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
