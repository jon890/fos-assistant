-- 켜진 루프 설정의 동의와 쉬기 시각을 같은 사용자와 에이전트의 CHECK 작업으로 복사한다.
-- 꺼진 설정은 작업을 바꾸지 않아 동의 없음으로 남고, 작업의 상태(일정의 켜짐)는 어느 경우에도 건드리지 않는다.
-- 켜진 설정에 짝지을 CHECK 작업이 없으면 옮길 곳이 없어 동의 없음이 된다. 그 사용자는 일정이 없어 깨우기가 돌지 않았다.
UPDATE task SET
    loop_enabled = TRUE,
    snoozed_until = (
        SELECT s.snoozed_until FROM proactive_loop_setting s
        WHERE s.user_id = task.owner_user_id AND s.agent_id = task.agent_id AND s.enabled = TRUE)
WHERE kind = 'CHECK'
  AND EXISTS (
        SELECT 1 FROM proactive_loop_setting s
        WHERE s.user_id = task.owner_user_id AND s.agent_id = task.agent_id AND s.enabled = TRUE);
