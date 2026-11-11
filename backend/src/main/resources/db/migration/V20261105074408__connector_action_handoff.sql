-- 플러그인이 사람에게 화면을 넘긴 호출을 그 호출의 판정 줄에 적는다. 화면 넘기기 카드와 알림이 이 칸을 읽는다.
-- 뜻은 backend/docs/data-schema.md 의 「connector_action」 이 갖는다. 새 테이블을 만들지 않는다.
ALTER TABLE connector_action ADD COLUMN handoff_need VARCHAR(20) NULL COMMENT '사람이 해야 하는 단계 LOGIN 로그인, CAPTCHA 보안 확인, DEVICE_CHECK 기기 인증. 넘기지 않은 줄은 NULL';
ALTER TABLE connector_action ADD COLUMN handoff_tab VARCHAR(128) NULL COMMENT '플러그인이 남긴 내 브라우저 탭의 CDP target id. 넘기지 않은 줄은 NULL';
ALTER TABLE connector_action ADD COLUMN handoff_at DATETIME(6) NULL COMMENT '넘기기를 적은 시각(UTC). 넘기지 않은 줄은 NULL';
ALTER TABLE connector_action ADD COLUMN handoff_closed_at DATETIME(6) NULL COMMENT '그 대화의 다음 turn 이 시작돼 카드를 닫은 시각(UTC). 열려 있거나 넘기지 않은 줄은 NULL';
