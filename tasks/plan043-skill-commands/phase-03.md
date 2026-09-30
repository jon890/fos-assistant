# Phase 03. 호출 이력이 Hermes 기본 스킬의 이름 규칙을 받는다

**Execution profile**: standard

## 목표

모델이 `skill_view` 로 점이나 밑줄이 든 Hermes 기본 스킬(`note_taking.v2`)을 읽으면 `MODEL` 이력으로 남는다. 지금은 올린 스킬의 이름 규칙만 받아 그 이름을 버린다.

**범위 외**: 커맨드 판별식(`chat/application/SkillCommand`)은 그대로 `[a-z0-9-]` 다. 커맨드로 부를 수 있는 이름과 이력으로 남는 이름은 다르다.

## 컨텍스트

**근거 문서**: `docs/code-architecture.md` 의 「호출 이력」 절, `docs/data-schema.md` 의 `execution_skill_use` 절(`skill_name VARCHAR(64)`)

- `skill/application/SkillUseRecorder`: `static final Pattern SKILL_NAME = "[a-z0-9][a-z0-9-]{0,63}"` 로 `nameOf(preview)` 가 이름을 거른다. `recordModel`, `recordCommand` 가 모두 `nameOf` 를 거친다
- `skill/application/SkillService`: `private static final Pattern HERMES_SKILL_NAME = "[a-z0-9][a-z0-9._-]{0,63}"` 가 켜고 끄기의 이름 규칙이다. 화면 `web/src/lib/skill.ts` 의 `HERMES_SKILL_NAME_PATTERN` 과 같다
- 테스트: `backend/src/test/java/com/bifos/assistant/skill/SkillUseRecorderTest.java`

## 의도 메모

- 규칙을 두 곳에 따로 두지 않는다. `SkillService` 의 `HERMES_SKILL_NAME` 을 패키지 안에서 읽을 수 있게 열고(`static final`, 접근 제한자 없음) `SkillUseRecorder` 가 그것을 쓴다. `SkillUseRecorder.SKILL_NAME` 은 지운다
- 64자 상한은 그대로다. 컬럼이 `VARCHAR(64)` 다

## 작업 항목

### 1. `SkillService`, `SkillUseRecorder`

- 위 의도 메모대로 `SkillUseRecorder.nameOf` 가 `SkillService.HERMES_SKILL_NAME` 을 쓴다

### 2. 이 phase 를 검증하는 테스트

- `SkillUseRecorderTest`: 미리보기 `note_taking.v2` 가 `MODEL` 로 적힌다. `note_taking.v2 → references/a.md` 는 화살표 앞 이름만 적힌다. `.hidden`, `_x`, `Note` 는 버린다. 기존 검사는 그대로 통과한다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests 'com.bifos.assistant.skill.*'
cd backend && ./gradlew test
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillUseRecorder.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillUseRecorderTest.java` | 수정 |
