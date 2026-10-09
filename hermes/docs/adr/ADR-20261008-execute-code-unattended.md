## ADR-20261008 / execute-code-unattended: docker 실행 공간을 쓰는 profile 은 API 경로의 `execute_code` 를 승인 없이 컨테이너에서 돌린다

- **status**: `accepted`
- Date: 2026-10-08
- [ADR-086](../../../docs/adr/ADR-086-셸과-파일-도구는-사용자별-docker-실행-공간에서만-돈다.md) 의 실행 공간 설정에 승인 설정 한 칸을 더한다. [ADR-20261008 / connector-output-files](ADR-20261008-connector-output-files.md) 가 기대는 `execute_code` 를 API 경로에서 쓸 수 있게 한다.

### 결정

**대시보드 plugin 이 profile 의 `terminal:` 을 docker 실행 공간으로 쓸 때 `approvals.unattended_mode: approve` 를 함께 쓴다. local 로 되돌릴 때는 그 키를 지운다.**

| 항목 | 정한 것 |
| --- | --- |
| 쓰는 때 | 셸 계열 도구 저장과 사진 도구 옛 설치가 docker `terminal:` 을 쓰는 같은 설정 쓰기 안에서 |
| 지우는 때 | 같은 두 경로가 `backend: local` 을 쓸 때. 운영자가 직접 넣은 값도 지운다 |
| 그대로 두는 것 | `approvals` 의 다른 키(`mode`, `deny`, `timeout`, `cron_mode` 등)와 셸 계열 도구를 건드리지 않는 저장 |
| 기대는 Hermes 동작 | gateway 가 `HERMES_EXEC_ASK` 를 켜서, API 경로의 셸 위험 명령과 plugin 승인 요청이 `unattended_mode` 와 무관하게 승인 카드로 간다. 계약 시험이 이 동작을 확인한다 |

Hermes 가 이 판정을 어떻게 하는지는 [실행 공간](../hermes-contract.md) 의 「`execute_code` 의 승인 판정」 이 갖는다.

### 맥락

API 경로에서 `execute_code` 를 부르면 Hermes 가 「unattended platform 이라 승인할 사람이 없다」 며 바로 거절했다(2026-10-08).
커넥터가 쓴 목록 파일을 스크립트로 계산하는 흐름이 그 자리에서 끝났다.

Hermes 는 docker 실행 공간의 `execute_code` 를 승인 없이 돌린다. 단 실행 공간에 호스트 경로가 bind mount 돼 있으면 그 예외를 주지 않는다.
ADR-086 의 실행 공간은 `/workspace` 부터 호스트 경로라서 격리한 profile 모두가 이 예외를 받지 못한다.
그다음 Hermes 는 API 서버를 사람이 없는 경로로 분류하고 `approvals.unattended_mode`(기본 `deny`)로 바로 판정한다.
이 판정이 승인 다리(`/v1/runs/{id}/approval`)보다 앞에 있어 승인 카드로도 가지 않는다.

`approvals.*` 는 판정할 때마다 그 실행의 profile 설정을 다시 읽는다. 공유 gateway 에서도 profile 하나에만 적용되고 다시 띄울 필요가 없다.

운영 Hermes 의 판정 함수를 profile 과 같은 조건으로 불러 확인했다.

| 조건 | `execute_code`(호스트 마운트 있음) | 셸 `rm -rf /workspace/tmp` | plugin 승인 요청 |
| --- | --- | --- | --- |
| `deny`, `HERMES_EXEC_ASK` 켜짐 | 거절 | 승인 대기 | 승인 대기 |
| `approve`, `HERMES_EXEC_ASK` 켜짐 | 승인 | 승인 대기 | 승인 대기 |
| `approve`, `HERMES_EXEC_ASK` 꺼짐 | 승인 | **승인** | **승인** |

운영 gateway 는 둘째 줄의 조건이다. 셋째 줄이 계약 시험으로 막아야 하는 상태다.

### 대안 기각

- **운영 명령으로 필요한 profile 에만 넣는다**: 값과 `terminal.backend` 가 따로 움직인다. local 로 돌아간 profile 에 `approve` 가 남으면 `execute_code` 가 Hermes 컨테이너에서 승인 없이 돌아 다른 profile 의 `.env` 에 닿는다.
- **`execute_code` 만 여는 설정을 쓴다**: Hermes 에 도구별 키가 없다.
- **plugin hook 으로 docker 일 때만 허용한다**: `pre_tool_call` hook 은 막거나 승인 요청으로 보낼 수만 있다. 판정은 도구 처리기 안에 있어 hook 이 승인을 줄 수 없다. 판정 함수를 바꿔 끼우는 것은 [ADR-001](../../../docs/adr/ADR-001-hermes를-런타임으로-두고-core를-고치지-않는다.md) 에 어긋난다.
- **스킬이 파일을 쓰고 셸에서 `python3 <파일>` 로 돌리게 한다**: 설정 없이 지금 된다. 열리는 범위는 이 결정과 같은데, Hermes 가 `execute_code` 에 둔 판정을 돌아가는 모양이 된다. 모델이 `execute_code` 를 먼저 부르는 것도 막지 못한다.
- **마운트를 호스트 경로로 보이지 않게 바꾼다**: 판정을 피하는 것이다. 커넥터 출력은 Hermes 와 같은 절대 경로로 붙는 것이 설계라 맞지도 않는다.
- **`approvals.mode: off`**: 셸 위험 명령까지 승인 없이 연다.
- **Hermes 에 API 경로의 `execute_code` 도 승인 카드로 보내 달라고 한다**: core 변경이라 상류의 일이다. 스크립트마다 카드를 눌러야 해 계산 요청마다 사람이 한 번 더 개입한다.

### 결과

- 얻는 것:
  - 격리한 profile 이 API 경로에서 `execute_code` 로 커넥터 출력 파일을 계산한다.
  - 값이 `terminal.backend` 와 같은 쓰기에서 움직여 local profile 에 `approve` 가 남지 않는다.
  - 되돌릴 때 다시 띄우지 않는다. 키를 지우면 다음 호출부터 거절로 돌아간다.
- 감당할 것:
  - **스크립트 안의 `subprocess` 는 셸 위험 명령 판정을 거치지 않는다.** `/workspace` 를 지우는 스크립트가 카드 없이 돈다. 같은 일은 셸의 `python3 <파일>` 로도 지금 승인 없이 된다. 닿는 범위는 그 사용자의 `/workspace`, 읽기 전용으로 붙인 경로, 인터넷과 실행 공간 망으로 셸과 같다.
  - 스크립트가 RPC 로 부르는 도구는 Hermes 로 돌아와 원래 판정을 다시 받는다.
  - 스크립트가 읽은 글을 인터넷으로 보내는 길은 [커넥터 READ 데이터의 흐름](../../../docs/features/connector-policy.md) 의 RF-08 과 같은 통제 없음이다. 스크립트 안에서 커넥터 도구를 부르는 길(RF-08a)은 그대로 거절된다.
  - **Hermes 가 API 경로에서 `HERMES_EXEC_ASK` 를 켜지 않게 바뀌면 이 값이 셸 위험 명령과 커넥터 승인까지 연다.** 계약 시험이 그 지점을 본다. 실패하면 Hermes 를 올리기 전에 이 결정을 다시 본다.
  - 이미 docker 인 profile 은 셸 계열 도구를 다시 저장해야 값이 들어간다. 반영은 운영이 한다.

- **적용 범위**: 대시보드 plugin 의 셸 계열 도구 저장과 사진 도구 옛 설치, Hermes 계약 시험. 설정 모양은 [`hermes/README.md`](../../README.md) 의 「셸 실행 공간」 이 갖는다.
