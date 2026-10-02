-- 만료 정리와 기동 정리가 상태와 만료 시각으로 찾고, 정책 판정이 같은 실행의 같은 호출을 실행 번호로 찾는다.
-- 판정 줄은 도구 호출마다 한 줄씩 쌓이므로 표 전체를 읽지 않게 한다.
CREATE INDEX idx_connector_action_status_expires ON connector_action (status, expires_at);
CREATE INDEX idx_connector_action_origin_call ON connector_action (origin_execution_id, hermes_tool, args_sha256);
-- 연결을 다시 등록하거나 해제할 때 그 연결의 승인 줄을 상태로 찾는다.
CREATE INDEX idx_connector_action_connection_status ON connector_action (user_id, connector_id, status);
