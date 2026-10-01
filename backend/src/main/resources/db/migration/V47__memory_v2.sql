-- Memory 를 collection, 종류, 꺼내는 방식, 민감도, 판, 출처로 넓힌다(ADR-051).
-- 기존 행은 모두 core collection 의 MEMORY 이고 판은 1 이다.
ALTER TABLE memory ADD COLUMN collection VARCHAR(64) NOT NULL DEFAULT 'core';
ALTER TABLE memory ADD COLUMN entry_type VARCHAR(20) NOT NULL DEFAULT 'MEMORY';
ALTER TABLE memory ADD COLUMN document_key VARCHAR(128) NULL;
ALTER TABLE memory ADD COLUMN retrieval VARCHAR(20) NOT NULL DEFAULT 'SEARCH';
ALTER TABLE memory ADD COLUMN sensitivity VARCHAR(20) NOT NULL DEFAULT 'NORMAL';
ALTER TABLE memory ADD COLUMN revision INT NOT NULL DEFAULT 1;
ALTER TABLE memory ADD COLUMN source_type VARCHAR(32) NULL;
ALTER TABLE memory ADD COLUMN source_ref VARCHAR(512) NULL;
ALTER TABLE memory ADD COLUMN source_date DATE NULL;

-- always_inject 는 한 배포 동안 남긴다. 옛 판으로 되돌려도 그 칸으로 동작한다.
UPDATE memory SET retrieval = 'ALWAYS' WHERE always_inject = TRUE;

-- DOCUMENT 는 주인과 collection 안에서 document_key 가 하나다. document_key 가 비어 있는 MEMORY 와 SOURCE 는 걸리지 않는다.
-- 주인 칸이 범위에 따라 갈려 색인을 둘로 둔다. 비어 있는 주인 칸은 유일 검사에 들지 않는다.
CREATE UNIQUE INDEX uk_memory_user_document ON memory (owner_user_id, collection, document_key);
CREATE UNIQUE INDEX uk_memory_group_document ON memory (group_id, collection, document_key);

-- 고치거나 지울 때 그 전의 판을 남긴다. 지운 뒤에도 남으므로 memory 에 외래 키를 걸지 않는다.
CREATE TABLE memory_revision (
    memory_id BIGINT NOT NULL,
    revision INT NOT NULL,
    change_type VARCHAR(20) NOT NULL,
    scope VARCHAR(20) NOT NULL,
    owner_user_id BIGINT NULL,
    group_id BIGINT NULL,
    collection VARCHAR(64) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    retrieval VARCHAR(20) NOT NULL,
    sensitivity VARCHAR(20) NOT NULL,
    changed_by_user_id BIGINT NULL,
    reason VARCHAR(200) NULL,
    changed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (memory_id, revision)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 그룹이 쓰는 collection 의 목록이다. 화면의 탭과 에이전트 접근 설정이 읽는다. 정책 칸은 두지 않는다.
CREATE TABLE memory_collection (
    group_id BIGINT NOT NULL,
    collection_key VARCHAR(64) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    sort_order INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (group_id, collection_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO memory_collection (group_id, collection_key, display_name, sort_order, created_at)
SELECT g.group_id, d.collection_key, d.display_name, d.sort_order, CURRENT_TIMESTAMP(6)
FROM (SELECT DISTINCT group_id FROM app_user WHERE group_id IS NOT NULL) g
CROSS JOIN (
    SELECT 'core' AS collection_key, '기본' AS display_name, 1 AS sort_order
    UNION ALL SELECT 'career', '커리어', 2
    UNION ALL SELECT 'learning', '학습', 3
    UNION ALL SELECT 'health', '건강', 4
    UNION ALL SELECT 'finance', '재무', 5
    UNION ALL SELECT 'home', '집', 6
    UNION ALL SELECT 'identity', '신원', 7
) d;

-- 에이전트가 받는 collection 이다(ADR-052). 줄이 없는 에이전트는 Memory 를 받지 않는다.
CREATE TABLE agent_memory_collection (
    agent_id BIGINT NOT NULL,
    collection VARCHAR(64) NOT NULL,
    allow_sensitive BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (agent_id, collection),
    CONSTRAINT fk_agent_memory_collection_agent FOREIGN KEY (agent_id) REFERENCES agent(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 기존 Memory 가 모두 core 로 가므로, 커넥터 에이전트가 아닌 모든 에이전트에 core 를 주면 주입 결과가 그대로다.
-- 커넥터 에이전트는 이 표와 관계없이 Memory 를 받지 않는다(ADR-045).
INSERT INTO agent_memory_collection (agent_id, collection, allow_sensitive, created_at)
SELECT id, 'core', FALSE, CURRENT_TIMESTAMP(6) FROM agent WHERE connector_managed = FALSE;
