# Phase 02. 도구 내용의 길이 상한을 hermes 가 갖는다

**Execution profile**: standard

## 목표

`hermes.ToolDetailRedactor` 가 `usage.domain.ExecutionEvent.DETAIL_LIMIT` 을 읽는 참조를 없앤다. `hermes` 는 층 순서의 맨 아래다.
이 참조는 컴파일 때 값으로 바뀌는 상수라 ArchUnit 이 보지 못한다. 소스에서 방향을 맞춰 둔다(ADR-068 의 「맥락」).

**범위 외**: 상한의 값(500). 가리는 규칙. `ToolDetailRedactor` 의 메서드 시그니처. 이미 적용된 Java 마이그레이션 `db/migration/V41__RedactToolDetails.java` 가 이 클래스의 메서드를 부르므로 시그니처를 바꾸지 않는다.

## 컨텍스트

- 규칙은 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 에 있고, 순환 규칙의 조건은 같은 디렉터리의 `TopLevelPackageCycles.java` 다. 최상위 패키지 간선 하나가 위반 하나다. `shared` 는 그래프 밖이다. 규칙은 컴파일한 클래스를 읽는다.
- 순환 규칙의 기준 파일은 `backend/config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948` 다. 손으로 고치지 않는다. 기준을 다루는 절차는 `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」 에 있다.
- 결정의 근거는 `docs/adr/ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md` 다. 층 순서는 아래에서 위로 `hermes`, `user`, `model`, `agent`, `skill`, `usage`, `memory`, `context`, `chat`, `orchestration`, `mcp`, `people`, `connector` 다.
- **동작을 바꾸지 않는다.**
- 포맷은 이 phase 에서 돌리지 않는다. 이 phase 커밋 뒤 team-lead 가 `./gradlew spotlessApply` 결과를 별도 커밋으로 낸다. import 순서를 손으로 정렬하지 않는다.
- 주석과 Javadoc 은 한국어로 쓴다. 테스트 메서드는 영문 camelCase 이름과 한국어 `@DisplayName` 을 갖는다. `gradlew` 는 `backend/` 안에 있다.
- `backend/src/main/java/com/bifos/assistant/usage/domain/ExecutionEvent.java` 에 `public static final int DETAIL_LIMIT = 500;` 이 있고 `@Column(name = "detail", length = DETAIL_LIMIT)` 와 잘라 넣는 식이 쓴다.
- `backend/src/main/java/com/bifos/assistant/hermes/ToolDetailRedactor.java` 가 `ExecutionEvent.DETAIL_LIMIT` 을 두 곳에서 읽는다. 이 클래스가 `usage` 를 import 하는 까닭은 이 상수 하나다.
- `usage` 는 이미 `hermes` 를 쓴다(`SubagentUsageReconciler` 등). `usage.domain` 이 `hermes` 의 상수를 읽어도 새 간선이 생기지 않고, 상수라 바이트코드에도 남지 않는다.
- 테스트 `backend/src/test/java/com/bifos/assistant/usage/ExecutionEventRecorderTest.java` 가 `ExecutionEvent.DETAIL_LIMIT` 을 읽는다. 이 이름은 남긴다.

**근거 문서**: 위 ADR-068, `docs/adr/ADR-047-도구-내용은-비밀값과-UUID를-가린-뒤-중계하고-저장한다.md`, `docs/adr/ADR-013-실행-사건은-우리-모델로-정규화해-저장한다.md`

## 의도 메모

- `ExecutionEvent.DETAIL_LIMIT` 을 지우지 않는다. 열 길이와 테스트가 이 이름을 쓴다. 값의 출처만 `hermes` 로 옮긴다.
- 상한을 메서드 인자로 받게 하지 않는다. 적용된 마이그레이션이 부르는 시그니처가 바뀐다.

## 작업 항목

### 1. `ToolDetailRedactor` 에 상수를 둔다

`public static final int DETAIL_LIMIT = 500;` 을 더하고 Javadoc 에 「가린 뒤 중계하고 저장하는 도구 내용의 글자 상한이다. 실행 사건의 `detail` 열 길이와 같다」 를 적는다.
`ExecutionEvent.DETAIL_LIMIT` 을 읽던 두 곳을 자기 상수로 바꾸고 `usage` 의 import 를 지운다.

### 2. `ExecutionEvent` 가 그 상수를 가리킨다

`public static final int DETAIL_LIMIT = ToolDetailRedactor.DETAIL_LIMIT;` 로 바꾼다. 컴파일 때 상수라 `@Column(length = DETAIL_LIMIT)` 가 그대로 컴파일된다. Javadoc 에 「값은 `hermes` 의 `ToolDetailRedactor` 가 갖는다」 를 한 줄 더한다.

### 3. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/hermes/ToolDetailRedactorTest.java` 에 테스트 하나를 더한다.
- 정상: `ToolDetailRedactor.DETAIL_LIMIT` 이 500 이고 `ExecutionEvent.DETAIL_LIMIT` 과 같다
- 경계는 그 파일의 기존 테스트가 이미 본다. `"가".repeat(600)` 을 넣고 길이 500 을 단언한다. 새로 더하지 않는다

### 4. ADR 의 문장을 고친다

ADR-068 의 「맥락」 은 이 참조를 「상한을 인자로 받게 고쳐 함께 끊는다」 고 적었다. 그 방법은 쓰지 않는다.
그 문장을 「상한 상수를 `hermes` 로 옮기고 `usage` 가 그 값을 읽게 해 함께 끊는다. 인자로 받게 하면 적용된 마이그레이션 `V41` 이 부르는 시그니처가 바뀐다」 로 고친다.
고친 문서에 `한국어 문체 검사기` 을 돌려 종료 코드 0 인지 본다.

기준 파일은 이 phase 에서 바뀌지 않는다. `./gradlew test` 가 구조 규칙을 함께 검사한다.

## 검증

```bash
# cwd: backend/
./gradlew test
./gradlew checkstyleMain checkstyleTest
git diff --exit-code -- config/archunit/store
! grep -rnE "^import (static )?com\.bifos\.assistant\.(usage|chat|agent|memory|skill|context|orchestration|mcp|people|connector|user|model)\." src/main/java/com/bifos/assistant/hermes
```

```bash
# cwd: 저장소 root. Docker 가 있어야 한다
scripts/check-mysql-migration.sh
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/hermes/ToolDetailRedactor.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/ExecutionEvent.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/ToolDetailRedactorTest.java` | 수정 |
| `docs/adr/ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md` | 수정 |
