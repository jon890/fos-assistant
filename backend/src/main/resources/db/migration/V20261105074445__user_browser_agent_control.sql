ALTER TABLE user_browser ADD COLUMN agent_control_at DATETIME(6) NULL COMMENT '에이전트가 이 브라우저를 쓰도록 사용자가 켠 시각(UTC). 꺼져 있으면 NULL';
