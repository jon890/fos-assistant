-- 에이전트의 소개, 아이콘, 색(docs/features/agent-team/spec.md 의 REQ1). 화면과 위임 도구가 쓰고 Hermes 는 읽지 않는다.
-- 비어 있으면 화면이 할 수 있는 일, 이름 첫 글자, code 로 정한 자동 색으로 대신 그린다.
ALTER TABLE agent ADD COLUMN description VARCHAR(60) NULL COMMENT '한 줄 소개. NULL 이면 할 수 있는 일로 대신 보인다';
ALTER TABLE agent ADD COLUMN icon VARCHAR(16) NULL COMMENT '아이콘 목록(AgentIntroduction.ICONS)의 이모지 하나. NULL 이면 이름 첫 글자';
ALTER TABLE agent ADD COLUMN color VARCHAR(16) NULL COMMENT '색 목록(AgentColor)의 이름. NULL 이면 code 로 정한 자동 색';
