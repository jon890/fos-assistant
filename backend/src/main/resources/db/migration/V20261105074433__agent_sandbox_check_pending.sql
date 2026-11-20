-- 배포 때 한 번 local 셸을 점검할 에이전트를 표시한다(ADR-20261120 / no-local-shell, 스펙 agent-tools REQ1, DECISION17).
-- Control Plane 기동 작업은 이 값이 참인 에이전트만 실행 공간 정책 밖일 때 셸 계열을 끄고, 점검을 끝내면 거짓으로 바꾼다. 값을 넣는 일은 다음 파일이 한다.
ALTER TABLE agent ADD COLUMN sandbox_check_pending BOOLEAN NOT NULL DEFAULT FALSE COMMENT '참이면 기동 작업이 local 로 켜졌을 수 있는 셸을 한 번 점검한다. 점검을 끝내면 거짓. 새 에이전트는 거짓으로 만든다';
