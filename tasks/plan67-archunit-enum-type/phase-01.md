# Phase 01. 저장되는 enum 여덟을 domain.type 으로 옮긴다

**Execution profile**: standard

## 목표

엔티티의 `@Enumerated` 필드가 쓰는 enum 여덟을 `<기능>.domain.type` 패키지로 옮겨
`ENUMERATED_FIELDS_USE_DOMAIN_TYPE` 의 기준을 0 줄로 만든다.
저장되는 값은 바꾸면 마이그레이션을 판단해야 하므로 한곳에 모아 보이게 한다.

**범위 외**: enum 의 상수 이름과 순서, 메서드, Javadoc 내용. 엔티티의 칸. 마이그레이션. 다른 규칙의 위반을 고치는 것.
`package` 줄과 `import` 줄, 전체 이름으로 적힌 참조만 바뀐다.

## 컨텍스트

- 규칙은 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 의 `ENUMERATED_FIELDS_USE_DOMAIN_TYPE` 이다.
  둘 곳의 기준은 `backend/AGENTS.md` 의 「enum 은 저장 여부로 둘 곳을 정한다」 에 있다.
- 본보기: `backend/src/main/java/com/bifos/assistant/chat/domain/type/ModelTier.java`, `backend/src/main/java/com/bifos/assistant/usage/domain/type/ReasoningEffortSource.java`.
- 모든 `@Enumerated` 는 `EnumType.STRING` 이다. DB 에는 상수 이름이 저장되고 패키지 이름은 저장되지 않는다. 그래서 패키지를 옮겨도 저장 값이 같다.
- enum 여덟은 다른 타입을 import 하지 않는다. `DOMAIN_TYPE_DEPENDS_ON_NOTHING_ABOVE` 에 걸리지 않는다.
- `gradlew` 는 `backend/` 안에 있다.

옮길 것이다. 경로는 `backend/src/main/java/com/bifos/assistant/` 아래다.

| 지금 | 옮긴 뒤 |
| --- | --- |
| `agent/domain/CostMode.java` | `agent/domain/type/CostMode.java` |
| `agent/domain/CredentialScope.java` | `agent/domain/type/CredentialScope.java` |
| `agent/domain/AgentVisibility.java` | `agent/domain/type/AgentVisibility.java` |
| `chat/domain/MessageRole.java` | `chat/domain/type/MessageRole.java` |
| `skill/domain/SkillUseSource.java` | `skill/domain/type/SkillUseSource.java` |
| `usage/domain/ExecutionStatus.java` | `usage/domain/type/ExecutionStatus.java` |
| `usage/domain/ExecutionEventType.java` | `usage/domain/type/ExecutionEventType.java` |
| `user/domain/UserRole.java` | `user/domain/type/UserRole.java` |

**근거 문서**: `docs/adr/ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`, `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」, `backend/AGENTS.md` 의 「enum 은 저장 여부로 둘 곳을 정한다」

## 의도 메모

- 동작을 바꾸지 않는다. 이 phase 의 diff 는 `package` 줄, `import` 줄, 전체 이름 참조, 기준 파일, 새 테스트 하나뿐이어야 한다.
- 기준 파일은 손으로 고치지 않고 명령으로 줄인다. `ArchitectureRules.java` 를 고치지 않는다.
- 포맷은 이 phase 에서 돌리지 않는다. `spotlessApply` 결과는 team-lead 가 따로 커밋한다.
- import 순서가 달라져도 손으로 정렬하지 않는다. 포매터가 정리한다.

## 작업 항목

### 1. 고치기 전에 상수 이름을 적어 둔다

enum 여덟의 상수 이름과 순서를 각 파일에서 읽어 둔다. 작업 항목 5 의 테스트 기대값이다.

### 2. `git mv` 로 여덟 파일을 옮기고 `package` 줄을 고친다

위 표대로 옮긴다. `git mv` 를 쓴다. `package com.bifos.assistant.<기능>.domain;` 을 `package com.bifos.assistant.<기능>.domain.type;` 으로 고친다.

### 3. 참조를 고친다

`backend/src/main/java` 와 `backend/src/test/java` 에서 아래를 모두 고친다.

- `import com.bifos.assistant.<기능>.domain.<Enum>;` 을 `import com.bifos.assistant.<기능>.domain.type.<Enum>;` 으로 바꾼다.
- `import static` 과 Javadoc 의 `{@link}`, 문자열 안의 전체 이름에 옛 패키지가 있으면 같이 바꾼다. 지금 `import static` 과 JPQL 문자열에는 없다.
- **옛 패키지와 같은 패키지에 있던 파일은 import 없이 쓰고 있었다.** `<기능>.domain` 패키지의 클래스와 같은 패키지 이름을 가진 테스트에 새 import 를 더한다.
  `./gradlew compileJava compileTestJava` 가 통과할 때까지 고친다.

끝난 뒤 아래가 0 건이어야 한다.

```bash
# cwd: backend/
grep -rnE "com\.bifos\.assistant\.(agent\.domain\.(CostMode|CredentialScope|AgentVisibility)|chat\.domain\.MessageRole|skill\.domain\.SkillUseSource|usage\.domain\.(ExecutionStatus|ExecutionEventType)|user\.domain\.UserRole)\b" src
```

### 4. 기준 파일을 줄이고 `SHARED_DOES_NOT_DEPEND_ON_DOMAINS` 를 다시 얼린다

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
```

이 명령은 `sharedDoesNotDependOnDomains` 에서 실패한다. 기준 파일 `backend/config/archunit/store/25192a86-f325-4b77-b6d1-3c590c06ead7` 의 다섯 줄이
`com.bifos.assistant.user.domain.UserRole` 을 글자로 담고 있어, 옮긴 뒤의 이름이 새 위반으로 잡히기 때문이다.
그 규칙 하나만 다시 얼린다.

```bash
# cwd: backend/
./gradlew archTest --rerun --tests '*ArchitectureRulesTest.sharedDoesNotDependOnDomains' -Parchunit.freeze.refreeze=true -Parchunit.freeze.store.default.allowStoreUpdate=true
F=config/archunit/store/25192a86-f325-4b77-b6d1-3c590c06ead7
diff <(git show "HEAD:backend/$F" | sed 's/ in (.*)$//' | sed 's/user\.domain\.UserRole/user.domain.type.UserRole/g' | sort) <(sed 's/ in (.*)$//' "$F" | sort)
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
```

`diff` 가 아무것도 내지 않아야 한다. 줄 끝의 `in (파일:줄번호)` 를 떼고 옛 이름을 새 이름으로 바꾸면 전과 같다는 뜻이다. 줄 수는 17 줄 그대로다.
`diff` 가 무엇을 내거나 다른 규칙이 새 위반으로 실패하면 다시 얼리지 말고 기준 파일을 그대로 둔 채 team-lead 에게 보고한다.

### 5. 이 phase 를 검증하는 `StoredEnumNamesTest.java`

`backend/src/test/java/com/bifos/assistant/architecture/StoredEnumNamesTest.java` 를 새로 만든다. Spring 컨텍스트 없이 도는 단위 테스트다.

- 정상: 옮긴 enum 여덟마다 `values()` 의 `name()` 목록이 작업 항목 1 에서 적어 둔 목록과 순서까지 같다. DB 에 저장되는 값이 옮기기 전과 같다는 뜻이다
- 실패: 여덟 가운데 패키지 이름이 `.domain.type` 으로 끝나지 않는 것이 있으면 그 타입 이름을 내며 실패한다
- 클래스 Javadoc 에 「상수 이름은 DB 에 저장된다. 이름을 바꾸거나 빼려면 마이그레이션을 함께 판단한다」 를 적는다

테스트 메서드는 영문 camelCase 이름과 한국어 `@DisplayName` 을 갖는다.

## 검증

```bash
# cwd: backend/
./gradlew test
./gradlew checkstyleMain checkstyleTest
test "$(wc -l < config/archunit/store/2c9d047b-fb3f-4e7d-b83b-975d0be92766)" -eq 0
test "$(wc -l < config/archunit/store/25192a86-f325-4b77-b6d1-3c590c06ead7)" -eq 17
! grep -rnE "com\.bifos\.assistant\.(agent\.domain\.(CostMode|CredentialScope|AgentVisibility)|chat\.domain\.MessageRole|skill\.domain\.SkillUseSource|usage\.domain\.(ExecutionStatus|ExecutionEventType)|user\.domain\.UserRole)\b" src config
```

```bash
# cwd: 저장소 root. Docker 가 있어야 한다
scripts/check-mysql-migration.sh
node test/e2e/run.ts
```

모두 종료 코드 0 이어야 한다. `./gradlew test` 가 `StoredEnumNamesTest` 를 함께 돌린다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/agent/domain/CostMode.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/CredentialScope.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/AgentVisibility.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/MessageRole.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/skill/domain/SkillUseSource.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/ExecutionStatus.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/ExecutionEventType.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/user/domain/UserRole.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/type/CostMode.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/type/CredentialScope.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/type/AgentVisibility.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/type/MessageRole.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/domain/type/SkillUseSource.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/type/ExecutionStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/type/ExecutionEventType.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/user/domain/type/UserRole.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/architecture/StoredEnumNamesTest.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/**/*.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/**/*.java` | 수정 |
| `backend/config/archunit/store/2c9d047b-fb3f-4e7d-b83b-975d0be92766` | 수정 |
| `backend/config/archunit/store/25192a86-f325-4b77-b6d1-3c590c06ead7` | 수정 |
