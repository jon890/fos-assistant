# Phase 03. 화면에서 사람을 더하고 첫 로그인에 에이전트가 생긴다

**Execution profile**: standard

## 목표

관리자가 화면에서 이메일과 이름과 profile 이름을 적으면 그 사람이 로그인해 바로 대화를 시작한다.
그 사이에 홈서버를 만지지 않는다.

**범위 외**:
`family_id` 를 그룹으로 넓히는 것은 이 plan 이 하지 않는다.
사람을 지우는 흐름도 만들지 않는다. `enabled` 를 내리는 것까지다.

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
| `GET /api/admin/people` | 목록 |
| `POST /api/admin/people` | 더한다 |
| `PATCH /api/admin/people/{id}` | `enabled` 를 올리고 내린다 |

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
| `visibility` | 자기만 보는 것 |
| `ownerUserId` | 방금 만든 사용자 |
| `enabled` | 참 |

**`apiBaseUrl` 을 이 저장소가 정하지 않는다.**
공유 listener 의 주소는 비공개 저장소가 소유한다.
설정으로 받는다. `HermesProperties` 에 `sharedListenerBaseUrl` 을 더하고,
그 값에 `/p/<profile>` 을 붙여 만든다.
접두를 붙이는 규칙은 `docs/hermes-integration.md` 의 「profile 접두」가 갖는다.

**허용 목록에 그 이메일이 없으면 에이전트를 만들지 않는다.**
사용자만 만들고 지나간다. 로그인 판정이 앞에서 막으므로 보통은 일어나지 않지만,
표에서 뺀 뒤에 남은 토큰으로 들어오는 경우가 있다.

`provider` 와 `model` 은 이 phase 가 정하지 않는다.
관리자가 뒤에 관리 화면에서 고른다. 기존 모델 동기화 경로를 그대로 쓴다.

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

`test/browser` 에 화면 검사를 하나 더한다.
관리자로 들어가 사람을 더하면 목록에 한 줄이 늘어나는 것을 본다.
`ADMIN` 이 아닌 사람에게 그 화면이 보이지 않는 것도 본다.

`test/e2e/scenarios/` 에 하나 더한다.
사람을 더하고, 그 사람으로 로그인하고, 에이전트 목록에 하나가 보이는 데까지 간다.

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
| `web/src/app/admin/people/page.tsx` | 신규 |
| `web/src/app/api/admin/people/route.ts` | 신규 |
| `web/src/app/api/admin/people/[id]/route.ts` | 신규 |
| `backend/src/test/java/com/bifos/assistant/people/PersonRegistrarTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/user/FirstSignInTest.java` | 신규 |
| `test/browser/` | 추가 |
| `test/e2e/scenarios/` | 추가 |

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
