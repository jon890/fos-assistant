-- MCP 토큰이 어느 Hermes profile 의 것인지 적는 칸을 더한다(ADR-032).
-- 토큰은 profile 만 증명하고, 사용자가 걸린 도구의 요청자는 서명한 _fos_ctx 로 찾은 부모 실행의 사용자다.
-- 새 토큰은 profile 로만 발급하고 user_id 를 비워 두므로 user_id 의 NOT NULL 을 푼다.
-- 이미 있는 토큰의 profile_name 은 여기서 채우지 않는다. 어느 토큰이 어느 profile 의 것인지는 운영 값이라
-- 마이그레이션에 적지 않고 관리 API(PUT /api/v1/admin/agent-tokens/{id}/profile)로 채운다.
-- profile_name 이 빈 채 남은 줄은 옛 토큰이고, assistant.mcp.legacy-user-tokens 가 참일 때만 user_id 로 돈다.

ALTER TABLE agent_token ADD COLUMN profile_name VARCHAR(64) NULL;
ALTER TABLE agent_token MODIFY COLUMN user_id BIGINT NULL;
