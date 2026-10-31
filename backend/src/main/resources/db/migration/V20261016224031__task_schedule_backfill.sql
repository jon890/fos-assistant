-- 일정과 발화의 작업 참조를 옮긴다. 사전 검사에서 다른 작업을 가리키는 기록은 거절했다.
UPDATE task t JOIN task_trigger s ON s.task_id = t.id
SET t.trigger_type = s.type, t.cron_expr = s.cron_expr, t.fire_at = s.fire_at,
    t.time_zone = s.time_zone, t.missed_policy = s.missed_policy,
    t.next_fire_at = s.next_fire_at, t.last_fired_at = s.last_fired_at;

UPDATE task_run r JOIN task_trigger s ON s.id = r.trigger_id SET r.task_id = s.task_id;
