-- 매일 루프의 동의와 쉬기 시각을 CHECK 작업이 갖게 한다(ADR-20261101 / proactive-suggestion). DML 은 다음 파일이 한다.
-- 루프 동의는 일정의 켜짐과 다른 동의라 작업 상태와 따로 둔다. 기존 줄은 모두 동의 없음이다.
ALTER TABLE task ADD COLUMN loop_enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'CHECK 작업만 쓴다. 사용자가 이 에이전트의 매일 루프에 동의했는가. 일정의 켜짐(state)과 다른 동의다';
ALTER TABLE task ADD COLUMN snoozed_until DATETIME(6) NULL COMMENT 'CHECK 작업만 쓴다. 이 시각(UTC) 전의 깨우기는 매일 루프로 잇지 않는다. NULL 이면 쉬지 않음';
