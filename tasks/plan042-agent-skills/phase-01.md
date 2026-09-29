# Phase 01. backend 가 스킬을 버전 디렉터리에 쓰고 Hermes 에 게시한다

**Execution profile**: deep

## 목표

`GET/PUT/DELETE /api/v1/agents/{code}/skills…` 를 연다. 저장할 때마다 그 profile 의 올린 스킬 전체를 새 버전 디렉터리에 쓰고 `skills.external_dirs` 로 게시한다. 스킬 본문은 데이터베이스에 두지 않는다.

**범위 외**: 호출 이력과 사용량(phase 02), 화면(phase 03), 스킬 커맨드(다른 계획).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-034-올린-스킬은-control-plane-이-버전-디렉터리에-쓰고-hermes-는-읽기만-한다.md`, `docs/code-architecture.md` 의 「스킬」 절, `docs/flow.md` 의 「스킬을 저장할 때」 절, `docs/hermes/profiles.md` 의 「Control Plane 이 부르는 대시보드 plugin 경로」 절, `docs/hermes/tools-and-skills.md` 의 「스킬 파일과 색인 적용 시점」 절

- 공유 디렉터리 설정의 선례: `chat/application/ArtifactProperties`, `chat/infra/ArtifactStore`, `application.yml` 의 `assistant.artifact.root`(`${ASSISTANT_ARTIFACT_ROOT}`, 기본값 없음). 원자 이동(`AtomicMover`)과 경로 검사가 거기 있다
- 대시보드 호출은 `hermes/HermesDashboardClient`(`HttpHermesDashboardClient`), 도구는 `hermes/HermesToolsetClient`(`readEnabled`, `writeApiServer`). 스킬용 호출을 `hermes/HermesSkillClient`(구현 `HttpHermesSkillClient`)로 더한다
- 편집 판정은 `AgentService.isEditableBy`, 읽기는 `requireReadable`, 잠금은 `requireReadableForUpdate`
- 도구 저장은 `agent/application/AgentToolService` 가 `toolsets.writeApiServer` 를 부른다. `AgentToolPolicy` 가 등급과 Control Plane MCP 를 정한다. `skills` toolset 은 주인 등급이다
- 에이전트 지우기는 `agent/application/AgentLifecycleService.delete`(에이전트 만들기 계획)가 한다. 그 계획이 머지되지 않았으면 → Blocked 조건
- SnakeYAML 은 Spring Boot 가 이미 가져온다
- 가짜 대시보드는 `test/e2e/fake-hermes.ts`(`PROFILES_PATH`, `CONFIG_PATH` 등)

## 의도 메모

- **버전 디렉터리는 통째로 새로 쓴다.** 쓰는 도중의 반쯤 바뀐 스킬을 실행이 읽지 않게 한다. 임시 이름으로 다 쓴 뒤 원자 이동한다
- 지금 버전은 `.published` 표식이 있는 가장 새 디렉터리다. 설정을 읽지 않는다
- 게시가 실패하면 새 디렉터리를 지우고 옛 버전을 둔다. 성공하면 표식을 쓰고, 표식 있는 최근 3개만 남기고 표식 없는 디렉터리도 지운다
- 버전 이름은 `v` 뒤에 UTC 밀리초와 무작위 4자다(`v1790661162144-a1b2`). plugin 의 버전 패턴과 맞춘다
- 게시와 `skills` 켜기는 한 `PUT /api/config` 다. 올린 스킬이 하나도 없게 되면 `external_dirs` 를 빈 목록으로 게시한다
- 파일은 hermes 가 읽을 수 있게 파일 644, 디렉터리 755 로 쓴다
- 같은 에이전트의 저장은 `requireReadableForUpdate` 로 한 번에 하나씩 돈다
- 앞머리 `name` 은 디렉터리 이름과 같아야 한다. 다르면 `VALIDATION_FAILED`

## Blocked 조건

- `agent/application/AgentLifecycleService` 가 없으면 → `PHASE_BLOCKED: 에이전트 만들기 계획이 먼저 머지되어야 한다`

## 작업 항목

### 1. 설정

- `skill/application/SkillProperties.java`(`@ConfigurationProperties("assistant.skill")`): `root`(`${ASSISTANT_SKILL_ROOT}`), `agentRoot`(`${ASSISTANT_SKILL_AGENT_ROOT}`), `keepVersions`(기본 3). 두 경로는 기본값이 없어 비면 기동이 실패한다(결과물과 같다). `application.yml` 과 `README.md` 환경 변수 표에 더한다
- 테스트 설정 `backend/src/test/resources/application-test.yml` 에 결과물 경로와 같은 방식으로 임시 경로를 더한다

### 2. `skill/infra/SkillStore.java`

- `Optional<String> currentVersion(String profile)`: 표식 있는 가장 새 버전
- `Map<String, SkillBundle> readCurrent(String profile)`: 지금 버전의 스킬들(`SkillBundle(String name, String skillMd, List<SkillFile> files)`, `SkillFile(String path, String content)`)
- `String writeVersion(String profile, Map<String, SkillBundle> skills)`: 임시 디렉터리에 쓰고 원자 이동해 새 버전 이름을 돌려준다
- `void markPublished(String profile, String version)`, `void discard(String profile, String version)`, `void prune(String profile)`, `void deleteAll(String profile)`
- `String agentPath(String profile, String version)`: `agentRoot/profile/version`
- 경로 검사: profile 과 버전과 스킬 이름과 파일 경로가 모두 규칙에 맞는지, 결과 경로가 루트 밖으로 나가지 않는지

### 3. `hermes/HermesSkillClient` 와 구현

- `List<HermesSkill> list(String profile)`(`GET /api/skills?profile=`, `HermesSkill(String name, String description, boolean enabled)`)
- `void toggle(String profile, String name, boolean enabled)`(`PUT /api/skills/toggle`)
- `void publish(String profile, List<String> externalDirs, List<String> apiServerToolsets)`(`PUT /api/config` 에 `skills.external_dirs` 와, 목록이 주어지면 `platform_toolsets.api_server` 를 함께)

### 4. `skill/application/SkillService.java`

- `SkillList list(CurrentUser user, String code)`: `requireReadable`. Hermes 목록에 지금 버전 이름을 대조해 `source` 를 `UPLOADED`/`HERMES` 로 붙인다. `editable` 과, `skills` 가 켜졌는지(`toolsets.readEnabled`)
- `SkillDetail read(CurrentUser user, String code, String name)`: 올린 스킬만. 없으면 `SKILL_NOT_FOUND`
- `SkillDetail save(CurrentUser user, String code, String name, String skillMd, List<SkillFile> files)`:
  1. 편집자가 아니면 `FORBIDDEN`. `requireReadableForUpdate` 로 잠근다
  2. 이름 `[a-z0-9][a-z0-9-]{0,63}` 이고 `new` 가 아니다(새 스킬 화면 경로와 겹친다), 앞머리 `name` 이 이름과 같고 `description` 이 있다, 경로는 `references/` 나 `templates/` 아래, 파일 20개, 파일마다 10만 자, 합계 1 MiB. 어기면 `VALIDATION_FAILED`
  3. Hermes 목록에 같은 이름이 있고 올린 것이 아니면 `SKILL_NAME_TAKEN`
  4. 지금 버전을 읽어 이 스킬을 바꿔 넣고 `writeVersion`
  5. 지금 켜진 도구에 `skills` 를 더한 목록과 새 경로로 `publish`. 실패하면 `discard` 하고 원래 오류
  6. `markPublished`, `prune`
- `void delete(CurrentUser user, String code, String name)`: 그 스킬을 뺀 새 버전을 같은 순서로 게시한다
- `void toggle(CurrentUser user, String code, String name, boolean enabled)`: 편집자만. `HermesSkillClient.toggle`
- `ErrorCode.SKILL_NOT_FOUND(404)`, `SKILL_NAME_TAKEN(409)` 를 더한다

### 5. 경로와 다른 곳의 연결

- `skill/presentation/SkillController.java`: `docs/code-architecture.md` 「스킬」 절의 다섯 경로. 요청과 응답 모양은 그 표를 따른다
- `AgentToolService`: 올린 스킬이 있는데 `skills` 를 빼려 하면 `VALIDATION_FAILED`
- `AgentLifecycleService.delete`: `profileManaged` 면 `SkillStore.deleteAll(profile)` 을 `deprovision` 뒤에 부른다

### 6. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/skill/SkillStoreTest.java` 신규: 쓰기가 새 버전을 만들고 표식 전에는 지금 버전이 옛 것이다. `prune` 이 표식 있는 3개만 남기고 표식 없는 것을 지운다. `../`, 절대 경로, 루트 밖 경로를 거절한다. 파일 권한이 644 와 755 다
- `backend/src/test/java/com/bifos/assistant/skill/SkillServiceTest.java` 신규(가짜 `HermesSkillClient` 와 도구 클라이언트): 저장하면 한 번의 `publish` 에 새 경로와 `skills` 가 함께 가고, 게시가 실패하면 새 디렉터리가 없고 지금 버전이 그대로다. 편집자가 아니면 `FORBIDDEN`. 앞머리 이름이 다르면, 경로가 `scripts/` 면, 상한을 넘으면 `VALIDATION_FAILED`. Hermes 기본 스킬 이름이면 `SKILL_NAME_TAKEN`. 지우면 그 스킬이 빠진 버전이 게시되고, 마지막 스킬을 지우면 빈 목록이 게시된다. 두 저장이 동시에 와도 둘 다 반영된 버전이 남는다
- `backend/src/test/java/com/bifos/assistant/skill/SkillControllerTest.java` 신규: 경로별 상태 코드와 응답 모양
- `backend/src/test/java/com/bifos/assistant/agent/AgentToolServiceTest.java` 에 올린 스킬이 있을 때 `skills` 끄기 거절을 더한다
- `test/e2e/fake-hermes.ts`: `GET /api/skills`, `PUT /api/skills/toggle`, `PUT /api/config` 의 `skills.external_dirs` 를 받아 게시된 디렉터리의 `SKILL.md` 를 읽어 목록에 보인다. `test/e2e/scenarios/skills.ts` 신규, `test/e2e/run.ts` 에 더한다: 주인이 스킬을 올리면 목록에 `UPLOADED` 로 보이고, 다른 사용자는 쓰지 못하며, 지우면 사라진다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests 'com.bifos.assistant.skill.*'
cd backend && ./gradlew test
node test/e2e/run.ts
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
| `backend/src/main/java/com/bifos/assistant/skill/infra/SkillStore.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/presentation/SkillController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/presentation/SkillDtos.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesSkillClient.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesSkillClient.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentToolService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentLifecycleService.java` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillStoreTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillControllerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentToolServiceTest.java` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/skills.ts` | 신규 |
| `test/e2e/run.ts` | 수정 |
