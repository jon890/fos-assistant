# Phase 05. 컨트롤러의 트랜잭션을 application 서비스로 옮긴다

**Execution profile**: standard

## 목표

`AgentAdminController` 와 `AgentToolController` 의 `@Transactional` 메서드 넷의 본문을 `agent.application` 의 서비스로 옮긴다.
트랜잭션 경계는 유스케이스를 아는 층이 정한다. `TRANSACTIONAL_ONLY_IN_APPLICATION` 의 기준에서 컨트롤러 줄 넷이 빠지고,
두 컨트롤러가 저장소를 바로 쓰던 `LAYER_DIRECTION` 의 줄도 함께 빠진다.

**범위 외**: 저장소의 `@Transactional` 과 `UserProvisioningService`. 다음 phase 가 맡는다. 다른 컨트롤러.

## 컨텍스트

- 먼저 `tasks/plan66-archunit-easy-rules/README.md` 를 읽는다.
- `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentAdminController.java` 의 `create`, `update` 와
  `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentToolController.java` 의 `write`, `writeAdmin` 이 대상이다.
- `application` 은 `presentation` 의 타입(`AgentDtos`)을 쓰지 못한다. 서비스는 값을 풀어서 받거나 `application` 의 record 로 받는다.
- 층 방향은 `docs/backend/packages.md` 가 갖는다. `backend/AGENTS.md` 의 「데이터 클래스는 컨트롤러 안에 두지 않는다」 도 읽는다. `application` 은 타입 하나에 파일 하나다.

**근거 문서**: `docs/backend/packages.md` 의 「backend 패키지」, `docs/adr/ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`, `docs/adr/ADR-033-사용자가-에이전트를-만들고-공개해도-만든-사람이-관리한다.md`

## 의도 메모

- **검사 순서와 오류 응답이 그대로여야 한다.** 관리자 확인이 가장 먼저이고, 그 뒤의 검사 순서(코드 중복, 주인, 닿는지 확인, 그룹 안전, 흐름 이름)를 옮긴 메서드에서도 지킨다.
- 잠금 조회(`findByCodeForUpdate`, `requireReadableForUpdate`)와 저장이 지금처럼 한 트랜잭션 안에 있어야 한다.
- 닿는지 확인(`AgentEndpointProbe.requireReachable`)은 지금도 트랜잭션 안에서 돈다. 밖으로 빼지 않는다. 빼면 동작이 바뀐다.
- 컨트롤러에는 경로, 관리자 확인, 요청을 풀어 넘기는 것, 응답 변환만 남긴다.

## 작업 항목

### 1. `agent/application/AgentAdminService.java` 신규

`@Service`, `@RequiredArgsConstructor`. `AgentRepository`, `AppUserRepository`, `AgentLifecycleService`, `AgentEndpointProbe`, `FlowRegistry`, `Clock` 을 받는다.

- `@Transactional public Agent create(AgentCreateCommand command)` — `AgentAdminController.create` 의 `currentUser.requireAdmin()` 뒤 본문 전체
- `@Transactional public Agent update(String code, AgentUpdateCommand command)` — `update` 의 `requireAdmin()` 뒤 본문 전체
- `@Transactional(readOnly = true) public List<Agent> list()` — 지우지 않은 에이전트 목록
- 컨트롤러의 private 보조 메서드 `requireKnownFlow`, `effectiveApiBaseUrl`, `stripTrailingSlash`, `requireAgentForUpdate`, `ownerId` 를 Javadoc 과 함께 옮긴다

`list` 는 지금 트랜잭션 없이 `findAll` 을 부른다. `readOnly` 트랜잭션을 더해도 응답이 같으면 그대로 두고, 테스트가 달라지면 `@Transactional` 을 뺀다.

### 2. `agent/application/AgentCreateCommand.java`, `agent/application/AgentUpdateCommand.java` 신규

`AgentDtos.CreateAgentRequest` 와 `AgentDtos.UpdateAgentRequest` 의 칸을 그대로 가진 record 다. 칸 이름과 타입은 `AgentDtos.java` 에서 읽는다. 검증 애너테이션은 요청 DTO 에 남기고 여기에는 달지 않는다.

### 3. `agent/application/AgentToolService.java` 에 메서드 추가

- `@Transactional public ToolsetsView writeReadable(CurrentUser user, String code, List<String> enabled)` — `agents.requireReadableForUpdate(user, code)` 뒤 `write`
- `@Transactional public ToolsetsView writeAsAdmin(CurrentUser user, String code, List<String> enabled)` — 컨트롤러의 `requireAgentForUpdate(code)` 뒤 `write`
- `public ToolsetsView readAsAdmin(CurrentUser user, String code)` — 컨트롤러의 `requireAgent(code)` 뒤 `read`

같은 클래스 안의 `write` 를 부르는 것은 프록시를 거치지 않아도 된다. `write` 에는 `@Transactional` 이 없다.
`AgentService` 를 주입해 빈 순환이 생기면 이 셋을 새 클래스 `agent/application/AgentToolAccessService.java` 에 둔다.

### 4. 두 컨트롤러의 변경

`@Transactional` 과 저장소 필드를 지우고 서비스를 부른다. `currentUser.requireAdmin()` 과 `currentUser.require()` 는 컨트롤러에 남기고 서비스 호출보다 먼저 부른다.

### 5. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/agent/AgentApiBaseUrlUpdateTest.java` 는 컨트롤러를 `new` 로 만든다. `AgentAdminService` 를 만들어 넘기도록 고치고 단언은 바꾸지 않는다.
- `backend/src/test/java/com/bifos/assistant/agent/AgentLifecycleFlagsTest.java` 가 컴파일되고 통과하게 고친다.
- `backend/src/test/java/com/bifos/assistant/agent/AgentAdminServiceTest.java` 를 새로 만든다. 정상: 그룹 공개 에이전트를 만들면 저장된 에이전트를 돌려준다. 실패: 이미 쓰는 코드면 `ApiException` 의 `ErrorCode.VALIDATION_FAILED` 이고 `AgentEndpointProbe.requireReachable` 을 부르지 않는다.

## 검증

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
./gradlew test
! grep -n "presentation" config/archunit/store/54473729-2b30-4508-9d02-64e810d6f34b
! grep -n "AgentAdminController\|AgentToolController" config/archunit/store/2790ecd4-faaa-4952-b703-a028b68814d5
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentAdminService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentCreateCommand.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentUpdateCommand.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentToolService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentAdminController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentToolController.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentAdminServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentApiBaseUrlUpdateTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentLifecycleFlagsTest.java` | 수정 |
| `backend/config/archunit/store/*` | 수정 |
