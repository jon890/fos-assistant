-- 승인하면서 준 허락을 그 승인 줄의 칸으로 옮긴다(ADR-20261102 / table-reduce-r2). 옛 두 표는 직전 이미지가 쓰도록 남긴다.
ALTER TABLE connector_action ADD COLUMN grant_scope VARCHAR(16) NULL COMMENT '승인하면서 함께 준 허락의 범위. TOOL 은 그 사용자의 그 플러그인 그 도구에 통하는 상시 허락, CONVERSATION 은 거기에 더해 이 줄의 대화와 에이전트에서만 통하는 대화 허락. 허락을 주지 않았으면 NULL';
ALTER TABLE connector_action ADD COLUMN grant_expires_at DATETIME(6) NULL COMMENT '허락이 끝나는 UTC 시각. 무기한은 없다. grant_scope 가 있는 줄만 채우고 아니면 NULL';
ALTER TABLE connector_action ADD COLUMN grant_revoked_at DATETIME(6) NULL COMMENT '허락을 거둔 UTC 시각. 사용자가 거두었거나, 연결을 해제하거나 다시 등록했거나, 에이전트에서 연결을 뗐거나, 선언이 허락을 닫았거나, 함께 준 실행이 실패한 때다. 거두지 않았으면 NULL';

CREATE INDEX idx_connector_action_grant ON connector_action (grant_scope, user_id, connector_id);

-- 코드가 더는 읽고 쓰지 않는 옛 표가 사용자 정리와 빈 작업 대화 삭제를 막지 않게 외래 키를 뗀다. 표는 DROP PR 이 지운다.
ALTER TABLE connector_tool_grant DROP FOREIGN KEY fk_connector_tool_grant_user;
ALTER TABLE connector_conversation_grant DROP FOREIGN KEY fk_connector_conversation_grant_user;
ALTER TABLE connector_conversation_grant DROP FOREIGN KEY fk_connector_conversation_grant_conversation;
