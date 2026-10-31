-- 매일 루프의 쉬기 시각을 CHECK 작업이 갖게 한다(ADR-20261101 / proactive-suggestion). DML 은 다음 파일이 한다.
ALTER TABLE task
    ADD COLUMN snoozed_until DATETIME(6) NULL COMMENT 'CHECK 작업만 쓴다. 이 시각(UTC) 전의 깨우기는 매일 루프로 잇지 않는다. NULL 이면 쉬지 않음';
