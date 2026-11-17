-- 중지한 답이 맡긴 하위 에이전트에 중지 알림 줄을 남긴 시각을 결과 전달과 따로 둔다(ADR-20261117 / native-delegation-wake, 스펙 DECISION34).
-- 중지 알림 줄을 남겨도 결과는 아직 전하지 않은 것으로 두고, 사용자가 다음 메시지를 보낼 때 그 turn 입력에 넣는다.
ALTER TABLE hermes_session_binding ADD COLUMN stop_noticed_at DATETIME(6) NULL COMMENT '중지한 답의 알림 줄을 남겼거나 같은 답의 앞 알림에 든 UTC 시각. 결과를 전했는지는 result_delivered_at 이 갖는다';
