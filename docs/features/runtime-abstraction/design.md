# Agent Runtime 을 바꿔 끼우기 기술 설계

스펙은 [`spec.md`](spec.md) 다. 이 설계의 DECISION 번호는 이 파일의 것이고, 스펙의 결정은 「스펙 DECISION」 으로 가리킨다.

**이 설계는 PR1(실행 port 정리)을 구현할 만큼 정하고, PR2 부터의 ACP 구현은 방향과 PR1 이 열어 둘 자리만 정한다.**
ACP 로 구독 로그인, 지침 넣기, Control Plane 도구 붙이기, 웹 검색이 되는지는 실제로 띄워 확인해야 정할 수 있다(스펙 DECISION3). 그 확인과 ACP 구현의 상세 설계는 PR2 가 이 파일을 고쳐 넣는다.

## 요구사항 연결

| ID | 해결 방법 |
| --- | --- |
| REQ1 | 새 최상위 패키지 `runtime` 에 중립 port `AgentRuntime` 과 그 타입을 두고, 실행을 부르는 모든 곳이 `AgentRuntimes`(런타임 고르기)를 거쳐 부른다. Hermes 구현은 지금의 HTTP 구현과 사건 스트림을 감싼 어댑터다. 「인터페이스와 데이터」 가 계약을 갖는다 |
| REQ2 | [기능 파일](../runtime-abstraction.md)의 「하네스 설계 원칙 비교」 를 PR1 이 함께 올린다. 코드 없음 |
| REQ3 | PR2. `agent` 에 런타임 컬럼을 더하고(스펙 DECISION15) `Agent.runtimeTarget()` 이 그 값으로 대상을 만든다. 바꾸는 API 는 `admin.agent` 가 갖고 스펙 DECISION18 의 주인 검사를 한다 |
| REQ4 | PR2. `AgentRuntime` 의 ACP 구현 하나가 제출, 사건, 결과를 맡는다. PR1 의 port 계약 테스트를 그대로 통과해야 한다. 실행 기록의 런타임 컬럼은 `RuntimeTarget.kind` 로 채운다 |
| REQ5 | PR2. 지침과 기억은 지금처럼 `RunCommand.instructions` 로 온다. ACP 구현이 그것을 런타임에 넣는 방법은 실제로 띄워 확인한 뒤 정한다 |
| REQ6 | PR2. ACP 구현의 `stop` 이 `session/cancel` 을 보낸다. 부르는 쪽의 중지 흐름은 그대로다 |
| REQ7 | PR2. ACP 구현의 글 끼우기가 `SteerOutcome.REJECTED`(끼우지 않았다)로 답해 지금의 「다음 turn 으로 넘기기」 흐름을 탄다 |
| REQ8 | PR2. 런타임이 검색을 도구 사건으로 알리면 ACP 구현이 도구 시작과 끝 사건으로 옮긴다. 검색이 ACP 로 되는지는 실제로 띄워 확인한다 |
| REQ9 | PR2. ACP 구현이 세션을 열 때 런타임이 알린 이미지 입력 능력을 보고, 받지 못하면 사진이 든 `RunCommand` 를 실패 결과로 답한다 |
| REQ10 | PR2 는 ACP 구현이 권한 요청을 자동으로 거절하고 실행 기록에 남긴다. PR4 는 권한 요청을 승인 카드로 옮기고 답을 기다린다(스펙 DECISION19) |
| REQ11 | PR2. `RuntimeTarget.unit` 이 사용자와 런타임의 짝마다 다르고, ACP 구현이 unit 마다 프로세스와 디렉터리를 따로 둔다 |
| REQ12 | PR2. ACP 구현이 프로세스 종료, 로그인 만료, 한도 초과를 실패 결과로 답하고, 다음 `submit` 에서 프로세스를 다시 띄운다. `AgentRuntimes` 는 다른 런타임으로 바꿔 돌리지 않는다 |
| REQ13 | PR3. ZeroClaw 는 ACP 구현의 설정 한 벌로 붙인다. 구현을 고쳐야 했던 곳은 기능 파일에 적는다 |
| REQ14 | PR4. 새 런타임이 Control Plane MCP 서버를 부를 때 「어느 실행이 불렀는지」 를 정하는 방법은 PR4 설계가 정한다. OpenClaw 의 ACP 연결은 세션마다 넘기는 MCP 서버를 받지 않는다 |
| REQ15 | PR4. Control Plane 도구의 승인 카드는 MCP 서버 쪽 판정이라 REQ14 가 되면 그대로 뜬다. 런타임이 묻는 권한은 REQ10 의 길이다 |
| REQ16 | PR5. 위임은 이미 Control Plane 의 `agent_delegate` 가 실행을 만들어 부르므로, 맡는 쪽 실행이 `AgentRuntimes` 로 가면 런타임을 넘는다. 결과 전달은 Control Plane 의 자동 turn 을 그대로 쓴다 |
| REQ17 | PR5. 코드 없음. 기능 파일에 표와 왕복 확인 결과를 적는다 |

## 책임과 경계

| 모듈 | 맡는 것 |
| --- | --- |
| `runtime`(새 최상위 패키지) | 중립 실행 port `AgentRuntime`, 그 타입, 대상의 런타임 종류로 구현을 고르는 `AgentRuntimes`. 아무 기능에도 기대지 않는다 |
| `hermes` | `AgentRuntime` 의 Hermes 구현(`hermes.infra`). Hermes 사건 이름과 상태 문자열을 중립 값으로 옮긴다. 지금의 `HermesRunsClient` 와 `HermesRunEventStream` 은 자기 타입(`HermesRunCommand`, `HermesRunResult`, `HermesRunLookup`, `HermesRunEvent`)과 함께 `hermes` 안의 내부 경계로 남아 이 구현만 부르고, 이 구현이 중립 타입과 서로 옮긴다(DECISION6). 나머지 Hermes port 는 그대로다(스펙 DECISION8) |
| `agent` | `Agent.runtimeTarget()`. 에이전트가 지금 어느 런타임의 어느 격리 단위에서 도는지를 값 하나로 내준다. PR1 에서는 늘 Hermes 다 |
| `chat`, `orchestration`, `usage` | 실행을 부르는 곳. `AgentRuntimes` 와 `runtime` 의 타입만 쓴다 |

층 순서는 `crypto < runtime < hermes < …` 로 둔다(`TopLevelPackageOrder`). `hermes` 가 `runtime` 의 port 를 구현하고, 위 모듈이 `runtime` 을 부른다. `runtime` 은 `hermes` 를 모른다.

`runtime` 안의 역할 패키지는 층 규칙(`LAYER_DIRECTION`: domain 은 domain 만 쓴다)이 정한다.

| 패키지 | 타입 | 까닭 |
| --- | --- | --- |
| `runtime.domain` | `RuntimeKind`, `RuntimeTarget`, `ToolDetailScope`, `ToolDetailRedactor`, `TokenUsage`, `SubagentSessionUsage` | `agent.domain`, `usage.execution.domain`, `usage.subagent.domain` 의 엔티티가 쓴다 |
| `runtime.application.port` | `AgentRuntime` | |
| `runtime.application.dto` | `RunCommand`, `RunImage`, `RunEvent`, `RunEventKind`, `RunResult`, `RunOutcome`, `RunLookup`, `SessionRuntime`, `SteerOutcome` | |
| `runtime.application` | `AgentRuntimes` | |

### 그대로 두는 Hermes 전용 자리

- 실행 경로 밖에서 주소와 profile 을 쓰는 기능(toolset, 스킬, 플러그인 붙이기, 성격 글, 모델 목록, 첨부 디렉터리, 원고 사본).
- **하위 에이전트 사용량 보정**(`SubagentUsageReconciler` 가 `hermes.infra` 의 provider 조회와 profile 기본 모델 조회를 부르는 부분). Hermes 내장 하위 에이전트에만 있는 일이고 새 런타임은 하위 에이전트를 끈다(스펙 DECISION17). port 의 `readSubagentUsage` 만 `runtime` 으로 옮기고 나머지는 Hermes 전용으로 남긴다.
- **실행 한도 설정**(`hermes.config.HermesProperties` 의 실행 제한 시간과 조회 간격). PR1 은 자리와 이름을 바꾸지 않는다. 새 런타임이 「Hermes 실행과 같은 한도」(스펙 REQ12)를 쓰므로 중립 설정으로 옮길지는 PR2 가 정한다.

## 인터페이스와 데이터

### port

`AgentRuntime` 은 지금 `HermesRunsClient` 가 가진 동작과 사건 스트림을 갖고, 주소와 profile 이름 대신 `RuntimeTarget` 을 받는다.

| 동작 | 지금 | 바뀐 뒤 | 하지 못하는 구현이 내는 값 |
| --- | --- | --- | --- |
| 제출, 끝까지 기다리기 | `submit(HermesRunCommand)`, `awaitCompletion` | `RunCommand` 가 `RuntimeTarget` 을 갖는다 | 해당 없음. 모든 구현이 한다 |
| 사건 받기 | application 이 `HermesRunEventStream.open(…)` 을 직접 부른다 | `openEvents(target, runId, onEvent, onOpened, scope)`. scope 에 따라 가린 사건만 낸다 | 해당 없음 |
| 중지 | `stop(apiBaseUrl, profileName, runId)` | `stop(target, runId)` | 해당 없음 |
| 조회 | `lookupRun` | `lookupRun(target, runId)` | 다시 물을 곳이 없으면 `NOT_FOUND` |
| 세션 삭제 | `deleteSession` | `deleteSession(target, sessionId)` | 아무것도 하지 않는다 |
| 글 끼우기 | `steer` → `SteerOutcome` 셋 | 값을 더하지 않는다 | `REJECTED`. 지금 기본 구현과 같고 부르는 쪽이 다음 turn 으로 넘긴다 |
| 잃은 실행 정리, 실제로 돈 모델, 하위 에이전트 사용량 | 기본 구현이 있다 | 그대로 | 아무것도 하지 않거나 null |
| 런타임 종류 | 없음 | 구현마다 `kind()`. `AgentRuntimes` 가 이것으로 고른다 | |

### 타입

`RunCommand`, `RunEvent`, `RunResult`, `RunLookup` 은 새로 만드는 중립 타입이다. `hermes` 의 짝 타입(`HermesRunCommand`, `HermesRunEvent`, `HermesRunResult`, `HermesRunLookup`)은 내부 경계에 남고 `HermesAgentRuntime` 이 둘 사이를 옮긴다. 지금 `hermes.application.dto.RunEvent` 는 단순 이름이 겹치지 않게 `HermesRunEvent` 로 이름을 바꾼다.

| 타입 | 내용 |
| --- | --- |
| `RuntimeKind` | PR1 은 `HERMES` 하나. PR2 와 PR3 이 값을 더한다 |
| `RuntimeTarget` | `kind`, `location`, `unit`. 구현만 뜻을 안다. Hermes 구현에게 `location` 은 API server 주소, `unit` 은 profile 이름이다 |
| `RunCommand` | `target`, `input`, `instructions`, `sessionId`, `provider`, `model`, `reasoningEffort`, `images`(`RunImage`). `provider` 와 `model` 을 함께 채우거나 비우는 규칙은 Hermes 구현이 검사한다 |
| `RunEvent` | `type` 문자열을 `RunEventKind`(답 조각, 중간 안내, 도구 시작, 도구 끝, 하위 에이전트 시작, 하위 에이전트 끝, 완료, 실패, 취소, 중단, 그 밖)로 바꾼다. 나머지 칸은 그대로다 |
| `RunResult` | `status` 문자열을 둘로 나눈다. `outcome`(`RunOutcome`: 성공, 실패, 취소)은 부르는 쪽이 분기에 쓰고, `statusLabel` 은 실행 기록의 오류 코드와 로그에 그대로 적는 글자다. 「provider 계정이 막힘」 접두 문자열 검사는 Hermes 구현으로 옮기고 결과에는 불리언으로 남긴다. 나머지 칸은 그대로다 |
| `RunLookup` | 지금 `HermesRunLookup` 과 같은 세 상태(도는 중, 끝남, 모름)이고 끝난 결과를 `RunResult` 로 갖는다 |
| `RunImage`, `SessionRuntime`, `SteerOutcome`, `TokenUsage`, `SubagentSessionUsage`, `ToolDetailScope`, `ToolDetailRedactor` | 칸과 동작은 그대로이고 `hermes` 에서 패키지만 옮긴다(`HermesImage` 는 이름도 바꾼다). Hermes 의 내부 경계도 옮긴 타입을 쓴다 |

**`status` 는 지금 application 이 읽는다.** 취소인지 보는 곳(`ChatTurnRunner`, `AgentRunner`, `RecoveredRunRecorder`)과, 대문자로 바꿔 실행 기록의 오류 코드로 저장하거나 로그에 남기는 곳(`ChatTurnRunner`, `AgentRunner`, `StarterSuggestionService`, `RoomContext`, `RecoveredRunRecorder`)이 있다.
Hermes 구현은 `statusLabel` 에 지금 저장되는 것과 같은 글자를 넣어 데이터베이스의 오류 코드 값이 바뀌지 않게 한다. 새 런타임 구현은 Hermes 가 쓰는 이름(`FAILED`, `CANCELLED`, `INTERRUPTED`)에 맞춘다.

### 대상을 만드는 두 길

| 길 | 쓰는 곳 | 만드는 값 |
| --- | --- | --- |
| 지금 에이전트에서 | 새 실행을 보내는 곳(대화 turn, 예약 작업 turn, 위임, 추천 질문, 대화방 요약) | `Agent.runtimeTarget()` |
| 저장된 기록에서 | 이미 보낸 실행을 다시 부르는 곳. 재기동 복구와 위임 중지(실행 기록의 profile 이름), 대화 삭제(세션 참조의 profile 이름), 하위 에이전트 사용량 정리(작업 줄의 주소와 profile 이름), 잃은 실행 정리 | `Agent.runtimeTargetOf(저장된 unit)` 또는 저장된 값 셋으로 만든 `RuntimeTarget`. 에이전트의 profile 이 바뀐 뒤에도 그 실행이 돈 곳으로 간다 |

- 저장하는 값은 PR1 에서 바뀌지 않는다. 실행 기록의 profile 이름 컬럼에 `target.unit()` 을 적고(지금 값과 같다), 저장된 줄의 종류는 모두 `HERMES` 로 읽는다. PR2 가 실행 기록에 런타임 컬럼을 더하면 그 값으로 종류를 읽는다.
- 부르는 쪽은 `unit` 을 저장하고, 같은지 비교하고, 그 값으로 조회할 수 있다(대화 삭제의 중복 빼기, 잃은 실행 정리의 조회, 사용량 정리의 「profile 이 바뀜」 판정). 뜻을 풀어 주소를 만들거나 Hermes 를 직접 부르는 데 쓰지 않는다.
- 주소와 profile 이름을 들고 다니는 `TurnRunRef`, `RunningDelegation` 은 `RuntimeTarget` 을 들게 바꾼다.

데이터베이스는 PR1 에서 바뀌지 않는다. 런타임 컬럼 둘(에이전트, 실행 기록)은 PR2 가 더한다(스펙 DECISION15).
실행 기록의 사건 종류(`ExecutionEventType`)와 화면으로 가는 사건(`ChatEvent`)은 지금도 중립 이름이라 바뀌지 않는다. Hermes 사건 이름을 해석하는 일은 `ExecutionEventRecorder` 와 `ChatRunEvents` 에서 Hermes 구현으로 옮긴다.

### PR2 가 채울 자리

- ACP 구현은 `runtime` 아래가 아니라 새 최상위 패키지에 두고 `runtime` 만 기댄다. 이름과 층 순서는 PR2 가 정한다.
- ACP 의 연결은 표준 입출력이라 `submit` 뒤에 프로세스를 붙들고 있어야 한다. unit 마다 프로세스 하나를 두고 세션 번호로 turn 을 나눈다. `openEvents` 는 그 연결의 `session/update` 를 `RunEvent` 로 옮긴다.
- `lookupRun` 은 Hermes 처럼 다시 물을 곳이 없다. Control Plane 이 다시 떠 연결을 잃은 실행은 `NOT_FOUND` 로 답해 지금의 재기동 복구가 실패로 기록하게 한다(스펙 DECISION21).
- 사건의 도구 내용 가리기는 `runtime.domain` 의 `ToolDetailRedactor` 를 그대로 쓴다. 어느 도구를 통째로 가릴지는 scope 가 정한다.

## 실패와 일관성

- **동작이 바뀌지 않아야 한다.** PR1 은 이름과 자리를 옮기는 리팩터링이다. 커밋을 「타입과 port 를 옮기기만 하는 커밋」 과 「사건 이름과 상태 해석을 Hermes 구현으로 옮기는 커밋」 으로 나눈다(AGENTS.md 「머지는 PR 로 한다」).
- **사건 해석을 옮길 때의 위험.** Hermes 밖에서 사건 이름을 해석하는 자리는 `ChatRunEvents` 와 `ExecutionEventRecorder` 둘이다. 두 곳은 이름을 소문자로 바꿔 비교하지만, `ChatRunEvents` 의 끝 사건 판정만은 받은 글자 그대로 비교한다. Hermes 구현이 한 번에 소문자로 옮기면 이 판정이 대소문자를 가리지 않게 된다. Hermes 가 사건 이름을 소문자로 보내므로 실제 차이는 없고, 그 근거를 이름 표의 단위 테스트에 적는다. 모르는 이름은 「그 밖」 으로 넘겨 지금처럼 버려지게 한다. 끝 사건 넷(완료, 실패, 취소, 중단)을 하나라도 놓치면 turn 이 스트림 한도까지 기다리므로 계약 테스트가 넷을 모두 확인한다.
- **글 끼우기.** 값을 더하지 않으므로 `SteerService` 의 분기는 바뀌지 않는다. enum 을 받는 분기가 화살표 `switch` 문과 if 줄이라 새 값을 놓쳐도 컴파일러가 알리지 않는다. 그래서 새 값을 만들지 않는 쪽을 골랐다(DECISION5).
- **대상의 출처.** 저장된 기록으로 다시 부르는 자리를 `Agent.runtimeTarget()` 으로 바꾸면 에이전트의 profile 이 바뀐 뒤 다른 profile 로 가게 된다. 「대상을 만드는 두 길」 을 지킨다.
- **런타임을 고르지 못할 때.** `AgentRuntimes` 가 대상의 종류에 맞는 구현을 찾지 못하면 실행을 실패로 기록하는 지금의 오류 길(`ApiException`)을 탄다. PR1 에서는 생기지 않고, PR2 의 「새 런타임 전체를 끈 설정」(스펙 DECISION14)이 이 길을 쓴다.
- **배포 순서.** PR1 은 스키마와 Hermes 호출을 바꾸지 않아 순서 제약이 없다. 되돌리기는 PR 되돌리기 하나다.
- **PR 크기.** 옮기는 타입을 쓰는 운영 코드 파일이 `hermes` 밖에 26개이고 테스트 파일이 97개다(2026-11-21). 중립 port 와 옛 내부 경계가 함께 있을 수 있어 부르는 쪽을 나눠 옮길 수 있다. 운영 코드 변경 줄이 상한(`scripts/pr/pr-size.mjs`)에 닿으면 스스로 끝나는 호출자(추천 질문, 대화방 요약, 하위 에이전트 사용량 정리, 대화 삭제)와 구조 규칙을 다음 PR 로 미루고 코디네이터에게 알린다(사용자 결정 2026-11-21: 다섯 단계 안에서 넘으면 나눈다). 나눠도 다섯 단계의 순서는 그대로다.

## 검증 전략

| 요구 | 어디서 확인하는지 |
| --- | --- |
| REQ1 port 에 Hermes 이름이 없다 | 구조 테스트(ArchUnit) 셋. `runtime` 패키지가 `hermes` 를 기대지 않는다. `hermes` 밖의 운영 코드가 `HermesRunsClient`, `HermesRunEventStream`, 옛 실행 타입을 기대지 않는다(금지 대상은 이 타입들로 좁게 정한다. `hermes.infra` 의 다른 클래스를 쓰는 Hermes 전용 기능은 대상이 아니다). 층 순서 테스트에 `runtime` 을 더한다. 사건 이름이 port 에서 빠졌는지는 `RunEventKind` 타입과 이름 표의 단위 테스트가 확인한다 |
| mcp 가 실행을 직접 부르지 않는다 | rule-11(`MCP_DOES_NOT_CALL_HERMES`)이 막는 타입에 `runtime` 의 `AgentRuntime` 과 `AgentRuntimes` 를 더한다. 지금 규칙은 `hermes` 패키지의 `*Client` 만 막아, 옮기면 빈틈이 생긴다 |
| REQ1 Hermes 동작이 그대로다 | 통합 테스트의 가짜 구현(`StubHermesRunsClient`, 흉내 낸 `HermesRunEventStream`)은 `hermes` 안의 내부 경계를 그대로 대신하고, Hermes 어댑터는 실제 것을 쓴다. 가짜 구현은 Hermes 의 내부 타입(`HermesRunResult` 등)을 그대로 돌려주므로, 지금 테스트가 Hermes 사건 이름과 상태 문자열로 준비한 값이 어댑터의 옮기기를 거쳐 흐르고 「provider 계정이 막힘」 판정도 같은 길로 간다. 테스트에서 바뀌는 것은 옮긴 타입의 import, `RunEvent` 의 새 이름, 부르는 쪽 클래스를 직접 만드는 단위 테스트의 생성자 인자다. 단언하는 값은 고치지 않는다 |
| 저장되는 오류 코드가 그대로다 | Hermes 가 내는 끝 상태마다 `statusLabel` 과 `outcome` 을 확인하는 단위 테스트. 지금 실행 기록 테스트가 오류 코드 값을 단언하는 것도 그대로 통과한다 |
| REQ1 왕복 확인 | 배포 뒤 Hermes 대화 한 번(답 흐름, 도구 표시, 중지, 글 끼우기)을 돌린다. 절차는 운영 저장소가 갖는다 |
| port 계약 | `AgentRuntime` 계약 테스트 하나를 추상 테스트로 두고 Hermes 구현(가짜 HTTP 서버)에 돌린다. 항목은 끝 사건 넷, 「하지 못하는 구현이 내는 값」 열, 「scope 에 따라 가린 사건만 낸다」 다. PR2 의 ACP 구현이 같은 테스트를 상속한다 |
| REQ2 | 문서 검사(`doc-links`, `doc-files`)와 사람 읽기 |

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | 중립 port 의 자리 | 새 최상위 패키지 `runtime`, 층 순서는 `crypto` 바로 위. 엔티티가 쓰는 타입은 `runtime.domain` | 작성자 | `hermes` 안에 두면 다른 런타임 구현이 `hermes` 를 기대게 된다. 가장 아래에 두어야 모든 기능이 부를 수 있다. domain 은 domain 만 쓴다는 층 규칙 |
| DECISION2 | 격리 단위를 넘기는 방법 | `RuntimeTarget` 값 하나. 새 실행은 지금 에이전트에서, 이미 보낸 실행은 저장된 기록에서 만든다 | 작성자 | 구현이 에이전트 줄을 직접 읽으면 `hermes` 가 `agent` 를 기대 층 순서가 뒤집힌다. 저장된 profile 로 다시 부르는 지금 동작(profile 이 바뀐 뒤의 복구와 삭제)을 지킨다. 「실행할 profile 은 요청자의 바인딩에서만 꺼낸다」 는 지금처럼 `Agent` 를 고르는 쪽이 지킨다 |
| DECISION3 | 사건 스트림 | port 에 넣는다 | 작성자 | 지금 application 이 `hermes.infra` 를 직접 부르는 것이 스펙 REQ1 과 어긋난다. ACP 는 사건이 연결 하나로 와서 「실행마다 스트림 열기」 를 구현이 감춰야 한다 |
| DECISION4 | 사건 종류와 끝난 상태 | 문자열 대신 enum 으로 두고 구현이 옮긴다. 저장하는 오류 코드 글자는 `statusLabel` 로 따로 넘긴다 | 작성자 | 해석 자리가 여럿이라 런타임마다 여러 곳을 고쳐야 한다. 오류 코드 값이 바뀌면 지난 실행 기록과 섞인다 |
| DECISION5 | 하지 못하는 동작 | 예외가 아니라 지금 있는 값으로 알린다(`REJECTED`, `NOT_FOUND`, null, 아무것도 하지 않음). 새 enum 값을 더하지 않는다 | 작성자 | 부르는 쪽에 런타임별 분기를 만들지 않는다. `SteerService` 는 새 값을 놓쳐도 컴파일되고, 놓치면 「끼우는 중」 표시가 남아 다음 turn 이 막힌다 |
| DECISION6 | 옛 port | application 이 보는 port 로는 남기지 않는다. `HermesRunsClient` 와 `HermesRunEventStream` 은 자기 명령·결과·사건 타입과 함께 `hermes` 안의 내부 경계로 남고 Hermes 어댑터만 부른다 | 작성자 | 통합 테스트가 이 둘을 가짜로 대신한다. 남겨야 어댑터의 이름 옮기기가 지금 테스트 경로에 들어가고 테스트 준비 코드를 다시 쓰지 않는다. `hermes` 밖에서 부르는 것은 구조 테스트가 막는다 |
| DECISION7 | 하위 에이전트 사용량 보정 | Hermes 전용으로 둔다 | 작성자 | 새 런타임은 하위 에이전트를 끈다(스펙 DECISION17). 중립으로 옮기면 쓰지 않을 추상화다 |
| DECISION8 | ACP 구현의 상세 설계 시점 | PR2 가 실제로 띄워 확인한 뒤 이 파일을 고친다 | 작성자 | 스펙 DECISION3 이 설계 단계의 확인을 요구한다. 구독 로그인이 필요한 확인이라 운영 절차와 함께 해야 한다 |

## 결정 기록 연결

| 결정 | 종류 | ADR 또는 필요 없음의 이유 | 현재 규칙의 원본 |
| --- | --- | --- | --- |
| 스펙 DECISION3, DECISION8, DECISION10, DECISION16, DECISION17, 이 설계 DECISION1 | architecture | [ADR-20261121 / acp-runtime](../../adr/ADR-20261121-acp-runtime.md) | 스펙, 이 설계 |
| 이 설계 DECISION2-DECISION8 | 필요 없음 | 위 ADR 의 경계 안에서 기존 층 규칙을 따르는 내부 선택이다 | 이 설계 |
