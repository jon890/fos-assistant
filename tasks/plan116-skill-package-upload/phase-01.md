# Phase 01. 새 스킬 검사를 `NewSkillRules` 로 옮긴다

**Execution profile**: fast

## 목표

`SkillService` 의 새 스킬 검사(`requireCreatable`)와 올린 스킬 이름 모으기(`uploadedNames`)를 `skill/application/NewSkillRules` 로 옮긴다.
동작은 바꾸지 않는 이동이다. phase 04 의 미리보기가 같은 규칙을 문제 목록으로 쓰고, `SkillService` 가 파일 길이 상한(500줄)을 넘지 않게 하려는 것이다.

**범위 외**: 규칙의 내용 변경. 미리보기용 판정 메서드(phase 04).

## 컨텍스트

**근거 문서**: `backend/docs/flow.md` 의 「스킬」 > 「호출 이력」 끝 「무엇 / 어디」 표의 `NewSkillRules` 행

- `backend/src/main/java/com/bifos/assistant/skill/application/SkillService.java` 는 495줄이다. `scripts/check-file-length.mjs` 가 `backend/src/main/**/*.java` 를 500줄로 막는다
- 옮길 것: `private void requireCreatable(String profile, String name, SkillFrontmatter frontmatter, Map<String, SkillBundle> current, Map<String, SkillBundle> pending)` 와 `private static Set<String> uploadedNames(Map<String, SkillBundle> current, Map<String, SkillBundle> pending)`. `requireCreatable` 은 `publisher.list(profile)`, `properties.maxPerAgent()`, `MAX_NEW_DESCRIPTION_CHARS` 를 쓴다
- `uploadedNames` 는 `assemble` 과 `requireCreatable` 이 쓴다
- `backend/AGENTS.md` 「포맷」: 기능 커밋과 `spotlessApply` 결과는 다른 커밋이다. 리팩터링은 이동 커밋과 동작을 바꾸는 커밋을 나눈다

## 작업 항목

### 1. `skill/application/NewSkillRules.java` 신규

- `@Component @RequiredArgsConstructor public class NewSkillRules`. 의존 `SkillPublisher publisher`, `SkillProperties properties`
- `public void requireCreatable(String profile, String name, SkillFrontmatter frontmatter, Map<String, SkillBundle> current, Map<String, SkillBundle> pending)`: `SkillService` 의 본문과 Javadoc 을 그대로 옮긴다. 상수는 `SkillService.MAX_NEW_DESCRIPTION_CHARS` 를 참조한다
- `public static Set<String> uploadedNames(Map<String, SkillBundle> current, Map<String, SkillBundle> pending)`: 그대로 옮긴다
- 클래스 Javadoc 한 줄: 새 스킬일 때만 보는 검사(Hermes 기본 스킬 이름, 개수 한도, 새 스킬 설명 60자)

### 2. `SkillService.java` 수정

- 두 메서드를 지우고 `private final NewSkillRules newSkills;` 를 더한다
- `requireCreatable(...)` 호출을 `newSkills.requireCreatable(...)`, `uploadedNames(...)` 를 `NewSkillRules.uploadedNames(...)` 로 바꾼다
- 쓰이지 않게 된 import(`HashSet` 등)를 지운다

### 3. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/skill/NewSkillRulesTest.java`

`SkillPublisher` 를 `mock` 으로, `SkillProperties` 를 `new SkillProperties(root, agentRoot, null, 2)` 처럼 직접 만들어 넣는 단위 시험이다.

- `uploadedNames` 가 지금 버전과 표식 없는 버전의 이름을 합친다
- `publisher.list` 에 같은 이름이 있으면 `SKILL_NAME_TAKEN`, 이름 수가 한도와 같으면 `VALIDATION_FAILED`, 설명이 61자면 `VALIDATION_FAILED`, 모두 아니면 던지지 않는다

기존 `backend/src/test/java/com/bifos/assistant/skill/SkillServiceTest.java` 가 저장 경로의 같은 검사를 확인하므로 함께 돌린다.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.skill.SkillServiceTest' --tests 'com.bifos.assistant.skill.NewSkillRulesTest' --tests 'com.bifos.assistant.architecture.*'
cd backend && ./gradlew spotlessCheck
node scripts/check-file-length.mjs
```

- 셋 다 종료 코드 0
- `wc -l backend/src/main/java/com/bifos/assistant/skill/application/SkillService.java` 가 470 이하

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/skill/application/NewSkillRules.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/skill/NewSkillRulesTest.java` | 신규 |
