-- 커넥터 도구 호출을 Control Plane 이 판정하기 시작한다(ADR-048).
--
-- 1. 마지막 연결 확인에서 MCP 서버가 낸 도구 가운데 manifest 가 선언하지 않은 수를 적을 칸을 더한다.
-- 2. 그때까지 READY 이던 연결을 모두 PENDING 으로 내리고 그 연결용 에이전트를 끄고 사진 받기를 내린다.
--    그 profile 의 hook 은 옛 판이라 판정을 묻지 않는다. READY 로 두면 도구 호출이 판정 없이 나간다.
--
-- 되돌리는 방법: 사용자가 연결 확인을 누르면 설치가 다시 가서 hook 이 새 판이 되고 재시작 대기가 된다.
-- 관리자가 공유 gateway 를 재시작하고 반영 완료를 누르면 READY 로 돌아온다.
-- restart_required 는 건드리지 않는다. 참으로 두면 연결 확인이 설치를 다시 보내지 않아 hook 이 새 판이 되지 않는다.
ALTER TABLE connector_connection ADD COLUMN undeclared_tools INT NOT NULL DEFAULT 0;

-- 연결 표보다 먼저 고친다. 아래 UPDATE 뒤에는 어느 연결이 READY 였는지 알 수 없다.
UPDATE agent SET enabled = FALSE, connector_attachments = FALSE
 WHERE id IN (SELECT agent_id FROM connector_connection WHERE status = 'READY');

UPDATE connector_connection SET status = 'PENDING' WHERE status = 'READY';
