-- 예약 작업이 발화하는 대화를 고를 모델 단계다. 비면 대화의 선택을 건드리지 않는다(ADR-20261008 / cron-to-task).
ALTER TABLE task ADD COLUMN model_tier VARCHAR(16) NULL;
