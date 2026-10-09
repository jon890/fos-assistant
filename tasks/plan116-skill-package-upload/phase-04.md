# Phase 04. 미리보기와 올리기 API 를 연다

**Execution profile**: deep

## 목표

`POST /api/v1/agents/{code}/skill-packages/preview` 와 `POST /api/v1/agents/{code}/skill-packages` 를 연다.
미리보기는 저장하지 않고 파일별 바뀜과 문제 목록, 지금 스킬의 지문을 준다. 올리기는 같은 판정을 다시 하고 에이전트 행 잠금 안에서 지문을 견준 뒤 기존 저장 경로로 저장한다.

**범위 외**: 화면(다음 plan). GitHub 가져오기. plugin.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261009-skill-package.md`, `backend/docs/flow.md` 의 「스킬 묶음 미리보기와 올리기」(이 phase 의 커밋이 「스킬 묶음 받기와 검사」 뒤에 더한다), 「스크립트와 실행 공간」

- phase 02 의 `SkillPackageZip`(`read`, `MAX_ZIP_BYTES`), phase 03 의 `SkillPackageCheck`(`check`), `CheckedSkillPackage`(`hasScripts()`), `SkillBundle.digest()`, phase 01 의 `NewSkillRules` 를 쓴다
- `backend/src/main/java/com/bifos/assistant/skill/application/SkillService.java`
  - `private Agent requireEditable(CurrentUser, String)` 는 읽을 수 있는지와 편집자인지를 보고 아니면 `FORBIDDEN` 이다. `requireEditableLocked` 는 그 뒤 행 잠금을 잡고 권한을 한 번 더 본다
  - `private static SkillBundle uploadedBundle(Map current, Map pending, String name)` 는 지금 버전, 없으면 표식 없는 더 새 버전의 스킬이다
  - `private SkillDetail saveBundle(CurrentUser, Agent, Map current, SkillBundle bundle, SkillBundle replaced)` 가 비밀 요청 칸, scripts 판정(`SKILL_SCRIPTS_NEED_SANDBOX`), 게시, 이전 버전 쓰기를 한다. `save` 와 `restorePrevious` 가 쓴다
- `backend/src/main/java/com/bifos/assistant/skill/infra/SkillStore.java` 의 `readCurrent(profile)`, `readPending(profile)`, `SkillPublisher` 의 `list(profile)`, `terminalEnabled(Agent)`, `SkillProperties.maxPerAgent()`
- `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` 의 각 값은 Javadoc 한 줄을 가진다(`SKILL_SCRIPTS_NEED_SANDBOX(HttpStatus.CONFLICT)` 본보기)
- multipart 본보기는 `backend/src/main/java/com/bifos/assistant/chat/presentation/AttachmentController.java` 의 `@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)` 와 `@RequestParam("file") MultipartFile file` 이다. `backend/src/main/resources/application.yml` 의 multipart 상한은 파일 11MB 라 2 MiB 판정은 컨트롤러와 서비스가 한다
- DTO 는 `backend/src/main/java/com/bifos/assistant/skill/presentation/SkillDtos.java` 하나에 둔다(`backend/AGENTS.md` 「데이터 클래스는 컨트롤러 안에 두지 않는다」). 컨트롤러 본보기는 같은 디렉터리의 `SkillController`(`CurrentUserProvider currentUser`, `currentUser.require()`)
- 시험 본보기: `backend/src/test/java/com/bifos/assistant/skill/SkillServiceTest.java`(`@BackendIntegrationTest`, 실제 `SkillStore`, `HermesSkillClient`·`HermesToolsetClient` 빈, `OWNER`/`MEMBER` 사용자, 에이전트 fixture), `SkillControllerTest`(standalone MockMvc, `mock(SkillService.class)`, `mock(CurrentUserProvider.class)`)
- `SkillService.java` 는 phase 01 뒤 약 467줄이고 파일 길이 상한은 500줄이다(`node scripts/check-file-length.mjs`). 아래 두 메서드의 Javadoc 은 한 줄씩 쓴다. 그래도 넘으면 `detailOf` 를 `SkillDetail` 의 `static SkillDetail of(SkillBundle bundle, Instant previousSavedAt)` 로 옮긴다
- 전역 Jackson 설정은 `null` 칸을 빼지 않는다. 새 DTO 에 `@JsonInclude` 를 붙이지 않는다

## 의도 메모

- 미리보기는 문제가 있어도 200 이다. 공통 오류 응답(`ErrorResponse`)에 세부 칸을 더하지 않으려는 것이다
- 미리보기와 올리기는 권한을 먼저 본다. 권한이 없으면 zip 을 풀지 않는다
- 올리기의 지문 비교는 에이전트 행 잠금을 잡은 뒤에 한다. 미리보기와 올리기 사이의 다른 저장을 막는 유일한 장치다
- `SCRIPTS_NEED_SANDBOX` 하나뿐인 올리기는 409 `SKILL_SCRIPTS_NEED_SANDBOX` 로 던진다. plugin 이 거절한 경우와 같은 코드라 화면이 같은 까닭을 보인다

## 작업 항목

### 1. `ErrorCode` 에 두 값

- `SKILL_CHANGED(HttpStatus.CONFLICT)`: 미리보기 뒤에 지금 스킬이 바뀌었거나, 덮어쓰기를 확인받지 않았다
- `SKILL_PACKAGE_INVALID(HttpStatus.BAD_REQUEST)`: 올린 스킬 묶음이 검사를 통과하지 못했다

### 2. `NewSkillRules` 에 미리보기용 판정

- `public boolean hermesNameTaken(String profile, String name)`: `publisher.list(profile)` 에 그 이름이 있다
- `public boolean limitReached(Map<String, SkillBundle> current, Map<String, SkillBundle> pending)`: `uploadedNames(current, pending).size() >= properties.maxPerAgent()`
- `requireCreatable` 이 이 둘을 쓰게 바꾼다. 던지는 오류와 메시지는 그대로다

### 3. `SkillService` 두 메서드

- `public Agent requireManageable(CurrentUser user, String code)`: `requireEditable` 을 부른다. 잠금은 잡지 않는다
- `@Transactional public SkillDetail saveUploaded(CurrentUser user, String code, SkillBundle bundle, String baseDigest)`: `requireEditableLocked` 로 잠근 뒤 `readCurrent`, `readPending` 으로 `uploaded = uploadedBundle(current, pending, bundle.name())` 를 구한다. `baseDigest` 가 비었거나 공백뿐이면 `null` 로 본다. `uploaded == null ? null : uploaded.digest()` 가 그 값과 `Objects.equals` 가 아니면 `ApiException(ErrorCode.SKILL_CHANGED, "the skill changed after the preview")`. 같고 `uploaded == null` 이면 `newSkills.requireCreatable(profile, bundle.name(), SkillFrontmatter.parse(bundle.skillMd()), current, pending)`. 그 뒤 `saveBundle(user, agent, current, bundle, uploaded)`

### 4. `skill/application/SkillPackageService`

- `@Service @RequiredArgsConstructor`. 의존 `SkillPackageZip`, `SkillPackageCheck`, `SkillService`, `NewSkillRules`, `SkillStore`, `SkillPublisher`
- 타입
  - `skill/application/model/SkillPackageChange.java`: enum `ADDED, CHANGED, SAME, REMOVED`
  - `skill/application/SkillPackageFile.java`: record `(String path, long size, SkillPackageChange change)`. `size` 는 UTF-8 바이트
  - `skill/application/SkillPackagePreview.java`: record `(String name, String description, String skillMdHead, boolean existing, String baseDigest, boolean hasScripts, List<SkillPackageFile> files, List<String> ignored, List<SkillPackageProblem> problems)`
- `public SkillPackagePreview preview(CurrentUser user, String code, byte[] zip)`
  - 첫 줄에서 `Agent agent = skills.requireManageable(user, code)`. 그 뒤 `check.check(zip.read(bytes))`
  - `name` 이 `null` 이 아니고 `SkillFilePaths.requireSkillName` 을 통과하면 지금 스킬을 읽는다(`store.readCurrent`, `store.readPending`, 지금 버전 우선). 에이전트에 따른 문제를 검사의 문제 뒤에 더한다
    - 지금 스킬이 없을 때: `newSkills.hermesNameTaken` → `NAME_TAKEN`, `newSkills.limitReached` → `LIMIT_REACHED`, 검사가 `DESCRIPTION_TOO_LONG` 을 내지 않았고 `SkillFrontmatter.parse(skillMd).indexedDescriptionLength() > SkillService.MAX_NEW_DESCRIPTION_CHARS` → `DESCRIPTION_TOO_LONG`. path 는 `NAME_TAKEN`, `LIMIT_REACHED` 는 `null`, `DESCRIPTION_TOO_LONG` 은 `SKILL.md`
    - `hasScripts()` 이고 `!publisher.terminalEnabled(agent)` → `SCRIPTS_NEED_SANDBOX`(path `null`)
  - `existing` 은 지금 스킬이 있는가, `baseDigest` 는 그 `digest()`, 없으면 `null`
  - `files` 는 `SkillPackageDiff.files(String skillMd, List<SkillFile> files, SkillBundle current)` 가 만든다. `SKILL.md` 를 첫 줄로 두고 나머지를 경로 순으로 둔다. 지금 스킬(`current`, 없으면 `null`)과 내용을 견줘 `CHANGED`/`SAME`, 지금 스킬에 없으면 `ADDED`, 지금 스킬에만 있는 파일은 `REMOVED` 로 지금 크기와 함께 뒤에 경로 순으로 붙인다. `skillMd` 가 `null` 이면(받기 실패, `SKILL.md` 없음, 글이 아님) 빈 목록이다
  - `skillMdHead` 는 `SkillPackageDiff.head(String skillMd)`: 앞 2,000자. 2,000번째 글자가 `Character.isHighSurrogate` 면 1,999자로 자른다. `skillMd` 가 `null` 이면 `null`
  - `skill/application/SkillPackageDiff.java` 는 `@NoArgsConstructor(access = AccessLevel.PRIVATE) final class` 의 두 정적 메서드다
  - 문제 목록은 50개까지다
- `public SkillPackagePreview previewTooLarge(CurrentUser user, String code)`: 권한을 본 뒤 `ZIP_TOO_LARGE` 하나만 든 미리보기(나머지 칸은 `null`, 거짓, 빈 목록)
- `public SkillDetail upload(CurrentUser user, String code, byte[] zip, String baseDigest)`
  - 첫 줄에서 권한을 본다. 그 뒤 `preview` 와 같은 판정을 한다(같은 private 메서드를 쓴다)
  - 문제가 `SCRIPTS_NEED_SANDBOX` 하나뿐이면 `ApiException(ErrorCode.SKILL_SCRIPTS_NEED_SANDBOX, "this agent has no sandbox shell to run skill scripts")`. 그 밖에 문제가 있으면 `ApiException(ErrorCode.SKILL_PACKAGE_INVALID, "<reason>: <path>")`(첫 문제, path 가 없으면 `<reason>` 만)
  - 없으면 `skills.saveUploaded(user, code, new SkillBundle(name, skillMd, files), baseDigest)`
- `public void uploadTooLarge(CurrentUser user, String code)`: 권한을 본 뒤 `ApiException(ErrorCode.SKILL_PACKAGE_INVALID, "ZIP_TOO_LARGE")`

### 5. `skill/presentation/SkillPackageController` 와 DTO

- `@RestController @RequestMapping("/api/v1/agents/{code}/skill-packages") @RequiredArgsConstructor`. 의존 `SkillPackageService packages`, `CurrentUserProvider currentUser`
- `@PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)`: `@RequestParam("file") MultipartFile file`. `file.getSize() > SkillPackageZip.MAX_ZIP_BYTES` 면 바이트를 읽지 않고 `previewTooLarge`, 아니면 `preview(user, code, file.getBytes())`. 응답 `SkillPackagePreviewView`
- `@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)`: `file` 과 `@RequestParam(value = "baseDigest", required = false) String baseDigest`. 크기가 넘으면 `uploadTooLarge`, 아니면 `upload`. 응답은 기존 `SkillDtos.SkillDetailView.from(...)`
- `file.getBytes()` 의 `IOException` 은 `UncheckedIOException` 으로 감싼다
- `SkillDtos` 에 `SkillPackagePreviewView`, `SkillPackageFileView`, `SkillPackageProblemView` 를 더한다. 칸 이름은 `SkillPackagePreview` 의 record 칸과 같다(`reason` 과 `change` 는 enum 이름 문자열). `name`, `description`, `skillMdHead`, `baseDigest`, `problems[].path` 는 `null` 이어도 칸을 보낸다

### 6. 이 phase 를 검증하는 시험

시험 zip 은 시험 안에서 Commons Compress 의 `ZipArchiveOutputStream` 으로 만든다. 사람 이름과 메일은 기존 fixture 의 가상 값만 쓴다.

- `backend/src/test/java/com/bifos/assistant/skill/SkillPackageServiceTest.java`: `SkillServiceTest` 와 같은 `@BackendIntegrationTest` 틀
  - 새 스킬 미리보기는 모두 `ADDED`, `existing` 거짓, `baseDigest` 는 `null` 이고 아무것도 쓰지 않는다(`store.currentVersion` 이 비었다)
  - 올리면 저장되고 Hermes 설정 쓰기가 불린다
  - 같은 이름의 다른 묶음 미리보기가 `CHANGED`, `SAME`, `ADDED`, `REMOVED` 를 맞게 주고, 그 `baseDigest` 로 올리면 덮어쓰고 `previousSavedAt` 이 차며 이전 버전이 첫 내용이다
  - `baseDigest` 없이 덮어쓰면, 또는 미리보기 뒤 `skills.save` 로 고친 다음 옛 `baseDigest` 로 올리면 `SKILL_CHANGED` 이고 지금 버전이 바뀌지 않는다
  - `terminal` 이 꺼진 에이전트에 scripts 묶음을 미리보면 `SCRIPTS_NEED_SANDBOX`, 올리면 `SKILL_SCRIPTS_NEED_SANDBOX` 이고 지금 버전이 바뀌지 않는다
  - 설명 61자의 새 스킬은 `DESCRIPTION_TOO_LONG`, Hermes 기본 스킬과 같은 이름은 `NAME_TAKEN`
  - 편집자가 아니면 미리보기와 올리기 모두 `FORBIDDEN`
  - 문제가 있는 묶음(비밀값이 든 `references/a.md` 로 `SECRET_VALUE`, `SKILL.md` 없는 묶음으로 `NO_SKILL_MD`)을 올리면 `SKILL_PACKAGE_INVALID` 이고 메시지가 `SECRET_VALUE: references/a.md`, `NO_SKILL_MD` 이며 `store.currentVersion` 이 그대로다
  - 올린 스킬 수가 한도에 닿은 에이전트에 새 스킬을 미리보면 `LIMIT_REACHED` 다. 한도는 시험 설정에 없어 기본 30 이다. 시험은 `store.writeVersion(profile, <이름이 다른 SkillBundle 30개의 Map>)` 과 `store.markPublished(profile, version)` 으로 지금 버전을 직접 만든다
  - `uploadTooLarge` 는 편집자에게 `SKILL_PACKAGE_INVALID`, 편집자가 아니면 `FORBIDDEN`
  - `baseDigest` 가 빈 문자열인 새 스킬 올리기는 저장된다
- `backend/src/test/java/com/bifos/assistant/skill/SkillPackageControllerTest.java`: standalone MockMvc 와 `mock(SkillPackageService.class)`. multipart `file` 과 `baseDigest` 가 서비스에 그대로 가고, 미리보기 JSON 칸 이름이 문서와 같고(`null` 칸 포함), 문제가 있어도 200 이며, 2 MiB 를 넘는 파일은 미리보기에서 `preview` 대신 `previewTooLarge` 를, 올리기에서 `upload` 대신 `uploadTooLarge` 를 부른다
- 기존 `SkillServiceTest` 가 그대로 통과한다(`requireCreatable` 의 오류와 메시지가 같다)

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.skill.*' --tests 'com.bifos.assistant.architecture.*'
cd backend && ./gradlew qualityCheck
node scripts/check-file-length.mjs
```

- 셋 다 종료 코드 0. `qualityCheck` 의 포맷 위반은 `cd backend && ./gradlew spotlessApply` 결과를 기능 커밋 뒤 따로 커밋한다
- `RepositoryQueryMysqlTest` 와 Flyway 는 이 phase 와 관계없다. 표를 바꾸지 않는다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/skill/application/NewSkillRules.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/skill/application/model/SkillPackageChange.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillPackageFile.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillPackagePreview.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillPackageService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillPackageDiff.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/presentation/SkillPackageController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/presentation/SkillDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillPackageServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillPackageControllerTest.java` | 신규 |
