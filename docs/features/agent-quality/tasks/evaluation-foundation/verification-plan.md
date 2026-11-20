# 배포 뒤 확인 기반(평가 사용자와 판정) 인수 검증 계획

이 PR 은 스펙 `docs/features/agent-quality/spec.md` 의 REQ2 를 사용량 「평가」 한 줄을 뺀 나머지까지, REQ1 은 기능별 판정 규칙까지만 확인한다. REQ1 의 실행과 REQ3 은 실행기 PR, REQ4 와 REQ2 의 사용량 부분은 화면 PR 이 확인한다.

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ1 | 기능별 관찰 값 | `cd backend && ./gradlew test --tests '*DeployCheckJudgeTest'` | 일곱 기능마다 통과 하나와 실패 하나 이상, 출처 절 사례가 `test/model-eval/answer/answer-eval-score.test.ts` 와 같은 기대값, 시간 초과·실행 실패·승인 카드가 기능 규칙보다 먼저 판정됨(부분 확인: 판정 규칙만) | 단위 테스트 | worker | 없음(phase 3 커밋) | PR 전 |
| REQ2 | Hermes 대시보드 대역이 있는 백엔드 통합 환경 | `cd backend && ./gradlew test --tests '*QualityAdminControllerTest' --tests '*EvaluationUserServiceTest'` | 관리자만 준비할 수 있고, 두 번 준비해도 사용자·에이전트·기억이 하나씩이며, 두 에이전트가 `PRIVATE` 이고 다른 사용자의 에이전트 목록에 없음. 관리자 사용자 목록에 「평가용」 표시 | 백엔드 통합 테스트 | worker | 없음(phase 2, 4 커밋) | PR 전 |
| REQ2 | 평가 사용자 행 | `cd backend && ./gradlew test --tests '*AllowedUserResolverEvaluationTest' --tests '*SignInPolicyTest'` | 로그인 확인과 매 요청 인증이 평가 사용자를 거절하고, 예약 작업 경로가 쓰는 `SignInPolicy.revoked` 는 평가 사용자를 거절하지 않음(예약 작업 발화 자체는 실행기 PR 의 e2e 가 본다) | 백엔드 통합 테스트 | worker | 없음(phase 2 커밋) | PR 전 |
| REQ2 | 실행 공간 정책 미등록 대역 | `cd backend && ./gradlew test --tests '*EvaluationUserServiceTest' --tests '*AgentPendingToolsetsTest'` | 준비는 끝나고 상태의 빠진 것에 `POLICY_REGISTRATION_REQUIRED`. 셸·파일 도구가 실행 공간 밖으로 켜지지 않음(Hermes 대역의 실행 공간 밖 쓰기가 셸 계열로 불리지 않음). 평가 사용자 에이전트로는 관리자에게 기본 도구 대기 알림이 가지 않음 | 백엔드 통합 테스트 | worker | 없음(phase 2 커밋) | PR 전 |
| REQ1, REQ2 | 이 PR 의 마이그레이션 | Draft CI 의 `backend-mysql` job(`MysqlMigrationTest`, `PurgeCoverageMysqlTest`, `RepositoryQueryMysqlTest`) | 새 표와 컬럼의 COMMENT, 사용자 삭제 뒤 `quality_run` 이 남고 `requested_by_user_id` 만 비는 것, 새 저장소 쿼리가 MySQL 에서 돈다 | CI 의 실제 MySQL | worker | push: Draft PR | PR 뒤 |
| REQ2 | 운영 배포, 운영 저장소가 평가 profile 을 실행 공간·첨부 정책에 등록함 | 관리자 토큰으로 `POST /api/v1/admin/quality/evaluation-user` 를 부르고 상태를 읽는다 | 빠진 것이 없다. 관리자 사용자 목록에 「평가용」 줄 하나 | 운영 Control Plane | 코디네이터 | 배포: 이 PR 머지와 배포, 운영 저장소 요청 5 | PR 뒤 |
