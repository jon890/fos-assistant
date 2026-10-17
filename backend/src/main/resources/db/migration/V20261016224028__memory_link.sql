-- 기억 두 개 사이의 연결이다(ADR-20261015 / memory-link). 종류는 Control Plane 이 다르게 동작하는 SUPERSEDES 와 RELATED 둘이고,
-- 연결의 뜻은 자유 글 label 에 둔다. 양 끝에 외래 키를 걸지 않고 항목을 지우면 서비스가 같은 트랜잭션에서 지운다.
CREATE TABLE memory_link (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '연결 번호',
    from_memory_id BIGINT NOT NULL COMMENT 'SUPERSEDES 면 대신하는 새 항목, RELATED 면 번호가 작은 쪽 항목',
    to_memory_id BIGINT NOT NULL COMMENT 'SUPERSEDES 면 대신 받는 옛 항목, RELATED 면 번호가 큰 쪽 항목',
    type VARCHAR(20) NOT NULL COMMENT '연결 종류. SUPERSEDES 나 RELATED',
    label VARCHAR(200) NULL COMMENT 'RELATED 연결의 뜻을 적은 자유 글. Control Plane 은 이 글로 판정하지 않는다',
    origin VARCHAR(20) NOT NULL COMMENT '연결을 만든 쪽. AUTO(코드가 찾음), AGENT, HUMAN',
    status VARCHAR(20) NOT NULL COMMENT 'PROPOSED(사람의 승인 전), ACTIVE(효력 있음), REVOKED(끊었거나 거절함)',
    score DOUBLE NULL COMMENT '코드가 찾은 연결의 유사도. 사람과 에이전트가 맺은 연결은 비어 있다',
    created_by_execution_id BIGINT NULL COMMENT '에이전트가 맺은 연결의 origin 실행 번호',
    created_at DATETIME(6) NOT NULL COMMENT '줄을 처음 넣은 시각',
    updated_at DATETIME(6) NOT NULL COMMENT '상태를 마지막으로 바꾼 시각',
    revoked_at DATETIME(6) NULL COMMENT '끊었거나 거절한 시각. 다시 맺으면 비운다',
    CONSTRAINT uk_memory_link_pair UNIQUE (from_memory_id, to_memory_id, type),
    INDEX idx_memory_link_to (to_memory_id, type, status),
    INDEX idx_memory_link_from (from_memory_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '기억 항목 두 개 사이의 대체와 관련 연결';

ALTER TABLE memory_capture
    ADD COLUMN external_read BOOLEAN NOT NULL DEFAULT FALSE COMMENT '기록을 남긴 대화가 그 전에 바깥 글을 읽는 도구를 불렀는가. 승인 카드가 이 값을 보인다';
