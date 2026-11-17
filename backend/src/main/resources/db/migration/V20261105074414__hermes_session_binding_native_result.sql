-- Hermes 내장 위임(delegate_task) 하위 에이전트의 끝 소식과 결과를 등록 줄에 둔다(ADR-20261117 / native-delegation-wake).
-- 새 칸은 모두 선택이다. 옛 등록 줄은 wake_eligible 이 거짓이라 깨우지도 기한을 세지도 않는다.
ALTER TABLE hermes_session_binding ADD COLUMN wake_eligible BOOLEAN NOT NULL DEFAULT FALSE COMMENT '끝 소식으로 부모 대화를 깨울 최상위 자식인가. 등록할 때 한 번 정하고 바꾸지 않는다';
ALTER TABLE hermes_session_binding ADD COLUMN goal_preview VARCHAR(512) NULL COMMENT '맡긴 일의 앞 40자. 암호화가 켜져 있으면 암호문이고 key 는 result_key_id 다. 깨울 자식에만 적고 비면 없음';
ALTER TABLE hermes_session_binding ADD COLUMN stopped_at DATETIME(6) NULL COMMENT '끝 소식을 받은 UTC 시각. 비면 아직 소식이 없다';
ALTER TABLE hermes_session_binding ADD COLUMN result_status VARCHAR(16) NULL COMMENT 'SUCCEEDED, FAILED. 끝 소식의 child_status 가 completed 면 SUCCEEDED 다. 비면 소식이 없다';
ALTER TABLE hermes_session_binding ADD COLUMN result_text LONGTEXT NULL COMMENT '끝 소식의 결과 글을 위임 답 상한으로 자른 것. 암호화가 켜져 있으면 암호문이다. 대화를 정리하면 비운다';
ALTER TABLE hermes_session_binding ADD COLUMN result_key_id BIGINT NULL COMMENT 'goal_preview 와 result_text 를 암호화한 user_data_key.id. 비면 평문이거나 글이 없다';
ALTER TABLE hermes_session_binding ADD COLUMN result_delivered_at DATETIME(6) NULL COMMENT '자동 turn 이 결과를 전했거나 중지 알림으로 끝냈거나 설정이 꺼져 전하지 않기로 한 UTC 시각';
ALTER TABLE hermes_session_binding ADD COLUMN timeout_noticed_at DATETIME(6) NULL COMMENT '1시간 기한 알림 줄을 남겼거나 같은 turn 의 앞 알림에 든 UTC 시각';
ALTER TABLE hermes_session_binding ADD COLUMN notice_message_id BIGINT NULL COMMENT '이 결과를 처음 전한 알림 줄(chat_message.id). 다시 전달은 바꾸지 않는다. 메시지를 지워도 남으므로 외래 키를 두지 않는다';
CREATE INDEX idx_hermes_session_binding_origin ON hermes_session_binding (origin_execution_id);
CREATE INDEX idx_hermes_session_binding_wake ON hermes_session_binding (wake_eligible, result_delivered_at, created_at);
