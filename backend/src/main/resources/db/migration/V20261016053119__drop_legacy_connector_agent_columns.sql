-- vault 이관을 마친 옛 전용 에이전트의 식별 칸과 연결에 남은 설치 상태를 제거한다(ADR-083).
-- 연결과 바인딩 행은 유지하고 현재 설치 상태는 agent_connector_binding이 갖는다.
ALTER TABLE connector_connection DROP FOREIGN KEY fk_connector_connection_agent;
ALTER TABLE connector_connection DROP INDEX uk_connector_connection_agent;
ALTER TABLE connector_connection DROP COLUMN agent_id;
ALTER TABLE connector_connection DROP COLUMN restart_required;
ALTER TABLE connector_connection DROP COLUMN desired_enabled;

ALTER TABLE agent DROP COLUMN connector_managed;
ALTER TABLE agent DROP COLUMN connector_attachments;
