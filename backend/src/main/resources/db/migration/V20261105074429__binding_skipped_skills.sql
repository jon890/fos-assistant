-- 바인딩 설치가 설치하지 못한 에이전트용 스킬과 까닭을 바인딩 줄에 둔다(ADR-20261120 / plugin-agent-skills). 새 테이블을 만들지 않는다.
-- 컬럼의 뜻은 backend/docs/data-schema.md 의 「agent_connector_binding」 이 갖는다.
ALTER TABLE agent_connector_binding ADD COLUMN skipped_skills TEXT NULL COMMENT '마지막으로 성공한 설치가 답한 빠진 에이전트용 스킬 JSON 글. 스킬 이름과 까닭 낱말 목록. 없으면 NULL';
