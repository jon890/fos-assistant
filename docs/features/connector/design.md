# 연결 반영 자동화 기술 설계

스펙: [`spec.md`](spec.md).
되돌리기 어려운 결정과 근거는 [ADR-20261120 / connector-auto-apply](../../adr/ADR-20261120-connector-auto-apply.md) 가 갖는다.
지금 동작은 [플러그인 연결](../connector.md) 의 「설치와 실패 처리」 와 [ADR-20261007 / connector-live-reload](../../adr/ADR-20261007-connector-live-reload.md) 가 갖는다.

스펙 DECISION9 대로 PR 셋으로 구현한다. 각 PR 이 그 단계의 설계 변경과 구현을 함께 담는다.

| PR | 요구사항 | 내용 |
| --- | --- | --- |
| PR1 | REQ1, REQ2, REQ10 | 대시보드 plugin 의 껐다 켜기, Control Plane 의 껐다 켜기 단계와 실패 표시(`apply_failed_at`). 정의 어긋남 점검이 껐다 켜기 바인딩을 관리자 알림에서 뺀다 |
| PR2 | REQ3, REQ4, REQ7 의 재시작 대기 확인 | 대시보드 plugin 의 gateway 기동 시각 조회, Control Plane 의 재시작 감지와 재시작 대기 줄의 다시 확인 |
| PR3 | REQ5, REQ6, REQ7, REQ8, REQ9 | 운영 정기 작업이 쓸 조회, 24시간 알림, 연결 응답의 실패 칸, 화면과 관리자 문구, `fos-home-infra` 요청 본문 |

## 지금 구조

- 바인딩 설치는 대시보드 plugin 의 `bind_connector`(`connector_binding.py`)가 profile 의 `config.yaml`, 소유 기록 `.fos-connectors.json`, `.env`, 뗀 서버 기록, 이름 대응, 스킬, `fos-ctx` 파일을 바꾸고 `restart_required`, `plugin_updated`, `reload_pending` 을 답한다.
  `restart_required` 는 이미 있던 서버의 정의나 `.env` 칸 값이 바뀌었거나 뗀 기록의 이름을 다시 붙였을 때 참이다. 로컬 플러그인의 값 교체는 실행기 정의의 보관 파일 리비전이 바뀌어 정의 변경으로 드러난다.
- 소유 기록의 서버 정의는 모양이 엄격하다. 로컬은 키가 정확히 `command`, `args`, `env`, `enabled`(와 `tools`)이고 `enabled` 가 참이어야 하며, 원격도 `enabled` 가 참이어야 한다(`connector_state.py`, `connector_remote.py`). 한 항목이 어긋나면 그 profile 의 소유 기록 전체를 읽지 못한다. 설정의 서버가 소유 기록과 다르면 설치는 「운영자가 바꾼 서버」 로 거절한다(`connector_binding.py`).
- Control Plane 은 `ConnectorInstallAttempts` 의 시작, 실행, 결과 세 단계로 설치를 보낸다. 결과 단계의 `ConnectorBindingInstalls` 가 `restart_required` 나 `plugin_updated` 가 참이면 `restart_required` 칸을 OR 로 누적하고 `restart_required_since` 를 시작 트랜잭션 시각으로 적는다. 둘 다 거짓이고 `reload_pending` 이면 `apply_due_at` 을 `apply-delay`(150초) 뒤로 적는다.
- `ConnectorBindingApplier` 가 30초마다 `apply_due_at` 이 지난 바인딩을 반영 맞추기 하고, 10분마다 `READY` 바인딩의 정의 어긋남을 점검한다. 재시작 대기 바인딩은 관리자 반영 완료(`confirmApplied`, `afterRestart=true`)만 `READY` 로 만든다.
- 진행 중인 실행은 두 곳에 있다. 실행 기록 `agent_execution` 의 `RUNNING` 줄(`profile_name` 을 갖는다)과, `UserExecutionLimiter` 가 메모리에 쥔 turn 자리와 원격 종료 확인 자리다. 원격 종료 확인 자리는 실행 줄을 끝냈지만 Hermes 의 run 이 끝났는지 아직 모를 때다.
- 대시보드와 공유 gateway 는 같은 컨테이너의 다른 프로세스다. Hermes 의 `gateway.status.get_running_pid(pid_path, cleanup_stale=False)` 가 gateway pid 를 정리 없이 읽고, 대시보드 자신의 gateway 상태 화면이 그렇게 부른다(v0.23.2 의 `hermes_cli/web_server_gateway.py`). 인자 없이 부르면 낡은 pid 파일과 잠금 파일을 지우는 정리를 할 수 있다.
- gateway 의 MCP 설정 맞추기는 `enabled: false` 인 이름을 그 profile 의 그 서버만 끊는다(v0.23.2 의 `tools/mcp_tool_discovery.py`, [ADR-20261007 / connector-live-reload](../../adr/ADR-20261007-connector-live-reload.md) 의 실측).

## 요구사항 연결

| ID | 해결 방법 |
| --- | --- |
| REQ1 | 설치 답에 `cycle_available` 을 더한다. Control Plane 은 재시작 대기 대신 껐다 켜기 단계로 두고, 실행이 비면 plugin 에 끄기 설치를 보낸 뒤 `apply-delay` 가 지나면 보통 설치로 다시 켠다. 다시 켠 설치는 `reload_pending` 이라 지금의 반영 예정 확인이 `READY` 로 만든다 |
| REQ2 | plugin 의 끄기는 `config.yaml` 의 그 서버 항목에 `enabled: false` 만 쓰고 소유 기록은 바꾸지 않는다. gateway 의 맞추기는 이름마다 연결을 맞추므로 같은 profile 의 다른 서버와 다른 profile 은 건드리지 않는다 |
| REQ3 | plugin 이 gateway 기동 시각을 답하는 조회를 연다. Control Plane 은 30초마다 재시작 대기 바인딩이 있을 때만 이 시각을 읽고, 대기 시각이 그 시각보다 앞서고 아직 실패 표시가 없는 바인딩을 `afterRestart=true` 로 반영 맞추기 한다 |
| REQ4 | 기동 시각을 읽지 못하거나 값이 없으면 아무것도 하지 않는다. 읽을 수 있다가 못 읽게 된 때에만 경고 한 줄, 다시 읽히면 info 한 줄을 남긴다 |
| REQ5 | 서비스 토큰으로 부르는 내부 조회 하나가 재시작 대기 바인딩 수와 가장 오래된 대기 시각, 진행 중 실행 수를 답한다. 재시작 조건(스펙 DECISION5)의 판단과 6시간 상한은 운영 정기 작업이 갖는다 |
| REQ6 | 연결 응답의 `restartRequired` 와 새 `applyFailed` 로 웹이 문구를 고른다. `boundReadiness` 와 `connectionStatusLabel` 이 바뀐다 |
| REQ7 | `confirmApplied` 의 경로와 응답 모양은 그대로 둔다. 화면의 단추 이름과 설명, 보이는 조건을 바꾸고, 껐다 켜기 단계와 재시작 대기에서 할 일을 아래 「관리자 다시 확인」 대로 정한다 |
| REQ8 | 바인딩에 `waiting_since` 와 `admin_notified_at` 을 두고, `waiting_since` 에서 24시간이 지나면 한 번 알린다. 실패 알림은 PR1 의 실패 표시가 적힐 때 함께 보낸다 |
| REQ9 | 자동 경로가 실패 까닭(스펙 DECISION10)으로 끝나면 `apply_failed_at` 을 적는다(PR1). 연결 응답과 관리자 목록이 이것으로 실패를 보인다(PR3) |
| REQ10 | 껐다 켜기의 끄기 단계는 그 profile 이 바쁘지 않거나 단계를 시작한 지 10분이 지났을 때만 보낸다 |

## 책임과 경계

- **대시보드 plugin 은 profile 파일을 바꾸는 일과 gateway 상태를 읽는 일만 한다.** 언제 끄고 켤지, 언제 재시작이 끝났다고 볼지는 Control Plane 이 정한다. 실행 기록과 바인딩 상태, 예약 작업이 모두 Control Plane 에 있기 때문이다.
- **공유 gateway 재시작은 운영 정기 작업이 한다.** Control Plane 과 plugin 에 재시작 권한을 주지 않는다. 사용자 요청에서 재시작하지 않는다는 지금 원칙([플러그인 연결](../connector.md) 의 「재시작」)을 지키고, 재시작 수단(s6)은 운영이 소유한다.
- Hermes core 는 바꾸지 않는다. plugin 이 새로 기대는 Hermes 내부 지점(`gateway.status.get_running_pid`)은 `hermes/tests/hermes_contract.py` 에 선언해 Hermes 를 올릴 때 계약 테스트가 확인하게 한다(ADR-088).
- Control Plane 안의 배치는 지금 그대로다. 단계 전이는 `ConnectorBinding` 이, 예약 작업은 `ConnectorBindingApplier` 가, 설치 보내기와 결과 적용은 `ConnectorInstallAttempts` 와 `ConnectorBindingInstalls` 가 맡는다. 운영 조회는 `connector.connection` 의 presentation 에 둔다.
- 「바쁜가」 는 `usage.execution` 의 `UserExecutionLimiter.used(userId)` 가 답한다. connector 가 usage 를 쓰는 방향은 지금도 있다.

## 인터페이스와 데이터

### 대시보드 plugin: 바인딩 설치의 껐다 켜기(PR1)

`PUT /api/connectors` 의 `bind` 에 선택 칸 `cycle` 을 더한다. 값은 `"off"` 하나다.
꺼진 상태는 **설정의 서버 항목에만** 둔다. 소유 기록의 `server` 는 늘 `enabled: true` 인 정의 그대로다.
끈 시각은 끄기 기록 `.fos-connector-cycle.json`(`{"<플러그인 id>": <epoch 초>}`)에 둔다.

| 요청 | plugin 이 하는 것 | 답 |
| --- | --- | --- |
| 보통 설치, 서버가 켜져 있다 | 지금처럼 쓴다 | 지금 답. 재시작이 필요한 까닭이 정의나 칸 값 변경, 뗀 이름 다시 붙이기뿐이면 `cycle_available: true` 를 더한다. `plugin_updated` 가 참이면 거짓이다 |
| `cycle: "off"`, 서버가 켜져 있다 | 지금 설치처럼 새 정의를 소유 기록에 쓰고, 설정에는 같은 정의에 `enabled: false` 를 넣어 쓴다. 끄기 기록에 지금 시각을 적고 뗀 서버 기록에서 그 이름을 지운다 | `cycle_off: true`, `restart_required: false`, `reload_pending: false` |
| 보통 설치나 `cycle: "off"`, 서버가 꺼져 있고 끄기 기록이 최소 시간보다 새롭다 | 새 정의를 소유 기록에 쓰고 설정은 꺼진 채 새 정의로 쓴다. 끄기 기록의 시각은 바꾸지 않는다 | `cycle_off: true`, `restart_required: false`, `reload_pending: false` |
| 보통 설치, 서버가 꺼져 있고 끄기 기록이 최소 시간보다 오래됐다 | 설정을 소유 기록과 같은 켜진 정의로 쓰고 끄기 기록에서 지운다. 끈 동안 gateway 가 옛 연결을 끊었으므로 재시작을 요구하지 않는다 | `restart_required: false`, `reload_pending: true` (`plugin_updated` 면 지금처럼 재시작 대기) |
| `cycle: "off"`, 서버가 꺼져 있고 끄기 기록이 오래됐다 | 꺼진 채 새 정의로 쓴다 | `cycle_off: true` |
| 떼기 | 지금처럼 떼고 끄기 기록에서 그 플러그인을 지운다 | 지금과 같다 |

- 「서버가 꺼져 있다」 는 설정의 항목이 소유 기록의 정의와 `enabled` 만 다르고 끄기 기록에 그 플러그인이 있다는 뜻이다. 이때만 「운영자가 바꾼 서버」 검사를 통과시킨다. 끄기 기록 없이 `enabled` 만 다르면 지금처럼 운영자가 바꾼 것으로 보고 거절한다.
- 상태 조회와 실행, probe 는 소유 기록을 기준으로 지금처럼 판정한다. 꺼진 서버의 `configured` 는 설정 항목이 기록과 달라 거짓일 수 있지만 Control Plane 은 껐다 켜기 단계의 바인딩을 읽지 않는다. 소유 기록의 모양이 그대로라 같은 profile 의 다른 바인딩은 영향이 없다.
- 최소 시간은 gateway 의 맞추기 주기(60초) 둘인 120초를 plugin 상수로 둔다. Control Plane 의 `apply-delay`(150초)가 더 길어 보통은 걸리지 않는다. Control Plane 의 시계나 예약이 틀려도 끊기 전에 다시 켜지 않게 하는 마지막 확인이다.
- 끄기 기록은 다른 profile 파일과 같은 원자적 교체와 복원 묶음에 넣는다.
- 이전 이미지로 되돌리면 꺼진 서버의 설정이 소유 기록과 달라 그 바인딩 하나만 「운영자가 바꾼 서버」 로 거절된다. 소유 기록은 그대로라 다른 바인딩은 돈다. 그래서 운영의 되돌리기 절차는 끄기 기록이 빈 것을 먼저 확인한다(운영 요청 본문에 적는다).

### 대시보드 plugin: gateway 기동 시각(PR2)

토큰으로 부르는 `GET /api/gateway` 를 연다. 답은 `{"started_at": <epoch 초> | null}` 이다.

- `gateway.status.get_running_pid(pid_path, cleanup_stale=False)` 로 그 컨테이너 gateway 의 pid 를 읽는다. 대시보드 자신의 상태 화면과 같은 부름이다. 정리하는 부름을 쓰지 않는다.
- 시작 시각은 Hermes 함수를 쓰지 않고 리눅스 `/proc/<pid>/stat` 의 시작 tick, `SC_CLK_TCK`, `/proc/stat` 의 `btime` 으로 벽시계 시각을 구한다. 정밀도는 1초다.
- pid 가 없거나 읽기가 실패하면 `null` 이다. 예외 본문과 경로는 로그에 남기지 않는다.

### Control Plane: 바인딩 칸과 단계

`agent_connector_binding` 에 칸을 더한다. 새 테이블을 만들지 않는다. 칸의 뜻은 [`backend/docs/data-schema.md`](../../../backend/docs/data-schema.md) 에 PR 마다 적는다.

| 칸 | PR | 뜻 |
| --- | --- | --- |
| `cycle_phase` VARCHAR(16) NULL | PR1 | 껐다 켜기 단계. 비었거나 `IDLE_WAIT`(실행이 비기를 기다림), `OFF`(끈 뒤 다시 켜기를 기다림) |
| `cycle_at` DATETIME(6) NULL | PR1 | `IDLE_WAIT` 이면 단계를 시작한 시각(10분 상한의 기준), `OFF` 이면 꺼졌다는 답을 처음 커밋한 시각 |
| `apply_failed_at` DATETIME(6) NULL | PR1 | 자동 경로가 실패한 시각(스펙 DECISION10). 사용자나 관리자가 시작한 설치(등록, 연결 확인, 다시 확인)와 `READY` 가 비운다. 자동 경로의 설치는 비우지 않는다 |
| `waiting_since` DATETIME(6) NULL | PR3 | 자동 대기가 시작된 시각. 비어 있을 때만 적고 단계가 바뀌어도 그대로 둔다. `READY` 와 사용자나 관리자가 시작한 설치가 비운다 |
| `admin_notified_at` DATETIME(6) NULL | PR3 | 지금 대기에 관리자 알림을 보낸 시각. `waiting_since` 가 비워지면 함께 비운다 |

자동 경로는 반영 예정 확인, 껐다 켜기 진행, 재시작 감지, 정의 어긋남 점검이다. `ConnectorBindingAttempt` 에 「자동 경로」 표시를 더해 결과 적용이 구분한다.

**plugin 의 답이 단계를 정한다.** 결과 적용은 보낸 요청이 무엇이었든 답으로 다음 단계를 고른다. 끄기를 보내는 사이 등록이 새 시도를 커밋해 끄기 결과가 버려져도, 등록의 답이 `cycle_off` 면 `OFF` 로 맞춰진다.

| 답 | 결과 적용 |
| --- | --- |
| `cycle_off: true` | `OFF`. `cycle_at` 이 비었거나 단계가 `OFF` 가 아니었으면 지금 시각으로 적는다. 반영 예정을 적지 않는다 |
| `restart_required` 이고 `cycle_available` 이며 `plugin_updated` 가 아니다 | 이미 재시작 대기면 지금처럼 재시작 대기로 둔다. 아니면 `IDLE_WAIT` 로 두고, 단계가 비어 있었을 때만 `cycle_at` 을 지금으로 적는다. `restart_required` 칸은 바꾸지 않는다 |
| `plugin_updated` | 지금처럼 재시작 대기. 단계가 `IDLE_WAIT` 면 비운다(재시작이 덮는다). `OFF` 면 그대로 둔다(서버가 꺼져 있어 켜기가 필요하다) |
| `reload_pending` 이고 단계가 `OFF` 였다 | 단계를 비우고 `apply_due_at` 을 적는다. 이것이 켜기다 |
| `changed: false` 이고 단계가 `OFF` 였다 | 켜기가 이미 끝난 것이다(앞선 켜기의 결과 커밋이 실패한 경우). 단계를 비우고 `apply_due_at` 을 적는다 |
| 그 밖의 답, 단계가 `IDLE_WAIT` 였다 | `IDLE_WAIT` 를 유지한다. gateway 가 옛 연결을 쥔 사실은 그대로이기 때문이다 |

예약 작업과 사람의 요청이 단계를 이렇게 다룬다.

| 지금 | 일 | 처리 |
| --- | --- | --- |
| `IDLE_WAIT`, 실패 표시 없음 | 그 profile 이 바쁘지 않거나 `cycle_at` 에서 10분이 지났다 | 끄기 설치를 보낸다 |
| `OFF`, 실패 표시 없음 | `cycle_at` 에서 `apply-delay` 가 지났다 | 보통 설치(켜기)를 보낸다 |
| `OFF` | 등록이 설치를 보낸다 | `cycle: "off"` 로 보낸다. 새 값으로 쓰되 꺼진 채 둔다 |
| `IDLE_WAIT` | 등록이 설치를 보낸다 | 보통 설치다. 답이 위 표대로 단계를 정한다 |
| `IDLE_WAIT`, `OFF` | 연결 확인 | 등록이 아니면 설치를 보내지 않고 새 `ResyncOutcome.CYCLE_PENDING` 으로 끝낸다 |
| 껐다 켜기 단계 | 반영 예정 확인, 재시작 감지 | 건너뛴다. 단계가 비어야 다룬다 |
| 어느 단계든 | 떼기, 에이전트 삭제 | 바인딩 행이 지워진다 |

- 껐다 켜기 단계의 바인딩은 `PENDING` 이다. 도구 허용 조건은 바꾸지 않는다(스펙 DECISION4).
- 연결 응답의 `restartRequired` 는 껐다 켜기 단계에서 거짓이다. 화면은 「몇 분 안에」 문구와 다시 읽기를 쓴다.
- 반영 예정 확인은 재시작 대기 바인딩을 지금처럼 건너뛴다. 그래서 `OFF` 에서 `plugin_updated` 를 받은 바인딩은 켜진 뒤에도 재시작 감지가 맡고, gateway 가 서버를 연결하기 전에 probe 만으로 `READY` 가 되지 않는다.

### Control Plane: 실패 표시(PR1)

- 자동 경로의 결과가 스펙 DECISION10 의 실패 까닭(`PROBE_FAILED`, `NO_TOOLS`, `NOT_INSTALLED`, `POLICY_HOOK_OFF`, `CALL_FAILED`, `CATALOG_MISSING`)이면 결과 트랜잭션이 `apply_failed_at` 을 적는다.
- 설치를 거치지 않고 `PENDING` 으로 두는 자동 경로도 같다. 정의 어긋남의 연속 상한과, 카탈로그를 읽지 못해 `PENDING` 으로 둔 반영 예정 확인과 정의 어긋남 점검이다.
- 실패 표시가 있는 바인딩은 껐다 켜기 진행과 재시작 감지가 다시 집지 않는다. 한 번이면 실패라는 스펙 DECISION10 을 지키고, 같은 실패에 설치를 되풀이해 보내지 않는다.
- 실패한 바인딩의 그룹 관리자에게 알림을 보내는 일은 PR1 에서는 지금의 `notifyAdmins` 를 쓰고, PR3 에서 문구와 한 번만 보내기(`admin_notified_at`)를 맞춘다.

### Control Plane: 관리자 다시 확인

`confirmApplied` 의 요청과 응답 모양은 그대로다. 바인딩의 지금 상태에 따라 하는 일이 갈린다.

| 바인딩 | 하는 일 |
| --- | --- |
| 껐다 켜기 단계 | 실패 표시를 비우고 그 단계의 다음 설치를 바로 보낸다. `IDLE_WAIT` 는 실행을 기다리지 않고 끈다. 관리자가 직접 눌렀기 때문이다. 응답은 `CYCLE_PENDING` 에 맞는 오류로 끝나 화면이 목록을 다시 읽는다 |
| 재시작 대기 | 잠그기 전에 `GET /api/gateway` 를 부른다. `started_at` 이 있고 `restart_required_since < started_at - 2초` 가 아니면 새 오류 코드 `CONNECTOR_RESTART_NOT_DONE`(「아직 공유 gateway 가 재시작되지 않았어요」)로 거절하고 아무것도 바꾸지 않는다. 기동 시각을 읽지 못하면 지금처럼 `afterRestart=true` 로 다시 맞춘다(스펙 DECISION19, PR2) |
| 그 밖의 `PENDING` | 지금처럼 다시 맞춘다 |

### Control Plane: 예약 작업

`ConnectorBindingApplier` 의 30초 주기(`apply-cron`)가 차례로 돈다. 바인딩마다 트랜잭션을 따로 열고 지금처럼 연결 사용자 행, 에이전트 행 차례로 잠근 뒤 다시 읽는다. 외부 호출과 「바쁜가」 조회는 잠금 밖에서 한다.

1. 반영 예정 확인(지금 그대로, 껐다 켜기 단계는 건너뜀)
2. 껐다 켜기 진행(PR1). 잠근 뒤 단계와 실패 표시가 그대로인지 다시 본다
3. 재시작 감지(PR2). 실패 표시가 없고 단계가 비었으며 반영 예정 시각이 없는 재시작 대기 바인딩이 하나라도 있을 때만 `GET /api/gateway` 를 한 번 부른다. `started_at` 이 있으면 `restart_required_since < started_at - 2초` 인 바인딩마다 `resync(afterRestart=true)` 를 돌린다
4. 24시간 알림(PR3). `waiting_since` 가 24시간 넘었고 `admin_notified_at` 이 빈 바인딩을 그룹별로 모아 알린다

**`restart_required_since` 를 결과 트랜잭션 시각으로 바꾼다(PR2).** 지금은 시작 트랜잭션 시각이라 HTTP 를 보내기 전이다. 그 사이 gateway 가 다시 뜨면 옛 파일로 뜬 gateway 를 반영된 것으로 본다. 결과 트랜잭션은 plugin 이 파일을 다 쓰고 답한 뒤라 이 시각보다 늦게 뜬 gateway 는 새 파일을 읽었다. 2초는 `btime` 과 tick 의 정밀도를 덮는 여유다. 관리자 다시 확인의 `restartRequiredSince` 대조는 같은 칸을 그대로 쓰므로 바뀌지 않는다.

### 바쁜가

`UserExecutionLimiter.used(userId)` 가 두 값을 함께 낸다. turn 자리, 원격 종료 확인 자리, 대화 turn 의 루트가 아닌 `RUNNING` 줄의 합이다.

- profile 하나가 바쁜가(스펙 DECISION18, REQ10): 그 profile 주인의 `used` 가 1 이상이다. 바인딩이 붙은 에이전트는 `PRIVATE` 라 그 profile 의 실행은 모두 주인의 실행이다. 주인의 다른 profile 실행도 바쁜 것으로 보지만 기다림이 길어질 뿐 대화를 끊지 않는 쪽이다. `agent_execution` 에 profile 단위 조회를 더하지 않은 것은 그 저장소 파일이 길이 상한에 닿았고 같은 판단을 이미 `used` 가 내기 때문이다
- 전체 진행 중 실행 수(스펙 DECISION5, REQ5): PR3 이 정한다. 사용자마다의 메모리 자리와 `RUNNING` 줄을 함께 센다

### Control Plane: 운영 조회(PR3)

`GET /internal/ops/connector-restart` 를 연다. 서비스 Bearer 토큰 하나로 인증하고, 토큰은 새 환경 변수로 받는다(이름은 plan 이 정하고 값과 자리는 `fos-home-infra` 가 갖는다). `/internal/connector-executions/support` 의 서비스 인증과 같은 방식이다.

```json
{"restartWaiting": 2, "oldestWaitingSince": "2026-11-20T09:00:00Z", "runningExecutions": 0}
```

- `restartWaiting` 은 실패 표시가 없는 재시작 대기 바인딩 수다. 껐다 켜기 단계와 실패한 바인딩은 세지 않는다. 실패한 바인딩은 재시작으로 풀리지 않아 세면 운영 작업이 재시작을 되풀이한다.
- `runningExecutions` 는 위 「바쁜가」 의 전체 수다.
- 재시작 여부, 6시간 상한, 재시작 뒤 확인은 운영 정기 작업이 정한다. 요청 본문은 PR3 이 써서 `fos-home-infra` 에 넘긴다. 이 저장소에는 두지 않는다.

### Control Plane: 알림과 연결 응답(PR3)

- 실패 알림은 실패 표시를 적은 결과 트랜잭션이 커밋된 뒤 그룹 관리자마다 보내고 `admin_notified_at` 을 적는다. 24시간 알림도 같은 칸을 쓰므로 한 대기에 두 번 가지 않는다.
- 정의 어긋남 점검의 `notifyAdmins` 는 재시작 대기와 껐다 켜기를 세지 않는다. PR1 에서 껐다 켜기를 빼고, PR3 에서 실패 알림으로 바꾼다.
- 알림 본문은 「연결 반영 확인」 에서 「다시 확인」 을 누르라고 쓴다.
- 연결 응답(`AgentConnectionView`, 관리자 목록 `AdminConnection`)에 `applyFailed`(boolean)를 더한다. 관리자 목록에는 `waitingSince` 도 더한다. 기존 칸은 바꾸지 않는다.
- 기동 시각을 읽지 못하는 상태는 메모리의 한 값으로 들고, 바뀔 때만 로그를 남긴다(REQ4).

### 웹(PR3)

| 자리 | 바뀌는 것 |
| --- | --- |
| `boundReadiness`, `connectionStatusLabel` | `applyFailed` 면 「반영하지 못했어요. 관리자에게 알렸어요.」, `restartRequired` 면 「자동으로 반영하고 있어요. 대화가 없는 틈에 반영돼서 조금 걸릴 수 있어요.」(상태 글은 「자동 반영 중」), 그 밖의 `PENDING` 은 「몇 분 안에 자동으로 반영돼요.」 |
| `isAwaitingApply` 와 다시 읽기 상한 | 실패한 바인딩은 다시 읽지 않는다. 상한을 껐다 켜기의 10분 대기와 두 번의 `apply-delay` 를 덮도록 15분 이상으로 늘린다 |
| `ConnectorAdminPanel` | 상태 글을 「자동 반영 중」 과 「반영 실패」 로 나눈다. 「다시 확인」 단추는 `applyFailed` 이거나 `waitingSince` 에서 24시간이 지난 줄에만 보인다. 설명에 「자동 반영이 실패했을 때만 누르세요」 |

## 실패와 일관성

- **자동 경로의 설치가 실패하면 실패 표시를 적고 단계를 그대로 둔다.** 다음 주기가 되풀이하지 않는다. 관리자 다시 확인이 실패 표시를 비우고 그 단계부터 잇는다.
- 끄기 설치는 성공했는데 결과 커밋이 실패하면 plugin 은 꺼져 있고 Control Plane 은 `IDLE_WAIT` 다. 다음 주기의 끄기 설치가 `cycle_off` 를 받아 `OFF` 로 맞춰진다. 끄기 기록의 시각은 바뀌지 않으므로 최소 시간이 앞당겨지지 않는다.
- 켜기 설치는 성공했는데 결과 커밋이 실패하면 Control Plane 은 `OFF` 로 남는다. 다음 켜기 설치가 `changed: false` 를 받아 단계를 비운다.
- 동시성은 지금의 설치 시도 UUID 와 연결 revision 대조(「설치 호출의 커밋 경계」)를 그대로 쓴다. 늦게 온 결과는 새 시도를 덮지 못하고, 버려진 결과의 효과는 다음 답이 단계를 맞춘다.
- 재시작 감지는 결과 트랜잭션 시각과 기동 시각을 비교하므로 옛 파일로 뜬 gateway 를 반영된 것으로 보지 않는다. 재시작 직전 설치는 대기로 남아 다음 재시작이 맡는다.
- 배포 순서는 plugin 먼저다. Control Plane 이 먼저 나가면 `cycle_available` 이 없는 답을 거짓으로 읽어 지금처럼 재시작 대기로 두고, `GET /api/gateway` 가 404 면 기동 시각을 모르는 것으로 본다. plugin 이 먼저 나가면 새 칸은 쓰이지 않는다(rule-66).
- 되돌리기: plugin 을 되돌리기 전에 운영이 끄기 기록이 빈지 확인한다. 꺼진 서버가 남은 채 되돌리면 그 바인딩 하나가 409 로 남고, 새 이미지로 다시 올리거나 운영이 그 서버 항목을 켜야 풀린다.

## 검증 전략

| 요구사항 | 경계와 환경 |
| --- | --- |
| REQ1 | plugin 단위 테스트가 표의 경우와 끄기 기록, 최소 시간, 「운영자가 바꾼 서버」 검사의 예외를 확인한다. 같은 profile 에 다른 바인딩이 있을 때 그 설치와 조회가 계속 되는지도 본다. Control Plane 통합 테스트가 가짜 대시보드로 `IDLE_WAIT` → `OFF` → 반영 예정 → `READY` 를 사람 조작 없이 지나는지, 끄기 중 등록이 끼어든 경우를 본다. 배포 뒤 실제 값 교체 한 번을 왕복시킨다(`fos-home-infra` 절차) |
| REQ2 | plugin 테스트가 끄기 설치에서 소유 기록, 같은 profile 의 다른 서버 항목, 다른 profile 파일이 바이트 그대로인지 본다 |
| REQ3, REQ4 | plugin 테스트가 가짜 `/proc` 와 pid 로 시각과 `null` 을, 정리 없는 부름을 본다. Control Plane 통합 테스트가 가짜 기동 시각의 앞뒤 바인딩, 실패 표시가 있는 바인딩, 404 와 `null`, 로그가 상태가 바뀔 때만 남는지를 본다. `hermes_contract.py` 의 새 지점은 CI 의 hermes job 이 Hermes 소스와 대조한다 |
| REQ5 | Control Plane 웹 통합 테스트가 토큰 없음, 틀린 토큰, 대기와 실행과 실패의 조합을 본다 |
| REQ6, REQ7, REQ9 | 웹 단위 테스트가 문구 함수의 경우를, 브라우저 테스트가 붙인 직후 안내와 관리자 패널의 단추 조건을 본다. Control Plane 통합 테스트가 실패 표시를 적는 경로와 비우는 경로, 껐다 켜기 단계의 다시 확인을 본다 |
| REQ8 | Control Plane 통합 테스트가 고정 시계로 24시간 경계, 단계가 바뀌어도 세기가 이어지는지, 한 번만 보내기를 본다 |
| REQ10 | Control Plane 통합 테스트가 가짜 `RUNNING` 실행과 메모리 자리로 기다림과 10분 상한을 본다 |

MySQL 의 새 칸은 Draft CI 의 `backend-mysql` 이 확인한다.

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | 껐다 켜기를 누가 진행하나 | Control Plane 이 단계를 저장하고 예약 작업으로 끄기와 켜기를 따로 보낸다. plugin 은 요청받은 쓰기만 한다 | 작성자 | 「바쁜가」 확인(스펙 DECISION18)과 재시작 뒤 이어 하기가 Control Plane 에 있다. plugin 안의 타이머는 대시보드가 다시 뜨면 서버를 꺼진 채 남긴다 |
| DECISION2 | 꺼진 상태를 두는 곳 | 설정의 서버 항목과 따로 둔 끄기 기록. 소유 기록은 바꾸지 않는다 | 작성자 | 소유 기록은 서버 정의의 `enabled` 가 참이어야 읽혀, 바꾸면 같은 profile 의 모든 바인딩과 이전 이미지가 멈춘다(`connector_state.py`) |
| DECISION3 | gateway 재시작을 알아채는 방법 | plugin 이 정리 없는 `get_running_pid` 로 gateway pid 를 읽고 `/proc` 로 시작 시각을 구해 답하고, Control Plane 이 대기 시각과 비교한다 | 작성자 | Hermes core 를 고치지 않고, 대시보드도 같은 부름으로 gateway 상태를 본다. `fos-ctx` 가 적재 때 표식을 남기는 방법은 plugin 을 다시 읽을 때도 표식이 바뀌어 재시작으로 잘못 본다 |
| DECISION4 | 재시작 감지의 시각 비교 | 결과 트랜잭션 시각인 `restart_required_since` 와 벽시계 기동 시각을 2초 여유로 비교한다. Control Plane 이 마지막으로 본 값을 저장하지 않는다 | 작성자 | 배포는 Control Plane 과 gateway 를 함께 다시 띄우므로, 메모리에 둔 이전 값과 비교하면 배포 때의 재시작을 놓친다. 시작 트랜잭션 시각은 파일을 쓰기 전이라 틀린다 |
| DECISION5 | 운영 조회의 인증 | 새 서비스 Bearer 토큰 하나 | 작성자 | 사용자 JWT 를 운영 작업에 줄 수 없고, 기존 서비스 토큰은 용도가 달라 범위를 넓히지 않는다 |
| DECISION6 | 재시작 판단의 자리 | 운영 정기 작업. Control Plane 은 수만 답한다 | 작성자 | 재시작 수단과 6시간 상한의 기록이 운영 쪽에 있고, Control Plane 에 재시작 권한을 주지 않는다 |
| DECISION7 | 새 칸의 자리 | `agent_connector_binding` 의 칸 | 작성자 | 사용자 결정(2026-11-01) 「테이블은 늘리지 않는다」. 모두 바인딩 하나의 상태다 |
| DECISION8 | 재시작 대기 줄의 다시 확인 | 기동 시각으로 재시작을 확인하고 아니면 거절한다. 읽지 못하면 지금처럼 진행한다. PR2 에 넣는다 | 사용자 2026-11-20 | 스펙 DECISION19. 기동 시각 조회가 PR2 에서 생긴다 |
| DECISION9 | 실패 표시를 넣는 PR | PR1 | 작성자 | 껐다 켜기와 재시작 감지가 실패한 바인딩을 되풀이해 집지 않으려면 첫 PR 부터 표시가 있어야 한다. 화면에 보이는 것은 PR3 이다 |
| DECISION10 | 「바쁜가」 의 범위 | `RUNNING` 줄과 `UserExecutionLimiter` 의 turn 자리, 원격 종료 확인 자리를 함께 본다 | 작성자 | 실행 줄을 끝냈어도 Hermes 의 run 이 아직 돌 수 있다. 스펙 DECISION13 의 「실행 기록」 보다 넓게 보아 기다림이 길어질 뿐 대화를 끊지 않는다 |
| DECISION11 | 껐다 켜기 단계의 다시 확인 | 실패 표시를 비우고 그 단계의 다음 설치를 바로 보낸다 | 작성자 | 스펙 DECISION16 은 24시간이 넘은 줄에도 단추를 보이므로, 눌러서 아무것도 하지 않으면 꺼진 채 멈춘 바인딩을 풀 길이 없다 |


## 결정 기록 연결

| 결정 | 종류 | 선택 이유를 남길 ADR | 현재 규칙의 원본 |
| --- | --- | --- | --- |
| 스펙 DECISION3, 설계 DECISION1 부터 DECISION4 | architecture | [ADR-20261120 / connector-auto-apply](../../adr/ADR-20261120-connector-auto-apply.md). [ADR-20261007 / connector-live-reload](../../adr/ADR-20261007-connector-live-reload.md) 의 값 교체 재시작 대기와 ADR-083 의 「반영」 행을 개정한다 | 이 설계, [플러그인 연결](../connector.md) 의 「바인딩의 반영 맞추기」, `hermes/plugins/dashboard-profile-api/README.md` |
| 스펙 DECISION5, 설계 DECISION6 | architecture, areas: operations | 같은 ADR | 운영 정기 작업은 `fos-home-infra` |
