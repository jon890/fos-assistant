-- 에이전트가 이 브라우저를 쓰도록 사용자가 켠 시각이다. 비어 있으면 꺼짐이다(docs/features/agent-browser/design.md 의 「PR1 스위치」).
ALTER TABLE user_browser ADD COLUMN agent_control_at DATETIME(6) NULL;
