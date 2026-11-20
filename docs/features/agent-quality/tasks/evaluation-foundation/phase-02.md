# Phase 02. 평가 전용 사용자 준비와 숨기기

**Execution profile**: standard

## 목표

관리자 요청 하나로 평가 전용 사용자(허용 목록 행, Hermes profile, `app_user`, 기본 에이전트와 위임 대상 에이전트, 도구, 미리 넣는 기억)를 준비하고, 그 사용자가 로그인하거나 일반 화면에 섞이지 않게 한다.

**범위 외**: 관리자 API(phase 4), 관리자 화면의 준비 단추와 사용량 「평가」 한 줄(뒤 화면 PR), 실행기(뒤 PR).

## 컨텍스트

**요구사항**: REQ2

**근거 문서**: `docs/features/agent-quality/design.md` 의 「평가 전용 사용자」, 「숨기는 곳」, `docs/features/users.md` 의 「흐름」

- 준비 순서(설계): (1) `people/application/PersonRegistrar.register(email, displayName, hermesProfile)` 가 허용 목록 행과 profile, key, MCP 토큰을 만든다. (2) `user/application/UserProvisioningService.resolve(email, displayName)` 가 `app_user`(역할 `MEMBER`)를 만들고 `people/application/FirstAgentCreator`(첫 로그인 리스너)가 `PRIVATE` 기본 에이전트를 만든다. (3) 같은 트랜잭션에서 phase 1 의 `AppUserRepository.markEvaluation`. (4) `agent/application/AgentLifecycleService.create(user, name, AgentVisibility.PRIVATE)` 로 위임 대상 에이전트. (5) 커밋한 뒤 `agent/application/AgentDefaultToolsets.enablePending(agent, List.of("image_gen", "vision"))` 로 그림과 사진 도구를 **실행 공간 안에서만** 켠다. 이 메서드는 실행 공간 쓰기(`writeApiServerInSandbox`)만 하고, 실행 공간이 없으면 다른 도구만 켜는 쓰기를 하지 않고 예외를 던진다. `AgentToolService.writeAsAdmin` 은 쓰지 않는다. 그 메서드는 정책에 등록되지 않은 profile 에도 셸·파일 도구를 격리 없이 켤 수 있어(`hermes/docs/hermes-contract.md` 의 실행 공간 절) 다른 profile 의 파일에 닿는다. 나머지 기본 도구는 첫 에이전트를 만들 때 `FirstAgentCreator` 가 실행 공간 여부를 보고 켠다. `delegation` 은 켜지 않는다. (6) `memory/application/MemoryService.create(evalUser, MemoryScope.USER, title, content, true)` 로 미리 넣는 기억 한 줄. 늘 넣는 기억(`alwaysInject` 참)으로 두어 검색 결과에 기대지 않게 한다. 기억 정비 작업은 제안 모드라 이 줄을 바꾸지 않는다.
- **단계마다 부르는 사람.** (1) 은 요청한 관리자의 `CurrentUser`, (5) 는 에이전트만 받는다. (4)(6) 은 평가 사용자의 `CurrentUser`(`AppUser` 에서 만든다. 선례 `chat/nextturn/application/DelegationWakeService`). `AgentLifecycleService.create` 는 요청자를 주인으로 적기 때문이다.
- **도구가 켜지지 않을 때.** `enablePending` 이 `ApiException`(예: `AGENT_SANDBOX_UNAVAILABLE`, 켜지지 않음)을 던지면 잡아 상태의 빠진 것에 `POLICY_REGISTRATION_REQUIRED` 를 적고 나머지 준비는 끝낸다. 상태 판정은 `AgentToolService.readAsAdmin(admin, code)` 의 지금 켜진 목록에 `image_gen`, `vision` 이 있는지와, 셸 계열(`terminal`, `file`, `code_execution`)이 켜졌다면 실행 공간에서 켜졌는지(정책 미등록이면 `FirstAgentCreator` 가 셸 계열을 빼고 켠다)로 한다. 첫 에이전트의 기본 도구는 커밋 뒤 백그라운드로 켜지고 읽고 고쳐 쓰는 방식이라 (5) 와 겹치면 한쪽 이름이 빠질 수 있다. 상태를 다시 읽어 빠졌으면 다시 준비하면 회복된다(재시도 안전).
- **트랜잭션.** `prepare` 에는 `@Transactional` 을 달지 않는다. (1) 의 Hermes 호출은 트랜잭션 밖, (2)(3) 만 `TransactionTemplate` 으로 한 트랜잭션에 묶는다(같은 클래스 안 호출은 프록시를 거치지 않는다). (4)(5)(6) 은 각 서비스의 트랜잭션을 쓴다.
- **기본 에이전트 찾기.** 첫 에이전트의 code 는 profile 이름이다(`FirstAgentCreator`). 평가 사용자가 이미 있으면 요청의 `hermesProfile` 은 무시하고 처음 만든 profile 로 빠진 것만 채운다. 상태에 지금 profile 이름을 담는다.
- 주소는 상수 `evaluation@quality.invalid`, 표시 이름은 「평가 사용자」 다. profile 이름은 운영이 실행 공간·첨부 정책에 등록한 이름을 관리자가 요청 본문으로 준다(설계 「운영 저장소에 넘길 요청」 5). 코드와 문서에 운영의 profile 이름을 적지 않는다.
- 이미 있으면 빠진 것만 채운다(재시도 안전). 다시 부르면 (5) 를 다시 시도한다.
- 숨기기(설계 「숨기는 곳」): 로그인 확인(`/api/v1/signin/allowed`, `/completed`)은 `people/application/SignInPolicy.admit` 과 `recordCompletion` 이 평가 사용자 주소를 거절한다. 컨트롤러가 저장소를 바로 읽으면 층 방향 규칙에 걸리므로 판정은 `SignInPolicy` 에 둔다. **`SignInPolicy.revoked` 에는 넣지 않는다.** `task/application/TaskRunStarter` 가 그 메서드로 주인을 확인해, 넣으면 평가 사용자의 예약 작업 발화가 늘 건너뛰어진다. 매 요청 인증은 `user/application/AllowedUserResolver.resolveAllowed` 가 평가 사용자면 `Optional.empty()` 를 돌려준다(이 메서드는 예약 작업 경로가 쓰지 않는다). `people/application/PersonAccessService.list` 의 `PersonAccess` 에 `evaluation` 표시를 더한다. `agent/application/AgentPendingToolsets` 의 관리자 알림에서 평가 사용자 주인의 에이전트를 뺀다. 에이전트는 둘 다 `PRIVATE` 이라 다른 사용자의 고르기·멘션·위임 대상에 보이지 않는다(`AgentService.readableBy`).
- 관리자 API 는 phase 4 가 만든다. 이 phase 는 `quality/application/EvaluationUserService` 의 `prepare(CurrentUser admin, String hermesProfile)` 와 `status(CurrentUser admin)` 까지다.

## 의도 메모

- 역할 값에 새 등급을 더하는 안은 권한 판정 코드 전체를 건드려 기각했다(설계 DECISION2).
- 평가 사용자의 실행을 다른 사용자가 보는 길은 만들지 않는다.

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/quality/application/EvaluationUserService.java`

위 순서의 `prepare` 와 `status`(준비됨, 빠진 것 목록: 사용자, 위임 대상 에이전트, 도구, 기억, 정책 등록). 위임 대상 에이전트는 이름 상수(「평가 위임 대상」)로 찾는다. 기억은 제목 상수로 찾는다.
값 record 는 `quality/application/dto/EvaluationUserStatus.java`.

### 2. 로그인 거절

`SignInPolicy.admit`, `recordCompletion` 과 `AllowedUserResolver.resolveAllowed` 가 평가 사용자를 거절한다. 판정은 `AppUserRepository.findByEmail` 의 `evaluation()` 이다. 사용자 행이 아직 없는 주소의 동작은 바꾸지 않는다. `revoked` 는 바꾸지 않는다.

### 3. 관리자 사용자 목록 표시와 알림 제외

`PersonAccessService.list` 의 항목에 `evaluation` 칸을 더하고 `admin/people/presentation/PeopleAdminDtos.java` 의 응답에 담는다. `AgentPendingToolsets.notice` 와 `resolveNotices` 가 주인이 평가 사용자면 건너뛴다.

### 4. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/quality/application/EvaluationUserServiceTest.java`: Hermes 대시보드 대역(`people` 테스트의 기존 대역 패턴)으로 `prepare` 가 사용자·두 에이전트·기억을 만들고 `evaluation` 이 참이다. 두 번 불러도 하나씩이다. 정책 미등록 대역(실행 공간 쓰기가 `AGENT_SANDBOX_UNAVAILABLE` 을 던짐)이면 준비는 끝나고 상태에 `POLICY_REGISTRATION_REQUIRED` 가 있으며, Hermes 대역의 실행 공간 밖 도구 쓰기(`writeApiServer`)가 셸 계열 이름으로 불리지 않았다.
- `backend/src/test/java/com/bifos/assistant/user/application/AllowedUserResolverEvaluationTest.java`(`BackendIntegrationTest`): 평가 사용자 주소는 `resolveAllowed` 가 비어 있다. 보통 사용자는 그대로다.
- `backend/src/test/java/com/bifos/assistant/people/SignInPolicyTest.java` 에 평가 사용자 주소는 `admit` 이 비고 `recordCompletion` 이 거짓이며 `revoked` 는 거짓 그대로인 사례를 더한다.
- 관리자 사용자 목록 응답의 `evaluation` 칸은 phase 4 의 컨트롤러 테스트가 본다. 이 phase 는 `PersonAccessService.list` 의 항목 값을 `EvaluationUserServiceTest` 에서 확인한다.
- 기존 `AgentPendingToolsets` 테스트에 평가 사용자 주인의 에이전트는 알림을 남기지 않는 사례를 더한다.

## 검증

```bash
cd backend && ./gradlew test --tests '*EvaluationUserServiceTest' --tests '*AllowedUserResolverEvaluationTest' --tests '*AgentPendingToolsets*' --tests '*SignIn*' --tests '*PersonAccess*' --tests '*TaskRunStarter*' --tests '*PersonActivityTest' --tests '*RevokedUserTest' --tests '*AgentToolServiceTest'
cd backend && ./gradlew archTest
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/quality/application/EvaluationUserService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/application/dto/EvaluationUserStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/people/application/SignInPolicy.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/people/SignInPolicyTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/people/PersonActivityTest.java` | 수정 (PersonAccess 칸이 늘면 생성자 호출을 맞춘다) |
| `backend/src/main/java/com/bifos/assistant/user/application/AllowedUserResolver.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/people/application/PersonAccessService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/people/application/dto/PersonAccess.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/admin/people/presentation/PeopleAdminDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentPendingToolsets.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/quality/application/EvaluationUserServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/user/application/AllowedUserResolverEvaluationTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentPendingToolsetsTest.java` | 수정 |
