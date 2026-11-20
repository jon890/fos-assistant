-- 바인딩의 껐다 켜기 단계와 자동 반영 실패 시각을 둔다(ADR-20261120 / connector-auto-apply).
-- 정의나 값이 바뀐 바인딩은 공유 gateway 재시작 대신 그 profile 의 그 서버만 한 주기 껐다 켠다. 단계는 Control Plane 이 진행한다.
ALTER TABLE agent_connector_binding ADD COLUMN cycle_phase VARCHAR(16) NULL COMMENT '껐다 켜기 단계. IDLE_WAIT 는 주인의 실행이 비기를 기다림, OFF 는 끈 뒤 다시 켜기를 기다림. NULL 이면 껐다 켜기 중이 아니다';
ALTER TABLE agent_connector_binding ADD COLUMN cycle_at DATETIME(6) NULL COMMENT 'IDLE_WAIT 면 단계를 시작한 UTC 시각(기다림 상한의 기준), OFF 면 꺼졌다는 답을 처음 커밋한 UTC 시각. 단계가 없으면 NULL';
ALTER TABLE agent_connector_binding ADD COLUMN apply_failed_at DATETIME(6) NULL COMMENT '자동 반영이 실패한 UTC 시각. 자동 경로가 다시 집지 않고 관리자의 다시 확인을 기다린다. READY 나 사람이 시작한 설치가 비운다';
