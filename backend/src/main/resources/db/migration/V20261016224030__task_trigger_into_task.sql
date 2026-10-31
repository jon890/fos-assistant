-- 작업마다 일정 하나가 있고 발화가 같은 작업의 일정을 가리키는지 컬럼 추가 전에 검사한다.
DROP PROCEDURE IF EXISTS assert_task_schedule_migration;
DELIMITER //
CREATE PROCEDURE assert_task_schedule_migration()
BEGIN
    IF EXISTS (
        SELECT t.id FROM task t LEFT JOIN task_trigger s ON s.task_id = t.id
        GROUP BY t.id HAVING COUNT(s.id) <> 1
    ) OR EXISTS (
        SELECT r.id FROM task_run r LEFT JOIN task_trigger s ON s.id = r.trigger_id
        WHERE s.id IS NULL OR r.task_id <> s.task_id
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'task schedule migration requires one matching schedule per task';
    END IF;
END//
DELIMITER ;
CALL assert_task_schedule_migration();
DROP PROCEDURE assert_task_schedule_migration;

ALTER TABLE task
    ADD COLUMN trigger_type VARCHAR(20) NULL COMMENT '일정 종류: CRON 반복, ONCE 한 번',
    ADD COLUMN cron_expr VARCHAR(100) NULL COMMENT '반복 일정의 5필드 cron, ONCE면 NULL',
    ADD COLUMN fire_at DATETIME(6) NULL COMMENT '한 번 실행할 UTC 시각, CRON이면 NULL',
    ADD COLUMN time_zone VARCHAR(64) NULL COMMENT '일정을 해석하는 IANA 시간대 이름',
    ADD COLUMN missed_policy VARCHAR(20) NULL COMMENT '놓친 발화 처리: SKIP 건너뜀, RUN_ONCE 한 번 실행',
    ADD COLUMN next_fire_at DATETIME(6) NULL COMMENT '다음 예정 UTC 시각, 끝난 ONCE나 다음 일정이 없으면 NULL',
    ADD COLUMN last_fired_at DATETIME(6) NULL COMMENT '마지막으로 처리한 예정 UTC 시각, 처리 전이면 NULL';
