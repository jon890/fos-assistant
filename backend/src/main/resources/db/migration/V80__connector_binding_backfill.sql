-- 이미 있는 연결마다 그 연결 전용 에이전트와의 바인딩을 만든다. 옛 커넥터 에이전트가 배포 뒤에도 지금처럼 돌게 한다(ADR-083).
-- 상태와 재시작 대기, 켜려는 의도, 시각은 연결의 값을 그대로 옮긴다. 재시작 대기의 시작 시각은 알 수 없어 연결의 updated_at 으로 둔다.
-- 해제된 연결은 그 에이전트가 이미 꺼져 있어 바인딩을 만들지 않는다. 서버 이름은 여기서 알 수 없어 비우고 연결 확인이 채운다.
INSERT INTO agent_connector_binding (
    agent_id, connection_id, mcp_server, status,
    restart_required, restart_required_since, desired_enabled, checked_at, created_at, updated_at
)
SELECT
    agent_id,
    id,
    NULL,
    status,
    restart_required,
    CASE WHEN restart_required THEN updated_at ELSE NULL END,
    desired_enabled,
    checked_at,
    created_at,
    updated_at
FROM connector_connection
WHERE status <> 'DISCONNECTED';
