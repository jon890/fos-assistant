-- 복사가 끝난 일정에 필수 제약과 조회 색인을 적용한 뒤 옛 표를 없앤다.
ALTER TABLE task
    MODIFY COLUMN trigger_type VARCHAR(20) NOT NULL COMMENT '일정 종류: CRON 반복, ONCE 한 번',
    MODIFY COLUMN time_zone VARCHAR(64) NOT NULL COMMENT '일정을 해석하는 IANA 시간대 이름',
    MODIFY COLUMN missed_policy VARCHAR(20) NOT NULL COMMENT '놓친 발화 처리: SKIP 건너뜀, RUN_ONCE 한 번 실행',
    ADD INDEX idx_task_due (state, next_fire_at, id);

ALTER TABLE task_run
    DROP FOREIGN KEY fk_task_run_trigger,
    DROP INDEX uk_task_run_trigger_scheduled,
    DROP INDEX idx_task_run_task_scheduled,
    ADD UNIQUE KEY uk_task_run_task_scheduled (task_id, scheduled_for),
    DROP COLUMN trigger_id;

DROP TABLE task_trigger;
