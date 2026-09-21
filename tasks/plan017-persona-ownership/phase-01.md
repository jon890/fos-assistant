# Phase 01. 성격을 읽고 쓰는 경로

**Execution profile**: deep

## 목표

Control Plane 에 성격을 읽고 쓰는 경로를 만든다.
본문은 그 profile 의 `SOUL.md` 에 그대로 두고 데이터베이스에 두지 않는다.

**범위 외**:
화면은 phase-02 가 한다. 이 phase 는 경로와 권한 판정까지다.
Hermes 대시보드에 경로를 여는 plugin 은 **이 저장소가 만들지 않는다.**
비공개 저장소 `fos-home-infra` 가 소유한다. 아래 「fos-home-infra 에서 할 것」을 본다.

## 컨텍스트

지금 성격을 고치려면 비공개 저장소의 파일을 고치고 배포해야 한다.
Hermes 대시보드에 그 파일을 읽고 쓰는 경로가 둘 다 있다.

**근거 문서**:
`docs/adr/ADR-019-페르소나는-hermes-가-갖고-control-plane-은-화면만-준다.md`,
`docs/code-architecture.md` 의 「페르소나」 절,
`docs/flow.md` 의 「페르소나를 고칠 때」 절,
`docs/hermes-integration.md` 의 「`SOUL.md` 를 읽고 쓰는 두 경로」 와
「경로는 문자열이 정확히 같아야 한다」.

**그 둘을 먼저 읽는다.** 받고 돌려주는 칸 이름과 경로를 여는 제약이 거기 있다.

### 데이터베이스를 쓰지 않는다

`agent_persona` 라는 표를 만들지 않는다. 마이그레이션을 더하지 않는다.
읽을 때도 쓸 때도 Hermes 대시보드를 부른다.

정본이 하나라 「반영됨」 과 「미반영」 이 갈리지 않는다.
다시 반영하는 경로도, 실행이 어느 판으로 돌았는지 적는 칸도 만들지 않는다.

## 의도 메모

- 본문을 데이터베이스에 두는 안을 버렸다. 정본이 둘이 되면 어긋남을 판정하는 것이 따라붙는다.
  근거는 ADR-019 의 「버린 대안」 이 갖는다.
- 판 이력을 쌓는 안을 버렸다. 되돌리고 싶은 일이 실제로 생기면 그때 표 하나를 더한다.
- 판 번호 대신 **앞 본문의 해시**로 덮어쓰기를 막는다.
  `SOUL.md` 에는 판 번호가 없다. 본문 전체를 되돌려 받는 안보다 가볍고,
  `agent_execution.instructions_hash` 가 이미 같은 방식을 쓴다.
- 빈 본문을 받지 않는다. 빈 `SOUL.md` 를 쓰면 그 사람의 성격이 지워진다.

## fos-home-infra 에서 할 것

**이 저장소에서 하지 않는다.** 배포를 요청할 때 아래를 함께 전한다.

대시보드 plugin 이 profile 이름마다 아래 둘을 토큰 경로로 등록한다.

- `GET /api/profiles/<이름>/soul`
- `PUT /api/profiles/<이름>/soul`

지금 여는 것은 `/api/profiles` 와 `/api/env` 뿐이다.
`register_token_route` 는 경로 문자열이 같은지만 보므로 자리표시자로는 열리지 않는다.
**되돌리기용 삭제 경로와 달리 이 둘은 계속 열려 있어야 한다.** 성격을 고치는 일은 반복된다.
새 profile 이 생긴 뒤 그 이름의 경로가 언제 열리는지도 그 저장소가 정한다.

**옮길 것은 없다.** 지금 파일에 있는 성격이 그대로 정본이고 화면에 그대로 보인다.
`default-SOUL.md` 도 그대로 둔다.

## 작업 항목

### 1. `shared/util/Sha256.java`

앞 본문의 해시를 만드는 자리다.

```
public static String hex16(String value)
```

- SHA-256 의 앞 16바이트를 16진수 32글자로 적는다
- **빈 문자열에도 그 값을 돌려준다.** null 을 돌려주지 않는다
- `value` 가 null 이면 빈 문자열과 같게 다룬다

`AssembledContext.instructionsHash()` 가 같은 형식을 쓰지만 record 의 인스턴스 메서드라
임의의 문자열에 쓸 수 없고, 넣은 문맥이 없을 때 null 을 돌려준다.
**여기서는 null 을 돌려주면 안 된다.** 빈 본문에도 해시가 있어야 첫 저장의 대조가 성립한다.

`AssembledContext` 와 `AgentTokenService.hash` 와 `MemoryService.proposalDedupKey` 를
이 클래스로 모으지 않는다. **이 phase 의 범위 밖이다.** 셋은 자리마다 자릿수와 null 규칙이 다르다.

### 2. `hermes/dto/SoulDocument.java`

`GET` 이 돌려주는 것을 담는다.

| 칸 | 뜻 |
| --- | --- |
| `content` | 본문. 파일이 없으면 빈 문자열 |
| `exists` | 그 파일이 있었는가 |

같은 디렉터리의 `HermesModelOptions` 가 record 를 쓰는 방식을 그대로 따른다.

### 3. `hermes/HermesDashboardClient.java` 에 둘을 더한다

```
SoulDocument readSoul(String profileName)
void putSoul(String profileName, String content)
```

구현은 `HttpHermesDashboardClient` 에 둔다.
같은 클래스의 `deleteProfile` 이 `RestClient` 로 경로에 이름을 넣는 방식과
`HermesCallFailure.of` 로 실패를 옮기는 방식을 그대로 따른다.

- 경로는 `{baseUrl}/api/profiles/{name}/soul` 이다
- `PUT` 의 본문은 `content` 한 칸이다. 칸 이름을 바꾸지 않는다
- `profileName` 에 `HermesProfileKeyStore` 와 같은 정규식 검사를 건다.
  그 필드는 `private static final` 이라 그대로 쓸 수 없다.
  **`hermes` 패키지에 `HermesProfileName` 을 만들어 두 곳이 함께 쓰게 한다.**
  `HermesProfileKeyStore` 도 그것을 쓰도록 고친다. 같은 정규식을 두 벌 두지 않는다

`HermesProfileName` 은 **판정만 내고 던지지 않는다.**

```
public static boolean isValid(String name)
```

`HermesProfileKeyStore.keyFile` 은 부르는 자리마다 다른 `ErrorCode` 를 던진다.
읽을 때 `HERMES_PROFILE_KEY_MISSING` 이고 쓰고 지울 때 `VALIDATION_FAILED` 다.
새 클래스가 코드 하나를 고정해 던지면 그 구분이 사라지고 `HermesProfileKeyStoreTest` 가 깨진다.
**던지는 것은 부르는 쪽이 하고 이 클래스는 참거짓만 낸다.**

이름 검사를 두는 까닭을 정확히 적는다.
`RestClient.uri(template, var)` 는 기본 인코딩에서 `/` 를 `%2F` 로 바꾸므로
검사가 없어도 경로를 벗어나지는 않는다.
**검사를 두는 것은 대시보드에 닿기 전에 거절해 헛된 호출을 없애기 위해서다.**
그 까닭을 주석에도 그대로 적는다. 틀린 근거를 코드에 남기지 않는다

### 4. `agent/application/PersonaService.java`

성격을 읽고 쓰는 순서를 안다. `hermes` 는 부르는 방법만 알고 순서는 여기가 안다.

| 메서드 | 하는 일 |
| --- | --- |
| `read(CurrentUser, String code)` | 그 에이전트의 지금 본문과 고칠 수 있는지 |
| `write(CurrentUser, String code, String body, String baseHash)` | 본문을 쓰고 쓴 결과를 돌려준다 |

둘 다 `AgentService.requireReadable(user, code)` 로 에이전트를 얻는다.
그것이 이미 볼 수 없는 에이전트를 `AGENT_NOT_FOUND` 로 숨긴다.
**같은 판정을 하는 클래스를 새로 만들지 않는다.**

**대시보드에 넘기는 것은 `code` 가 아니라 `agent.hermesProfile()` 이다.**
둘이 다른 값이다. `code` 를 그대로 넘겨도 검사는 모두 통과한다.
브라우저와 e2e 의 씨 데이터가 둘을 같은 값으로 두고 있어 운영에서만 남의 profile 을 읽는다.

쓰기 권한은 이 클래스가 판정한다.

```
고칠 수 있다 = user.isAdmin() 또는 agent.ownerUserId() 가 user.id() 와 같다
```

`write` 의 순서다.

1. `requireReadable` 로 에이전트를 얻는다
2. 고칠 수 있는 사람이 아니면 `FORBIDDEN`
3. 본문 앞뒤 공백을 뗀다. 떼고 나서 비면 `VALIDATION_FAILED`
4. `readSoul` 로 지금 본문을 읽는다
5. 그 본문의 해시가 `baseHash` 와 다르면 `PERSONA_STALE`
6. `putSoul` 로 쓴다
7. 쓴 본문과 그 해시를 돌려준다

**해시는 언제나 `readSoul` 이 돌려준 원문 그대로에 건다.** 공백을 떼고 걸지 않는다.
`read` 와 `write` 가 다른 쪽을 고르면 줄바꿈으로 끝나는 `SOUL.md` 의 첫 저장이 언제나 `PERSONA_STALE` 이다.
대역은 정확한 문자열을 돌려주므로 어느 검사도 이것을 잡지 못한다.
7번이 돌려주는 해시도 쓴 본문 그대로에 건 것이다.

**4번과 6번 사이에 트랜잭션이 없다.** 데이터베이스를 쓰지 않으므로 걸 것이 없고,
그 사이에 다른 사람이 쓰면 그 글이 덮어쓰인다.
가족 다섯이 쓰는 서비스에서 그 창이 실제로 문제가 될 만큼 넓지 않다고 보고 그대로 둔다.

해시는 작업 항목 1 의 `Sha256.hex16` 으로 만든다.

`baseHash` 가 비어 있을 때의 규칙이다.

- 지금 본문이 있으면 `PERSONA_STALE`. 보지 않고 덮어쓰는 것을 막는다
- 지금 본문이 없으면(`exists` 가 거짓이거나 공백뿐) 통과한다. 처음 쓰는 것이다

**화면은 언제나 `GET` 을 먼저 하므로 이 경로로 오지 않는다.**
화면을 거치지 않고 부르는 쪽을 위한 규칙이다.

### 5. `agent/presentation/AgentPersonaController.java`

경로와 응답 모양은 `docs/code-architecture.md` 의 「경로」가 정한다.

| 경로 | 응답 |
| --- | --- |
| `GET /api/v1/agents/{code}/persona` | `PersonaView` |
| `PUT /api/v1/agents/{code}/persona` | `PersonaView` |

요청과 응답 record 는 `agent/presentation/AgentDtos.java` 에 더한다.
컨트롤러 안에 record 를 두지 않는다. `backend/AGENTS.md` 가 그렇게 정한다.

`PersonaView` 에 담을 것이다.

| 칸 | 뜻 |
| --- | --- |
| `body` | 지금 본문. 파일이 없으면 빈 문자열 |
| `bodyHash` | 그 본문의 해시. 화면이 저장할 때 그대로 돌려보낸다. **빈 본문에도 값이 있다** |
| `editable` | 지금 요청자가 고칠 수 있는가 |
| `maxChars` | 본문 상한. 화면이 남은 글자 수를 보인다 |

`WritePersonaRequest` 는 `body` 와 `baseHash` 를 받는다.
`body` 는 `@NotBlank` 이고 `@Size(max = 8000)` 이다.
상한은 `docs/code-architecture.md` 의 「페르소나」가 정한다.

**`baseHash` 에 `@NotBlank` 를 붙이지 않는다.** 비어 있는 것이 뜻을 갖는 값이고
그 규칙은 작업 항목 4 가 갖는다.

### 6. 오류 코드를 하나 더한다

`shared/error/ErrorCode.java` 에 더한다. 기존 이름 짓는 방식과 주석 방식을 따른다.

| 코드 | 상태 | 언제 |
| --- | --- | --- |
| `PERSONA_STALE` | 409 | 화면이 보고 있던 본문이 지금 본문이 아니다 |

**대시보드에 닿지 못한 것을 새 코드로 만들지 않는다.**
`HermesCallFailure` 가 이미 `HERMES_UNAVAILABLE` 로 옮긴다.

### 7. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/agent/PersonaServiceTest.java`
`HermesDashboardClient` 의 대역을 쓴다.

**대역은 이미 있다.** `backend/src/test/java/com/bifos/assistant/hermes/StubHermesDashboardClient.java` 다.
인터페이스에 메서드를 더하면 그 파일이 컴파일되지 않으므로 **함께 고친다.**
받은 본문을 되읽을 수 있게 두어 검사가 무엇이 넘어갔는지 보게 한다.

| 무엇 | 기대 |
| --- | --- |
| 지금 본문을 읽는다 | 그 본문과 그 해시가 온다 |
| 파일이 없는 profile 을 읽는다 | 본문이 빈 문자열이다 |
| 맞는 `baseHash` 로 쓴다 | `putSoul` 이 그 본문으로 불렸다 |
| 한 글자 다른 `baseHash` 로 쓴다 | `PERSONA_STALE`. `putSoul` 이 불리지 않았다 |
| 지금 본문이 있는데 `baseHash` 가 비어 있다 | `PERSONA_STALE`. `putSoul` 이 불리지 않았다 |
| 파일이 없는 profile 에 `baseHash` 없이 쓴다 | `putSoul` 이 불렸다 |
| 공백만 있는 본문 | 거절. `putSoul` 이 불리지 않았다 |
| 앞뒤에 공백이 붙은 본문 | 뗀 본문으로 `putSoul` 이 불렸다 |
| profile 이름이 규칙에 안 맞는다 | `putSoul` 이 대시보드를 부르지 않는다 |

마지막 줄은 대역으로는 검증되지 않는다.
**`backend/src/test/java/com/bifos/assistant/hermes/HermesDashboardRequestTest.java` 에 더한다.**
그 파일이 이미 메서드와 경로와 본문과 토큰을 보는 검사다. 새 파일을 만들지 않는다.
그 검사가 없으면 이름 검사를 통째로 지워도 테스트가 통과한다.

같은 파일에 `readSoul` 과 `putSoul` 의 경로와 본문 검사도 더한다.

| 무엇 | 기대 |
| --- | --- |
| `readSoul` | `GET {baseUrl}/api/profiles/{이름}/soul` 로 가고 토큰이 붙는다 |
| `putSoul` | `PUT` 이고 본문이 `content` 한 칸이다 |

`backend/src/test/java/com/bifos/assistant/agent/AgentPersonaControllerTest.java`

| 무엇 | 기대 |
| --- | --- |
| 자기만 보는 자기 에이전트를 주인이 읽는다 | 200 이고 `editable` 이 참 |
| 남의 자기만 보는 에이전트를 읽는다 | `AGENT_NOT_FOUND`. `FORBIDDEN` 이 아니다 |
| 가족 공용 에이전트를 `MEMBER` 가 읽는다 | 200 이고 `editable` 이 거짓 |
| 가족 공용 에이전트를 `MEMBER` 가 쓴다 | `FORBIDDEN`. `putSoul` 이 불리지 않았다 |
| 가족 공용 에이전트를 `ADMIN` 이 쓴다 | 200 |
| 8000자를 넘는 본문 | 400 |
| 대시보드가 읽기에서 실패한다 | 502 이고 `HERMES_UNAVAILABLE` |

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
scripts/check-public-safe.sh
```

`pnpm build` 가 요구하는 환경 변수는 `web/AGENTS.md` 의 「검사」 절이 갖는다.

아래가 아무것도 내지 않아야 한다. 이 phase 는 표를 만들지 않는다.

```bash
# cwd: 저장소 root
git status --short backend/src/main/resources/db/migration/
```

아래도 아무것도 내지 않아야 한다. 주소와 토큰이 코드에 들어가면 안 된다.

```bash
# cwd: 저장소 root
grep -rn "api/profiles" backend/src/main/java --include='*.java' | grep -v HermesDashboardClient
```

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/shared/util/Sha256.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/dto/SoulDocument.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesProfileName.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesDashboardClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesDashboardClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesProfileKeyStore.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/PersonaService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentPersonaController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/StubHermesDashboardClient.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/HermesDashboardRequestTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/PersonaServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentPersonaControllerTest.java` | 신규 |

## 끝낸 뒤

`tasks/plan017-persona-ownership/index.json` 의 이 phase 를 `completed` 로 바꾸고
`current_phase` 를 2로 올린다.

**배포하지 않는다.** 아직 성격을 쓸 화면이 없다. phase-02 와 함께 배포한다.
