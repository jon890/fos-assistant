# Phase 03. 화면에서 사람을 더하고 첫 로그인에 에이전트가 생긴다

**Execution profile**: standard

## 목표

관리자가 화면에서 이메일과 이름과 profile 이름을 적으면 그 사람이 로그인해 바로 대화를 시작한다.
그 사이에 홈서버를 만지지 않는다.

**범위 외**:
`family_id` 를 그룹으로 넓히는 것은 이 plan 이 하지 않는다.
사람을 지우는 흐름도 만들지 않는다. `enabled` 를 내리는 것까지다.

**`enabled` 를 내려도 이미 만들어진 에이전트는 그대로 둔다.**
그 사람이 남은 토큰으로 대화를 이어갈 수 있다는 뜻이다.
막으려면 에이전트의 `enabled` 도 함께 내려야 하는데, 이 plan 은 거기까지 가지 않는다.
다시 켤 때 무엇을 되살릴지가 함께 정해져야 하기 때문이다.

## 컨텍스트

phase-01 이 허용 목록 표와 로그인 판정을, phase-02 가 profile 을 만드는 한 벌을 만들었다.
이 phase 가 그 둘을 잇고 화면을 붙인다.

**근거 문서**:
`docs/flow.md` 의 「사람을 더할 때」 절,
`docs/code-architecture.md` 의 「사용자를 더할 때」 절,
`docs/data-schema.md` 의 「allowed_person」 절,
`docs/adr/ADR-018-사람을-더하는-것을-control-plane-이-끝낸다.md`.

### 왜 두 시점에 나뉘나

자기만 보는 에이전트는 주인이 있어야 한다.
`backend/.../agent/presentation/AgentAdminController.java` 의 `ownerId` 가
이메일로 사용자를 찾고 없으면 거절한다.
그런데 사용자는 그 사람이 로그인하기 전에는 없다.

그래서 관리자가 더할 때는 profile 까지만 하고, 에이전트는 첫 로그인에 만든다.

## 의도 메모

- 주인 없는 에이전트를 미리 만들어 두고 나중에 주인을 채우는 안을 버렸다.
  주인 없는 자기 전용 에이전트라는 상태가 생기고,
  그 상태로 실행이 들어올 때 무엇을 할지 정해야 한다. 상태를 늘리지 않는다.
- 사람을 더하는 것을 비동기로 돌리는 안을 버렸다.
  드물게 일어나고 호출이 빠르다. 실패하면 화면에 그대로 알린다.
- 지우는 경로를 만들지 않았다. 이 저장소는 실행 기록이 가리키는 것을 지우지 않는다.

## 작업 항목

### 1. `people/application/PersonRegistrar.java`

사람 하나를 더하는 순서를 안다.

```
1. 이메일과 profile 이름이 이미 쓰이는지 본다. 쓰이면 거절한다
2. allowed_person 에 행을 만든다
3. HermesProfileProvisioner 로 profile 과 key 를 만든다
4. 3번이 실패하면 2번 행을 되돌린다
```

**Hermes 를 부르기 전에 우리 표를 먼저 채운다.**
이름이 겹치는지 우리 쪽에서 먼저 걸러야 Hermes 에 헛일을 시키지 않는다.

`hermes_profile` 이 이미 쓰이는지는 두 곳을 본다.
`allowed_person` 과 `agent` 표다. 둘 다 그 칸이 유일하다.

### 2. `people/presentation/PeopleAdminController.java`

`ADMIN` 만 부른다. 권한을 거는 방법은
`backend/.../agent/presentation/AgentAdminController.java` 가 하는 것을 그대로 따른다.

| 경로 | 하는 일 |
| --- | --- |
| `GET /api/v1/admin/people` | 목록 |
| `POST /api/v1/admin/people` | 더한다 |
| `PATCH /api/v1/admin/people/{id}` | `enabled` 를 올리고 내린다 |

**백엔드와 웹의 경로가 다르다.** 백엔드는 `/api/v1/...` 이고 웹 서버 라우트는 `/api/admin/people` 이다.
`web/src/app/api/admin/agents/route.ts` 가 그 형태로 되어 있다.

요청과 응답의 모양은 `people/presentation/PeopleDtos.java` 에 모은다.
컨트롤러 안에 record 를 두지 않는다. `backend/AGENTS.md` 가 그렇게 정한다.

`PersonView` 에 담을 것이다.

| 칸 | 뜻 |
| --- | --- |
| `id` | |
| `email` | |
| `displayName` | |
| `hermesProfile` | |
| `enabled` | |
| `joined` | 그 이메일의 `app_user` 가 있는가 |

`joined` 가 거짓이면 아직 한 번도 들어오지 않은 사람이다.

`CreatePersonRequest` 는 셋을 받는다.
`hermesProfile` 은 `[a-z0-9][a-z0-9-]{0,63}` 만 받는다.
`HermesProfileKeyStore` 의 `PROFILE_NAME` 과 같은 규칙이다.

### 3. 첫 로그인에 에이전트를 만든다

`backend/.../user/domain/UserProvisioningService.java` 의 `resolve` 가
새 `AppUser` 를 만들 때 에이전트도 함께 만든다.

| 칸 | 값 |
| --- | --- |
| `code` | 허용 목록의 `hermes_profile` 과 같다 |
| `name` | 허용 목록의 `display_name` |
| `hermesProfile` | 허용 목록의 `hermes_profile` |
| `apiBaseUrl` | **아래를 본다** |
| `provider` | `readOptions` 가 준다. 비어 오면 에이전트를 만들지 않는다 |
| `model` | `readOptions` 가 준다 |
| `costMode` | 설정의 `assistant.people.default-cost-mode` |
| `credentialScope` | 설정의 `assistant.people.default-credential-scope` |
| `visibility` | 자기만 보는 것 |
| `ownerUserId` | 방금 만든 사용자 |
| `enabled` | 참 |

**넷은 비울 수 없다.** `backend/.../agent/domain/Agent.java` 에서
`provider` 와 `model` 과 `costMode` 와 `credentialScope` 가 모두 `nullable = false` 다.
비우고 저장하면 첫 로그인이 실패한다.

#### 설정 둘을 `people` 쪽에 새로 둔다

`HermesProperties` 에 넣지 않는다. Hermes 를 부르는 설정이 아니다.
`people` 패키지에 `ConfigurationProperties` 를 하나 만든다.

| 이름 | 기본값 |
| --- | --- |
| `assistant.people.default-cost-mode` | `SUBSCRIPTION` |
| `assistant.people.default-credential-scope` | `SHARED_HOUSEHOLD` |

**기본값을 코드에 둔다.** 없다고 기동을 막을 값이 아니다.
`HermesProperties` 의 compact 생성자가 `pollInterval` 을 채우는 방식을 그대로 따른다.

근거는 `docs/adr/ADR-002-profile은-나누고-ai-계정은-가족이-함께-쓴다.md` 다.
profile 은 사람마다 나누고 AI 계정은 가족이 함께 쓰기로 이미 정해져 있다.
그래서 이 둘은 사람마다 다를 값이 아니다.

다르게 써야 하면 관리자가 기존 에이전트 관리 화면에서 고친다. 그 폼에 둘이 이미 있다.

**`apiBaseUrl` 을 이 저장소가 정하지 않는다.**
공유 listener 의 주소는 비공개 저장소가 소유한다.
설정으로 받는다. `HermesProperties` 에 `sharedListenerBaseUrl` 을 더하고,
그 값에 `/p/<profile>` 을 붙여 만든다.
접두를 붙이는 규칙은 `docs/hermes-integration.md` 의 「profile 접두」가 갖는다.

##### 이 값은 테스트마다 달라진다

phase-02 의 `dashboardBaseUrl` 과 `dashboardToken` 처럼 `${...}` 로 두면 테스트가 기동하지 못한다.
**그런데 이 값은 앞의 둘과 성격이 다르다.**

가짜 Hermes 가 받는 주소는 실행마다 빈 포트를 받아 쓰므로 고정값으로 적을 수 없다.

| 파일 | 무엇 |
| --- | --- |
| `backend/src/test/resources/application-test.yml` | 기동만 되게 아무 값이나 적는다 |
| `test/e2e/run.ts` 의 `startControlPlane` | **띄운 대역의 실제 주소**를 env 로 넘긴다 |
| `test/browser/fixtures.ts` 의 `startControlPlane` | 같다 |

**허용 목록에 그 이메일이 없으면 에이전트를 만들지 않는다.**
사용자만 만들고 지나간다. 로그인 판정이 앞에서 막으므로 보통은 일어나지 않지만,
표에서 뺀 뒤에 남은 토큰으로 들어오는 경우가 있다.

#### 모델은 등록할 때와 같은 경로로 읽는다

`AgentAdminController.create` 가 하는 둘을 그대로 한다.

1. `hermesModels.readOptions(apiBaseUrl, hermesProfile)` 로 `model` 과 `provider` 를 읽는다
2. `models.seedFirst(saved, new ModelOption(provider, model))` 로 1순위를 만든다

**`readModel` 이 아니라 `readOptions` 를 쓴다.**
`readModel` 은 모델 이름만 돌려주고 `readOptions` 는 `HermesModelOptions(model, provider)` 를 돌려준다.
`AgentModelSync.sync` 가 이미 그 쪽을 쓴다.

**`provider` 가 비어 올 수 있다.** `HermesModelClient` 의 Javadoc 이 그것을 적었다.
`AgentModelSync` 는 그때 지금 1순위의 provider 로 되돌아가는데,
첫 로그인에는 되돌아갈 1순위가 없다.

**그때는 기본값으로 메우지 않고 에이전트를 만들지 않는다.**
틀린 provider 로 만들어진 에이전트는 실행할 때마다 실패하고 그 원인이 화면에 드러나지 않는다.
만들지 않은 것을 로그에 남긴다. `app_user` 는 정상으로 만든다.

**`seedFirst` 를 빠뜨리면 그 사람의 첫 대화가 `NO_MODEL_AVAILABLE` 로 실패한다.**

#### 모델을 읽지 못하면 에이전트를 만들지 않는다

`readOptions` 가 실패해도 **사용자는 만들고 지나간다.**
위의 「허용 목록에 그 이메일이 없으면」과 같은 형태다.

`AgentAdminController.create` 는 이때 `AGENT_MODEL_UNKNOWN` 으로 거절하지만,
여기서 거절하면 Hermes 가 답하지 않는 동안 그 사람이 아무것도 하지 못한다.

**시도하는 것은 `app_user` 를 새로 만드는 그 순간뿐이다.**
`users.findByEmail` 이 비어 있어 새로 저장하는 경로에서만 에이전트를 만든다.

##### 에이전트가 없는지 매번 보지 않는다

`backend/.../shared/auth/ControlPlaneJwtFilter.java` 의 `authenticate` 가
**매 요청** `users.resolve(email, name)` 를 부른다.

「에이전트가 없으면 다시 시도한다」로 적으면 그 사람의 모든 API 요청이
`HermesModelClient.readOptions` 호출을 하나씩 끌고 다닌다.
그 호출의 `connectTimeout` 은 5초이고 `readTimeout` 은 30초이며,
`UserProvisioningService.resolve` 가 `@Transactional` 이라 그동안 데이터베이스 커넥션을 잡는다.

**Hermes 가 답하지 않으면 화면 전체가 요청마다 최대 35초씩 늘어진다.**

##### 실패했을 때 메우는 길

에이전트 없이 지나간 사람은 관리자가 기존 에이전트 등록 화면에서 만든다.
그 사람의 `app_user` 가 이미 있으므로
`AgentAdminController` 의 `ownerId` 가 이메일로 그 사용자를 찾는다.

**이 plan 은 그것 말고 다시 시도하는 길을 만들지 않는다.**

그 화면도 Hermes 가 답할 때에만 열린다.
`AgentAdminController.create` 가 모델을 읽지 못하면 `AGENT_MODEL_UNKNOWN` 으로 거절한다.

### 4. `web/src/app/admin/people/` 화면

`web/src/app/admin/agents` 의 짜임을 그대로 따른다.
색과 간격은 테마 토큰만 쓴다. `web/AGENTS.md` 가 그것을 정한다.

- 목록 표. 이름, 이메일, profile, 들어온 적 있는지, 켜짐
- 더하기 폼. 이메일, 이름, profile 이름
- 각 줄에 켜고 끄는 단추

상태 셋을 보인다.

| 상태 | 무엇을 보이나 |
| --- | --- |
| 아무도 없다 | 「아직 아무도 없습니다」 와 더하기 폼 |
| 더하는 중 | 단추를 잠그고 도는 표시 |
| 실패 | 오류 코드에 맞는 문장. 무엇이 겹쳤는지 말한다 |

`web/src/app/api/admin/people/` 에 서버 라우트를 둔다.
`web/src/app/api/admin/agents` 가 Control Plane 을 부르는 방식을 그대로 따른다.

**더하기 폼과 켜고 끄는 단추는 client component 로 뺀다.**
`web/src/app/admin/people/people-admin-panel.tsx` 에 둔다.
같은 자리에 있는 `web/src/app/admin/agents/agent-admin-panel.tsx` 가 그 선례다.
`page.tsx` 는 서버에서 목록을 읽어 그 부품에 넘기는 데까지만 한다.

### 5. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/people/PersonRegistrarTest.java`

| 무엇 | 기대 |
| --- | --- |
| 새 사람을 더한다 | 행이 생기고 profile 이 만들어졌다 |
| profile 만들기가 실패한다 | **행이 남지 않았다** |
| 이미 쓰는 이메일 | 거절. Hermes 를 부르지 않았다 |
| 이미 쓰는 profile 이름 | 거절. Hermes 를 부르지 않았다 |
| `agent` 가 이미 쓰는 profile 이름 | 거절 |

`backend/src/test/java/com/bifos/assistant/user/FirstSignInTest.java`

| 무엇 | 기대 |
| --- | --- |
| 허용 목록에 있는 사람의 첫 요청 | `app_user` 와 에이전트가 함께 생겼다 |
| 같은 사람의 두 번째 요청 | 에이전트가 늘지 않았다 |
| 허용 목록에 없는 이메일의 토큰 | 사용자만 생기고 에이전트는 없다 |
| 만들어진 에이전트 | 주인이 그 사람이고 자기만 본다 |
| 만들어진 에이전트의 `costMode` 와 `credentialScope` | 설정의 기본값과 같다 |
| `readOptions` 가 실패한다 | **사용자만 생기고 에이전트는 없다.** 로그인은 막히지 않는다 |
| `readOptions` 가 `provider` 를 비워서 준다 | 같다. 기본값으로 메우지 않는다 |

`test/browser` 에 화면 검사를 하나 더한다.
관리자로 들어가 사람을 더하면 목록에 한 줄이 늘어나는 것을 본다.
`ADMIN` 이 아닌 사람에게 그 화면이 보이지 않는 것도 본다.

`test/e2e/scenarios/` 에 하나 더한다.
사람을 더하고, 그 사람의 에이전트 목록에 하나가 보이는 데까지 간다.
**`test/e2e/run.ts` 의 `SCENARIOS` 배열에 넣는다.**

**e2e 는 웹 계층을 띄우지 않으므로 「로그인한다」를 그대로 쓸 수 없다.**
그 사람의 이메일로 토큰을 만들어 부르는 것으로 적는다.
`test/e2e/harness.ts` 가 토큰을 만드는 방식을 그대로 따른다.

phase-01 이 미룬 「허용된 주소가 통과한다」도 여기서 함께 본다.
사람을 더한 뒤 `POST /api/v1/signin/allowed` 를 그 주소로 불러 `allowed` 가 참인 것을 확인한다.

#### 대역이 새로 만든 profile 을 받아들여야 한다

`test/browser/fixtures.ts` 는 `startFakeHermes(PROFILE_KEYS)` 로 profile 과 key 를 미리 고정해 넘긴다.
화면에서 더한 사람의 profile 은 그 목록에 없다.

**`test/e2e/fake-hermes.ts` 가 대시보드 경로 셋을 받으면 그 profile 을 자기 목록에 더하게 한다.**
그러지 않으면 새로 더한 사람의 에이전트가 대역에서 401 을 받는다.
phase-02 가 그 셋을 대역에 더하므로 여기서는 받아들이는 것까지 잇는다.

**key 는 `PUT /api/env` 로 들어온 `API_SERVER_KEY` 를 그 profile 의 key 로 삼는다.**
`POST /api/profiles` 는 이름만 싣고 key 를 싣지 않는다.
대역의 `authorized` 가 `profileKeys[profile]` 과 `Authorization` 헤더를 비교하므로,
key 를 대역이 스스로 만들면 Control Plane 이 파일에 쓴 값과 어긋나 401 이 된다.

`/p/<profile>/api/model/options` 는 대역에 이미 있다. 그 경로를 새로 만들지 않는다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
scripts/check-public-safe.sh
```

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/people/application/PersonRegistrar.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/people/presentation/PeopleAdminController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/people/presentation/PeopleDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/user/domain/UserProvisioningService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/people/application/PeopleProperties.java` | 신규 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `web/src/app/admin/people/page.tsx` | 신규 |
| `web/src/app/admin/people/people-admin-panel.tsx` | 신규 |
| `web/src/app/api/admin/people/route.ts` | 신규 |
| `web/src/app/api/admin/people/[id]/route.ts` | 신규 |
| `backend/src/test/java/com/bifos/assistant/people/PersonRegistrarTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/user/FirstSignInTest.java` | 신규 |
| `test/browser/` | 추가 |
| `test/browser/fixtures.ts` | 수정 |
| `test/e2e/scenarios/` | 추가 |
| `test/e2e/run.ts` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |

## 끝낸 뒤

`tasks/plan016-add-people/index.json` 의 이 phase 를 `completed` 로,
plan 의 `status` 를 `completed` 로 바꾼다.

## 배포한 뒤 실제로 한 번 돌린다

**테스트가 모두 통과해도 운영에서 안 될 수 있다.**
Hermes 대역이 실제와 다른 응답을 내도록 쓰여 있으면 테스트는 통과한다.
실제로 그렇게 스트리밍이 통째로 안 된 채 배포된 적이 있다.

배포한 뒤 사람을 하나 더해 보고 아래를 확인한다.

- 관리 화면의 목록에 한 줄이 늘었다
- 그 사람이 로그인하면 에이전트가 하나 보인다
- 그 에이전트로 대화가 한 번 왕복한다

선행이 둘 있다. 둘 다 비공개 저장소 `fos-home-infra` 가 소유한다.

- Hermes 대시보드에 토큰 경로를 여는 plugin
- Control Plane 이 key 디렉터리에 쓸 수 있게 붙이는 것

**배포 요청에 그 둘을 함께 적는다.**
