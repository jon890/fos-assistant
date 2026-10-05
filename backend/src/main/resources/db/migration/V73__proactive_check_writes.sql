-- 먼저 살펴보기에 쓰기 도구를 허용하는 에이전트별 설정과, 살펴보기를 시작할 때 그 값을 옮겨 적는 칸을 더한다(ADR-082).
-- 칸의 뜻은 docs/backend/schema/users-agents.md 와 docs/backend/schema/proactive.md 가 갖는다.
-- 이미 있는 에이전트와 살펴보기는 읽기 경계 그대로이므로 기본값 거짓으로 둔다.

ALTER TABLE agent ADD COLUMN proactive_check_writes_allowed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE proactive_check ADD COLUMN writes_allowed BOOLEAN NOT NULL DEFAULT FALSE;
