-- 바인딩에 반영 예정 시각 칸을 더한다(ADR-20261007 / connector-live-reload).
-- 새 MCP 서버나 스킬만 바꾼 바인딩 설치는 공유 gateway 를 재시작하지 않고 MCP 설정 맞추기 주기가 반영한다.
-- Control Plane 이 이 시각이 지나면 그 바인딩의 반영 맞추기를 스스로 돌려 READY 나 PENDING 으로 둔다. 예정이 없으면 비어 있다.
ALTER TABLE agent_connector_binding ADD COLUMN apply_due_at DATETIME(6) NULL;
