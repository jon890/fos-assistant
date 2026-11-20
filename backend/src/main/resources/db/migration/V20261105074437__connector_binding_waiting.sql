-- 바인딩의 자동 대기 시작 시각과 관리자 알림 시각을 둔다(ADR-20261120 / connector-auto-apply).
-- 자동 반영이 실패했거나 대기가 생긴 뒤 24시간 안에 READY 가 되지 않은 바인딩에만 그룹 관리자에게 한 번 알린다.
ALTER TABLE agent_connector_binding ADD COLUMN waiting_since DATETIME(6) NULL COMMENT '자동 대기(재시작 대기, 껐다 켜기, 반영 예정, 자동 반영 실패)가 시작된 UTC 시각. 비어 있을 때만 적고 단계가 바뀌어도 그대로 둔다. READY 와 사람이 시작한 설치가 비운다';
ALTER TABLE agent_connector_binding ADD COLUMN admin_notified_at DATETIME(6) NULL COMMENT '지금 대기에 관리자 알림을 보낸 UTC 시각. 한 대기에 한 번만 보낸다. waiting_since 가 비워지면 함께 비운다';
