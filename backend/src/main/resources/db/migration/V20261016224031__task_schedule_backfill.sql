-- 일정과 발화의 작업 참조를 옮긴다. 사전 검사에서 다른 작업을 가리키는 기록은 거절했다.
UPDATE task SET
    trigger_type = (SELECT s.type FROM task_trigger s WHERE s.task_id = task.id),
    cron_expr = (SELECT s.cron_expr FROM task_trigger s WHERE s.task_id = task.id),
    fire_at = (SELECT s.fire_at FROM task_trigger s WHERE s.task_id = task.id),
    time_zone = (SELECT s.time_zone FROM task_trigger s WHERE s.task_id = task.id),
    missed_policy = (SELECT s.missed_policy FROM task_trigger s WHERE s.task_id = task.id),
    next_fire_at = (SELECT s.next_fire_at FROM task_trigger s WHERE s.task_id = task.id),
    last_fired_at = (SELECT s.last_fired_at FROM task_trigger s WHERE s.task_id = task.id);

UPDATE task_run SET task_id = (SELECT s.task_id FROM task_trigger s WHERE s.id = task_run.trigger_id);
