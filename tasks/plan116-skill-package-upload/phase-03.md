# Phase 03. 받은 묶음을 에이전트와 무관한 규칙으로 검사한다

**Execution profile**: deep

## 목표

`skill/application/SkillPackageCheck` 가 `ReceivedSkillPackage` 만 보고 빼는 항목, 감싼 폴더, 경로, 글 파일, 크기, 비밀값, 앞머리를 판정해 `CheckedSkillPackage` 를 준다.
문제는 처음 하나로 끝내지 않고 모아 화면이 한 번에 보이게 한다. 지금 스킬과 견줄 지문 `SkillBundle.digest()` 를 더한다.

**범위 외**: 에이전트에 따른 판정(Hermes 기본 스킬 이름, 개수 한도, 새 스킬 설명 60자, scripts 와 `terminal`)과 API(phase 04).

## 컨텍스트

**근거 문서**: `backend/docs/flow.md` 의 「스킬 묶음 받기와 검사」 > 「묶음 검사」

- phase 02 의 `ReceivedSkillPackage`, `SkillPackageEntry`, `SkillPackageProblem`, `model/SkillPackageReason` 을 쓴다
- 경로 규칙은 `backend/src/main/java/com/bifos/assistant/skill/infra/SkillFilePaths.java` 다. `requireSkillName(String)`, `requireFilePath(String)`, `requireFileSet(Collection<String>)` 은 어기면 `ApiException`(`VALIDATION_FAILED`)을 던진다. `SKILL_MD` 상수가 `"SKILL.md"`, `isScript(String)` 는 `scripts/` 로 시작하면 참이다
- 앞머리는 `backend/src/main/java/com/bifos/assistant/skill/application/SkillFrontmatter.java` 의 `parse(String)`(못 읽으면 `ApiException`)이고 record 칸 `name()`, `description()`, `hasBody()`, `requestsSecrets()`, 메서드 `rawDescriptionLength()`, `indexedDescriptionLength()` 가 있다
- 상수는 `SkillService.MAX_FILES`(20), `MAX_CHARS_PER_FILE`(100,000), `MAX_TOTAL_BYTES`(1 MiB), `MAX_DESCRIPTION_CHARS`(1024) 를 그대로 쓴다
- 지문 계산은 `com.bifos.assistant.shared.util.Sha256.hex(String)`(UTF-8, 소문자 64자)만 쓴다. `ArchitectureRules.MESSAGE_DIGEST_ONLY_IN_SHA256` 이 그 밖의 `MessageDigest.getInstance` 를 막는다

## 의도 메모

- 비밀값 찾기는 `ToolDetailRedactor.PREFIXED_SECRET` 를 쓰지 않는다. 앞 경계가 없어 `task-runner` 의 `sk-runner` 를 잡는다. 아래 정규식을 `SkillPackageCheck` 안에 따로 둔다
- 글자 수는 기존 저장과 같게 Java `String.length()` 로 센다

## 작업 항목

### 1. `skill/application/CheckedSkillPackage.java`

record `(String name, String description, String skillMd, List<SkillFile> files, List<String> ignored, List<SkillPackageProblem> problems)`.
`files` 는 경로 순이고 `SKILL.md` 는 빠진다. 앞머리를 읽지 못하면 `name`, `description` 은 `null`. `SKILL.md` 가 없거나 글로 읽히지 않으면(`NOT_TEXT`) `skillMd` 는 `null` 이다.
`public boolean hasScripts()` 는 `files` 에 `SkillFilePaths.isScript` 인 경로가 있으면 참이다.

### 2. `skill/application/SkillPackageCheck.java`

- `@Component public class SkillPackageCheck`. `public CheckedSkillPackage check(ReceivedSkillPackage received)`. 에이전트를 보지 않는다
- `received.problem()` 이 있으면 그 하나만 담아 돌려준다
- 아래 순서로 한다
  1. 조각 하나라도 `.` 으로 시작하거나 첫 조각이 `__MACOSX` 인 항목을 빼고 `ignored` 에 원래 경로를 넣는다
  2. 남은 항목이 모두 같은 첫 조각 아래(조각 둘 이상)이고 한 조각 경로 `SKILL.md` 가 없으면 첫 조각을 뗀다. 한 번만
  3. 경로 `SKILL.md` 가 없으면 `NO_SKILL_MD` 하나로 끝낸다
  4. `SKILL.md` 가 아닌 항목마다: 마지막 조각이 대소문자 무시로 `SKILL.md` 면 `NESTED_SKILL_MD`, 아니면 `SkillFilePaths.requireFilePath` 가 던지면 `PATH_NOT_ALLOWED`(path 는 그 경로). 통과한 경로 모음에 `SkillFilePaths.requireFileSet` 가 던지면 `PATH_NOT_ALLOWED`(path 없음)
  5. `SKILL.md` 와 4에서 통과한 파일만(4에서 걸린 파일은 다시 보지 않는다) `StandardCharsets.UTF_8.newDecoder()` 에 `CodingErrorAction.REPORT` 로 읽는다. 실패하거나 `\u0000` 이 있으면 `NOT_TEXT`. 글자 수가 `SkillService.MAX_CHARS_PER_FILE` 를 넘으면 `FILE_TOO_LARGE`. 4에서 통과한 파일 수(SKILL.md 제외)가 `SkillService.MAX_FILES` 를 넘으면 `TOO_MANY_FILES`, 글로 읽힌 파일의 UTF-8 바이트 합계가 `SkillService.MAX_TOTAL_BYTES` 를 넘으면 `TOTAL_TOO_LARGE`
  6. 글로 읽힌 모든 파일에서 `(?<![A-Za-z0-9_-])(?:sk-|gh[pousr]_|github_pat_|xox[a-z]*-|AIza)[A-Za-z0-9_-]{20,}` 나 `(?m)^-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----` 를 찾으면 `SECRET_VALUE`(path 는 그 파일)
  7. `SKILL.md` 가 글로 읽혔으면 `SkillFrontmatter.parse` 로 읽는다. `ApiException` 이면 `FRONTMATTER_INVALID`. 읽히면 `name` 이 `SkillFilePaths.requireSkillName` 을 통과하지 못하면 `NAME_INVALID`, `!hasBody()` 면 `NO_BODY`, `requestsSecrets()` 면 `SECRET_REQUEST`, `rawDescriptionLength() > SkillService.MAX_DESCRIPTION_CHARS` 면 `DESCRIPTION_TOO_LONG`. 이 문제들의 path 는 `SKILL.md` 다
- 문제는 50개까지 모은다. 넘는 것은 버린다
- `files` 에는 4와 5를 통과한 파일만 넣는다

### 3. `skill/domain/SkillBundle.java` 에 `public String digest()`

`("SKILL.md", skillMd)` 와 `files` 를 경로 순으로 정렬해 `경로 + "\0" + 내용 + "\0"` 을 이은 문자열을 `Sha256.hex` 에 넘긴 값이다. `backend/docs/flow.md` 「묶음 검사」 끝의 지문 설명과 같다.

### 4. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/skill/SkillPackageCheckTest.java`

`ReceivedSkillPackage` 를 직접 만들어 넣는 단위 시험이다.

- `my-skill/` 로 감싼 묶음이 벗겨지고 `__MACOSX/…`, `.DS_Store`, `my-skill/.git/config` 가 `ignored` 로 간다. 문제가 없고 `name`, `description`, `files` 가 맞다
- 맨 위 `SKILL.md` 가 없으면 `NO_SKILL_MD`, `references/SKILL.md` 는 `NESTED_SKILL_MD`, `bin/run.sh` 는 `PATH_NOT_ALLOWED`, PNG 머리 바이트는 `NOT_TEXT`, 참고 파일 21개는 `TOO_MANY_FILES`, `sk-ant-` 뒤 30자는 `SECRET_VALUE`, `task-runner-` 뒤 30자는 문제 없음, `-----BEGIN OPENSSH PRIVATE KEY-----` 줄은 `SECRET_VALUE`, 앞머리의 `required_environment_variables` 는 `SECRET_REQUEST`
- 여러 문제가 함께 있으면 모두 나온다
- 받기의 문제가 있으면 그 하나만 나온다
- `SkillBundle.digest()` 가 파일 순서에 관계없이 같고, 내용 한 글자가 바뀌면 달라진다

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.skill.SkillPackageCheckTest' --tests 'com.bifos.assistant.skill.SkillPackageZipTest' --tests 'com.bifos.assistant.architecture.*'
cd backend && ./gradlew qualityCheck
node scripts/check-file-length.mjs
```

- 셋 다 종료 코드 0. `qualityCheck` 의 포맷 위반이면 `cd backend && ./gradlew spotlessApply` 결과를 기능 커밋 뒤 따로 커밋한다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/skill/application/CheckedSkillPackage.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillPackageCheck.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/domain/SkillBundle.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillPackageCheckTest.java` | 신규 |
