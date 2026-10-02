# Phase 01. ModelChoice 와 ModelTier 를 새 패키지 model 로 옮긴다

**Execution profile**: standard

## 목표

모델 선택을 담는 값 `ModelChoice` 와 저장되는 enum `ModelTier` 를 `chat` 에서 새 최상위 패키지 `model` 로 옮긴다(ADR-068 의 C2).
`usage` 와 `memory` 가 이 둘 때문에 `chat` 을 import 하던 참조가 없어진다.

**범위 외**: 두 타입의 본문. `ModelTierService` 같은 서비스는 `chat` 에 그대로 둔다. `usage` 가 `Conversation` 을 쓰는 참조는 다음 phase 가 맡는다. 이 phase 의 diff 는 `package` 줄, `import` 줄, 전체 이름 참조, 문서, 기준 파일, 테스트 하나뿐이어야 한다.

## 컨텍스트

- 규칙은 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 의 `TOP_LEVEL_PACKAGES_FREE_OF_CYCLES` 다. 최상위 패키지 간선 하나가 위반 하나다. `B` 에서 `A` 로 돌아올 수 있으면 간선 `A -> B` 가 위반이다. `shared` 는 그래프 밖이다. 규칙은 컴파일한 클래스를 읽는다.
- 기준 파일은 `backend/config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948` 다. 손으로 고치지 않고 아래 명령으로 줄인다. 절차는 `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」 에 있다.
- 결정의 근거는 `docs/adr/ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md` 다. 층 순서는 아래에서 위로 `hermes`, `user`, `model`, `agent`, `skill`, `usage`, `memory`, `context`, `chat`, `orchestration`, `mcp`, `people`, `connector` 다.
- `LAYER_DIRECTION` 은 패키지를 넘어서도 건다. `domain` 은 어느 층이나 쓸 수 있다. `application` 은 `presentation` 과 `application` 만 접근할 수 있고 `infra` 는 `application` 만 접근할 수 있다. port 를 구현하는 클래스는 위 패키지의 `application` 에 둔다.
- `application` 과 `domain` 은 타입 하나에 파일 하나다(`backend/AGENTS.md`).
- **동작을 바꾸지 않는다.** 저장되는 값, 질의 수, 트랜잭션이 열리는 자리가 그대로여야 한다. `ArchitectureRules.java` 를 고치지 않는다.
- 포맷은 이 phase 에서 돌리지 않는다. `spotlessApply` 결과는 team-lead 가 따로 커밋한다. import 순서를 손으로 정렬하지 않는다. BSD `sed` 는 `\b` 를 모른다. 여러 파일의 이름을 바꿀 때는 `perl -pi -e` 를 쓴다.
- 주석과 Javadoc 은 한국어로 쓴다. 테스트 메서드는 영문 camelCase 이름과 한국어 `@DisplayName` 을 갖는다. `gradlew` 는 `backend/` 안에 있다.
- 옮길 것이다. 경로는 `backend/src/main/java/com/bifos/assistant/` 아래다.

| 지금 | 옮긴 뒤 |
| --- | --- |
| `chat/domain/ModelChoice.java` | `model/domain/ModelChoice.java` |
| `chat/domain/type/ModelTier.java` | `model/domain/type/ModelTier.java` |

- `ModelChoice` 는 `shared.error` 만 쓰는 record 다. `ModelTier` 는 아무것도 import 하지 않는 enum 이고 `Conversation`, `AgentExecution`, `ModelTierDefinition`, `ModelTierGroupSetting` 이 `@Enumerated(EnumType.STRING)` 으로 저장한다. DB 에는 상수 이름만 저장된다.
- `ENUMERATED_FIELDS_USE_DOMAIN_TYPE` 은 저장되는 enum 이 `..domain.type..` 패키지에 있기를 요구한다. `model.domain.type` 은 이 조건을 만족한다.
- JPQL 문자열과 Javadoc 에 옛 전체 이름이 있는지 `grep -rn "chat\.domain\.type\.ModelTier\|chat\.domain\.ModelChoice" backend/src docs` 로 찾아 모두 고친다. 문자열 안의 이름은 컴파일이 잡지 못하고 `scripts/check-mysql-migration.sh` 가 잡는다.

**근거 문서**: 위 ADR-068 의 C2, `docs/model-tiers.md`, `docs/backend/packages.md` 의 「패키지와 책임」, `backend/AGENTS.md` 의 「enum 은 저장 여부로 둘 곳을 정한다」

## 의도 메모

- 두 타입을 `usage.domain` 에 두지 않는다. 모델 단계와 숨김과 기본 모델은 따로 커지는 영역이라 `usage` 의 것으로 읽히면 안 된다(ADR-068 의 「대안 기각」).
- `chat/domain/type` 에는 `ModelSelectionMode` 가 남는다. 디렉터리는 지우지 않는다.

## 작업 항목

### 1. `git mv` 로 둘을 옮기고 `package` 줄을 고친다

위 표대로 옮긴다. 본문은 `package` 줄만 바꾼다.

### 2. 참조를 고친다

`backend/src/main/java` 와 `backend/src/test/java` 에서 두 import 를 새 패키지로 바꾼다. 패턴 끝을 `;` 로 고정한다.
같은 패키지라 import 없이 쓰던 `chat.domain` 과 `chat.domain.type` 의 클래스에 import 를 더한다. `./gradlew compileJava compileTestJava` 가 통과할 때까지 고친다.

### 3. 문서를 고친다

- `docs/backend/packages.md` 의 「패키지와 책임」 표에서 `user` 줄 다음에 `| `model` | 모델 선택을 담는 값과 모델 단계 값. 서비스는 아직 `chat` 에 있다 |` 줄을 더한다
- `git grep -n "chat[./]domain[./]\(type[./]\)\?\(ModelChoice\|ModelTier\)\b" -- docs backend/AGENTS.md AGENTS.md` 로 문서의 옛 위치를 찾아 새 위치로 고친다

고친 문서에 `bash /Users/nhn/personal/fos-skills/content-preview/scripts/style-check.sh <파일>` 을 돌려 종료 코드 0 인지 본다.

### 4. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/architecture/StoredEnumNamesTest.java` 의 `STORED_ENUMS` 에 `ModelTier` 를 더한다. 기대 상수는 `ModelTier.java` 에서 읽은 이름과 순서 그대로다.
정상: `ModelTier` 의 상수 이름과 순서가 옮기기 전과 같다. 실패: 패키지가 `.domain.type` 으로 끝나지 않으면 그 타입 이름을 내며 실패한다.
`Map.of` 는 열 쌍까지만 받는다. 아홉 쌍이 되므로 그대로 쓸 수 있다.

### 5. 기준 파일을 줄인다

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
```

이 명령은 테스트 소스도 컴파일한다. `usage` 와 `memory` 는 아직 `Conversation` 을 써서 `chat` 간선이 남는다. 이 phase 에서 순환 기준이 줄지 않을 수 있다.
줄이 늘거나 새 위반으로 실패하면 다시 얼리지 말고 보고한다. 끝난 뒤의 줄 수를 회신에 적는다.

## 검증

```bash
# cwd: backend/
./gradlew test
./gradlew checkstyleMain checkstyleTest
! grep -rn "chat\.domain\.type\.ModelTier\|chat\.domain\.ModelChoice" src config
test -f src/main/java/com/bifos/assistant/model/domain/ModelChoice.java
```

```bash
# cwd: 저장소 root. Docker 가 있어야 한다
scripts/check-mysql-migration.sh
node test/e2e/run.ts
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/domain/ModelChoice.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/type/ModelTier.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/model/domain/ModelChoice.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/model/domain/type/ModelTier.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/**/*.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/StoredEnumNamesTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/**/*.java` | 수정 |
| `docs/backend/packages.md` | 수정 |
| `docs/**/*.md` | 수정 |
| `backend/config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948` | 수정 |
