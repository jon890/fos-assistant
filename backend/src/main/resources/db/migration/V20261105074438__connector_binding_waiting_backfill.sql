-- 앞 마이그레이션 전부터 PENDING 이던 바인딩에 대기 시각을 채운다(ADR-20261120 / connector-auto-apply).
-- 비워 두면 그 줄은 24시간 알림과 관리자 다시 확인 단추의 대상이 되지 못한다. 재시작 대기, 껐다 켜기 시작, 마지막 상태 시각 차례로 쓴다.
UPDATE agent_connector_binding
SET waiting_since = COALESCE(restart_required_since, cycle_at, updated_at)
WHERE status = 'PENDING' AND waiting_since IS NULL;
