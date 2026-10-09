# Phase 01. 스킬 저장이 넓은 경로와 scripts 와 이전 버전을 다룬다

**Execution profile**: deep

## 목표

묶음 올리기가 기댈 저장 기반을 만든다.
스킬 파일 경로 규칙을 넓히고, `scripts/` 가 든 스킬은 실행 공간이 있는 에이전트에만 저장하고, 스킬마다 이전 버전 하나를 남겨 되돌린다.
편집기 저장(`PUT /api/v1/agents/{code}/skills/{name}`)도 같은 규칙을 탄다.

**범위 외**: zip 받기와 묶음 검사, 미리보기와 올리기 경로(다음 PR 의 plan). plugin 의 스킬 마운트와 `require_sandbox` 판정(phase 02). 화면(그 뒤 plan).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261009-skill-package.md`, `docs/backend/skill.md` 의 「제한」 표, 「스킬 저장이 갈리는 지점」, 「스크립트와 실행 공간」, 「이전 버전」, `docs/adr/ADR-034-올린-스킬은-control-plane-이-버전-디렉터리에-쓰고-hermes-는-읽기만-한다.md`

- 저장 순서는 `backend/src/main/java/com/bifos/assistant/skill/application/SkillService.java` 의 `save` 와 `publishVersion` 이다. 에이전트 행 잠금(`requireEditableLocked`) 안에서 버전을 쓰고(`store.writeVersion`), 게시하고(`publisher.publish`), 표식을 쓰고(`store.markPublished`), 정리한다(`store.prune`). 게시가 `HermesRequestRejected` 면 `discardQuietly` 로 새 디렉터리를 지운다
- 경로 규칙과 읽기, 쓰기는 `backend/src/main/java/com/bifos/assistant/skill/infra/SkillStore.java` 하나에 있다. 지금 `FILE_PATH` 는 `(references|templates)/[a-z0-9][a-z0-9._-]{0,99}` 이고 `readBundle` 은 그 두 디렉터리 한 단계만 읽는다. 파일은 `FILE_PERMISSIONS`(644)로 쓴다
- 게시는 `backend/src/main/java/com/bifos/assistant/skill/infra/SkillPublisher.java` 의 `publish(user, agent, externalDirs, connectorServers)` 가 `HermesSkillClient.publish(profile, externalDirs, apiServerToolsets, sandboxOwner)` 를 부른다. `skills` 가 이미 켜져 있으면 `apiServerToolsets` 는 `null` 이다(`toolsetsWithSkills`)
- HTTP 구현은 `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesSkillClient.java` 의 `publish` 다. 본문은 `{profile, config, sandbox_owner}` 이고, 409 `sandbox_unavailable` 은 `HermesCallFailure.sandboxRejection` 이 `HermesRequestRejected(ErrorCode.AGENT_SANDBOX_UNAVAILABLE, …)` 로 바꾼다. 도구 목록을 함께 보낼 때만 `attachmentDirectory.ensure(sandboxOwner)` 를 부른다
- 같은 모양의 「실행 공간 필수」 쓰기 본보기는 `backend/src/main/java/com/bifos/assistant/hermes/HermesToolsetClient.java` 의 `writeApiServerInSandbox` 와 `HttpHermesToolsetClient.writeConfig(…, requireSandbox)` 다
- 오류 코드는 `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` 에 있다. `SKILL_NOT_FOUND`(404), `SKILL_NAME_TAKEN`(409) 근처에 더한다
- 상세 응답은 `backend/src/main/java/com/bifos/assistant/skill/application/SkillDetail.java` 와 `backend/src/main/java/com/bifos/assistant/skill/presentation/SkillDtos.java` 의 `SkillDetailView` 다
- 시험 본보기: `backend/src/test/java/com/bifos/assistant/skill/SkillServiceTest.java` 는 Spring 시험이다. 실제 `SkillStore`(`build/test-skills`)와 저장소를 쓰고 `HermesSkillClient`, `HermesToolsetClient` 를 mock 으로 둔다. `verify(skillClient).publish(eq(profile), dirs.capture(), apiServer.capture(), eq("u" + OWNER.id()))` 처럼 게시를 확인한다. `SkillStoreTest`, `SkillControllerTest`(standalone MockMvc, `SkillService` mock)도 같은 디렉터리에 있다

## 의도 메모

- `HermesSkillClient.publish` 의 인자를 늘리지 않고 `publishRequiringSandbox` 를 따로 둔다. 기존 시험의 `verify(...publish(...))` 가 그대로 맞고, 도구 쪽 `writeApiServerInSandbox` 와 이름이 짝이 된다
- `scripts/` 판정은 저장하는 그 스킬에만 한다. 함께 실리는 다른 스킬은 보지 않는다. 셸을 끈 에이전트도 다른 스킬을 고칠 수 있어야 한다(ADR 의 대안 기각)
- 마지막 스킬을 지울 때 profile 디렉터리를 남긴다. phase 02 의 plugin 이 그 디렉터리를 실행 공간에 붙이므로, 지우면 다음 컨테이너 생성이 없는 원본을 만난다. 에이전트를 지울 때(`AgentLifecycleService` 가 부르는 `deleteAll`)는 지금처럼 다 지운다
- 이전 버전 쓰기는 게시가 성공한 뒤에 한다. 실패해도 저장을 실패로 바꾸지 않는다. Hermes 는 이미 새 버전을 쓴다
- 실행 비트는 경로로 정한다. `scripts/` 아래만 755 다. 다음 PR 의 묶음 올리기도 받은 mode 를 보지 않는다
- 대시보드가 409 `sandbox_unavailable` 로 거절한 경우만 `SKILL_SCRIPTS_NEED_SANDBOX` 로 바꾼다. 첨부 디렉터리 준비 실패(`SandboxAttachmentDirectory.ensure`)도 같은 `AGENT_SANDBOX_UNAVAILABLE` 의 `HermesRequestRejected` 지만 cause 가 `IOException` 이고 원인이 셸 유무가 아니다. 그 경우는 바꾸지 않고 그대로 올린다

## 작업 항목

### 1. `SkillStore` 의 경로 규칙을 넓힌다

- `FILE_PATH` 정규식을 지우고 `public static void requireFilePath(String path)` 를 `docs/backend/skill.md` 「제한」 표의 「경로」 줄대로 다시 쓴다
  - 1~4 조각, 전체 200자 이하. 조각은 `[A-Za-z0-9][A-Za-z0-9._-]{0,99}`
  - 한 조각이면 `.md` 나 `.txt` 로 끝나야 하고(대소문자 무시) `SKILL.md` 와 대소문자 무시로 같으면 안 된다
  - 둘 이상이면 첫 조각이 `references`, `templates`, `scripts`, `assets` 중 하나다
  - 마지막 조각이 대소문자 무시로 `SKILL.md` 이면 거절한다
  - 어기면 지금처럼 `VALIDATION_FAILED` 다. 메시지는 「skill file paths must follow the skill file path rule」 로 바꾼다
- 여러 경로를 함께 보는 `public static void requireFileSet(Collection<String> paths)` 를 더한다. 대소문자 무시로 같은 두 경로, 한 경로가 다른 경로의 디렉터리 앞부분(`a/b` 와 `a/b/c.md`)인 경우를 `VALIDATION_FAILED` 로 거절한다. `SkillService.requireFiles` 와 `writeVersion` 이 부른다
- 스크립트 판정용 `public static boolean isScript(String path)` 를 둔다. `scripts/` 로 시작하면 참이다
- `readBundle` 을 스킬 디렉터리 전체를 링크를 따라가지 않고 걷도록 바꾼다. 이름이 `.` 으로 시작하는 파일과 디렉터리는 건너뛰고 그 아래로 내려가지 않는다. 나머지에서 링크나 일반 파일·디렉터리가 아닌 항목을 만나면 경로와 관계없이 `IOException` 이다(plugin 의 `_skill_dir_rejection` 이 버전 디렉터리 안의 모든 링크를 거절하는 것과 같다). `SKILL.md` 를 뺀 일반 파일 가운데 상대 경로가 `requireFilePath` 를 통과하는 것만 읽고, 통과하지 못하는 일반 파일은 건너뛴다. 결과는 경로 순이다
- `writeVersion` 에서 `isScript(file.path())` 인 파일은 `rwxr-xr-x`, 나머지는 지금처럼 `rw-r--r--` 로 쓴다
- 지금 `createDirectory` 는 `Files.createDirectories` 뒤 끝 디렉터리 하나에만 755 를 준다. 중첩 경로(`scripts/lib/util.py`)의 중간 디렉터리가 umask 를 따르지 않게, 스킬 디렉터리에서 파일의 부모까지 한 단계씩 `createDirectory` 를 불러 각각 755 로 만든다. `writePrevious` 도 같은 방법을 쓴다

### 2. `SkillStore` 에 이전 버전 자리와 비우기를 더한다

- 상수 `PREVIOUS_DIR = ".previous"`, `SAVED_AT = ".saved-at"`
- `public void writePrevious(String profile, SkillBundle bundle)`: `<root>/<profile>/.previous/<이름>` 을 임시 디렉터리(`.previous/.tmp-<이름>-<무작위>`)에 다 쓴 뒤 기존 것을 지우고 옮긴다. `.saved-at` 에 `clock.millis()` 를 10진수 글로 쓴다. 파일 권한은 `writeVersion` 과 같다
- `public Optional<PreviousSkill> readPrevious(String profile, String name)`: 없으면 빈 값. `PreviousSkill` 은 `skill/infra/PreviousSkill.java` 의 record `(SkillBundle bundle, Instant savedAt)` 다. `readBundle` 로 읽는다
- `public void deletePrevious(String profile, String name)`: 없으면 지나간다
- `public void clearVersions(String profile)`: profile 디렉터리 안의 버전 디렉터리, `.previous`, `.tmp-*` 를 지우고 profile 디렉터리는 남긴다. `deleteAll` 은 그대로 둔다
- `prune` 은 `.previous` 를 지우지 않는다. 지금도 버전 이름과 `.tmp-` 만 보므로 바꿀 것이 없다. 시험으로만 확인한다

### 3. 게시에 「실행 공간 필수」 를 더한다

- `HermesSkillClient` 에 `void publishRequiringSandbox(String profile, List<String> externalDirs, List<String> apiServerToolsets, String sandboxOwner);` 를 더한다. Javadoc 에 「도구 목록은 null 이 아니다. 본문에 `require_sandbox: true` 를 싣는다. 409 `sandbox_unavailable` 이면 `AGENT_SANDBOX_UNAVAILABLE` 의 `HermesRequestRejected`」 를 적는다
- `HttpHermesSkillClient` 의 `publish` 본문 조립을 private `send(profile, externalDirs, apiServerToolsets, sandboxOwner, boolean requireSandbox)` 로 옮기고 두 공개 메서드가 부른다. `requireSandbox` 면 본문에 `"require_sandbox", true` 를 더한다. 본문은 지금 `Map.of(...)` 라 `LinkedHashMap` 으로 바꾼다
- `SkillPublisher` 에 `public void publishWithScripts(CurrentUser user, Agent agent, List<String> externalDirs, Set<String> connectorServers)` 를 더한다. `skills` 가 켜져 있어도 지금 켜진 알려진 도구에 `skills` 를 (없으면) 더한 목록을 `AgentToolPolicy.requestedForWrite` 로 판정해 `publishRequiringSandbox` 로 보낸다. 지금 `toolsetsWithSkills` 의 목록 조립을 「`skills` 가 이미 있으면 null」 분기 앞의 private 메서드로 나눠 함께 쓴다
- `SkillPublisher` 에 `public boolean terminalEnabled(Agent agent)` 를 더한다. `enabledToolsets(agent).contains("terminal")`. 문자열은 `AgentToolPolicy` 에 상수가 있으면 그것을 쓰고 없으면 `AgentToolPolicy` 에 `TERMINAL = "terminal"` 을 더해 쓴다

### 4. `SkillService` 가 scripts 와 이전 버전을 다룬다

- `ErrorCode` 에 `SKILL_SCRIPTS_NEED_SANDBOX(HttpStatus.CONFLICT)` 를 더한다
- `save` 에서 `bundleOf` 뒤, `publishVersion` 앞에서 새 묶음에 `SkillStore.isScript` 인 파일이 있으면 `publisher.terminalEnabled(agent)` 를 본다. 거짓이면 `new ApiException(ErrorCode.SKILL_SCRIPTS_NEED_SANDBOX, "this agent has no sandbox shell to run skill scripts")` 를 던진다. 버전 디렉터리를 쓰기 전이다
- `publishVersion(user, agent, skills)` 에 `boolean withScripts` 인자를 더한다. 참이면 `publisher.publishWithScripts`, 거짓이면 지금의 `publisher.publish` 를 부른다. `withScripts` 이고 잡은 `HermesRequestRejected` 의 코드가 `AGENT_SANDBOX_UNAVAILABLE` 이며 `getCause()` 가 `RestClientResponseException` 이면(대시보드의 거절), 디렉터리를 지운 뒤 `SKILL_SCRIPTS_NEED_SANDBOX` 의 `ApiException` 을 원래 예외를 cause 로 던진다. `delete` 가 부르는 곳은 `false` 다
- 새 스킬일 때만 하는 검사(Hermes 기본 스킬 이름 `SKILL_NAME_TAKEN`, 개수 한도, 설명 60자)를 private `requireCreatable(String profile, String name, SkillFrontmatter frontmatter, Map<String, SkillBundle> current, Map<String, SkillBundle> pending)` 로 나눈다. `save` 는 새 스킬이면 그것을 부르고, 아래 공용 저장 흐름에는 넣지 않는다. 다음 PR 의 묶음 올리기도 이 메서드를 부른다
- 게시와 `prune` 이 끝난 뒤, 저장 전에 그 이름의 스킬이 있었으면(`uploaded != null`) `store.writePrevious(profile, uploaded)` 를 부른다. `RuntimeException` 이면 `log.warn` 으로 남기고 넘어간다
- `delete` 는 게시가 끝난 뒤 `store.deletePrevious(profile, name)` 를 부른다. 남는 스킬이 없을 때는 `store.deleteAll(profile)` 대신 `store.clearVersions(profile)` 를 부른다
- `SkillDetail` record 에 `Instant previousSavedAt` 을 더한다. `read` 와 `save` 의 응답에서 `store.readPrevious(profile, name)` 의 `savedAt` 을 싣는다. `save` 의 응답은 이전 버전을 쓴 뒤의 값이다
- `public SkillDetail restorePrevious(CurrentUser user, String code, String name)` 를 더한다. `@Transactional`. 잠금을 잡고 지금 스킬(지금 버전, 없으면 표식 없는 더 새 버전)이 없으면 `SKILL_NOT_FOUND`, 이전 버전이 없어도 `SKILL_NOT_FOUND` 다. 이전 버전의 `skillMd` 를 `requireSkillMd` 로 다시 보고(비밀 요청 칸), 새 스킬 검사(설명 60자, 개수, Hermes 이름)는 하지 않는다. 그 뒤 `save` 와 같은 순서(`requireNoStoredSecretRequests`, scripts 판정, `publishVersion`, `SkillsChanged`, 이전 버전 쓰기)를 탄다. `save` 와 이 메서드가 같은 private 저장 흐름(새 스킬 검사를 뺀 것)을 쓰게 정리한다

### 5. 되돌리기 경로와 상세 응답

- `SkillController` 에 `@PostMapping("/{name}/restore-previous")` 를 더한다. `SkillDetailView.from(skills.restorePrevious(currentUser.require(), code, name))`
- `SkillDtos.SkillDetailView` 에 `Instant previousSavedAt` 을 더한다. `null` 이어도 칸을 보낸다(`@JsonInclude` 를 붙이지 않는다). `docs/backend/skill.md` 의 상세 응답 줄과 같다

### 6. 시험

- `SkillStoreTest`
  - 경로 규칙: `FORMS.md`, `references/API.md`, `scripts/lib/util.py`, `assets/a/b/c.txt` 는 받고, `notes.py`(맨 위 `.md`/`.txt` 아님), `SKILL.md`, `references/SKILL.md`, `other/x.md`, `scripts/a/b/c/d.py`(5조각), `references/.hidden`, `references/../x.md` 는 거절한다
  - `requireFileSet`: `references/A.md` 와 `references/a.md`, `references/a` 와 `references/a/b.md` 를 거절한다
  - `scripts/run.sh` 는 755, `references/a.md` 는 644 로 쓰인다(`Files.getPosixFilePermissions`). `scripts/lib/util.py` 를 쓰면 `scripts` 와 `scripts/lib` 디렉터리가 모두 755 다
  - 버전 디렉터리의 스킬 안에 링크를 두면(`references/x.md` 를 다른 파일로 가리키는 링크, 경로 규칙 밖의 `bin` 링크 모두) 읽기가 `INTERNAL_ERROR` 다
  - 기존 `writeVersion` 거절 시험(162행에서 166행 근처)의 `scripts/run.sh` 를 여전히 거절되는 `bin/run.sh` 로 바꾼다
  - `readBundle` 이 중첩 경로와 맨 위 `.md` 를 읽고, `.published` 같은 점 파일은 읽지 않는다
  - `writePrevious` 뒤 `readPrevious` 가 같은 묶음과 시각을 주고, 두 번 쓰면 뒤의 것이 남는다. `prune` 뒤에도 `.previous` 가 남는다. `clearVersions` 뒤 profile 디렉터리는 남고 안은 비었다
- `SkillServiceTest`
  - `scripts/run.sh` 가 든 스킬을 `terminal` 이 없는 에이전트에 저장하면 `SKILL_SCRIPTS_NEED_SANDBOX` 이고 새 버전 디렉터리가 없으며 `skillClient` 의 두 게시가 모두 불리지 않는다
  - `terminal` 이 켜진 에이전트(`toolsets.readEnabled` 가 `skills`, `terminal` 을 준다)에 저장하면 `publishRequiringSandbox` 가 `skills` 와 `terminal` 이 든 목록으로 불리고 `publish` 는 불리지 않는다
  - `publishRequiringSandbox` 가 `RestClientResponseException` 을 cause 로 둔 `AGENT_SANDBOX_UNAVAILABLE` 의 `HermesRequestRejected` 를 던지면 `SKILL_SCRIPTS_NEED_SANDBOX` 이고 새 버전 디렉터리가 지워졌다. cause 가 `IOException` 이면 `AGENT_SANDBOX_UNAVAILABLE` 그대로다
  - 기존 `breakingInputRulesIsValidationFailedAndHermesNameIsSkillNameTaken` 의 `scripts/run.sh` 단언(519행에서 526행 근처)을 여전히 `VALIDATION_FAILED` 인 `bin/run.sh` 로 바꾼다
  - scripts 가 든 기존 스킬 A 가 있는 에이전트에서 `terminal` 없이 scripts 없는 스킬 B 를 저장하면 성공하고 `publish` 가 불린다
  - 같은 스킬을 두 번 저장하면 `readPrevious` 가 첫 내용이고 응답의 `previousSavedAt` 이 차 있다. `restorePrevious` 뒤 지금 스킬이 첫 내용이고 이전 버전이 둘째 내용이다. 이전 버전이 없으면 `SKILL_NOT_FOUND`
  - 지우면 이전 버전도 없고, 마지막 스킬을 지운 뒤 profile 디렉터리는 남는다
- `SkillControllerTest`: `POST /api/v1/agents/{code}/skills/{name}/restore-previous` 가 `SkillDetailView` 모양(`previousSavedAt` 포함)을 주고, 서비스의 `SKILL_NOT_FOUND` 가 404 다

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.skill.*'
cd backend && ./gradlew test
cd backend && ./gradlew qualityCheck
```

- 셋 다 종료 코드 0
- `cd backend && ./gradlew spotlessApply` 결과는 기능 커밋과 다른 커밋으로 둔다(`backend/AGENTS.md` 「포맷」)

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/skill/infra/SkillStore.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/skill/infra/PreviousSkill.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/infra/SkillPublisher.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillDetail.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/skill/presentation/SkillController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/skill/presentation/SkillDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesSkillClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesSkillClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/AgentToolPolicy.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillStoreTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/HermesSkillRequestTest.java` | 수정 |
