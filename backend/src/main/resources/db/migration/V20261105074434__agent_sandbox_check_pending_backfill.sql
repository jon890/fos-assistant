-- 이 배포 전에 있던 에이전트만 local 셸 점검 대상으로 둔다(ADR-20261120 / no-local-shell).
-- 이 파일은 한 번만 돌므로, 뒤에 운영이 정책에서 profile 을 빼거나 정책을 잘못 고친 채 재시작해도 셸을 끄지 않는다.
-- 사람이 저장한 것이 아니어서 updated_at 은 바꾸지 않는다.
UPDATE agent
   SET sandbox_check_pending = TRUE
 WHERE deleted_at IS NULL;
