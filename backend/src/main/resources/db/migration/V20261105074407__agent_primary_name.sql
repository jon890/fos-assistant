-- 첫 로그인에 만든 에이전트(기본 비서)의 이름을 사용자 표시 이름에서 「비서」 로 바꾸고 소개를 채운다(docs/features/agent-team/spec.md 의 DECISION5).
-- 사람이 이름을 바꾸지 않았고 주인이 그대로인 줄만 바꾼다. 기본 비서는 그 사람의 기본 profile 을 가리키는 에이전트다.
-- 이관은 사람이 저장한 것이 아니어서 updated_at 은 바꾸지 않는다.
UPDATE agent
SET name = '비서',
    description = COALESCE(description, '무엇이든 먼저 물어보세요. 필요하면 전문 에이전트에게 맡겨요')
WHERE deleted_at IS NULL
  AND EXISTS (
    SELECT 1
    FROM allowed_person p
    JOIN app_user u ON u.email = p.email
    WHERE p.hermes_profile = agent.hermes_profile
      AND p.display_name = agent.name
      AND u.id = agent.owner_user_id);
