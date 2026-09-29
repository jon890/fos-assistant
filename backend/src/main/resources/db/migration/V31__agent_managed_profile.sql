-- 에이전트의 profile 을 Control Plane 이 만들었는지와 지운 시각을 적는 칸을 더한다(ADR-033).
-- profile_managed 가 참인 에이전트만 지울 때 Hermes profile 까지 거둔다. 관리자가 운영에서 만든 profile 은 남긴다.
-- 에이전트 행은 지우지 않는다. 대화, 실행, 사용량이 agent_id 로 이름을 읽기 때문이다. 지우면 deleted_at 만 적는다.
-- 이미 있는 에이전트는 관리자가 운영에서 만든 profile 이나 사용자의 기본 profile 을 가리키므로 기본값 거짓 그대로 둔다.

ALTER TABLE agent ADD COLUMN profile_managed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE agent ADD COLUMN deleted_at DATETIME(6) NULL;
