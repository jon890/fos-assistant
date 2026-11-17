# Hermes v0.23.2 호환 기술 설계

스펙: [`spec.md`](spec.md). upstream 동작의 근거는 스펙의 「upstream 소스로 확인한 사실」 표가 갖는다.

## 요구사항 연결

| ID | 해결 방법 |
| --- | --- |
| REQ1 | `HttpHermesRunsClient` 가 `queued` 를 지금처럼 「아직 돈다」 로 읽고, `unknown` 은 REQ2 의 해제로 넘긴다. 상태 문자열은 어댑터 밖으로 새로 나가지 않는다 |
| REQ2 | 어댑터가 `unknown` 을 보면 같은 조회 응답의 `admission_id`, `execution_generation` 으로 `resolve-unknown` 을 보낸다. 그 뒤 `lookupRun` 은 기존 `NOT_FOUND`(재기동 정리가 `REMOTE_RUN_LOST` 로 적는 신호)를 돌려주고, `awaitCompletion` 은 새 `ErrorCode.REMOTE_RUN_LOST` 로 실패한다. 중지가 409 `unknown_execution` 을 받으면 같은 해제를 하고 중지를 끝낸 것으로 본다. 아무도 그 run 을 다시 묻지 않는 경우를 막으려고, 새 실행을 보내기 전에 같은 profile 의 최근 연결 실패·시간 초과 실행을 어댑터에 넘겨 잃은 것이면 해제한다(「잃은 실행 미리 풀기」). Hermes 가 정상 종료로 끊은 실행은 조회에서 `cancelled` 로 와도 어댑터가 `interrupted` 로 옮겨 실패로 적히게 한다 |
| REQ3 | `HermesRunEventStream` 이 받은 종료 사건을 infra 안의 `HermesRunTerminals` 에 남기고, `toResult` 가 조회 응답에 빠진 칸을 거기서 채운다. 사건 스트림을 열지 않은 호출자(위임, 흐름, 방 요약, 추천 질문, 재기동 정리)는 어댑터가 끝난 실행의 사건 스트림을 한 번 다시 읽어 보관분에서 종료 사건을 얻는다. 받지 못했으면 결과에 「`pending_steer` 를 모른다」 를 표시하고, `ChatTurnRunner`·`RecoveredRunRecorder` 가 그 표시로 `SteerSettlement.unknown()` 을 고른다 |
| REQ4 | 사건 파서(`HermesRunEventStream`)가 `tool_call_id` 를 무시하고, `tool.completed` 의 `duration` 0 을 걸린 시간 없음(`null`)으로 읽는다. `run.queued` 는 아는 사건 목록 밖이라 지금처럼 버린다. 오지 않는 사건은 고칠 것이 없다(DECISION2) |
| REQ5 | fos-ctx `pre_tool_call` 이 `terminal` 의 위험 명령(`detect_dangerous_command`), 승인이 필요한 파일 쓰기(`write_approval_key`), `computer_use` 호출을 막는다 |
| REQ6 | fos-ctx `tool_guard` 가 `session_id` 는 있고 `tool_call_id` 가 없는 호출에 판정 요청용 번호를 만들어 Control Plane 에 묻는다 |
| REQ7 | `hermes_contract.py` 의 선언을 버전별 기대값으로 나누고, 테스트가 소스의 `pyproject.toml` 버전 계열(major.minor)로 기대값을 고른다. 본문 없는 요청 진단 두 묶음은 PR4 의 표지 파일로 v0.23 계열에서만 돈다 |
| REQ8 | 대시보드 plugin 코드는 바꾸지 않는다. 계약의 지점 선언이 두 버전의 annotation 표기와 의존성 경고 함수 이름을 함께 받는다 |
| REQ9 | PR1 이 ADR-061 과 계약 문서의 Runs 절을, PR2 가 [ADR-20261008 / execute-code-unattended](../../../hermes/docs/adr/ADR-20261008-execute-code-unattended.md) 와 승인 절을, PR3 이 버전 기준 절을 고친다 |
| REQ10 | 코드 변경 없음. 스펙의 「적용 조건」 을 `fos-home-infra` 가 쓴다 |
| REQ11 | 코드 변경 없음. 스펙의 「배포 뒤 왕복 확인」 을 코디네이터가 운영에서 본다 |
| REQ12 | 코드 변경 없음. `check-hermes-contract.sh <tag>` 를 그대로 쓴다 |
| REQ13 | 관리 bootstrap 의 내부 경계를 `hermes_scope` 의 scope 상태와 `bind_profile_scope` 로 다시 선언한다. 아래 「PR4」 절 |

## 책임과 경계

**Hermes 의 버전 차이는 `hermes/infra` 가 흡수한다(사용자 2026-11-17, AgentRuntime 포트 추상화 예정).**
application 계층(`chat`, `usage`, `orchestration`)에 Hermes 상태 문자열이나 사건 이름을 비교하는 코드를 새로 두지 않는다.
application 이 새로 보는 것은 포트 DTO 의 boolean 하나(`HermesRunResult.pendingSteerKnown()`), 새 `ErrorCode` 하나, 포트 메서드 하나(`releaseIfLost`)뿐이다.

| 모듈 | 이번에 맡는 것 |
| --- | --- |
| `hermes/infra/HttpHermesRunsClient` | 포트 구현. v0.23.2 응답이면 아래 `HermesSessionRuns` 에 맡긴다. 파일 길이 상한 때문에 번역을 나눴다 |
| `hermes/infra/HermesSessionRuns`(새 class) | `queued`, `unknown`, v0.23.2 의 `cancelled` 번역, `resolve-unknown` 호출, 중지의 409 `unknown_execution` 판정, 조회 응답에 빠진 칸 채우기(`HermesRunEnding`), `releaseIfLost` |
| `hermes/infra/HermesRunReplay`(새 class) | 끝난 실행의 사건 보관분에서 끝 사건만 읽는다. 읽기 전체에 `assistant.hermes.terminal-event-read-timeout` 상한을 둔다 |
| `hermes/infra/HermesRunEventStream` | 받은 끝 사건(`HermesRunTerminal`)을 `HermesRunTerminals` 에 넘기기, `duration` 0 처리 |
| `hermes/infra/HermesRunTerminals`(새 class) | profile 과 run 번호마다 받은 종료 사건 하나, 이 프로세스가 중지를 보낸 run, 끝 상태를 확인한 run 을 메모리에 짧게 둔다 |
| `hermes/application/port/HermesRunsClient` | `releaseIfLost(apiBaseUrl, profileName, runId)` 를 더한다. 그 run 이 잃은 실행이면 해제하고, 아니면 아무것도 하지 않는다. 기본 구현은 아무것도 하지 않는다 |
| `hermes/application/dto/HermesRunResult` | `pendingSteerKnown` 필드를 더한다. 기존 생성자는 `true` 로 채워 지금 호출자를 바꾸지 않는다 |
| `chat/turn/ChatTurnRunner`, `chat/recovery/RecoveredRunRecorder` | steer 정리에서 `pendingSteerKnown()` 이 거짓이면 중지 판정보다 먼저 `SteerSettlement.unknown()` 을 고른다 |
| 새 실행을 보내는 자리(`ChatRunEvents.submit`, `AgentRunner`, `RoomContext`) | 보내기 전에 `LostRunRelease` 로 같은 profile 의 잃었을 수 있는 지난 실행을 `releaseIfLost` 로 넘긴다. 고르는 쿼리는 `usage/execution/infra/LostRunCandidateRepository` 가 갖는다(실행 저장소가 길이 상한에 닿아 나눴다) |
| `shared/error/ErrorCode` | `REMOTE_RUN_LOST(BAD_GATEWAY)` 를 더한다. 재기동 정리의 같은 이름 문자열 상수와 뜻이 같다 |
| `web/src/components/error-message.ts` | `REMOTE_RUN_LOST` 의 문구를 v0.21.6 에서 같은 상황이 보이던 `HERMES_UNAVAILABLE` 문구와 같게 둔다 |
| `hermes/plugins/fos-ctx` | REQ5, REQ6 |
| `hermes/tests` | REQ7, REQ8 |

## 인터페이스와 데이터

### 조회 응답 읽기

| Hermes 의 답 | v0.21.6 | v0.23.2 | 어댑터가 돌려주는 것 |
| --- | --- | --- | --- |
| `running`, `stopping`, `waiting_for_approval`, `queued`, 모르는 값 | 도는 중 | 도는 중, 대기 | `RUNNING`. `awaitCompletion` 은 다시 묻는다. 대기도 `hermes.run-timeout` 에 든다 |
| `completed`, `failed`, `error`, `interrupted` | 끝 | 끝 | `FINISHED` 와 결과 |
| `cancelled` | 사용자 중지 | 사용자 중지, Hermes 정상 종료로 끊김, 해제됨 | v0.21.6 은 그대로. v0.23.2 는 아래 「`cancelled` 구분」 |
| `unknown` | 없음 | 재기동으로 잃음 | 해제한 뒤 `lookupRun` 은 `NOT_FOUND`, `awaitCompletion` 은 `ApiException(REMOTE_RUN_LOST)` |
| 404 | 모름, 재기동, 1시간 지남 | 모름(session 을 지웠음) | 지금처럼 `NOT_FOUND`. `awaitCompletion` 은 지금처럼 호출 실패 |

해제한 실행을 다시 조회하면 v0.23.2 는 `cancelled` 를 준다(결과가 `interrupted`).
그래서 어댑터는 해제한 뒤 다시 읽지 않고 그 자리에서 위 신호를 돌려준다.

v0.23.2 응답인지는 조회 응답에 `admission_id` 가 있는지로 안다.

### `cancelled` 구분

v0.23.2 의 `cancelled` 는 사용자 중지, Hermes 의 정상 종료로 끊긴 실행, 해제된 실행을 모두 담는다.
어댑터는 종료 사건과 자기 기록으로 나눈다. 정상 종료로 끊긴 실행은 v0.21.6 처럼 `interrupted`(실패)로 적혀야 REQ11 의 「재기동하면 실패」 가 성립한다.

「중지를 보냈다」 는 이 Control Plane 프로세스가 보낸 모든 중지다. 사용자 중지 말고도 남은 원격 실행 기다리기, 위임의 제출 뒤 정리, 재기동 정리의 상한 중지가 들어간다. 그 줄들은 이미 실패로 적힌 뒤라 결과가 바뀌지 않는다.

| 근거 | 넘기는 상태 |
| --- | --- |
| 종료 사건이 `run.cancelled` | `cancelled` |
| 종료 사건이 `run.interrupted` | `interrupted` |
| 종료 사건을 얻지 못했고 이 프로세스가 그 run 에 중지를 보냈다 | `cancelled` |
| 종료 사건을 얻지 못했고 중지를 보낸 기록이 없다 | `interrupted` |

마지막 줄은 Control Plane 이 다시 뜬 뒤 사용자가 그 전에 누른 중지를 실패로 적을 수 있다. 사건 보관분이 남은 5분 안에는 종료 사건으로 정해지므로 드물다.

### 해제

해제 요청 본문은 `{"admission_id", "execution_generation"}` 두 칸이다.
200 이면 해제다. 이미 해제된 같은 generation 을 다시 보내도 Hermes 는 200 을 준다.
409 와 404 를 받으면 한 번 다시 조회한다. `unknown` 이 아니면 해제된 것으로 보고, 여전히 `unknown` 이면 `HermesCallFailure` 로 던진다.
5xx, 503 `storage_unavailable`, 연결 실패도 `HermesCallFailure` 로 던진다. 부르는 쪽은 다음 조회에서 다시 해제한다.

### 잃은 실행 미리 풀기

잃은 run 은 누군가 그 run 을 다시 물을 때만 해제된다. Hermes 가 `hermes.run-timeout` 보다 오래 내려가 있으면 turn 조회, 남은 원격 실행 기다리기, 재기동 정리가 모두 포기해, 그 session 의 새 실행이 계속 `queued` 에 머문다.
그래서 새 실행을 보내기 전에 같은 profile 의 지난 실행 가운데 잃었을 수 있는 것을 어댑터에 넘긴다(`releaseIfLost`).
고르는 열쇠는 Hermes session 이 아니라 profile 이다. 실행 줄의 `hermes_session_id` 는 대화의 루트 session 이고 Hermes 에 보낸 session 은 압축 뒤 바뀔 수 있으며(`RunSession`), 방 요약 줄은 session 과 대화가 비어 있다(`ExecutionRecorder.startDetached`). profile 은 모든 줄에 있다.
고르는 조건: 그 profile 의 `FAILED` 실행 가운데 `hermes_run_id` 가 있고, 최근 24시간 안에 끝났으며, `error_code` 가 Hermes 와 닿지 못했거나 기다리다 그만둔 코드(`HERMES_UNAVAILABLE`, `HERMES_RUN_TIMEOUT`, `RECONCILE_TIMEOUT`, `RECONCILE_UNREACHABLE`)인 것을 최근 순으로 최대 10개. 우리 오류 코드만 보므로 application 에 Hermes 상태 문자열이 생기지 않는다.
어댑터는 조회해 `unknown` 이면 해제하고, 끝 상태이거나 Hermes 가 모르는 run(404)이면 「확인 끝」 으로 메모리에 남겨 다시 묻지 않는다. 도는 중이거나 묻지 못했으면 남기지 않는다.
`CANCELLED` 줄은 고르지 않는다. 사용자 중지는 흔해서 10개 상한을 채워 실제로 잃은 실행을 밀어낸다. 중지한 run 이 잃은 것이면 중지의 409 처리나 남은 원격 실행 기다리기가 해제한다. 둘 다 Hermes 가 오래 내려가 그만둔 뒤 그 session 을 다시 쓰는 경우는 풀리지 않고 남는다. 사용자 중지와 Hermes 재기동이 겹치고 그 뒤 Hermes 가 상한보다 오래 내려가 있어야 생겨 드물므로 받아들인다. 남으면 운영 왕복 확인에서 보이고 그때 다시 정한다. 저장소 조회를 포함해 이 단계의 실패는 로그만 남기고 제출을 막지 않는다. 조회가 처음 실패하면 Hermes 가 닿지 않는 것이므로 남은 후보를 건너뛴다. 그래서 제출 전 지연은 조회 한 번의 제한 시간을 넘지 않는다.
부르는 자리는 대화 turn(`ChatRunEvents.submit`), 위임과 흐름(`AgentRunner`), 방 요약(`RoomContext`) 셋이다. 추천 질문은 늘 새 session 으로 보내 막힐 대기열이 없으므로 부르지 않는다.

### 결과 칸 채우기

`toResult` 는 조회 응답을 먼저 읽고, 빠진 칸만 종료 사건에서 채운다.

| 칸 | 조회 응답에 있으면 | 없고 종료 사건이 있으면 | 둘 다 없으면 |
| --- | --- | --- | --- |
| `error` | 그 값 | 종료 사건의 `error` | `null`. 실패 코드는 지금처럼 상태에서 정한다 |
| `pending_steer` | 그 값, 아는 것 | 종료 사건의 값(없으면 없음으로 앎). 단 `run.interrupted` 는 그 칸을 버리므로 모른다 | `pendingSteerKnown=false` |
| `runtime` | 그 값 | `run.completed` 의 `runtime` | `null`. 지금처럼 session 행에서 읽는다 |

v0.21.6 응답에서는 지금처럼 조회 응답만 읽는다.
종료 사건은 두 곳에서 얻는다. 대화 turn 은 이미 연 사건 스트림이 `HermesRunTerminals` 에 남긴 것을 쓴다. 남은 것이 없으면(스트림을 열지 않은 호출자, 스트림이 먼저 끊긴 turn, Control Plane 이 다시 뜬 뒤) 어댑터가 `GET /v1/runs/{id}/events` 를 한 번 열어 보관분을 읽는다. 끝난 실행은 보관분을 보내고 스트림을 닫으므로 기다림이 짧다. 연결 제한 시간은 `assistant.hermes.terminal-event-read-timeout`(기본 5초)이다.
보관분은 실행이 끝나고 5분(`_RUN_STREAM_TTL`) 뒤 사라진다. 그 뒤에 읽는 결과는 위 표의 「둘 다 없으면」 이다. 그래서 provider 계정이 막힌 실패(`PROVIDER_BLOCKED`)는 5분이 지나 재기동 정리로 적는 실행에서만 일반 실패로 적힌다.

`HermesRunTerminals` 는 profile 과 run 번호를 키로 종료 사건 하나를 둔다. 읽으면 지우고, 읽히지 않으면 30분 뒤 지운다. 중지를 보낸 run 번호도 30분 둔다. 끝 상태를 확인한 run 번호는 미리 풀기가 다시 묻지 않게 24시간(미리 풀기가 고르는 기간) 둔다. 보관 시간을 `hermes.run-timeout` 대신 고정값으로 둔 것은 그 설정을 `LiveProperties` 밖에서 새로 읽지 못하게 하는 구조 규칙 때문이다. 테이블은 늘리지 않는다.

### 중지

`stop` 이 409 `unknown_execution` 을 받으면 어댑터가 조회해 해제한다. 해제했으면 정상으로 돌아온다.
도는 turn 의 `awaitCompletion` 은 다음 조회에서 이미 해제된 실행을 `cancelled` 로 받고, 이 프로세스가 중지를 보냈으므로 그대로 `cancelled` 다. 사용자가 중지를 누른 turn 이므로 「중지됨」 이다(스펙 DECISION16).

### fos-ctx

`pre_tool_call` 의 맨 앞(스킬 관리 차단 다음)에 위험 명령 판정을 둔다.
`tool_name == "terminal"` 이고 `args["command"]` 가 문자열이면 `detect_dangerous_command(command)[0]` 이 참일 때 막는다. 글은 「이 명령은 위험 명령으로 분류돼 실행하지 않는다. 다시 부르지 말고 다른 방법을 찾는다.」 다.
import 나 판정이 예외를 던지면 막는다(닫힌 쪽). `command_allowlist` 는 보지 않는다(스펙 DECISION18).
`detect_dangerous_command` 는 새 내부 의존이다. `hermes_contract.py` 에 두 버전의 위치(`tools/approval_detection.py`)와 시그니처를 선언한다([rule-64](../../code-rules/rule-64.md)).
같은 hook 이 Hermes 의 승인 관문(`_run_approval_gate`)을 쓰는 나머지 두 동작도 막는다(스펙 DECISION8).

| 도구 | 막는 조건 | Hermes 내부 지점 |
| --- | --- | --- |
| `write_file`, `patch` | 대상 경로 가운데 하나라도 `write_approval_key(경로)` 가 값을 준다. `patch` 의 V4A 모드는 Hermes 의 머리줄 경로 추출로 대상 경로를 얻는다. 상대 경로는 Hermes 의 경로 풀이에 hook 이 받은 `task_id` 를 넣어 푼다 | `agent.file_safety.write_approval_key`, `tools.file_tools._collect_v4a_header_paths`, `tools.file_tools_write_guards._resolved_or_raw` |
| `computer_use` | 모든 호출 | 없음 |

`write_approval_key` 가 없는 Hermes(v0.21.6)에서는 파일 판정을 건너뛴다. 그 버전은 승인 카드로 가서 시간 초과로 거절되므로 지금 동작 그대로다.
함수가 있는데 경로 추출이나 판정이 예외를 던지면 막는다.
새 내부 지점 넷(`detect_dangerous_command` 포함)은 `hermes_contract.py` 에 계열별 위치와 시그니처로 선언한다.

`tool_guard` 는 `session_id` 가 있고 `tool_call_id` 가 비면 `code-exec:` 뒤에 무작위 16진수 32자를 붙인 번호를 만든다. 서명과 요청은 지금과 같다. Control Plane 의 판정(`EventToolGuard`)은 이 번호를 서명 확인에만 쓰므로 바꾸지 않는다.
`session_id` 가 없는 호출(v0.21.6)은 지금처럼 통과한다.

### 계약 테스트

`hermes_contract.py` 는 기준 버전의 선언을 그대로 두고, 버전마다 다른 기대값만 `HERMES_VARIANTS` 에 계열(`0.21`, `0.23`)별로 둔다. upstream 소스의 `pyproject.toml` 버전은 `0.0.0` 이라 쓸 수 없다. 테스트는 계열마다 선언한 표지 파일(`0.23` 은 `gateway/platforms/api_server_authority_runs.py`)이 `HERMES_SOURCE` 에 있는지로 계열을 고른다. 그래서 새 패치 tag(v0.23.x)는 계열이 같으면 코드를 고치지 않고 돌릴 수 있다(REQ12). 모르는 계열이면 기준 버전의 기대값으로 실패를 보인다.
v0.23.2 에서 고칠 것은 이렇다.

| 실패 | 고칠 것 |
| --- | --- |
| 타입 표기(`Tuple`→`tuple`, `Set`→`set`) | 두 표기를 버전별 기대값으로 둔다 |
| plugin 의존성 경고 함수 이름 | 버전별 함수 이름 |
| `HERMES_EXEC_ASK` 를 넣는 자리 | `gateway/run_bootstrap.py` 로 옮겼다. v0.23.2 에서는 승인 알림 경로가 없을 때 이 값이 판정을 정하지 않으므로, 그 테스트의 뜻을 「셸 위험 명령은 fos-ctx 가 먼저 막는다」 로 바꾸고 REQ5 의 판정 지점을 함께 본다 |
| 공유 wire 와 스트림 handler 의 분리 실행 | 테스트 쪽 대역이 새 helper(`_call_id`, `_check_run_auth`)를 함께 넘긴다 |
| 새 내장 도구 | 도구 목록에 `browser_vault`, `set_theme`, `setup_beat` 를 안전 목록 밖으로 더한다(스펙 DECISION13) |
| 코드 실행 안 호출의 session | v0.23.2 기대값을 「`session_id` 가 있고 `tool_call_id` 가 없다」 로 둔다 |
| 본문 없는 요청 진단 | PR4 가 선언을 v0.23 계열로 바꾸고 `BODYLESS_SOURCE_LINE` 의 표지가 없는 소스에서는 건너뛴다. PR2 는 따로 건너뛰기를 두지 않는다 |
| 끝난 실행의 사건 보관분 | 새 기대: 끝난 run 에 처음 연결하면 보관분을 보내고 닫는다(`_handle_run_events`). 어댑터의 다시 읽기가 이 동작에 기댄다 |

### 가짜 Hermes

backend 의 `StubHermesRunsClient` 는 포트 대역이라 v0.23.2 차이를 모른다. `releaseIfLost` 는 부른 run 번호를 기록만 한다. 어댑터의 번역은 HTTP 대역 서버로 `HttpHermesRunsClient` 를 직접 부르는 테스트가 본다.
E2E 의 가짜 Hermes(`test/e2e/fake-hermes`)에 v0.23.2 모양을 켜는 설정을 더한다. 켜면 조회 응답에서 `error`, `pending_steer` 를 빼고 `admission_id`, `execution_generation` 을 넣으며, 재기동 흉내로 도는 실행을 `unknown` 으로 바꾸고 `resolve-unknown` 경로를 연다. 그 session 의 다음 실행은 해제 전까지 `queued` 로 둔다.

## 실패와 일관성

- 해제는 멱등이다. 같은 실행을 turn 조회, 남은 원격 실행 기다리기, 재기동 정리, 미리 풀기가 함께 보면 모두 200 이거나 다시 조회해 해제를 확인한다. 실행 줄은 지금처럼 먼저 적은 쪽이 정한다.
- 해제한 뒤 실행 줄을 적기 전에 Control Plane 이 내려가면, 다시 뜬 재기동 정리는 `cancelled` 를 읽는다. 종료 사건이 없고 중지 기록도 없으므로 `interrupted` 로 적는다. 실패로 적히므로 스펙과 맞다.
- Hermes 가 오래 내려가 있어 도는 turn 이 상한에 걸리면 그 turn 은 지금처럼 시간 초과로 실패한다. 잃은 run 은 그 대화의 다음 제출 직전에 풀린다.
- 배포 순서: PR1, PR2 는 v0.21.6 에서 지금 동작을 바꾸지 않는다(위험 명령 거절만 기다림이 사라지고, 미리 풀기는 v0.21.6 에서 `unknown` 이 없어 아무것도 하지 않는다). 운영 Hermes 를 올리기 전에 배포한다([rule-66](../../code-rules/rule-66.md)). PR3 이 v0.21.6 분기를 걷어 낸다.
- 되돌리기: Control Plane 을 옛 버전으로 되돌리면 v0.23.2 의 `unknown` 을 도는 중으로 읽어 그 대화가 실행 한도까지 기다린다. 그래서 운영 Hermes 를 올린 뒤에는 PR1 을 되돌리지 않는다. `fos-home-infra` 의 되돌리기는 Hermes 이미지와 데이터를 함께 되돌린다.

## 검증 전략

| ID | 경계와 환경 |
| --- | --- |
| REQ1, REQ2 | HTTP 대역 서버로 `HttpHermesRunsClient` 를 부르는 단위 테스트. 두 버전 응답 모양, 해제 요청 본문, 409·404 뒤 다시 조회, 5xx, `cancelled` 구분 네 경우, `releaseIfLost`. 미리 풀기의 실행 고르기는 저장소 테스트(H2). 재기동 정리와 turn 경로는 기존 `RestartReconciler`, `ChatTurnRunner` 테스트에 `NOT_FOUND`, `REMOTE_RUN_LOST` 예외 경우를 더한다 |
| REQ3 | `HermesRunEventStream` 과 `HttpHermesRunsClient` 를 함께 쓰는 단위 테스트(스트림의 종료 사건, 보관분 다시 읽기, 보관분 없음). `SteerLedger` 정리는 `pendingSteerKnown=false` 경우를 더한다 |
| REQ4 | `HermesRunEventStreamTest` 에 v0.23.2 합성 사건 fixture |
| REQ5, REQ6 | `hermes/tests/test_fos_ctx.py` 와 tool guard 테스트, v0.23.2 소스로 돈 계약 테스트 |
| REQ7, REQ8 | `check-hermes-contract.sh`(기준 버전)와 `check-hermes-contract.sh v0.23.2` |
| REQ9 | 문서 검사와 리뷰 |
| REQ11 | 운영 왕복. 코드 테스트로 대신하지 않는다 |

E2E 는 가짜 Hermes 의 v0.23.2 모양으로 재기동 뒤 다음 turn 이 도는 장면 하나를 더한다. 실제 Hermes 와의 차이는 REQ11 이 본다.

## PR4

REQ13 을 맡는다. addon 은 운영에 연결하지 않았으므로 v0.23 계열 소스에서만 맞춘다(스펙 「범위」).
v0.23.2 소스(commit `ae2a81cc`)와 v0.21.6 소스를 대조해 정했다.

### 바뀐 upstream 경계

| 지점 | v0.21.6 | v0.23.2 |
| --- | --- | --- |
| profile 문맥 저장 | `agent/secret_scope.py` 와 `hermes_constants.py` 가 각자의 `ContextVar` 를 가진다 | `hermes_scope._STATE` 하나가 scope, home override, secret, terminal 정책을 한 값(`ScopeState`)으로 가진다. `current_secret_scope_home` 과 `get_hermes_home_override` 는 그 값을 읽기만 한다 |
| 묶기 | `set_secret_scope` 와 `set_hermes_home_override` 를 따로 부른다 | `agent.runtime_scope.bind_profile_scope(home)` 이 셋을 한 변경으로 묶는다. 옛 setter 는 scope 가 묶여 있으면 그 profile 로 scope 전체를 옮기거나(local) 거절한다 |
| 되돌리기 | `ContextVar.reset(token)` | `StateToken.reset()` 이 그 변경만 빼고 뒤 변경을 다시 쌓는다. 빠진 scope 안에서 한 변경은 함께 빠져, 순서가 어긋난 reset 도 두 profile 의 조각이 섞인 상태를 남기지 않는다 |
| cron child | payload 를 바로 읽는다 | `__main__` 이 payload 를 읽기 전에 `finish_worker_boot()` 로 `hermes_bootstrap` 을 부른다. 끝나지 않은 source update 가 있으면 다른 interpreter 로 `execv` 한다 |
| 위임 | 자식은 부모 profile 에서 만든다 | `_build_child_agent` 의 `profile_home` 경로가 자식을 다른 profile 의 scope 안에서 만들 수 있다 |

writer 설치 경계(helper 시그니처와 code, lazy forward, 세 호출부, 다섯 파일 지문)는 v0.23.2 에서도 그대로다. 호출부의 분리 실행만 새 helper(`build_request_payload`)를 함께 실행하게 고친다.

### 정한 것

- **묶는 진입점은 `bind_profile_scope` 다.** Hermes 가 다른 profile 의 상태가 필요할 때 쓰라고 정한 진입점이고 home, secret, terminal 정책을 한 변경으로 묶어 갈라지지 않는다. `push_state` 를 직접 부르면 terminal 정책이 빠지고 `ScopeState` 칸 이름에 묶인다.
- **resolver 는 `hermes_scope.current_scope()` 로 현재 profile 을 확인한다.** 묶인 scope 가 local 이고 `scope.home()` 이 등록된 agent 의 profile home 과 같을 때만 목적지를 준다. 그 밖(scope 없음, 다른 profile, hosted scope)은 `None` 이고 writer 는 아무것도 쓰지 않는다. secret 의 stamp(`current_secret_scope_home`)는 실행 profile 을 묶을 때 비어 있어(`stamp_home=False`) 확인에 쓰지 않는다.
- **지문을 둘 함수는 `BODYLESS_MANAGED_FUNCTIONS` 가 갖는다.** 묶기 경로(`bind_profile_scope`, `scope_for_profile`, `_bind_whole_profile`, `bind_state`, `owner_secret_scope`, `launch_secret_scope`, `_is_process_home`), scope 상태(`current_state`, `current_scope`, `push_state`, `pop_state`, `_fold`), 위임의 profile 경로(`delegated_profile_scope`, `legacy_switch`), 기존 lifecycle 과 cron 함수, 새 `finish_worker_boot` 다. scope 상태의 저장소와 값 타입은 `BODYLESS_MANAGED_MODULE_NODES` 에 둔다. 옛 setter 는 선언에서 뺀다.
- **위임 자식의 capability 규칙은 그대로다.** 자식을 만들 때 묶인 scope 가 부모 profile 과 다르면 부모 capability 를 넘기지 않고 생성 전에 거절한다. 그래서 관리 interpreter 에서는 owner 가 그 profile 의 바인딩을 갖기 전까지 profile 경로 위임이 막힌다. 운영에 연결하지 않아 지금 영향은 없다.
- **cron relaunch 는 private 활성화 수락 조건으로 넘긴다.** relaunch 된 interpreter 는 관리 bootstrap 을 거치지 않을 수 있다. 이미지에 끝나지 않은 source update 가 없어 relaunch 가 일어나지 않는지는 `fos-home-infra` 의 활성화 수락에서 본다.
- **계약 테스트는 표지 파일로 계열을 고른다.** `BODYLESS_SOURCE_LINE` 의 표지(`agent/runtime_scope.py`)가 없는 소스(v0.21.6)에서는 세 source 묶음을 건너뛴다. 기준 버전을 올리는 PR3 이 표지 검사를 지운다. PR2 의 계열 선택이 먼저 들어오면 그 표지로 바꾼다.
- 공개 addon 코드(`hermes/runtime/`)와 설치 API 는 바꾸지 않는다.

### 검증

| 대상 | 방법 |
| --- | --- |
| 선언한 지점 | `check-hermes-contract.sh` 의 세 본문 없는 묶음을 v0.23.2 소스로 돌린다. v0.21.6 에서는 건너뛰고 0 으로 끝난다 |
| profile 문맥 | 실제 `hermes_scope.py` 와 `agent/runtime_scope.py` 를 실행한다. 복사 문맥과 중첩 묶기에서 profile 마다 home, secret, terminal 정책이 섞이지 않고 빠져나오면 이전 문맥이 돌아오는지, 순서가 어긋난 token reset 이 섞인 상태를 남기지 않는지 본다. secret 파일 읽기와 terminal 정책 만들기는 대역이다 |
| writer 거절 | 위 resolver 꼴과 실제 writer 로 다른 profile 의 scope, scope 없음, 등록되지 않은 agent 에서 파일이 생기지 않는지 본다 |
| 반례 | 전역 home 대체, 진입점의 키워드 전용 인자 변경, decorator 추가, 저장소 재대입을 선언이 거절하는지 본다 |

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | `unknown` 을 application 에 알리는 방법 | 새 상태를 만들지 않고 `lookupRun` 은 기존 `NOT_FOUND`, `awaitCompletion` 은 `ErrorCode.REMOTE_RUN_LOST` 예외로 알린다 | 작성자 | 재기동 정리와 남은 원격 실행 기다리기가 이미 `NOT_FOUND` 를 「잃음」 으로 처리한다. turn 경로는 예외의 코드를 실행 줄의 `error_code` 로 적는다(`ChatRunEvents.awaitCompletion`). application 에 새 분기가 생기지 않는다 |
| DECISION2 | 해제를 누가 하나 | 어댑터가 `unknown` 을 처음 본 자리에서 한다 | 작성자 | 해제에 필요한 두 값이 같은 조회 응답에 있다. 부르는 쪽마다 해제를 넣으면 Hermes 의 상태 이름이 application 으로 샌다 |
| DECISION3 | 종료 사건의 칸을 조회 결과로 옮기는 자리 | infra 안의 메모리 보관(`HermesRunTerminals`) | 작성자 | 두 어댑터가 같은 실행을 따로 읽는다. 테이블을 늘리지 않는다(사용자 2026-11-01). 프로세스가 다시 뜨면 사라지지만 그때는 DECISION3(스펙)대로 「모른다」 가 맞다 |
| DECISION4 | 위험 명령과 민감 파일 판정 함수 | Hermes 의 `detect_dangerous_command`, `write_approval_key` 를 부른다 | 작성자 | 패턴을 우리가 복제하면 Hermes 를 올릴 때마다 어긋난다. 계약 테스트가 위치와 시그니처를 지킨다 |
| DECISION5 | 계약 테스트의 두 버전 처리 | 기준 선언과 계열별 차이. 계열은 표지 파일로 고른다 | 작성자 | 기준 버전 하나만 보던 지금 구조를 유지하고 차이만 더한다. commit 으로 고르면 새 패치마다 코드를 고쳐야 해 REQ12 가 성립하지 않는다. PR3 이 v0.21.6 차이를 지운다 |
| DECISION7 | v0.23.2 의 `cancelled` | 종료 사건과 이 프로세스의 중지 기록으로 `cancelled` 와 `interrupted` 를 나눈다. 근거가 없으면 `interrupted` | 작성자 | 정상 종료로 끊긴 실행이 「중지됨」 으로 적히면 REQ11 의 「재기동하면 실패」 가 깨진다. 어긋나는 쪽은 Control Plane 재기동을 넘긴 사용자 중지뿐이다 |
| DECISION8 | 아무도 다시 묻지 않는 잃은 run | 새 실행을 보내기 전에 같은 profile 의 최근 연결 실패·시간 초과 실행을 `releaseIfLost` 로 푼다 | 작성자 | 해제를 조회에만 기대면 Hermes 가 오래 내려간 뒤 대화가 영구히 `queued` 에 머문다. 실행 줄의 session 은 Hermes 에 보낸 session 과 다를 수 있고 방 요약 줄에는 없어 profile 을 열쇠로 쓴다. 잃은 run 은 그 run 을 기다리다 그만둔 실패로만 남으므로 그 오류 코드로 좁힌다 |
| DECISION9 | 종료 사건을 얻는 경로 | 열어 둔 사건 스트림의 기록, 없으면 끝난 실행의 사건 보관분을 한 번 다시 읽는다 | 작성자 | 위임, 흐름, 방 요약, 추천 질문은 스트림을 열지 않아 `error` 를 받을 길이 없고 `PROVIDER_BLOCKED` 가 사라진다. 보관분은 끝난 뒤 5분 남는다 |
| DECISION10 | `REMOTE_RUN_LOST` 의 화면 문구 | `HERMES_UNAVAILABLE` 과 같은 문구 | 작성자 | 스펙 REQ2 의 「지금 실패한 turn 과 같게 보인다」. v0.21.6 에서 같은 상황은 연결 실패로 보였다 |
| DECISION6 | `duration` 0 | 걸린 시간 없음으로 읽는다(두 버전 모두) | 작성자 | v0.23.2 는 늘 0 을 보낸다. v0.21.6 의 실제 0초 도구는 드물고 잃는 것이 표시 한 칸이다 |

## 결정 기록 연결

| 결정 | 종류 | ADR 또는 필요 없음의 이유 | 현재 규칙의 원본 |
| --- | --- | --- | --- |
| 스펙 DECISION9, 이 문서 DECISION1 | 재기동 정리 | [ADR-061](../../../backend/docs/adr/ADR-061-재기동-때-남은-실행은-hermes-에-물어-정하고-도는-실행에는-다시-붙는다.md) 의 Hermes 답 표에 `unknown` 줄과 해제를 더한다(PR1) | ADR-061 |
| 스펙 DECISION8, 이 문서 DECISION4 | 승인 경계 | [ADR-20261008 / execute-code-unattended](../../../hermes/docs/adr/ADR-20261008-execute-code-unattended.md) 에 셸 위험 명령을 fos-ctx 가 먼저 막는다는 줄을 더한다(PR2) | `hermes/docs/hermes-contract.md` 의 「`execute_code` 의 승인 판정」 |
