-- 켜진 루프 설정의 쉬기 시각을 같은 사용자와 에이전트의 CHECK 작업으로 복사한다.
-- 옛 루프 동의만으로 꺼진 CHECK 작업을 켜지 않는다. 작업의 상태는 건드리지 않는다.
UPDATE task SET
    snoozed_until = (
        SELECT s.snoozed_until FROM proactive_loop_setting s
        WHERE s.user_id = task.owner_user_id AND s.agent_id = task.agent_id AND s.enabled = TRUE)
WHERE kind = 'CHECK'
  AND EXISTS (
        SELECT 1 FROM proactive_loop_setting s
        WHERE s.user_id = task.owner_user_id AND s.agent_id = task.agent_id
          AND s.enabled = TRUE AND s.snoozed_until IS NOT NULL);
