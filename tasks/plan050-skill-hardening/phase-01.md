# Phase 01. 백엔드 저장 규칙을 Hermes 새 스킬 검사와 맞추고 개수 한도를 둔다

**Execution profile**: deep

## 목표

`PUT /api/v1/agents/{code}/skills/{name}` 이 Hermes 가 제대로 색인할 수 없는 스킬을 `VALIDATION_FAILED` 로 거절한다.
새 스킬의 설명 60자, 설명 1024자, 앞머리 뒤 본문, 에이전트별 올린 스킬 수 한도를 본다.
목록 응답에 한도 값 `uploadLimit` 을 싣는다.

**범위 외**: 실행 입력의 `[스킬 관리]` 단락은 phase 02, 화면의 사전 검사와 문구는 phase 03 이다.
Hermes 업그레이드의 이름 충돌 검사는 `fos-home-infra` 가 한다. 이 저장소에서 구현하지 않는다.

## 컨텍스트

**근거 문서**:
- `docs/adr/ADR-034-올린-스킬은-control-plane-이-버전-디렉터리에-쓰고-hermes-는-읽기만-한다.md` 의 「저장할 수 있는 스킬은 Hermes 가 제대로 고를 수 있는 스킬이다」
- `docs/hermes/tools-and-skills.md` 의 「스킬 파일과 색인 적용 시점」 안의 v0.21.5 검사 표
- `docs/code-architecture.md` 의 「스킬」 절 제한 표와 경로 표
- `docs/flow.md` 의 「스킬을 저장할 때」 와 「갈리는 지점」

지금 코드:

- `backend/src/main/java/com/bifos/assistant/skill/application/SkillService.java`
  - `save(CurrentUser, String code, String name, String skillMd, List<SkillFileInput>)` 는 `@Transactional` 이다.
    `requireEditableLocked` 로 에이전트 행을 잠근 뒤 `SkillStore.requireSkillName`, `requireSkillMd`, `requireFiles` 를 부른다.
    그다음 `store.readCurrent(profile)` 와 `uploadedBundle(profile, current, name)` 으로 이미 올린 스킬인지 보고,
    `uploaded == null` 이면서 Hermes 목록에 같은 이름이 있으면 `SKILL_NAME_TAKEN` 이다.
  - `uploadedBundle` 이 `null` 이면 **새 스킬**이다. 지금 버전(`readCurrent`)과 표식 없는 더 새 버전(`readPending`)에 모두 없다는 뜻이다.
  - `uploadedNames(current, pending)` 이 올린 스킬 이름 집합이다. `assemble` 이 이미 쓴다. 개수는 이 집합의 크기로 센다.
  - `requireSkillMd(name, skillMd)` 는 빈 값, 10만 자, 앞머리 `name` 일치만 본다.
  - `assemble` 이 `new SkillList(List.copyOf(items.values()), editable, publisher.skillsToolsetEnabled(agent))` 를 만든다.
- `backend/src/main/java/com/bifos/assistant/skill/application/SkillFrontmatter.java`
  - `record SkillFrontmatter(String name, String description)`. `parse(String skillMd)` 는 줄로 나눠 첫 줄 `---` 과 다음 `---` 줄 사이를 SnakeYAML `SafeConstructor` 로 읽는다.
    `textOf` 가 `String.valueOf(value).strip()` 이다. 본문은 보지 않는다.
- `backend/src/main/java/com/bifos/assistant/skill/application/SkillProperties.java`
  - `@ConfigurationProperties(prefix = "assistant.skill") record SkillProperties(String root, String agentRoot, Integer keepVersions)`.
    `keepVersions` 가 `null` 이면 `DEFAULT_KEEP_VERSIONS = 3`, 1 미만이면 `IllegalStateException` 이다. 같은 방식으로 칸을 더한다.
- `backend/src/main/resources/application.yml` 의 `assistant.skill` 에 `root`, `agent-root`, `keep-versions: 3` 이 있다.
- `backend/src/main/java/com/bifos/assistant/skill/application/SkillList.java` 는 `record SkillList(List<SkillListItem> skills, boolean editable, boolean skillsToolsetEnabled)` 다.
- `backend/src/main/java/com/bifos/assistant/skill/presentation/SkillDtos.java` 의 `SkillListView(List<SkillItemView> skills, boolean editable, boolean skillsToolsetEnabled)` 와 `from(SkillList)` 이 응답 모양이다.

Hermes v0.21.5 의 검사(`tools/skill_manager_tool.py` 의 `_validate_frontmatter`)는 이렇다.

- 설명 60자: `create` 일 때만. `len(desc.strip().strip("'\""))` 이다. 앞뒤 공백을 뺀 뒤, 양 끝에서 `'` 와 `"` 를 몇 개든 뺀다. Python 문자 수는 code point 수다.
- 설명 1024자: 늘. `len(str(parsed["description"]))`. 앞뒤를 빼지 않는다.
- 본문: 늘. 닫는 `---` 뒤가 공백뿐이면 거절한다.

## 의도 메모

- 60자 상수는 Hermes 를 읽지 않고 Control Plane 이 따로 갖는다. Javadoc 에 「Hermes v0.21.5 의 `SKILL_PROMPT_DESC_LIMIT` 와 같다. Hermes 를 올리며 바뀌면 함께 고친다」 를 적는다.
- 글자 수는 `String.length()` 가 아니라 `codePointCount` 로 센다. 이모지 하나가 UTF-16 두 단위라 `length()` 로 세면 Hermes 보다 엄격해진다.
- 60자는 새 스킬에서만 본다. 이미 올린 스킬(표식 없는 더 새 버전에만 있는 이름 포함)을 고칠 때는 보지 않는다. Hermes 도 고칠 때는 보지 않는다.
- 개수 한도는 새 스킬일 때만, 잠금을 잡은 뒤 읽은 `current` 와 `readPending` 으로 센다. 잠금 밖에서 세면 동시 생성 둘이 모두 통과한다.
- 오류는 모두 `ErrorCode.VALIDATION_FAILED` 다. 새 오류 코드를 만들지 않는다.
- 스키마 변경은 없다.
- 본문 검사는 `SkillFrontmatter.parse` 가 이미 찾은 닫는 줄 번호 뒤의 줄들로 한다. 정규식을 새로 두지 않는다.
- `description` 이 없거나 비면 지금처럼 거절한다. Hermes 보다 엄격하지만 화면이 설명을 보여 줘야 해서 유지한다.

## 작업 항목

### 1. `SkillFrontmatter` 에 원문 설명과 본문 유무를 싣는다

`backend/src/main/java/com/bifos/assistant/skill/application/SkillFrontmatter.java`

- record 칸을 `(String name, String description, String rawDescription, boolean hasBody)` 로 바꾼다.
  - `description` 은 지금과 같다(앞뒤 공백을 뺀 값, 화면에 보이는 값).
  - `rawDescription` 은 YAML 값의 `String.valueOf(value)` 그대로다. 앞뒤를 빼지 않는다.
  - `hasBody` 는 닫는 `---` 줄 다음 줄부터 끝까지를 이은 글이 `isBlank()` 가 아니면 참이다.
- `parse` 는 본문이 없어도 던지지 않는다. 본문 검사는 `SkillService` 가 저장할 때 한다. `descriptionOf` 처럼 읽기만 하는 곳이 본문 없는 옛 스킬도 읽을 수 있어야 하기 때문이다.
- 메서드 `int indexedDescriptionLength()` 를 둔다. `rawDescription.strip()` 뒤 양 끝의 `'` 와 `"` 를 반복해 빼고 `codePointCount` 를 돌려준다.
- 메서드 `int rawDescriptionLength()` 는 `rawDescription` 의 `codePointCount` 다.
- 두 메서드의 Javadoc 에 Hermes 의 어느 식과 같은지 적는다.

### 2. `SkillProperties` 와 설정에 개수 한도를 더한다

- `backend/src/main/java/com/bifos/assistant/skill/application/SkillProperties.java`: 칸 `Integer maxPerAgent` 를 마지막에 더한다. `null` 이면 `DEFAULT_MAX_PER_AGENT = 30`, 1 미만이면 `IllegalStateException("assistant.skill.max-per-agent must be at least 1")`. Javadoc `@param` 을 더한다.
- `backend/src/main/resources/application.yml`: `assistant.skill` 에 주석과 함께 `max-per-agent: ${ASSISTANT_SKILL_MAX_PER_AGENT:30}` 를 더한다. 주석은 「에이전트 하나에 올릴 수 있는 스킬 수. 새 스킬을 만들 때만 본다」.
- `backend/src/test/java/com/bifos/assistant/skill/SkillStoreTest.java` 의 `new SkillProperties(root.toString(), AGENT_ROOT + "/", 3)` 에 넷째 인자 `null` 을 더한다.

### 3. `SkillService.save` 가 새 규칙을 본다

`backend/src/main/java/com/bifos/assistant/skill/application/SkillService.java`

- `private final SkillProperties properties;` 를 받는다(`@RequiredArgsConstructor`).
- 상수를 더한다. 이름은 화면(`web/src/lib/skill.ts`)의 짝과 같게 둔다.
  - `public static final int MAX_NEW_DESCRIPTION_CHARS = 60;`
  - `public static final int MAX_DESCRIPTION_CHARS = 1024;`
- 기존 한도 위 두 줄 주석(「아래 세 한도는 화면(...skill-editor.tsx)이 같은 값으로 저장 전에 검사한다. 바꾸면 두 곳을 함께 고친다.」)을 이렇게 바꾼다.
  「아래 파일 세 한도는 화면(web/src/components/agent/skill-editor.tsx)이, 설명 두 한도는 web/src/lib/skill.ts 가 같은 값으로 저장 전에 검사한다. 바꾸면 함께 고친다.」
- `requireSkillMd(name, skillMd)` 가 `SkillFrontmatter` 를 돌려주게 바꾸고 아래를 더 본다. 메시지는 영어로 쓴다(기존 메시지와 같다).
  - `rawDescriptionLength() > MAX_DESCRIPTION_CHARS` → `VALIDATION_FAILED` `"SKILL.md description can be at most 1024 characters"`
  - `!hasBody()` → `VALIDATION_FAILED` `"SKILL.md must have content after the frontmatter"`
- `save` 순서. 잠금, 이름, `requireSkillMd`, `requireFiles` 까지는 지금과 같다. `uploaded` 를 구한 뒤:
  1. `boolean creating = uploaded == null;`
  2. `creating` 이고 Hermes 목록에 같은 이름이 있으면 지금처럼 `SKILL_NAME_TAKEN`.
  3. `creating` 이고 `uploadedNames(current, store.readPending(profile)).size() >= properties.maxPerAgent()` 이면 `VALIDATION_FAILED` `"an agent can have at most " + max + " uploaded skills"`.
  4. `creating` 이고 `frontmatter.indexedDescriptionLength() > MAX_NEW_DESCRIPTION_CHARS` 이면 `VALIDATION_FAILED` `"a new skill description can be at most 60 characters"`.
- `save` 의 Javadoc 에 새 스킬일 때만 보는 두 검사와 그 까닭(ADR-034)을 한 단락 더한다.
- `assemble` 이 `new SkillList(..., editable, publisher.skillsToolsetEnabled(agent), properties.maxPerAgent())` 를 만든다.

### 4. 목록 응답에 `uploadLimit` 을 싣는다

- `backend/src/main/java/com/bifos/assistant/skill/application/SkillList.java`: 칸 `int uploadLimit` 을 마지막에 더하고 `@param` 을 적는다.
- `backend/src/main/java/com/bifos/assistant/skill/presentation/SkillDtos.java`: `SkillListView` 에 `int uploadLimit` 을 더하고 `from` 에서 옮긴다. `@param` 을 적는다.
- `new SkillList(` 를 쓰는 테스트에 넷째 인자를 더한다. `git grep -n "new SkillList(" backend/src/test` 로 찾는다. 지금은 아래 셋이다.
  - `backend/src/test/java/com/bifos/assistant/skill/SkillCommandCatalogTest.java`
  - `backend/src/test/java/com/bifos/assistant/skill/SkillControllerTest.java`
  - `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java`
- `SkillControllerTest` 의 목록 검사에 `jsonPath("$.uploadLimit")` 단언을 더한다.

### 5. `SkillFrontmatterTest` 를 새로 만든다

`backend/src/test/java/com/bifos/assistant/skill/SkillFrontmatterTest.java` (Spring 없이 도는 단위 테스트)

- 설명 60자(한국어 60자)는 `indexedDescriptionLength() == 60`
- 앞뒤 공백과 따옴표: `description: "  '가나다'  "` 처럼 값 양 끝에 공백과 따옴표가 섞인 경우 Hermes 식과 같은 수를 돌려준다
- 이모지 하나는 1 로 센다
- 앞머리 뒤에 `# 본문` 이 있으면 `hasBody()` 가 참, 앞머리 뒤가 비었거나 공백과 빈 줄뿐이면 거짓. 본문이 없어도 `parse` 는 던지지 않는다
- `rawDescriptionLength()` 는 앞뒤를 빼지 않은 수다

### 6. `SkillServiceTest` 에 규칙과 한도를 더한다

`backend/src/test/java/com/bifos/assistant/skill/SkillServiceTest.java`. 이 클래스는 트랜잭션 없이 실제 잠금과 실제 스킬 디렉터리를 쓴다. 기존 `skillMd(name)` 과 `assertCode` 를 쓴다.
설명을 받는 도우미 `skillMd(String name, String description)` 와 본문 없는 원문 도우미를 더한다.

- 새 스킬의 설명 60자는 저장되고 61자는 `VALIDATION_FAILED` 이며 게시하지 않는다(`verify(skillClient, never()).publish(...)`)
- 앞머리만 있는 원문은 새 스킬이든 이미 올린 스킬을 고치는 것이든 `VALIDATION_FAILED`
- 설명 1025자는 이미 올린 스킬을 고칠 때도 `VALIDATION_FAILED`
- 이미 올린 스킬을 61자 설명으로 고치면 저장된다. 먼저 같은 이름을 60자 이하로 저장한 뒤 61자로 다시 저장한다
- 개수 한도(기본 30):
  - 29개가 있을 때 30번째 새 스킬은 저장된다
  - 30개일 때 31번째 새 스킬은 `VALIDATION_FAILED` 이고 게시하지 않는다
  - 30개일 때 이미 올린 스킬을 고치는 것은 저장된다
  - 30개에서 하나를 지운 뒤 새 스킬은 저장된다
  - 29개를 미리 채우는 것은 `store.writeVersion(OWNED_PROFILE, bundles)` 와 `store.markPublished(OWNED_PROFILE, version)` 로 해도 된다. 서비스로 29번 저장해도 된다
- 동시 생성: 29개일 때 서로 다른 새 이름 둘을 두 스레드에서 함께 저장하면 하나만 저장되고 다른 하나는 `VALIDATION_FAILED` 다. 기존 `같은_에이전트에_두_저장이_동시에_와도_둘_다_반영된_버전이_남는다` 의 `CountDownLatch` 방식으로 첫째를 게시에서 잡아 둔 채 둘째를 보낸다. 끝난 뒤 `store.readCurrent(OWNED_PROFILE)` 가 30개다. 실패한 쪽 `Future.get()` 은 `ExecutionException` 을 던지므로 기존 `assertCode` 를 그대로 쓰지 못한다. 그 cause 가 `ApiException` 이고 `code()` 가 `VALIDATION_FAILED` 인지 본다
- 목록의 `uploadLimit()` 이 30 이다
- 한도를 테스트마다 바꾸지 않는다. `application-test.yml` 에 `max-per-agent` 를 넣지 않고 기본값 30 을 쓴다

### 7. e2e 시나리오에 API 검사를 더한다

`test/e2e/scenarios/skills.ts`

- `SkillList` 타입에 `uploadLimit: number` 를 더하고, 목록 단계에서 `list.uploadLimit === 30` 을 단언한다
- 새 이름으로 61자 설명을 보내면 400 `VALIDATION_FAILED`, 앞머리만 보내면 400 `VALIDATION_FAILED` 인 단계를 하나 더한다. 기존 `SKILL_NAME_TAKEN` 단계의 `call` 과 `ErrorBody` 를 따른다
- 거절된 요청은 실행을 만들지 않으므로 `SKILL_READ_TURNS`, `SKILL_COMMAND_TURNS` 는 그대로다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests 'com.bifos.assistant.skill.*' --tests 'com.bifos.assistant.chat.ChatServiceTest'
cd backend && ./gradlew test
node test/e2e/run.ts
```

기대값: 모두 통과. `SkillFrontmatterTest`, `SkillServiceTest` 의 새 테스트가 실행 목록에 있다.
`git grep -n "new SkillList(" backend/src` 의 모든 줄이 인자 넷이다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillFrontmatter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillList.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/skill/presentation/SkillDtos.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillFrontmatterTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillStoreTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillCommandCatalogTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 |
| `test/e2e/scenarios/skills.ts` | 수정 |
