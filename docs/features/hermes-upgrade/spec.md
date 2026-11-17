# Hermes v0.23.2 로 올리기 위한 Control Plane 과 plugin 호환

확인: 2026-11-17

## 목표

운영 Hermes 를 v0.21.6(commit `818c13be`)에서 정식 v0.23.2(commit `ae2a81cc`)로 올려도 대화, 위임, 예약, 사건 실행, 플러그인, 대시보드가 지금처럼 동작한다(사용자 2026-11-17).
Hermes 가 재기동돼 잃은 실행이 그 대화의 다음 turn 을 막지 않고, 승인 판정이 바뀌어도 셸 위험 명령이 사람 없이 열리지 않는다.

v0.22 부터 Hermes 는 `/v1/runs` 실행을 session 마다 디스크의 대기열(session authority)에 넣고 그 기록으로 상태를 답한다.
그래서 실행 상태에 `queued` 와 `unknown` 이 생기고, 재기동 뒤에도 조회가 답하며, 조회 응답과 사건 스트림의 모양이 바뀐다.
이 스펙은 fos-assistant 쪽 호환(Control Plane 의 실행 클라이언트와 재기동 정리, 가짜 Hermes, fos-ctx, 대시보드 plugin, 계약 테스트와 계약 문서)을 정한다.
이미지 빌드, 리허설, 운영 적용과 되돌리기는 `fos-home-infra` 가 갖는다. 이 스펙은 적용 전에 무엇이 확인돼야 하는지만 적는다.

## 상류 소스로 확인한 사실

2026-11-17 에 v0.23.2 와 v0.21.6 의 tag 소스를 대조했다. 운영 왕복으로는 확인하지 않았다. 계약 문서로 옮길 내용이며 여기서는 요구사항의 근거로만 둔다.

| 무엇 | v0.23.2 의 동작 | 근거 |
| --- | --- | --- |
| 실행 경로 | gateway 가 뜰 때 profile 마다 session authority 를 만들고, `/v1/runs` 는 모두 그 대기열에 들어간다 | `gateway/run_runtime.py`, `gateway/platforms/api_server_runs.py` 의 `_admit_run_to_authority` |
| 상태 값 | `queued`, `running`, `waiting_for_approval`, `unknown`, `completed`, `failed`, `cancelled`. `unknown` 은 종료 상태가 아니다. 결과가 `interrupted` 인 종료 기록은 조회에서 `cancelled` 로 보인다. 사람이 중지하지 않고 Hermes 쪽에서 끊긴 실행도 여기 든다 | `gateway/platforms/api_server_authority_runs.py` 의 `run_projection` |
| 재기동 | 도는 중에 gateway 가 내려간 사람 turn 은 `unknown` 이 되고, 그 session 의 뒤 turn 은 `POST /v1/runs/{id}/resolve-unknown` 이 올 때까지 `queued` 로 멈춘다. 끝난 실행은 재기동 뒤에도 상태, `output`, `usage` 를 답한다. 기록은 session 을 지울 때 함께 지워진다 | `gateway/session_authority.py`, `hermes_state_mutation_retirement.py` |
| 해제 요청 | 본문은 조회 응답의 `admission_id` 와 `execution_generation` 두 칸이다. `unknown` 실행에 중지를 보내면 409 `unknown_execution` 이다 | `api_server_authority_runs.py` 의 `resolve_unknown_run`, `stop_run` |
| 조회 응답 | `status`, `session_id`, `output`, `usage`, `admission_id`, `execution_generation`, `pending_controls` 를 준다. `error` 와 `pending_steer` 는 없다. `model`, `runtime` 은 같은 gateway 프로세스의 메모리에 남아 있을 때만 붙는다 | `api_server_runs.py` 의 `_durable_run_status` |
| 종료 사건 | `run.failed` 의 `error`, 끝까지 돈 실행의 종료 사건에 붙는 `pending_steer`, `run.completed` 의 `runtime` 은 그대로 온다. 대기 중 gateway 가 물러나면 종료가 아닌 `run.queued` 가 온다. gateway 가 정상 종료로 내려가면 도는 실행에 `run.interrupted` 가 먼저 오고, 그 사건에는 `error` 만 있고 `pending_steer` 와 `runtime` 이 없다 | `api_server_runs.py` 의 `_execute_run`, `_mark_shutdown_interrupted_runs` |
| 사건 스트림 | `message.delta`, `tool.started`, `tool.completed`, 승인 사건, 종료 사건만 온다. `message.interim`, `reasoning.available`, `subagent.start`, `subagent.complete` 는 이 경로로 오지 않는다. `tool.started`, `tool.completed` 에 `tool_call_id` 가 붙고, `tool.completed` 의 `duration` 은 0 이다 | `api_server_runs.py` 의 `_execute_run` 이 넘기는 observer, `gateway/session_api_turn.py` 의 `wire_api_observers` |
| 승인 판정 | `/v1/runs` 실행에 승인 알림 경로가 등록돼 있지 않으면 셸 위험 명령도 `approvals.unattended_mode` 로 바로 정한다. docker 실행 공간 profile 은 이 값이 `approve` 다. session authority 의 turn 실행기는 turn 마다 알림 경로를 등록하지만, 그 session key 가 닿지 않는 스레드가 어디인지는 확인하지 못했다 | `tools/approval.py` 의 `_presence`, `gateway/run_turn_runner.py` |
| 코드 실행 안 도구 호출 | `execute_code` 안의 호출이 `session_id` 를 달고 hook 에 온다. `tool_call_id` 는 없다 | `tools/code_execution_rpc.py`, `model_tools.py` 의 `handle_function_call` |
| 내장 도구 | `browser_vault`, `set_theme`, `setup_beat` 가 새로 생겼다 | `toolsets.py` |
| 의존성과 저장 | MCP SDK 2.3.0, `cryptography` 50.0.2(Python 3.14 조건). session DB schema 31 → 33. 이미지만 되돌려서는 저장 상태가 돌아오지 않는다 | `pyproject.toml`, `hermes_state_common.py` |

Control Plane 은 Hermes 의 승인 카드(`POST /v1/runs/{id}/approval`)에 답하지 않는다. 그래서 v0.21.6 에서도 셸 위험 명령은 승인 시간(기본 300초)을 기다린 뒤 거절된다.

v0.23.2 소스를 계약 테스트에 넣으면 `test_hermes_contract.py` 6건, `test_hermes_runs_stream_contract.py` 2건, `test_tool_guard_contract.py` 2건, 본문 없는 요청 진단의 두 묶음 39건이 실패한다. 다수는 내부 함수 이름과 타입 표기가 바뀐 것이고, 본문 없는 요청 진단은 `agent/secret_scope.py` 와 `hermes_constants.py` 의 문맥 저장 방식이 새로 짜였다.

## 범위

PR 나눔은 DECISION1 을 따른다. 모든 호환 PR 은 v0.21.6 과 v0.23.2 에서 함께 동작해야 한다([rule-66](../../code-rules/rule-66.md)). 운영 Hermes 를 올리기 전에 머지하고 배포한다.
PR4 는 운영에 연결하지 않은 addon 만 고치므로 v0.23.2 에서만 맞으면 된다.

| 순서 | 담는 것 | 요구사항 |
| --- | --- | --- |
| PR1 | 이 스펙과 설계, Control Plane 의 실행 클라이언트·재기동 정리·가짜 Hermes 호환, ADR-061 갱신 | REQ1, REQ2, REQ3, REQ4, REQ9 의 backend 부분 |
| PR2 | fos-ctx 의 셸 위험 명령 거절과 코드 실행 안 호출 판정, 대시보드 plugin 호환, 계약 테스트를 두 버전에서 통과하게 고치기 | REQ5, REQ6, REQ7, REQ8, REQ12 |
| fos-home-infra | 이미지 준비, 복제 데이터 리허설, 운영 적용 | REQ10 |
| PR4 | 본문 없는 요청 진단 addon 과 계약을 v0.23.2 의 scope 경계로 다시 설계한다(DECISION14). PR1, PR2 와 나란히 진행하고 PR3 전에 머지한다 | REQ13 |
| PR3 | 운영 적용과 왕복 확인 뒤 기준 버전을 v0.23.2 로 바꾸고 계약 문서를 고친다. v0.21.6 에만 필요한 호환을 걷어 낸다 | REQ9, REQ11 |

범위 밖:

- 이미지 빌드, FROM digest, compose 태그, 리허설과 적용 절차, 되돌리기. `fos-home-infra` 가 갖는다
- 셸 위험 명령을 다시 승인 카드로 여는 일. 어느 실행에 알림 경로가 없는지 소스와 운영으로 확인한 뒤 따로 정한다(DECISION7)
- Hermes 승인 카드에 Control Plane 이 답하는 경로
- steer 계약 표의 「중단」 줄. `steer-cancel-pending` 작업이 고친다
- v0.23.2 의 새 기능(Bot Chat, Desktop, room 등)을 쓰는 일
- `Idempotency-Key` 를 보내는 일([ADR-061](../../../backend/docs/adr/ADR-061-재기동-때-남은-실행은-hermes-에-물어-정하고-도는-실행에는-다시-붙는다.md) 이 기각한 그대로다)

## 요구사항과 인수 조건

| ID | 요구사항 | 관측할 인수 조건 |
| --- | --- | --- |
| REQ1 | 실행 상태의 새 값을 읽는다 | 가짜 Hermes 가 조회에 `queued` 를 주면 Control Plane 은 실행을 `RUNNING` 으로 두고 계속 묻는다. `unknown` 을 주면 REQ2 대로 해제한다. 화면은 DECISION4 를 따른다. v0.21.6 모양의 응답(404, `interrupted`)도 지금처럼 처리한다 |
| REQ2 | Hermes 재기동으로 잃은 실행은 Control Plane 이 자동으로 해제하고 실패로 적는다 | 도는 실행, Control Plane 이 다시 떠서 다시 정하는 실행, 중지를 보낸 실행 가운데 조회가 `unknown` 이면 Control Plane 이 그 응답의 `admission_id` 와 `execution_generation` 으로 `resolve-unknown` 을 한 번 보내고 실행 줄을 `FAILED` 로 적는다. 대화 화면은 지금 실패한 turn 과 같게 보인다. 해제 대상은 Control Plane 이 보낸 모든 실행이다. 대화 turn, 위임, 흐름, 방 요약처럼 대화와 같은 session 을 쓰는 부가 실행이 모두 든다(DECISION17). Hermes 가 다시 뜨는 동안 조회가 연결 실패를 받으면 turn 은 지금처럼 바로 실패로 보이고, 남은 원격 실행을 기다리는 경로와 재기동 정리가 `unknown` 을 보는 대로 해제한다(DECISION15). 해제한 뒤 같은 대화의 다음 turn 이 `queued` 에 머물지 않고 돈다. 대기 줄과 다시 보내기는 ADR-048 그대로다(사용자 2026-11-17). 해제 요청이 실패하면 다음 조회에서 다시 해제한다 |
| REQ3 | 끝난 실행의 결과를 지금과 같은 뜻으로 적는다 | 조회 응답에 `error`, `pending_steer` 가 없어도 종료 사건에서 받은 값으로 실패 사유와 넘어간 steer 글을 처리한다. 종료 사건을 받지 못한 경우, 해제한 실행, `pending_steer` 없는 `run.interrupted` 로 끝난 실행에 끼운 글은 DECISION3 을 따른다(DECISION16). 실제로 돈 모델은 조회의 `runtime` 이 있으면 그것을, 없으면 지금처럼 session 행에서 읽는다 |
| REQ4 | 사건 스트림의 바뀐 모양을 받는다 | `tool_call_id` 와 모르는 칸을 무시하고 도구 사건을 지금처럼 저장한다. `run.queued` 를 종료로 읽지 않는다. `tool.completed` 의 `duration` 이 0 이면 걸린 시간을 모름으로 적는다. 오지 않는 사건(`message.interim`, `reasoning.available`, `subagent.*`)의 처리는 DECISION2 를 따른다 |
| REQ5 | 셸 위험 명령, 민감 파일 쓰기, `computer_use` 는 승인 경로와 상관없이 바로 거절한다 | v0.21.6 과 v0.23.2 에서 Hermes 가 위험하다고 분류하는 `terminal` 명령(패턴 분류만 보고 `command_allowlist` 는 보지 않는다, DECISION18)을 에이전트가 부르면 fos-ctx 가 hook 에서 바로 막고, 모델은 다른 길을 찾으라는 글을 받는다. v0.23.2 에서 파일 도구가 Hermes 가 승인을 요구하는 경로(`~/.ssh/config`, 셸 시작 파일 등, `agent.file_safety.write_approval_key` 판정)에 쓰려 하면 같은 hook 이 막고, `computer_use` 호출은 모두 막는다(사용자 2026-11-17). 승인 시간만큼 기다리지 않는다. docker 실행 공간의 `execute_code` 는 지금처럼 승인 없이 돈다 |
| REQ6 | 코드 실행 안의 도구 호출도 Control Plane 에 묻는다 | `session_id` 가 있는 코드 실행 안의 안전 목록 밖 호출을 fos-ctx 가 Control Plane 의 도구 판정에 묻는다(사용자 2026-11-17). 보통 대화에서는 통과하고 사건 실행 트리에서는 막힌다. 묻지 못하거나 답이 쓸 수 없으면 막는다. `session_id` 가 없는 v0.21.6 의 호출은 지금처럼 통과한다 |
| REQ7 | 계약 테스트가 두 버전에서 통과한다 | `scripts/checks/check-hermes-contract.sh` 가 고정 버전 v0.21.6 과 `v0.23.2` 인자에서 모두 0 으로 끝난다. 본문 없는 요청 진단의 두 묶음은 REQ13 이 맡고, 기준 버전이 v0.21.6 인 동안 v0.21.6 에서만 통과하면 된다. 새 내장 도구 세 개는 계약의 도구 목록에 있고 안전 목록 밖이다 |
| REQ8 | 대시보드 plugin 이 v0.23.2 에서 뜨고 지금 경로가 그대로 답한다 | v0.23.2 소스로 돌린 대시보드 plugin 테스트가 통과하고, plugin 이 기대는 내부 지점(토큰 인증, 도구 설정, plugin 의존성 경고)이 계약 테스트를 통과한다. `PyYAML` 과 `cryptography` 가 이미지에 있는지는 REQ10 의 적용 조건이다 |
| REQ9 | 계약 문서와 ADR 이 새 동작을 적는다 | ADR-061 의 Hermes 답 표에 `queued`, `unknown` 과 해제가 있다. 계약 문서의 Runs API, 실행 조회 기간, 사건 형태, 내장 승인, `execute_code` 승인 판정 절이 v0.23.2 를 적는다. [ADR-20261008 / execute-code-unattended](../../../hermes/docs/adr/ADR-20261008-execute-code-unattended.md) 가 REQ5 로 바뀐 기대를 적는다 |
| REQ10 | 운영 적용 전에 확인할 것이 정해져 있다 | 아래 「적용 조건」 이 모두 확인된 뒤에만 `fos-home-infra` 가 운영에 적용한다 |
| REQ11 | 적용한 뒤 운영에서 왕복시켜 본다 | 아래 「배포 뒤 왕복 확인」 을 코디네이터가 운영에서 확인하고 결과만 남긴다. 하나라도 어긋나면 기준 버전을 올리지 않고 스펙이나 설계로 돌아간다 |
| REQ13 | 본문 없는 요청 진단 addon 이 v0.23.2 의 scope 경계에서 지금과 같은 보장을 지킨다(사용자 2026-11-17) | addon 의 설치 API(`install_bodyless_writer(*, resolver, fingerprints)`)와 writer 인자는 그대로다. 관리 bootstrap 이 기대는 내부 경계를 v0.23.2 의 runtime scope(`hermes_scope` 의 scope 상태, `agent.runtime_scope.bind_profile_scope`)로 다시 정해 계약 문서의 「관리 bootstrap의 추가 내부 의존」 표와 `BODYLESS_MANAGED_FUNCTIONS` 를 고친다. v0.23.2 의 `set_secret_scope` 는 옛 방식으로 남은 함수이고 scope 가 이미 묶여 있으면 거절하거나 scope 를 옮기므로 기대하지 않는다. 분리 실행으로 profile 마다 문맥이 섞이지 않고 token 으로 이전 문맥이 돌아오는지, 다른 profile 의 문맥에서 writer 가 거절하는지 확인한다. `check-hermes-contract.sh v0.23.2` 에서 두 묶음이 통과한다. 운영 연결은 하지 않는다([ADR-20261012 / bodyless-request-diagnostics](../../../hermes/docs/adr/ADR-20261012-bodyless-request-diagnostics.md) 그대로) |
| REQ12 | 새 패치가 나오면 계약 테스트만 다시 돌려 옮긴다 | 적용 전 v0.23.x 패치가 나오면 그 tag 로 `check-hermes-contract.sh` 를 돌려 실패가 없을 때 목표를 그 패치로 바꾼다. 실패가 있으면 이 스펙의 해당 요구사항으로 돌아간다(사용자 2026-11-17) |

### 적용 조건

`fos-home-infra` 가 운영에 적용하기 전에 모두 확인한다. 실행 방법은 그 저장소가 갖는다.

- PR1, PR2 가 머지되고 운영 Control Plane 과 profile 의 plugin 에 배포됐다
- 목표 tag 로 계약 테스트가 통과한다(REQ7, REQ12)
- 이미지에 대시보드 plugin 의 `PyYAML`, 보관 파일의 `cryptography`, MCP SDK 2.3.0 이 있다
- 관리 bootstrap 과 cron wrapper 의 지문을 새 소스로 다시 확인했다. `fos-home-infra` 의 본문 없는 요청 진단 관리 bootstrap 은 공개 `install_bodyless_writer` 와 옛 scope 함수(`set_secret_scope`, `current_secret_scope_home`, `set_hermes_home_override`)를 쓰는 비활성 prototype 이고, 운영 기동 경로는 그것을 부르지 않는다(2026-11-17 그 저장소의 코드로 확인). 그래서 운영 적용을 막지 않는다. 활성화하려면 REQ13 뒤 그쪽 prototype 과 봉인한 manifest 를 새 경계로 고쳐야 한다
- 복제 데이터로 리허설해 config migration 과 session DB schema 33 이 오르고, 아래 왕복 확인의 실행, 재기동, 위임 항목이 통과했다(사용자 2026-11-17)
- 모든 profile 의 설정과 session 저장소를 백업했고, 되돌리기는 이미지와 데이터를 함께 되돌린다

### 배포 뒤 왕복 확인

| 항목 | 확인할 것 |
| --- | --- |
| 실행 | 보통 대화 turn 이 답과 도구 사건을 흘리고 끝나며, 실행 기록에 실제 provider 와 모델, 사용량과 캐시 토큰이 남는다 |
| steer | 설정이 켜져 있으면 도는 실행에 끼운 글이 다음 단계에 들어가고, 넘어간 글이 다음 turn 으로 간다 |
| 재기동 | 도는 turn 중에 Hermes 를 다시 띄우면 그 turn 이 실패로 적히고 같은 대화의 다음 turn 이 돈다. Control Plane 만 다시 띄우면 도는 turn 에 다시 붙는다 |
| 승인 | 셸 위험 명령이 바로 거절되고, 플러그인 승인 카드(Control Plane 의 카드)는 지금처럼 동작한다 |
| 플러그인 | 연결을 붙인 에이전트가 플러그인 도구를 부르고 hook 이 판정하며, 결과가 `<external-data>` 로 감싸진다 |
| 브라우저 | 「내 브라우저」 를 쓰는 플러그인이 한 번 끝까지 돈다 |
| 대시보드 | profile 만들기, 도구 설정 저장, 바인딩 설치, 카탈로그가 답한다 |
| 위임 | `@` 맡김과 native 하위 에이전트가 끝나고 결과가 부모 대화로 간다. 실행 그래프에 보이는 것은 DECISION2 를 따른다 |
| 예약 | 예약 작업이 한 번 돌고 결과 대화가 생긴다 |
| 사건 실행 | 사건 살펴보기에서 안전 목록 밖 도구가 막히고, `execute_code` 안의 호출도 막힌다 |

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | PR 나눔과 첫 PR 의 범위 | 「범위」 표의 네 단계. PR1(스펙·설계와 Control Plane 호환), PR2(fos-ctx·대시보드 plugin·두 버전 계약 테스트), fos-home-infra 적용, PR3(기준 버전 올리기와 옛 호환 걷어 내기) | 사용자 2026-11-17 | PR 마다 관심사가 하나이고 backend 와 hermes 가 서로 기다리지 않으며 둘 다 운영 적용 전에 배포된다 |
| DECISION2 | v0.23.2 에서 오지 않는 사건(`message.interim`, `reasoning.available`, `subagent.*`)의 처리 | 오는 사건만 그린다. 운영 왕복에서 실제로 오지 않는지 확인해 계약 문서에 적고, 메울지는 뒤에 따로 정한다 | 사용자 2026-11-17 | 답과 기록은 잃지 않고 중간 안내, native 자식 표시, steer 말풍선의 자식 안내만 줄어든다 |
| DECISION3 | 종료 사건을 받지 못해 `pending_steer` 를 모를 때 | 그 turn 에 끼운 글을 「전달됐는지 확인 못 함」 으로 두고 멈춘 대기 줄로 되돌려 사용자가 고른다. 종료 사건이나 중지로 돌아온 `pending_steer` 는 지금 규칙(「다음 차례로 넘어감」, 중지면 「전달 안 됨」)을 그대로 쓴다. 실패 사유는 종료 사건이 없으면 일반 실패 문구로 적는다(작성자) | 사용자 2026-11-17 | 글을 잃지 않고 두 번 보내지 않으며 [steer 스펙](../chat/spec.md)의 화면과 규칙을 그대로 쓴다 |
| DECISION4 | `queued` 실행을 화면에 보이는 방식 | 지금처럼 「답을 만드는 중」 으로 보인다. 화면 변경은 없다 | 사용자 2026-11-17 | Control Plane 이 대화마다 turn 을 하나씩 보내고 `unknown` 을 바로 해제해 `queued` 는 짧다. 방 요약처럼 같은 session 을 쓰는 부가 실행과 겹치면 그만큼 기다린다. 길게 남는 경우가 운영 왕복에서 보이면 따로 정한다 |
| DECISION5 | 본문 없는 요청 진단 addon 의 v0.23.2 계약 | v0.23.2 의 새 scope 경계로 addon 과 계약을 다시 설계한다(REQ13) | 사용자 2026-11-17 | 작성자 권장(이번 범위에서 빼기)과 다른 선택이다 |
| DECISION14 | REQ13 을 둘 PR | 호환 PR 과 따로 둔 PR4 로 하고 PR3 보다 먼저 머지한다. PR1, PR2 와 나란히 진행할 수 있다 | 사용자 2026-11-17 | 사용자가 제안을 요청했다(2026-11-17). addon 은 운영에 연결하지 않아 운영 적용을 막지 않지만, 기준 버전을 올리는 PR3 의 CI 를 막으므로 그 전에 끝나야 한다. 관심사가 호환 PR 과 달라 섞으면 PR 크기 상한을 넘는다 |
| DECISION6 | 목표 버전과 적용 시점 | v0.23.2. 적용은 지켜본 뒤 하고, 새 패치는 계약 테스트만 다시 돌려 옮긴다 | 사용자 2026-11-17 | 코디네이터 지시서 |
| DECISION7 | 승인 판정 변화에 대한 배포 조건 | 알림 경로가 없는 실행을 소스로 확인하기 전까지 셸 위험 명령을 거절로 좁혀 배포한다 | 사용자 2026-11-17 | 보안 경계는 닫힌 쪽 |
| DECISION8 | 승인 관문을 좁히는 방법과 범위 | fos-ctx 의 `pre_tool_call` hook 이 Hermes 가 위험하다고 분류한 `terminal` 명령, Hermes 가 승인을 요구하는 파일 쓰기, `computer_use` 호출을 막는다. profile 의 `unattended_mode` 는 `approve` 로 둔다 | 사용자 2026-11-17 (범위만 정했고 방법은 작성자) | DECISION7 은 셸 명령만 좁혔다. `unattended_mode: deny` 는 docker 의 `execute_code` 까지 닫는다. Control Plane 이 Hermes 승인 카드에 답하지 않아 지금도 그 동작은 시간 초과로 거절되므로 사용자에게 보이는 결과는 기다림이 사라지는 것뿐이다. 설계 검토에서 파일 쓰기와 `computer_use` 도 같은 관문(`_run_approval_gate`)이 열린다는 것이 나와 사용자가 범위를 넓혔다 |
| DECISION9 | 재기동으로 잃은 실행 | Control Plane 이 `resolve-unknown` 으로 자동 해제하고 실패로 적는다. 대기 줄과 다시 보내기는 ADR-048 그대로 | 사용자 2026-11-17 | 코디네이터 지시서 |
| DECISION10 | 해제한 실행의 `error_code` | `REMOTE_RUN_LOST` 를 그대로 쓴다 | 작성자 | v0.21.6 에서 재기동 뒤 404 로 잃은 실행과 같은 뜻이다(ADR-061) |
| DECISION11 | 코드 실행 안 도구 호출 | Control Plane 에 묻는 쪽으로 둔다. `tool_call_id` 가 없으므로 fos-ctx 가 판정 요청용 값을 만든다 | 사용자 2026-11-17 (방법은 작성자) | Control Plane 의 판정(`EventToolGuard`)은 session 으로 정하고 `tool_call_id` 는 서명에만 쓴다 |
| DECISION12 | 운영 적용 전 리허설 | 복제 데이터로 리허설한다 | 사용자 2026-11-17 | `fos-home-infra` 의 일 |
| DECISION15 | Hermes 가 다시 뜨는 동안 도는 turn 이 받는 조회 연결 실패 | 지금처럼 그 turn 을 바로 실패로 적는다(실패 코드도 지금 그대로). Hermes 쪽 실행의 해제는 남은 원격 실행을 기다리는 경로(`holdUntilRemoteEnds`)와 재기동 정리가 `unknown` 을 볼 때 한다 | 작성자 | 사용자에게 보이는 실패 시점과 문구를 바꾸지 않는다. 대기열이 막히는 것은 해제로 풀린다 |
| DECISION16 | 해제한 turn 과 `pending_steer` 없는 `run.interrupted` 로 끝난 turn 에 끼운 steer 글 | DECISION3 처럼 「전달됐는지 확인 못 함」 으로 두고 멈춘 대기 줄로 되돌린다. 사용자가 중지를 누른 turn 이 해제로 끝나면 결과는 지금 중지와 같게 「중지됨」 으로 보인다 | 작성자 | 끊긴 실행은 글이 들어갔는지 알 수 없다. [steer 스펙](../chat/spec.md)의 DECISION14(404 로 실행을 잃었을 때)와 같은 규칙이다. 중지는 사용자가 이미 고른 결과다 |
| DECISION17 | 해제 대상 | Control Plane 이 보낸 모든 실행(대화 turn, 위임, 흐름, 방 요약, 추천 질문 같은 부가 실행) | 작성자 | DECISION9 가 Control Plane 의 자동 해제를 정했다. 방 요약은 대화와 같은 session 을 쓰므로(`RoomContext`) 빼면 그 대화가 멈춘다 |
| DECISION18 | REQ5 의 위험 명령 범위 | Hermes 의 위험 패턴 분류에 걸리는 명령은 모두 막는다. `command_allowlist` 의 영구 허용은 보지 않는다 | 작성자 | 우리 profile 은 허용 목록을 쓰지 않는다. 그 값은 프로세스 전역이고 import 때 한 번 읽어 profile 별로 맞지 않는다(계약 문서 「같은 부류의 계약 둘」). Control Plane 이 승인 카드에 답하지 않아 「항상 허용」 으로 쌓인 항목도 없다 |
| DECISION13 | 새 내장 도구 세 개의 판정 | 안전 목록 밖에 둔다 | 작성자 | 안전 목록은 읽기 전용 도구만 담는다([사건 실행 설계](../proactive/design.md)) |

## 도메인 규칙 변경

도메인 규칙 변경 없음: 실행 상태의 뜻([ADR-011](../../../backend/docs/adr/ADR-011-실행은-시작할-때-기록하고-끝날-때-갱신한다.md))과 대기 줄 규칙(ADR-048)을 그대로 쓴다. 재기동 정리 표는 ADR-061 에 행을 더한다.

## 결정 기록 연결

| 결정 | 종류 | 선택 이유를 남길 ADR | 현재 규칙의 원본 |
| --- | --- | --- | --- |
| DECISION9 | 재기동 정리 정책 | ADR-061 을 고친다 | ADR-061 |
| DECISION8 | 승인 경계 | [ADR-20261008 / execute-code-unattended](../../../hermes/docs/adr/ADR-20261008-execute-code-unattended.md) 를 고친다 | `hermes/docs/hermes-contract.md` 의 「`execute_code` 의 승인 판정」 |
