# Phase 01. backend 가 스킬을 버전 디렉터리에 쓰고 Hermes 에 게시한다

**Execution profile**: deep

## 목표

`GET/PUT/DELETE /api/v1/agents/{code}/skills…` 를 연다. 저장할 때마다 그 profile 의 올린 스킬 전체를 새 버전 디렉터리에 쓰고 `skills.external_dirs` 로 게시한다. 스킬 본문은 데이터베이스에 두지 않는다.

**범위 외**: 호출 이력과 사용량(phase 02), 화면(phase 03, 04), 스킬 커맨드(다른 계획).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-034-올린-스킬은-control-plane-이-버전-디렉터리에-쓰고-hermes-는-읽기만-한다.md`, `docs/code-architecture.md` 의 「스킬」 절, `docs/flow.md` 의 「스킬을 저장할 때」 절, `docs/hermes/profiles.md` 의 「Control Plane 이 부르는 대시보드 plugin 경로」 절, `docs/hermes/tools-and-skills.md` 의 「스킬 파일과 색인 적용 시점」 절

- 공유 디렉터리 설정의 선례: `chat/application/ArtifactProperties`, `chat/infra/ArtifactStore`, `application.yml` 의 `assistant.artifact.root`(`${ASSISTANT_ARTIFACT_ROOT}`, 기본값 없음). 원자 이동(`AtomicMover`)과 경로 검사가 거기 있다
- 대시보드 호출은 `hermes/HermesDashboardClient`(`HttpHermesDashboardClient`), 도구는 `hermes/HermesToolsetClient`(`readEnabled`, `writeApiServer`), 요청 모양 테스트의 선례는 `backend/src/test/java/com/bifos/assistant/hermes/HermesToolsetRequestTest.java`. 스킬용 호출을 `hermes/HermesSkillClient`(구현 `HttpHermesSkillClient`)로 더한다
- 대시보드 실패는 `HermesCallFailure` 가 `HERMES_UNAVAILABLE` 로 바꾼다. read timeout 과 5xx 도 같은 코드가 된다
- 편집 판정은 `AgentService.isEditableBy`, 읽기는 `AgentService.requireReadable`
- **잠금**: `AgentService.requireReadableForUpdate` 는 `AgentRepository.findByCodeForUpdate`(lock timeout 0) 라 경합하면 곧바로 `AGENT_BUSY` 다. 스킬 저장은 docs 대로 기다렸다가 차례로 돌아야 하므로 기다리는 잠금 `AgentRepository.findByIdForUpdate` 를 쓴다(`agent/application/StarterService` 가 선례다)
- 도구 저장은 `agent/application/AgentToolService.write` 가 `AgentToolPolicy.requestedForWrite(user, agent, requested, current)` 로 목록을 만들어 `toolsets.writeApiServer` 를 부른다. `AgentToolPolicy.isKnown` 이 설정할 수 있는 toolset 인지, `CONTROL_PLANE_MCP` 가 Control Plane MCP 이름이다. `skills` toolset 은 주인 등급이다
- 에이전트 지우기는 `agent/application/AgentLifecycleService.delete` 가 `agent.profileManaged()` 일 때 `HermesProfileProvisioner.deprovision(profile)` 을 부른다
- profile 이름 규칙은 `hermes/HermesProfileName` 의 `[a-z0-9][a-z0-9-]{0,63}` 이다
- SnakeYAML 은 Spring Boot 가 이미 가져온다
- 가짜 대시보드는 `test/e2e/fake-hermes.ts`(`PROFILES_PATH`, `CONFIG_PATH` 등). `PUT /api/config` 는 지금 도구 본문만 받고 키가 다르면 400 이다
- Control Plane 을 띄우는 검사 실행기는 둘이고 결과물 경로를 환경 변수로 넘긴다: `test/e2e/run.ts`(`ASSISTANT_ARTIFACT_ROOT` 줄), `test/browser/fixtures.ts`(같은 줄)

## 의도 메모

- **버전 디렉터리는 통째로 새로 쓴다.** 쓰는 도중의 반쯤 바뀐 스킬을 실행이 읽지 않게 한다. 임시 이름으로 다 쓴 뒤 원자 이동한다
- 지금 버전은 `.published` 표식이 있는 가장 새 디렉터리다. Hermes 설정을 읽지 않는다
- **게시 실패의 처리는 실패 종류로 나눈다.** 대시보드가 4xx 로 분명히 거절했을 때만 새 디렉터리를 지운다. timeout, 5xx, 연결 실패는 Hermes 가 이미 반영했을 수 있으므로 표식 없는 디렉터리로 남긴다. 지우면 `external_dirs` 가 없는 경로를 가리키고 Hermes 는 그 경로를 오류 없이 건너뛴다. 어느 쪽이든 원래 오류를 그대로 돌려준다
- **prune 은 새 버전 게시가 성공해 표식을 쓴 뒤에만 돈다.** 표식 있는 최근 3개를 남기고, **방금 게시한 버전보다 오래된** 표식 없는 디렉터리만 지운다. Hermes 가 가리키고 있을지 모르는 디렉터리를 먼저 지우지 않기 위해서다
- 실패한 저장 뒤의 다음 저장은 표식 있는 최신 버전을 기준으로 쓴다. 그래서 실패한 저장의 변경은 반영되지 않는다. 화면은 다시 저장해 달라고 안내한다(phase 03)
- 버전 이름은 `v` 뒤에 UTC 밀리초 13자리와 소문자 영숫자 4자다(`v1790661162144-a1b2`, 정규식 `v[0-9]{13}-[a-z0-9]{4}`). plugin 이 이 형식만 받는다
- 게시와 `skills` 켜기는 한 `PUT /api/config` 다. 올린 스킬이 하나도 없게 되면 `external_dirs` 를 빈 목록으로 게시한다
- 파일은 hermes 가 읽을 수 있게 파일 644, 디렉터리 755 로 쓴다
- 같은 에이전트의 저장은 기다리는 행 잠금으로 한 번에 하나씩 돈다. 뒤의 저장은 앞의 저장이 게시한 버전을 읽어 그 위에 쓴다
- 잠금은 트랜잭션이 끝날 때 풀린다. 그래서 `save` 와 `delete` 는 `@Transactional` 이고 잠금부터 `markPublished` 와 `prune` 까지 한 트랜잭션이다. 그동안 DB 커넥션을 Hermes 호출 시간(connect 5초, read 30초, `HermesProperties`) 만큼 쥔다. 같은 에이전트의 도구와 공개 범위 변경은 그동안 `AGENT_BUSY` 로 거절된다
- 앞머리 `name` 은 디렉터리 이름과 같아야 한다. 다르면 `VALIDATION_FAILED`
- **배포 전제**: 운영에 두 환경 변수와 공유 디렉터리 마운트(Control Plane 쓰기, Hermes 읽기 전용), `skills.external_dirs` 를 받는 plugin 이 먼저 있어야 한다. 이 저장소에는 적지 않고 PR 본문에 선행 조건으로 적는다

## 작업 항목

### 1. 설정

- `skill/application/SkillProperties.java`(`@ConfigurationProperties("assistant.skill")`): `root`(`${ASSISTANT_SKILL_ROOT}`), `agentRoot`(`${ASSISTANT_SKILL_AGENT_ROOT}`), `keepVersions`(기본 3). 두 경로는 기본값이 없어 비면 기동이 실패한다(결과물과 같다). `application.yml` 과 `README.md` 환경 변수 표에 더한다
- `backend/src/test/resources/application-test.yml` 에 `assistant.skill.root: build/test-skills`, `agent-root: build/test-skills` 를 더한다. 동시 저장 테스트를 위해 datasource URL 에 `;LOCK_TIMEOUT=10000` 을 더한다
- `test/e2e/run.ts` 와 `test/browser/fixtures.ts` 가 Control Plane 을 띄울 때 `ASSISTANT_SKILL_ROOT` 와 `ASSISTANT_SKILL_AGENT_ROOT` 에 **같은** 임시 디렉터리를 준다. 결과물 경로를 만드는 방식을 따른다. 가짜 대시보드가 게시된 경로에서 `SKILL.md` 를 읽으려면 두 뿌리가 같아야 한다. e2e 는 그 디렉터리를 가짜 대시보드에도 알린다

### 2. 입력 규칙

`SkillService.save` 가 저장 전에 모두 본다. 어기면 `VALIDATION_FAILED` 다.

| 대상 | 규칙 |
| --- | --- |
| 스킬 이름 | `[a-z0-9][a-z0-9-]{0,63}`, `new` 가 아니다 |
| `SKILL.md` | 앞머리(`---` 로 감싼 YAML)가 있고 `name` 이 스킬 이름과 같고 `description` 이 비어 있지 않다. 10만 자까지 |
| 참고 파일 경로 | `(references\|templates)/[a-z0-9][a-z0-9._-]{0,99}` 한 단계. `..` 을 담지 않는다. 같은 경로가 두 번 오지 않는다 |
| 참고 파일 수 | 20개까지. `SKILL.md` 는 세지 않는다 |
| 참고 파일 크기 | 파일마다 10만 자 |
| 합계 | `SKILL.md` 와 참고 파일을 UTF-8 바이트로 더해 1 MiB(1,048,576) 까지 |

`SkillStore` 도 쓰고 읽을 때 profile(`HermesProfileName` 규칙), 버전, 스킬 이름, 파일 경로를 같은 규칙으로 보고, 정규화한 결과 경로가 루트 밖이면 거절한다. 읽을 때 심볼릭 링크를 따라가지 않는다(`LinkOption.NOFOLLOW_LINKS`).

### 3. `skill/infra/SkillStore.java`

- `Optional<String> currentVersion(String profile)`: 표식 있는 가장 새 버전
- `Map<String, SkillBundle> readCurrent(String profile)`: 지금 버전의 스킬들(`SkillBundle(String name, String skillMd, List<SkillFile> files)`, `SkillFile(String path, String content)`)
- `String writeVersion(String profile, Map<String, SkillBundle> skills)`: 임시 디렉터리에 쓰고 원자 이동해 새 버전 이름을 돌려준다
- `void markPublished(String profile, String version)`, `void discard(String profile, String version)`, `void deleteAll(String profile)`
- `void prune(String profile, String publishedVersion)`: 표식 있는 버전 중 최근 `keepVersions` 개를 남기고, `publishedVersion` 보다 오래된 표식 없는 디렉터리와 남은 임시 디렉터리를 지운다. `publishedVersion` 보다 새 표식 없는 디렉터리는 두지 않는다(같은 profile 은 잠금으로 한 번에 하나씩 쓰므로 생기지 않는다)
- `String agentPath(String profile, String version)`: `agentRoot/profile/version`

### 4. `hermes/HermesSkillClient` 와 구현, `skill/infra/SkillPublisher`

- `HermesSkillClient`
  - `List<HermesSkill> list(String profile)`(`GET /api/skills?profile=`, 응답 배열에서 `HermesSkill(String name, String description, boolean enabled)`)
  - `void toggle(String profile, String name, boolean enabled)`(`PUT /api/skills/toggle` 본문 `{profile, name, enabled}`)
  - `void publish(String profile, List<String> externalDirs, List<String> apiServerToolsets)`: `PUT /api/config` 본문 `{profile, config:{skills:{external_dirs}}}`. `apiServerToolsets` 가 null 이 아니면 `config.platform_toolsets.api_server` 를 함께 넣는다
  - 4xx 로 거절됐는지 호출한 쪽이 알 수 있게 한다. 예: 4xx 는 `HermesRequestRejected`(`HermesCallFailure` 와 같은 `ApiException` 계열, 원래 오류 코드 유지)로 따로 던진다. 나머지는 지금처럼 `HERMES_UNAVAILABLE`
- `skill/infra/SkillPublisher.java`: `docs/code-architecture.md` 「스킬」 의 클래스 표대로 `external_dirs` 게시와 대시보드 스킬 목록을 맡는다. `HermesSkillClient` 를 부르고, 게시할 도구 목록을 계산한다
  - 지금 켜진 목록(`HermesToolsetClient.readEnabled`)에 `skills` 가 이미 있으면 `apiServerToolsets` 는 null 이다(도구를 건드리지 않는다)
  - 없으면 지금 켜진 목록 가운데 `AgentToolPolicy.isKnown` 인 이름에 `skills` 를 더해 `AgentToolPolicy.requestedForWrite(user, agent, 그 목록, 지금 켜진 목록)` 으로 만든다. 이 결과에 `CONTROL_PLANE_MCP` 가 붙는다
  - 빈 목록(마지막 스킬을 지움)을 게시할 때는 `apiServerToolsets` 를 null 로 둔다

### 5. `skill/application/SkillService.java`

결과 타입은 파일을 따로 둔다(`backend/AGENTS.md`): `SkillList(List<SkillListItem> skills, boolean editable, boolean skillsToolsetEnabled)`, `SkillListItem(String name, String description, SkillSource source, boolean enabled, SkillUsageSummary usage)`, `SkillSource`(`UPLOADED`, `HERMES`), `SkillUsageSummary(long count, Instant lastInvokedAt)`(이 phase 는 늘 null 로 둔다. phase 02 가 채운다), `SkillDetail(String name, String description, String body, List<SkillFileInfo> files)`, `SkillFileInfo(String path, long size)`.

- `SkillList list(CurrentUser user, String code)`: `requireReadable`. Hermes 목록에 지금 버전 이름을 대조해 `source` 를 붙인다. `editable` 은 `isEditableBy`, `skillsToolsetEnabled` 는 `toolsets.readEnabled` 에 `skills` 가 있는가
- `SkillDetail read(CurrentUser user, String code, String name)`: `requireReadable`, 편집자만(아니면 `FORBIDDEN`). 올린 스킬만. 없으면 `SKILL_NOT_FOUND`. `body` 는 **`SKILL.md` 원문 전체(앞머리 포함)**, `description` 은 앞머리 값, `files` 의 `size` 는 UTF-8 바이트
- `SkillDetail save(CurrentUser user, String code, String name, String skillMd, List<SkillFileInput> files)`(`SkillFileInput(String path, String content)`):
  1. 이 메서드와 `delete` 는 `@Transactional` 이다. `requireReadable` 로 읽고 편집자가 아니면 `FORBIDDEN`. 그 에이전트를 `AgentService` 에 새로 더한 기다리는 잠금 메서드(`AgentRepository.findByIdForUpdate` 를 부른다)로 잠근다
  2. 「입력 규칙」 을 본다
  3. Hermes 목록에 같은 이름이 있고 지금 버전에 없으면 `SKILL_NAME_TAKEN`
  4. 지금 버전을 읽어 이 스킬을 바꿔 넣는다. `content` 가 null 인 파일은 **지금 버전의 같은 스킬의 같은 경로 내용을 그대로 쓴다.** 그 파일이 없으면 `VALIDATION_FAILED`. 합계 검사는 채운 뒤의 내용으로 한다. `writeVersion`
  5. `SkillPublisher` 로 새 경로를 게시한다. 4xx 거절이면 `discard` 하고 원래 오류, 그 밖의 실패는 디렉터리를 두고 원래 오류
  6. `markPublished`, `prune(profile, 새 버전)`
- `void delete(CurrentUser user, String code, String name)`: 없으면 `SKILL_NOT_FOUND`. 그 스킬을 뺀 새 버전을 같은 순서로 게시한다. 남은 스킬이 없으면 새 버전을 쓰지 않고 빈 `external_dirs` 를 게시한 뒤 표식 있는 버전을 모두 지운다
- `void toggle(CurrentUser user, String code, String name, boolean enabled)`: 편집자만. `HermesSkillClient.toggle`
- `ErrorCode.SKILL_NOT_FOUND(404)`, `SKILL_NAME_TAKEN(409)` 를 더한다

### 6. 경로와 다른 곳의 연결

- `skill/presentation/SkillController.java` 와 `SkillDtos.java`: `docs/code-architecture.md` 「스킬」 절의 다섯 경로. 요청과 응답 모양은 그 표를 따른다. `PUT` 의 `files[].content` 는 생략할 수 있다
- `AgentToolService.write`: 올린 스킬이 있는데(`SkillStore.currentVersion` 이 있고 그 버전에 스킬이 하나 이상) 요청 목록에 `skills` 가 없으면 `VALIDATION_FAILED`
- `AgentLifecycleService.delete`: `profileManaged` 면 `deprovision` 뒤에 `SkillStore.deleteAll(profile)`. 실패하면 로그만 남기고 삭제는 성공으로 둔다(profile 은 이미 지웠다)
- `docs/code-architecture.md` 「스킬」: 게시 실패 줄을 「4xx 로 거절되면 새 디렉터리를 지운다. timeout 과 5xx 는 표식 없이 남기고, 다음 게시가 성공한 뒤 그보다 오래된 표식 없는 디렉터리를 지운다. 실패한 저장의 변경은 반영되지 않으므로 다시 저장한다」 로 고친다. `GET …/{name}` 의 `body` 가 앞머리를 포함한 원문이고 `PUT` 의 `content` 를 생략하면 지금 파일을 유지한다는 것을 표에 적는다. 잠금 줄을 「기다리는 에이전트 행 잠금」 으로 적는다
- `docs/flow.md` 「스킬을 저장할 때」: `alt` 의 실패 분기를 4xx 와 그 밖으로 나눈다
- `docs/hermes/profiles.md` 「Control Plane 이 부르는 대시보드 plugin 경로」: 스킬 게시 줄에 버전 이름 형식 `v[0-9]{13}-[a-z0-9]{4}` 를 적는다

### 7. 이 phase 를 검증하는 테스트

- 스킬 디렉터리를 쓰는 테스트는 앞선 실행이 남긴 버전 디렉터리에 기대지 않는다. 직접 만드는 `SkillStore` 는 `@TempDir` 로 루트를 받고(`ArtifactWriteServiceTest` 선례), 설정 경로를 쓰는 Spring 테스트는 `@BeforeEach` 에서 그 profile 디렉터리를 지운다
- `backend/src/test/java/com/bifos/assistant/skill/SkillStoreTest.java` 신규: 쓰기가 새 버전을 만들고 표식 전에는 지금 버전이 옛 것이다. `prune` 이 표식 있는 3개만 남기고 게시한 버전보다 오래된 표식 없는 것을 지운다. `../`, 절대 경로, 루트 밖 경로, 심볼릭 링크를 거절한다. 파일 권한이 644 와 755 다
- `backend/src/test/java/com/bifos/assistant/skill/SkillServiceTest.java` 신규(가짜 `HermesSkillClient` 와 도구 클라이언트):
  - 저장하면 한 번의 `publish` 에 새 경로와 `skills` 가 든 도구 목록(`CONTROL_PLANE_MCP` 포함)이 함께 간다. `skills` 가 이미 켜졌으면 도구 목록은 null 이다
  - 게시가 4xx 로 거절되면 새 디렉터리가 없고 지금 버전이 그대로다
  - 게시가 timeout(5xx)으로 실패하면 새 디렉터리가 표식 없이 남고 지금 버전은 옛 것이다. 다음 저장이 성공하면 그 표식 없는 디렉터리가 지워지고, 새 버전에는 실패한 저장의 변경이 없다
  - 편집자가 아니면 `FORBIDDEN`. 앞머리 이름이 다르면, 경로가 `scripts/` 면, 파일이 21개면, 합계가 1 MiB 를 넘으면 `VALIDATION_FAILED`. Hermes 기본 스킬 이름이면 `SKILL_NAME_TAKEN`
  - `content` 를 생략한 파일은 지금 내용이 그대로 새 버전에 있다
  - 지우면 그 스킬이 빠진 버전이 게시되고, 마지막 스킬을 지우면 빈 목록이 게시된다
  - 같은 에이전트에 두 저장이 동시에 와도 둘 다 반영된 버전이 남는다. 테스트 클래스는 `@Transactional` 이 아니고 두 저장을 다른 스레드(각자 트랜잭션)에서 돌린다. 가짜 클라이언트가 첫 게시를 붙잡는 동안 둘째가 잠금을 기다린다. H2 의 기본 lock timeout 이 2초라서 `application-test.yml` 의 datasource URL 에 `;LOCK_TIMEOUT=10000` 을 더한다
- `backend/src/test/java/com/bifos/assistant/skill/SkillControllerTest.java` 신규: 경로별 상태 코드와 응답 모양
- `backend/src/test/java/com/bifos/assistant/hermes/HermesSkillRequestTest.java` 신규: `publish` 본문이 `{profile, config:{skills:{external_dirs}}}` 이고 도구 목록이 주어지면 `platform_toolsets.api_server` 가 함께 있다. `list` 가 query `profile` 을 보내고 배열 응답을 읽는다. 4xx 와 5xx 가 다른 예외가 된다
- `backend/src/test/java/com/bifos/assistant/agent/AgentToolServiceTest.java` 에 올린 스킬이 있을 때 `skills` 끄기 거절을 더한다
- `backend/src/test/java/com/bifos/assistant/agent/AgentLifecycleServiceTest.java` 에 `profileManaged` 에이전트를 지우면 deprovision 뒤 스킬 디렉터리가 없고, `deleteAll` 이 실패해도 삭제가 성공하는 사례를 더한다. `AgentApiBaseUrlUpdateTest.java` 는 생성자 인자를 맞춘다
- `test/e2e/fake-hermes.ts`: `GET /api/skills`, `PUT /api/skills/toggle`, `PUT /api/config` 의 `skills.external_dirs` 를 받아 게시된 디렉터리의 `SKILL.md` 앞머리를 읽어 목록에 보인다. 게시 거절 규칙은 `docs/hermes/profiles.md` 표를 따른다: 경로 둘 이상, 없는 디렉터리, `skills` 가 켜지지 않은 채(본문의 도구 목록에도 없이) 비지 않은 목록 게시, 허용하지 않는 키는 400. 도구 목록이 함께 오면 지금 도구 규칙(`CONTROL_PLANE_MCP` 포함 등)을 그대로 적용한다
- `test/e2e/scenarios/skills.ts` 신규, `test/e2e/run.ts` 에 더한다: 주인이 스킬을 올리면 목록에 `UPLOADED` 로 보이고, 다른 사용자는 쓰지 못하며, 지우면 사라진다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests 'com.bifos.assistant.skill.*'
cd backend && ./gradlew test
node test/e2e/run.ts
cd web && pnpm test:browser
scripts/check-public-safe.sh
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/application.yml` | 수정 |
| `README.md` | 수정 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillBundle.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillFile.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillFileInput.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillList.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillListItem.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillSource.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillUsageSummary.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillDetail.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillFileInfo.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillFrontmatter.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/infra/SkillStore.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/infra/SkillPublisher.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/presentation/SkillController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/presentation/SkillDtos.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesSkillClient.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesSkillClient.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesRequestRejected.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesCallFailure.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/AgentToolPolicy.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentToolService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentLifecycleService.java` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillStoreTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillControllerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/hermes/HermesSkillRequestTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentToolServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentLifecycleServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentApiBaseUrlUpdateTest.java` | 수정 |
| `docs/code-architecture.md` | 수정 |
| `docs/flow.md` | 수정 |
| `docs/hermes/profiles.md` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/skills.ts` | 신규 |
| `test/e2e/run.ts` | 수정 |
| `test/browser/fixtures.ts` | 수정 |
