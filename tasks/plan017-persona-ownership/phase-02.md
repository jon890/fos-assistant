# Phase 02. 데이터베이스의 성격을 Hermes 에 민다

**Execution profile**: deep

## 목표

저장한 성격이 그 profile 의 `SOUL.md` 에 들어가게 한다.
밀지 못해도 행은 남고 그 상태가 응답에 드러난다.

**범위 외**:
화면은 phase-03 이 한다. 이 phase 는 미는 경로와 그 상태를 내는 데까지다.
Hermes 대시보드에 경로를 여는 plugin 은 **이 저장소가 만들지 않는다.**
비공개 저장소 `fos-home-infra` 가 소유한다. 아래 「fos-home-infra 에서 할 것」을 본다.

## 컨텍스트

Hermes 대시보드에 `PUT /api/profiles/{name}/soul` 이 있다.
무엇이 어떻게 도는지는 `docs/hermes-integration.md` 의 「profile 을 HTTP 로 만드는 길」이 갖는다.
**그 절을 먼저 읽는다.** 특히 「경로는 문자열이 정확히 같아야 한다」 를 읽는다.

**근거 문서**:
`docs/hermes-integration.md` 의 「profile 을 HTTP 로 만드는 길」,
`docs/flow.md` 의 「페르소나를 고칠 때」 절,
`docs/code-architecture.md` 의 「페르소나」 절,
`docs/data-schema.md` 의 「agent_persona」 절,
`docs/adr/ADR-019-페르소나는-control-plane-이-갖고-hermes-에-민다.md`.

### 실행이 읽는 것은 파일이다

Control Plane 은 실행할 때 성격을 보내지 않는다. Hermes 가 자기 `SOUL.md` 를 읽는다.
그래서 실행이 쓰는 것은 마지막 판이 아니라 **마지막으로 반영된 판**이다.
그 판의 번호를 실행 줄에 적는 것이 이 phase 의 일이다.

## Blocked 조건

`backend/src/main/java/com/bifos/assistant/hermes/HermesDashboardClient.java` 가 없으면
`PHASE_BLOCKED: 대시보드를 부르는 한 벌이 아직 없다` 를 내고 멈춘다.
그 클래스와 `HermesProperties` 의 `dashboardBaseUrl` 과 `dashboardToken` 은
사람을 더하는 흐름이 먼저 만든다. 없는 상태에서 여기서 새로 만들면 같은 것이 둘이 된다.

## 의도 메모

- 행을 만드는 것과 미는 것을 한 트랜잭션에 두는 안을 버렸다.
  미는 데 실패하면 행까지 사라져 사람이 쓴 글이 없어진다.
- 밀지 못한 것을 오류 응답으로 내는 안을 버렸다.
  글은 저장됐다. 저장 실패와 반영 실패를 같은 모양으로 알리면 사용자가 다시 쓴다.
- 미반영 상태에서 실행을 거절하는 안을 버렸다.
  대시보드가 잠깐 멈추면 대화가 통째로 멈춘다.
- 실행할 때마다 다시 밀어 보는 안을 버렸다.
  대화 한 번에 대시보드 호출이 하나 늘고, 그 호출이 느리면 답이 그만큼 늦어진다.

## 작업 항목

### fos-home-infra 에서 할 것

**이 저장소에서 하지 않는다.** 배포를 요청할 때 아래를 함께 전한다.

1. 대시보드 plugin 이 `/api/profiles/<이름>/soul` 을 profile 이름마다 토큰 경로로 등록한다.
   지금 여는 것은 `/api/profiles` 와 `/api/env` 뿐이다.
   `register_token_route` 는 경로 문자열이 같은지만 보므로 자리표시자로는 열리지 않는다.
   **되돌리기용 삭제 경로와 달리 이것은 계속 열려 있어야 한다.** 성격을 고치는 일은 반복된다.
   새 profile 이 생긴 뒤 그 이름의 경로가 언제 열리는지도 그 저장소가 정한다.
2. `services/hermes-assistant/personas/` 의 `SOUL.md` 사본을 처분한다.
   데이터베이스가 정본이 된 뒤 그 사본은 읽히지 않는다.
   **이관이 끝난 profile 의 것부터 지운다.** 폐기된 profile 의 사본도 함께 본다.
3. `default-SOUL.md` 를 어떻게 할지 정한다.
   새 profile 은 `POST /api/profiles` 가 Hermes 기본 `SOUL.md` 를 넣으므로
   이 저장소는 기본 본문을 갖지 않는다.

### 1. `hermes/HermesDashboardClient.java` 에 `putSoul` 을 더한다

같은 클래스의 기존 메서드가 `RestClient` 를 쓰는 방식과 오류를 옮기는 방식을 그대로 따른다.

```
putSoul(String profileName, String body)
  PUT {dashboardBaseUrl}/api/profiles/{profileName}/soul
```

본문의 모양은 `hermes_cli/web_routers/profiles.py` 가 정한다.
**부르기 전에 그 핸들러가 받는 칸 이름을 확인한다.** 짐작으로 적지 않는다.

`profileName` 에 `HermesProfileKeyStore` 의 `PROFILE_NAME` 과 같은 검사를 건다.
이름이 경로에 들어가므로 그 검사가 없으면 경로를 벗어나는 값이 들어갈 수 있다.

### 2. `agent/application/PersonaPublisher.java`

판 하나를 Hermes 에 미는 자리다.

| 메서드 | 하는 일 |
| --- | --- |
| `publish(Agent, AgentPersona)` | 밀고, 성공하면 그 행의 `synced_at` 을 적는다 |

- 성공하면 `synced_at` 을 적고 참을 돌려준다
- 실패하면 거짓과 오류 코드를 돌려주고 로그를 남긴다. 예외를 위로 던지지 않는다
- **`synced_at` 을 적는 것은 민 뒤다.** 먼저 적으면 실패한 판이 반영된 것으로 보인다

돌려주는 타입은 `agent/application/PersonaPublishResult.java` 에 따로 둔다.
`backend/AGENTS.md` 가 서비스 안에 결과 타입을 두지 않는 것으로 정한다.

### 3. 저장과 미는 것을 잇는다

`agent/application/PersonaEditor.java` 를 만든다.
phase-01 에서 컨트롤러가 `PersonaService.write` 를 직접 부르던 자리를 이것으로 바꾼다.

```
1. PersonaService.write 로 행을 만든다. 여기까지가 한 트랜잭션이다
2. 커밋한 뒤 PersonaPublisher.publish 를 부른다
3. 결과를 담아 PersonaView 를 만든다
```

**2번이 실패해도 1번을 되돌리지 않는다.**
`PersonaEditor` 자신에 `@Transactional` 을 걸지 않는다. 걸면 2번의 실패가 1번을 되돌린다.

### 4. 다시 반영하는 경로

`AgentPersonaController` 에 하나를 더한다.

```
POST /api/v1/agents/{code}/persona/sync
응답  PersonaView
```

지금 판을 다시 민다. **판을 새로 만들지 않는다.**
고칠 수 있는 사람만 부른다. `PersonaAccess.requireWritable` 을 건다.
판이 하나도 없으면 아무것도 하지 않고 지금 상태를 그대로 돌려준다.

### 5. 실행 줄에 어느 판으로 돌았는지 적는다

`backend/src/main/resources/db/migration/V18__execution_persona.sql` 로
`agent_execution` 에 칸 하나를 더한다.
칸 이름과 뜻은 `docs/data-schema.md` 의 「agent_execution」 절이 정한다.

`chat/application/ChatService` 가 실행 줄을 만들 때 그 값을 넣는다.
`ExecutionRecorder.start` 가 지금 `context_chars` 를 받는 자리 옆에 더한다.

- 반영된 판이 있으면 그 행의 번호를 넣는다
- 없으면 비워 둔다. 그때는 홈서버 파일의 `SOUL.md` 로 돈 것이다
- **이 값을 읽지 못해도 실행을 막지 않는다.** 관측용이다

### 6. 응답에 반영 상태를 담는다

`PersonaView` 의 `synced` 와 `publishedRevision` 이 phase-01 에서 늘 같은 값을 냈다.
이제 실제 값이 들어간다. 더할 것이 하나 있다.

| 칸 | 뜻 |
| --- | --- |
| `syncFailureCode` | 마지막 반영이 실패한 까닭. 성공했으면 비어 있다 |

화면이 「대시보드에 닿지 못했다」 와 「거절당했다」 를 갈라 보이기 위한 것이다.

### 7. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/agent/PersonaPublisherTest.java`
`HermesDashboardClient` 를 대역으로 바꿔 넣는다.

| 무엇 | 기대 |
| --- | --- |
| 미는 데 성공한다 | 그 행의 `synced_at` 이 찼다 |
| 대시보드가 실패를 돌려준다 | `synced_at` 이 비어 있다. 예외가 올라오지 않는다 |
| 대시보드가 실패를 돌려준다 | 행이 그대로 남아 있다 |
| profile 이름이 규칙에 안 맞는다 | 부르지 않는다 |

`backend/src/test/java/com/bifos/assistant/agent/PersonaEditorTest.java`

| 무엇 | 기대 |
| --- | --- |
| 저장은 되고 미는 것이 실패한다 | 응답이 200 이고 `synced` 가 거짓, 행이 남아 있다 |
| 같은 상태에서 다시 반영을 부른다 | 판이 늘지 않고 다시 민다 |
| 다시 반영이 성공한다 | `synced` 가 참이 되고 `publishedRevision` 이 지금 판과 같다 |
| 판이 없는 에이전트에 다시 반영을 부른다 | 대시보드를 부르지 않는다 |

`backend/src/test/java/com/bifos/assistant/chat/ExecutionPersonaTest.java`

| 무엇 | 기대 |
| --- | --- |
| 반영된 판이 있는 에이전트의 실행 | 실행 줄이 그 판을 가리킨다 |
| 반영된 판이 없는 에이전트의 실행 | 그 칸이 비어 있고 실행은 그대로 돈다 |
| 지금 판과 반영된 판이 다른 에이전트의 실행 | 실행 줄이 **반영된 판**을 가리킨다 |

`test/e2e` 의 Hermes 대역에 `PUT /api/profiles/{이름}/soul` 을 더한다.
`test/e2e/` 의 기존 대역이 대시보드 경로를 흉내 내는 방식을 그대로 따른다.
**대역이 받은 본문을 되읽을 수 있게 둔다.** 민 본문이 맞는지 시나리오가 보게 한다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
scripts/check-public-safe.sh
```

아래가 아무것도 내지 않아야 한다. 주소와 토큰이 코드에 들어가면 안 된다.

```bash
# cwd: 저장소 root
grep -rn "api/profiles" backend/src/main/java --include=*.java | grep -v HermesDashboardClient
```

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/hermes/HermesDashboardClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/PersonaPublisher.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/PersonaPublishResult.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/PersonaEditor.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentPersonaController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentDtos.java` | 수정 |
| `backend/src/main/resources/db/migration/V18__execution_persona.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/PersonaPublisherTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/agent/PersonaEditorTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ExecutionPersonaTest.java` | 신규 |
| `test/e2e/` | 수정 |

## 끝낸 뒤

`tasks/plan017-persona-ownership/index.json` 의 이 phase 를 `completed` 로 바꾸고
`current_phase` 를 3으로 올린다.

**배포하지 않는다.** 아직 성격을 쓸 화면이 없다. phase-03 과 함께 배포한다.
