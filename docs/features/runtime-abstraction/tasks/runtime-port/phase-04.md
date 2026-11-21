# Phase 04. 구조 규칙으로 경계를 지키고 문서를 맞춘다

**Execution profile**: standard

## 목표

「`runtime` 은 `hermes` 를 모른다」 와 「`hermes` 밖은 Hermes 의 실행 port 와 타입을 쓰지 않는다」 를 구조 테스트로 지키고, mcp 가 새 port 로 실행을 직접 부르지 못하게 rule-11 을 넓힌다. 문서를 지금 코드에 맞춘다. 운영 코드는 주석만 바뀐다.

**범위 외**: ACP 구현(다음 PR).

## 컨텍스트

- 구조 규칙은 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 에 상수로 두고 `ArchitectureRulesTest` 가 `FreezingArchRule.freeze(규칙).check(MAIN)` 로 돌린다. 규칙마다 `/** rule-N: 까닭과 예외는 docs/code-rules/rule-N.md를 본다. */` 주석과 `docs/code-rules/rule-N.md`, `docs/code-rules/INDEX.md` 의 행이 있고 `test/repo/rule-index.test.ts` 가 그 형식을 본다.
- 기준 파일은 평소 쓰지 않는다. 규칙을 새로 더하면 `backend/docs/code-architecture.md` 의 「규칙을 새로 더했다」 절차(`./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true`)로 그 규칙의 기준 파일을 만든다. 위반이 없으면 빈 기준 파일이 생긴다.
- rule-11 은 `ArchitectureRules.MCP_DOES_NOT_CALL_HERMES` 다. `hermes` 패키지의 `*Client`, `HermesRunEventStream`, `HermesProfileKeyStore` 만 막는다. 기준 파일(`backend/config/archunit/store/stored.rules`)이 규칙의 `.as(...)` 문구를 키로 쓴다.

**요구사항**: REQ1

**근거 문서**: `docs/features/runtime-abstraction/design.md` 의 「검증 전략」, `docs/adr/ADR-20261121-acp-runtime.md` 의 「확인 방법」

## 의도 메모

- rule-11 의 `.as("mcp 는 Hermes 를 부르는 타입에 의존하지 않는다")` 문구는 바꾸지 않는다. 바꾸면 기준 파일의 키가 끊긴다. 조건만 넓힌다.
- 새 규칙의 금지 대상은 타입 이름으로 좁게 둔다. `hermes.infra` 의 다른 클래스를 쓰는 Hermes 전용 기능(모델 목록, 첨부 디렉터리, 원고 사본, 하위 에이전트 사용량 보정)은 대상이 아니다.

## 작업 항목

### 1. 새 구조 규칙 둘

`ArchitectureRules` 에 상수 둘을 더하고 `ArchitectureRulesTest` 에 테스트 메서드를 더한다. 번호는 `docs/code-rules/INDEX.md` 의 마지막 번호 다음 둘이다. 규칙 문서 둘을 `docs/code-rules/rule-11.md` 와 같은 절(검사, 까닭, 예외와 검토 범위, 이 저장소의 사례)로 만들고 INDEX 에 행을 더한다.

- `RUNTIME_DOES_NOT_KNOW_HERMES`: `com.bifos.assistant.runtime..` 의 클래스는 `com.bifos.assistant.hermes..` 에 기대지 않는다. 까닭: 다른 런타임 구현이 `runtime` 만 기대고 붙어야 한다(ADR-20261121 / acp-runtime).
- `ONLY_HERMES_USES_HERMES_RUN_TYPES`: `com.bifos.assistant.hermes..` 밖의 클래스는 단순 이름이 `HermesRunsClient`, `HermesRunEventStream`, `HermesRunCommand`, `HermesRunResult`, `HermesRunLookup`, `HermesRunEvent` 인 타입에 기대지 않는다. 까닭: 실행은 `AgentRuntimes` 로 고른 구현을 거친다.

phase 03 에서 호출자를 다음 PR 로 미뤘으면 그 클래스의 위반이 둘째 규칙의 기준 파일에 들어간다. 그 사실을 커밋 메시지와 PR 본문에 적는다. 미루지 않았으면 기준 파일은 비어 있어야 한다.

규칙이 실제로 막는지 확인한다: `chat` 의 아무 클래스에 `HermesRunsClient` 필드를 임시로 더해 `./gradlew archTest` 가 실패하는 것을 보고 되돌린다. 확인한 사실을 커밋 메시지에 적는다.

### 2. rule-11 넓히기

`MCP_DOES_NOT_CALL_HERMES` 의 `dependOnClassesThat(...)` 조건에 「`com.bifos.assistant.runtime..` 의 `AgentRuntime` 또는 `AgentRuntimes`」 를 `or` 로 더한다. `docs/code-rules/rule-11.md` 의 첫 문장을 「mcp는 Hermes 호출 타입과 런타임 실행 port 를 직접 쓰지 않는다.」 로 고치고 「예외와 검토 범위」 에 「`runtime` 의 값 타입(`TokenUsage` 등)은 허용한다」 를 더한다.

### 3. 주석

`runtime` 으로 옮긴 타입(`TokenUsage`, `SubagentSessionUsage`, `ToolDetailScope`, `ToolDetailRedactor`, `SessionRuntime`, `SteerOutcome`, `RunImage`)의 Javadoc 에서 Hermes 에만 맞는 서술(`POST /v1/runs/{run_id}/steer`, 「Hermes 가 …」)을 「런타임 구현이 …」 로 바꾸고, Hermes 의 구체 서술이 필요하면 `HermesAgentRuntime` 이나 `HermesRunsClient` 의 Javadoc 으로 옮긴다. `HermesRunsClient` 의 클래스 Javadoc 첫 문단에 「`hermes` 안의 내부 경계다. 밖에서는 `AgentRuntime` 을 쓴다」 를 더한다.

### 4. 문서

- `docs/features/runtime-abstraction.md` 맨 위의 「아직 들어온 동작이 없다.」 문장을 「실행 port 가 `runtime` 패키지의 중립 계약(`AgentRuntime`)으로 옮겨졌고 구현은 Hermes 하나다.」 로 바꾼다. 「지금 Control Plane 이 Hermes 에 기대는 곳」 표의 「실행」 행의 port 를 `AgentRuntime`(Hermes 구현은 `HermesAgentRuntime`)으로 고친다.
- `backend/docs/code-architecture.md` 에서 실행 흐름이 `HermesRunsClient` 를 부른다고 적은 문장(`git grep -n "HermesRunsClient\|HermesRunEventStream" -- backend/docs docs hermes/docs`)을 `AgentRuntimes` 를 거친다고 고친다. ADR 본문은 고치지 않는다(결정 당시의 기록이다).

## 검증

```bash
cd backend && ./gradlew archTest
cd backend && ./gradlew qualityCheck
cd backend && ./gradlew test
node --test test/repo/rule-index.test.ts test/repo/doc-links.test.ts test/repo/doc-files.test.ts
scripts/dev/check-local.sh --fast
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRulesTest.java` | 수정 |
| `backend/config/archunit/store/*` | 신규·수정 |
| `docs/code-rules/*.md` | 신규·수정 |
| `backend/src/main/java/com/bifos/assistant/runtime/**/*.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/application/port/HermesRunsClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/infra/HermesAgentRuntime.java` | 수정 |
| `docs/features/runtime-abstraction.md` | 수정 |
| `backend/docs/code-architecture.md` | 수정 |
