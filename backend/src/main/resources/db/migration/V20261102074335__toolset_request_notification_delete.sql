-- 도구 사용 요청이 만든 알림 줄을 지운다(ADR-20261102 / table-reduce-r2 D5).
-- 해당 enum 값을 코드에서 지웠으므로 남은 줄이 있으면 알림 목록을 읽을 때 enum 변환이 실패한다.
DELETE FROM notification
WHERE kind IN ('TOOLSET_REQUESTED', 'TOOLSET_REQUEST_DECIDED')
   OR target_type IN ('ADMIN_TOOL_REQUEST', 'TOOLSET_REQUEST');
