# Phase 01. 함께 쓰는 값 타입을 runtime 패키지로 옮긴다

**Execution profile**: standard

## 목표

실행 결과와 사건이 담는 값 타입 일곱을 새 최상위 패키지 `com.bifos.assistant.runtime` 으로 옮긴다. 다음 phase 의 중립 port 가 `hermes` 패키지를 기대지 않게 하려는 준비다. **옮기고 이름만 바꾼다. 칸, 메서드, 동작은 그대로다.**

**범위 외**: 중립 port 와 새 타입(phase 02), 부르는 쪽 바꾸기(phase 03), 구조 규칙(phase 04). `HermesRunCommand`, `HermesRunResult`, `HermesRunLookup` 은 옮기지 않고 `hermes` 에 둔다.

## 컨텍스트

- 층 순서는 `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrder.java` 의 `ORDER` 가 갖는다. 지금 맨 아래가 `crypto`, 그 위가 `hermes` 다. `runtime` 을 둘 사이에 넣는다.
- 층 규칙(`PackageLayoutRules` 의 `LAYER_DIRECTION`)은 domain 이 domain 만 쓰게 한다. 엔티티(`usage/execution/domain/AgentExecution`, `usage/execution/domain/ExecutionEvent`, `usage/subagent/domain/SubagentUsageJob`)가 쓰는 타입은 `runtime.domain` 에 둔다.
- 이름 규칙(rule-78)은 운영 코드에 같은 단순 이름의 클래스를 둘 못 두게 한다. phase 02 가 `runtime` 에 `RunEvent` 를 새로 만들므로, 지금 `hermes.application.dto.RunEvent` 를 이 phase 에서 `HermesRunEvent` 로 바꿔 둔다.

**요구사항**: REQ1

**근거 문서**: `docs/features/runtime-abstraction/design.md` 의 「책임과 경계」, 「타입」, `docs/adr/ADR-20261121-acp-runtime.md`

## 의도 메모

- 옮기는 커밋과 동작을 바꾸는 커밋을 나눈다(AGENTS.md 「머지는 PR 로 한다」). 이 phase 에서 칸이나 로직을 고치지 않는다.
- `git mv` 로 옮겨 이름 변경으로 잡히게 한다. PR 크기 계산이 이름 변경은 바뀐 줄만 센다.
- 주석은 고치지 않는다. 패키지 줄, import, 타입 이름만 고친다.

## 작업 항목

### 1. 타입 옮기기

| 지금(`backend/src/main/java/com/bifos/assistant/hermes/` 아래) | 옮긴 뒤(`backend/src/main/java/com/bifos/assistant/` 아래) |
| --- | --- |
| `domain/TokenUsage.java` | `runtime/domain/TokenUsage.java` |
| `domain/SubagentSessionUsage.java` | `runtime/domain/SubagentSessionUsage.java` |
| `domain/ToolDetailScope.java` | `runtime/domain/ToolDetailScope.java` |
| `domain/ToolDetailRedactor.java` | `runtime/domain/ToolDetailRedactor.java` |
| `application/dto/SessionRuntime.java` | `runtime/application/dto/SessionRuntime.java` |
| `application/dto/SteerOutcome.java` | `runtime/application/dto/SteerOutcome.java` |
| `application/dto/HermesImage.java` | `runtime/application/dto/RunImage.java`(타입 이름도 `RunImage`) |
| `application/dto/RunEvent.java` | `hermes/application/dto/HermesRunEvent.java`(패키지는 그대로, 타입 이름만 `HermesRunEvent`) |

운영 코드와 테스트의 import 와 타입 이름을 모두 따라 바꾼다. 찾는 명령:

```bash
git grep -lE "HermesImage|\bRunEvent\b|hermes\.application\.dto\.(SessionRuntime|SteerOutcome)|hermes\.domain\.(TokenUsage|SubagentSessionUsage|ToolDetailScope|ToolDetailRedactor)" -- backend/src
```

- 같은 패키지라 import 없이 쓰던 `hermes` 안의 파일에는 import 를 더한다.
- `backend/src/main/java/db/migration/V41__RedactToolDetails.java` 가 `ToolDetailRedactor` 를 import 한다. import 줄만 고친다(Java 마이그레이션이라 체크섬 대상이 아니다).
- `ToolDetailRedactor` 의 package-private 메서드를 쓰는 `backend/src/test/java/com/bifos/assistant/hermes/domain/ToolDetailRedactorTest.java` 는 `backend/src/test/java/com/bifos/assistant/runtime/domain/` 으로 함께 옮긴다. 같은 폴더의 다른 테스트가 옮긴 타입의 package-private 멤버를 쓰면 같이 옮긴다.
- 옮긴 타입이 `hermes` 의 다른 타입을 쓰면(검증의 마지막 명령) 멈추고 `PHASE_BLOCKED` 로 알린다. `runtime` 은 `hermes` 를 import 하지 못한다.

### 2. 층 순서에 `runtime` 넣기

`TopLevelPackageOrder.ORDER` 에서 `"crypto"` 다음, `"hermes"` 앞에 `"runtime"` 을 넣는다.
`TopLevelPackageOrderTest` 의 개수 단언(`hasSize(23)`, `hasSize(24)`)을 하나씩 올리고, 「`hermes` 는 `runtime` 을 쓸 수 있고 `runtime` 은 `hermes` 를 쓰지 못한다」 를 확인하는 사례를 더한다(같은 파일의 `violations("hermes", List.of(...))` 사례를 따른다).

### 3. 문서

`backend/docs/code-architecture.md` 의 「최상위 패키지의 층 순서」 에 `runtime` 한 단락을 더한다: 런타임에 묶이지 않는 실행 타입과 port 를 갖고 `crypto` 바로 위다. 까닭은 ADR-20261121 / acp-runtime 을 링크한다.

## 검증

```bash
cd backend && ./gradlew compileJava compileTestJava
cd backend && ./gradlew archTest
cd backend && ./gradlew test
git grep -n "import com.bifos.assistant.hermes" -- backend/src/main/java/com/bifos/assistant/runtime; test $? -eq 1
```

`archTest` 가 기준에 든 클래스의 이름이 바뀌었다고 실패하면 `backend/docs/code-architecture.md` 의 「기준에 든 클래스의 이름만 바꿨다」 절차를 따르고 까닭을 커밋 메시지에 적는다.
마지막 줄은 `runtime` 이 `hermes` 를 import 하지 않는지 본다(찾은 것이 없어야 한다).

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/runtime/domain/TokenUsage.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/runtime/domain/SubagentSessionUsage.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/runtime/domain/ToolDetailScope.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/runtime/domain/ToolDetailRedactor.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/runtime/application/dto/SessionRuntime.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/runtime/application/dto/SteerOutcome.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/runtime/application/dto/RunImage.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/application/dto/HermesRunEvent.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/domain/TokenUsage.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/hermes/domain/SubagentSessionUsage.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/hermes/domain/ToolDetailScope.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/hermes/domain/ToolDetailRedactor.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/hermes/application/dto/SessionRuntime.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/hermes/application/dto/SteerOutcome.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/hermes/application/dto/HermesImage.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/hermes/application/dto/RunEvent.java` | 삭제 |
| `backend/src/main/java/db/migration/V41__RedactToolDetails.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/**/*.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/runtime/domain/ToolDetailRedactorTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/hermes/domain/ToolDetailRedactorTest.java` | 삭제 |
| `backend/src/test/java/com/bifos/assistant/**/*.java` | 수정 |
| `backend/docs/code-architecture.md` | 수정 |
