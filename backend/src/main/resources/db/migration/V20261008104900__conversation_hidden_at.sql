-- 「보고할 것 없음」 으로 끝난 예약 작업 대화를 목록에서 숨긴 시각이다. 비면 목록에 보인다(ADR-20261008 / cron-to-task).
ALTER TABLE conversation ADD COLUMN hidden_at DATETIME(6) NULL;
