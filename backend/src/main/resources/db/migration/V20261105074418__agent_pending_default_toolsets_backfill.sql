-- 이미 있는 기본 비서에 「그림 만들기」 를 한 번 켜려고 기다리는 목록에 넣는다(스펙 generated-image REQ11, DECISION23).
-- 기본 비서는 주인의 기본 profile(allowed_person.hermes_profile)을 가리키는, 주인 혼자 쓰는 에이전트다.
-- 이 파일은 한 번만 돌므로 관리자가 끈 뒤의 배포와 재시작이 다시 켜지 않는다. 사람이 저장한 것이 아니어서 updated_at 은 바꾸지 않는다.
UPDATE agent
   SET pending_default_toolsets = 'image_gen'
 WHERE deleted_at IS NULL
   AND visibility = 'PRIVATE'
   AND owner_user_id IS NOT NULL
   AND EXISTS (
       SELECT 1
         FROM app_user u
         JOIN allowed_person p ON p.email = u.email
        WHERE u.id = agent.owner_user_id
          AND p.hermes_profile = agent.hermes_profile);
