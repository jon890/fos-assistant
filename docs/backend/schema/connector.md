# 커넥터

커넥터 연결과 바인딩, 도구 호출의 판정과 승인, 상시 허락을 저장하는 표 넷의 칸과 제약을 갖는다.
상태가 바뀌는 조건과 API 는 [커넥터 연결](../../connectors.md) 이 갖는다.

## connector_connection

사용자마다 커넥터 하나에 연결 하나를 둔다. 근거는 [ADR-043](../../adr/ADR-043-커넥터는-plugin-의-connector-json-으로-선언하고-control-plane-은-범용-흐름만-갖는다.md) 이다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | `BIGINT` 기본키 | |
| `user_id` | `BIGINT NOT NULL` | `app_user` 참조 |
| `connector_id` | `VARCHAR(64) NOT NULL` | manifest 의 `id` |
| `agent_id` | `BIGINT`, 유니크 | `agent` 참조. 옛 연결 전용 에이전트. V79 부터 비어도 된다. 쓰지 않는 칸으로 남는다 |
| `status` | `VARCHAR(20) NOT NULL` | `DISCONNECTED`, `PENDING`, `READY` |
| `fields` | `VARCHAR(4000) NOT NULL` | JSON 텍스트 `{"values": {key: 값}, "secretPrefixes": {key: 앞 4자}}`. 비밀 칸의 원문과 해시는 넣지 않는다. 앞부분은 값이 16자 이상일 때만 넣는다. 16자 미만인 값은 앞 4자가 원문의 큰 부분이기 때문이다 |
| `restart_required` | `BOOLEAN NOT NULL` | 공유 gateway 재시작 뒤 반영 완료를 기다린다. 쓰지 않는 칸으로 남는다. 재시작 대기는 바인딩이 갖는다 |
| `desired_enabled` | `BOOLEAN NOT NULL` | env 와 설치 반영이 모두 성공해 활성화 후보가 되었는가. 등록, 교체, 해제 시작과 반영 실패에서 false. true 여도 실행 확인 전에는 `PENDING`. 쓰지 않는 칸으로 남는다. 켜려는 의도는 바인딩이 갖는다 |
| `checked_at` | `DATETIME(6)` | 마지막 확인 시각. 비어도 된다 |
| `undeclared_tools` | `INT NOT NULL` 기본 0 | 마지막 확인에서 MCP 서버가 낸 도구 가운데 manifest 의 `tools` 에 없던 수. `schema: 1` 은 0 이다 |
| `vault_stored` | `BOOLEAN NOT NULL` 기본 거짓 | 칸 값을 대시보드 plugin 의 보관 파일에 둔 적이 있는가. 옛 연결은 연결 확인이 옛 profile 의 값을 보관 파일로 옮길 때 참이 된다 |
| `created_at`, `updated_at` | `DATETIME(6) NOT NULL` | |

- `(user_id, connector_id)` 가 유니크다. 한 사람이 같은 커넥터를 둘 연결하지 못한다
- `agent_id`, `restart_required`, `desired_enabled` 는 연결을 에이전트에 붙이는 바인딩([ADR-083](../../adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md))이 생기며 쓰지 않는 칸이 됐다. 이전 이미지로 되돌릴 때를 위해 남겨 두고, 칸을 지우는 마이그레이션은 옛 커넥터 에이전트를 정리할 때 둔다. `agent_id` 의 유일 제약과 FK 는 남아 있고 비어 있는 값은 유일 제약에 걸리지 않는다
- 엔티티는 이 세 칸을 매핑하지 않는다. 새 행에서 `agent_id` 는 비고 두 boolean 칸은 기본값 거짓으로 저장된다. 옛 행의 값은 고치지 않아 그대로 남는다
- 해제해도 행은 남기고 `fields` 를 `{"values": {}, "secretPrefixes": {}}` 로 비운다. 지우는 경로는 없다
- 칸 값이 비밀이 아닌지는 DB 가 아니라 Control Plane 이 manifest 의 `secret` 으로 판정해 지킨다
- `fields` 를 MySQL `JSON` 타입이 아니라 문자열로 둔다. 칸 안을 SQL 로 찾을 일이 없고, 검사가 쓰는 H2 와 MySQL 의 JSON 리터럴 문법이 달라 이관 SQL 을 한 벌로 쓸 수 없다. 엔티티는 변환기로 record 로 읽는다
- V40 이전에 저장한 행은 앞부분의 원래 길이를 알 수 없어 `secretPrefixes` 가 비어 있다

`agent.connector_managed BOOLEAN NOT NULL DEFAULT FALSE` 는 바인딩이 생기기 전에 연결마다 만든 옛 커넥터 에이전트를 표시한다. 지금은 새로 참이 되지 않는다.
이 값이 참인 에이전트는 일반 설정 편집과 공개 범위 변경을 막고 사용자당 에이전트 상한에 세지 않는다. 지우기는 받는다. 사용자가 새 방식으로 옮긴 뒤 그 에이전트를 지울 길이 이것뿐이다.
남아 있는 동안의 규칙은 [커넥터 설치](../connector-install.md) 의 「옛 커넥터 에이전트」 가 갖는다.

`agent.connector_attachments BOOLEAN NOT NULL DEFAULT FALSE` 는 그 옛 커넥터 에이전트가 사진을 받는지다.
선언은 plugin 의 `connector.json` 에 있다. Control Plane 이 연결 확인과 관리자 반영 완료에서 선언한 toolset 이 켜진 것을 확인했을 때만 참으로 둔다.
옛 커넥터 에이전트가 아닌 에이전트에서는 쓰지 않는다.

## agent_connector_binding

에이전트에 연결을 붙인 것이다. 에이전트와 연결은 다대다다. 근거는 [ADR-083](../../adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md) 이다.
연결의 `status` 는 「값이 확인돼 쓸 수 있는가」 이고, 바인딩의 `status` 는 「그 에이전트의 profile 에 설치되고 반영됐는가」 다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | `BIGINT` 기본키 | |
| `agent_id` | `BIGINT NOT NULL` | `agent` 참조. 연결을 붙인 에이전트 |
| `connection_id` | `BIGINT NOT NULL` | `connector_connection` 참조 |
| `mcp_server` | `VARCHAR(64)` | 붙일 때 manifest 가 선언한 MCP 서버 이름. 비밀이 아니다. 실행마다 카탈로그를 읽지 않고, 운영자가 카탈로그에서 커넥터를 빼도 그 profile 에 설치된 서버 이름을 잃지 않게 둔다. 마이그레이션이 만든 옛 바인딩은 비어 있고 연결 확인과 관리자 반영 완료가 채운다 |
| `status` | `VARCHAR(20) NOT NULL` | `PENDING`, `READY` |
| `restart_required` | `BOOLEAN NOT NULL` 기본 거짓 | 공유 gateway 재시작 뒤 반영 완료를 기다린다. 재시작은 profile 마다 필요하므로 연결이 아니라 여기에 둔다 |
| `restart_required_since` | `DATETIME(6)` | 재시작이 필요해진 가장 늦은 설치 시각. 관리자가 재시작한 뒤에 다시 설치가 있었으면 반영 완료가 대기를 풀지 않게 하려고 둔다 |
| `apply_due_at` | `DATETIME(6)` | 반영 예정 시각. 재시작 없이 공유 gateway 의 MCP 설정 맞추기 주기가 반영할 설치(`reload_pending`)를 보냈을 때 적는다. 이 시각이 지나면 Control Plane 이 반영 맞추기를 스스로 한 번 돌리고, 돌리기 전에 비운다. `READY` 가 되면 비운다. 예정이 없으면 비어 있다([ADR-20261007 / connector-live-reload](../../adr/ADR-20261007-connector-live-reload.md)) |
| `desired_enabled` | `BOOLEAN NOT NULL` 기본 거짓 | 그 profile 에 설치가 성공해 반영 후보가 되었는가. 설치를 시작하면 false. true 여도 반영 확인 전에는 `PENDING` |
| `checked_at` | `DATETIME(6)` | 마지막 확인 시각. 비어도 된다 |
| `created_at`, `updated_at` | `DATETIME(6) NOT NULL` | |

- `(agent_id, connection_id)` 가 유니크다(`uk_agent_connector_binding`). 한 에이전트에 같은 연결을 둘 붙이지 못한다
- `agent_id` 는 `agent` 를, `connection_id` 는 `connector_connection` 을 FK 로 가리킨다. 연결로 바인딩을 찾으려고 `connection_id` 에 색인을 둔다
- 떼면 행을 지운다. 떼기는 재시작을 기다리지 않아 남길 상태가 없다. 이력은 `connector_action` 이 갖는다
- 해제되지 않은 옛 연결에는 V80 이 그 연결 전용 에이전트와의 바인딩을 만들었다. 그 바인딩의 `restart_required_since` 는 연결의 `updated_at` 이다
- 옛 커넥터 에이전트(`agent.connector_managed` 가 참)의 바인딩만 그 에이전트를 켜고 끈다. `READY` 가 되면 켜고, `PENDING` 이 되면 끄고 사진 받기를 내린다. 다른 에이전트의 바인딩은 에이전트를 건드리지 않는다

## connector_action

커넥터 도구 호출 하나의 판정과, 승인이 필요했던 호출의 승인 줄이다. 근거는 [ADR-049](../../adr/ADR-049-커넥터-도구-호출은-profile-plugin-의-hook-이-control-plane-에-물어-판정한다.md) 과 [ADR-050](../../adr/ADR-050-커넥터-쓰기는-control-plane-이-승인-줄을-저장하고-승인한-인자로-한-번만-실행한다.md) 이다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | `BIGINT` 기본키 | |
| `public_id` | `BINARY(16) NOT NULL`, 유니크 | 화면과 모델에 보이는 승인 요청 번호. 대화의 공개 식별자와 같은 방식이다([ADR-025](../../adr/ADR-025-대화는-주소에-공개-식별자를-쓰고-번호는-안에만-둔다.md)) |
| `user_id` | `BIGINT NOT NULL` | 연결의 주인 |
| `agent_id` | `BIGINT NOT NULL` | 판정한 실행의 에이전트. 그 에이전트에 붙은 바인딩으로 판정했고, 승인하면 그 에이전트의 profile 에서 실행한다. 바인딩 전에 남은 옛 줄은 옛 커넥터 에이전트를 가리킨다 |
| `connector_id` | `VARCHAR(64) NOT NULL` | |
| `tool_name` | `VARCHAR(128)` | MCP 서버의 원래 도구 이름. 대응 파일에서 찾지 못했거나 등록 이름과 맞는 것을 확인하지 못한 호출은 비운다 |
| `hermes_tool` | `VARCHAR(128) NOT NULL` | hook 이 받은 등록 이름 |
| `risk` | `VARCHAR(16)` | 판정 당시의 위험도. 정책을 읽지 못했거나, 선언이 없었거나, 연결이 준비되지 않아 거절한 호출은 비운다 |
| `approval_mode` | `VARCHAR(16)` | 판정 당시의 승인 방식. 위와 같을 때 비운다 |
| `decision` | `VARCHAR(20) NOT NULL` | `ALLOWED`, `DENIED`, `NEEDS_APPROVAL` |
| `deny_reason` | `VARCHAR(40)` | `DENIED` 일 때만. `POLICY_UNAVAILABLE`, `NOT_READY`, `UNDECLARED`, `RISK_NOT_OPEN`, `ARGS_TOO_LARGE`, `READ_ONLY_RUN`(먼저 살펴보기 트리) |
| `passed` | `BOOLEAN NOT NULL` | hook 에 통과로 답했는가. `decision` 이 `ALLOWED` 일 때만 참이다 |
| `status` | `VARCHAR(20)` | 승인 줄만. `PENDING`, `EXECUTING`, `SUCCEEDED`, `FAILED`, `UNKNOWN`, `REJECTED`, `EXPIRED` |
| `origin_execution_id` | `BIGINT NOT NULL` | hook 의 session 으로 찾은 실행 |
| `conversation_id` | `BIGINT` | 그 실행의 대화. 결과를 돌려줄 곳이다. 대화 없는 실행이면 비운다 |
| `dedupe_key` | `VARCHAR(64) NOT NULL`, 유니크 | `v1-connector`, profile, 루트 session, session, `tool_call_id` 를 줄바꿈으로 이어 SHA-256 한 값. 같은 호출이 다시 와도 줄이 하나다 |
| `args_json` | `MEDIUMTEXT` | 승인 줄만. hook 이 보낸 글자 그대로다. 16KB 까지 |
| `args_sha256` | `VARCHAR(64) NOT NULL` | 인자 글의 SHA-256. 원문을 두지 않는 줄에서도 무엇을 불렀는지 맞춰 볼 수 있다 |
| `expires_at` | `DATETIME(6)` | 승인 줄만. 만든 시각에서 24시간 뒤 |
| `decided_at` | `DATETIME(6)` | 승인, 거절, 만료한 시각 |
| `executed_at` | `DATETIME(6)` | 실행 결과를 적은 시각 |
| `result_text` | `MEDIUMTEXT` | 실행 결과. 위임 답과 같은 상한으로 자른다. `FAILED` 줄은 커넥터가 선언한 오류 계약이 있을 때만 `{"kind": "connector_error", "code", "details", "recovery"}` 를 담고 없으면 null 이다([ADR-092](../../adr/ADR-092-승인한-실행의-실패는-커넥터가-선언한-오류-코드와-복구-어휘와-정수-세부만-에이전트까지-전한다.md)) |
| `error_code` | `VARCHAR(64)` | 공통 오류 어휘 넷과 `TIMEOUT`. 사용자가 거절한 것이 아니라 시스템이 실행하지 않고 끝낸 `REJECTED` 줄은 `not_executable`(승인할 때 연결이나 그 에이전트의 바인딩이 준비되지 않았거나, 그 에이전트에서 연결을 떼었거나, 정책이 바뀜)이나 `connection_changed`(연결을 해제했거나 값을 다시 등록함)다 |
| `result_delivered_at` | `DATETIME(6)` | 결과나 거절, 만료를 대화에 전한 시각. `agent_execution` 의 같은 이름 칸과 뜻이 같다 |
| `created_at` | `DATETIME(6) NOT NULL` | |

- 허용과 거절도 한 줄씩 남긴다. 사용자 수가 적어 양이 문제가 되지 않는다
- `NEEDS_APPROVAL` 인 줄은 `passed` 가 거짓이고 `status` 가 `PENDING` 으로 시작하며 `args_json` 과 `expires_at` 을 갖는다. 승인 엔진이 켜지기 전에 남은 `NEEDS_APPROVAL` 줄은 `status` 와 `args_json` 이 비어 있어 승인 줄로 다루지 않는다
- `(conversation_id, status)` 와 `(user_id, created_at)` 에 색인을 둔다. 만료 정리가 찾는 `(status, expires_at)`, 같은 실행의 같은 호출을 찾는 `(origin_execution_id, hermes_tool, args_sha256)`, 연결의 승인 줄을 찾는 `(user_id, connector_id, status)` 에도 둔다
- 외래 키는 `user_id` 와 `agent_id` 에만 둔다. 실행과 대화는 지워져도 이 줄을 남긴다
- 인자 원문은 주인에게만 보인다. 관리자 목록과 로그에는 싣지 않는다

## connector_tool_grant

사용자가 도구 하나에 준 상시 허락이다. 승인하면서 기간을 골라 준다. 근거는 [ADR-050](../../adr/ADR-050-커넥터-쓰기는-control-plane-이-승인-줄을-저장하고-승인한-인자로-한-번만-실행한다.md) 이다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | `BIGINT` 기본키 | |
| `user_id` | `BIGINT NOT NULL` | |
| `connector_id` | `VARCHAR(64) NOT NULL` | |
| `tool_name` | `VARCHAR(128) NOT NULL` | 원래 도구 이름 |
| `expires_at` | `DATETIME(6) NOT NULL` | 무기한은 없다 |
| `created_at` | `DATETIME(6) NOT NULL` | |
| `revoked_at` | `DATETIME(6)` | 사용자가 거두었거나 연결을 해제한 시각. 선언이 상시 허락을 닫아 정리가 거둔 시각도 이 칸이다 |

- `revoked_at` 이 비고 `expires_at` 이 지금보다 뒤인 줄만 유효하다
- `approval: always` 인 도구와 선언이 `"grant": false` 인 도구에는 만들지 않는다. 판정할 때도 그 도구는 허락을 보지 않고, 선언이 닫은 도구(또는 `approval` 이 `required` 가 아니게 된 도구)에 남은 줄은 1분마다 `revoked_at` 으로 거둔다([ADR-065](../../adr/ADR-065-외부로-나가는-도구는-상시-허락을-닫는-선언을-둔다.md))
- 같은 도구에 허락을 다시 주면 새 줄을 만든다. 유니크 제약은 없다
