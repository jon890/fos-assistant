# 커넥터

커넥터 연결과 바인딩, 도구 호출의 판정과 승인, 상시 허락을 저장하는 표 넷에서 코드만으로 알 수 없는 것을 적는다.
칸과 타입, 색인은 마이그레이션이, 상태 값은 엔티티의 enum 이 갖는다.
상태가 바뀌는 조건과 API 는 [커넥터 연결](../../connectors.md) 이 갖는다.

## connector_connection

사용자마다 커넥터 하나에 연결 하나를 둔다. 근거는 [ADR-043](../../../backend/docs/adr/ADR-043-커넥터는-plugin-의-connector-json-으로-선언하고-control-plane-은-범용-흐름만-갖는다.md) 이다.

- `fields` 는 JSON 텍스트 `{"values": {key: 값}, "secretPrefixes": {key: 앞 4자}}` 다. 비밀 칸의 원문과 해시는 넣지 않는다. 앞부분은 값이 16자 이상일 때만 넣는다. 16자 미만인 값은 앞 4자가 원문의 큰 부분이기 때문이다
- `undeclared_tools` 는 마지막 확인에서 MCP 서버가 낸 도구 가운데 manifest 의 `tools` 에 없던 수다. `schema: 1` 은 0 이다
- `vault_stored` 는 칸 값을 대시보드 plugin 의 보관 파일에 둔 적이 있는가다. 옛 연결은 연결 확인이 옛 profile 의 값을 보관 파일로 옮길 때 참이 된다
- `(user_id, connector_id)` 가 유니크다. 한 사람이 같은 커넥터를 둘 연결하지 못한다
- `agent_id`, `restart_required`, `desired_enabled` 는 연결을 에이전트에 붙이는 바인딩([ADR-083](../../adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md))이 생기며 쓰지 않는 칸이 됐다. 이전 이미지로 되돌릴 때를 위해 남겨 두고, 칸을 지우는 마이그레이션은 옛 커넥터 에이전트를 정리할 때 둔다. `agent_id` 의 유일 제약과 FK 는 남아 있고 비어 있는 값은 유일 제약에 걸리지 않는다
- 엔티티는 이 세 칸을 매핑하지 않는다. 새 행에서 `agent_id` 는 비고 두 boolean 칸은 기본값 거짓으로 저장된다. 옛 행의 값은 고치지 않아 그대로 남는다
- 해제해도 행은 남기고 `fields` 를 `{"values": {}, "secretPrefixes": {}}` 로 비운다. 지우는 경로는 없다
- 칸 값이 비밀이 아닌지는 DB 가 아니라 Control Plane 이 manifest 의 `secret` 으로 판정해 지킨다
- `fields` 를 MySQL `JSON` 타입이 아니라 문자열로 둔다. 칸 안을 SQL 로 찾을 일이 없고, 검사가 쓰는 H2 와 MySQL 의 JSON 리터럴 문법이 달라 이관 SQL 을 한 벌로 쓸 수 없다. 엔티티는 변환기로 record 로 읽는다
- V40 이전에 저장한 행은 앞부분의 원래 길이를 알 수 없어 `secretPrefixes` 가 비어 있다

`agent.connector_managed` 는 바인딩이 생기기 전에 연결마다 만든 옛 커넥터 에이전트를 표시한다. 지금은 새로 참이 되지 않는다.
이 값이 참인 에이전트는 일반 설정 편집과 공개 범위 변경을 막고 사용자당 에이전트 상한에 세지 않는다. 지우기는 받는다. 사용자가 새 방식으로 옮긴 뒤 그 에이전트를 지울 길이 이것뿐이다.
남아 있는 동안의 규칙은 [커넥터 설치](../connector-install.md) 의 「옛 커넥터 에이전트」 가 갖는다.

`agent.connector_attachments` 는 그 옛 커넥터 에이전트가 사진을 받는지다.
선언은 plugin 의 `connector.json` 에 있다. Control Plane 이 연결 확인과 관리자 반영 완료에서 선언한 toolset 이 켜진 것을 확인했을 때만 참으로 둔다.
옛 커넥터 에이전트가 아닌 에이전트에서는 쓰지 않는다.

## agent_connector_binding

에이전트에 연결을 붙인 것이다. 에이전트와 연결은 다대다다. 근거는 [ADR-083](../../adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md) 이다.
연결의 `status` 는 「값이 확인돼 쓸 수 있는가」 이고, 바인딩의 `status` 는 「그 에이전트의 profile 에 설치되고 반영됐는가」 다.

- `mcp_server` 는 붙일 때 manifest 가 선언한 MCP 서버 이름이다. 비밀이 아니다. 실행마다 카탈로그를 읽지 않고, 운영자가 카탈로그에서 커넥터를 빼도 그 profile 에 설치된 서버 이름을 잃지 않게 둔다. 마이그레이션이 만든 옛 바인딩은 비어 있고 연결 확인과 관리자 반영 완료가 채운다
- `restart_required` 는 공유 gateway 재시작 뒤 반영 완료를 기다리는가다. 재시작은 profile 마다 필요하므로 연결이 아니라 여기에 둔다
- `restart_required_since` 는 재시작이 필요해진 가장 늦은 설치 시각이다. 관리자가 재시작한 뒤에 다시 설치가 있었으면 반영 완료가 대기를 풀지 않게 하려고 둔다
- `apply_due_at` 은 반영 예정 시각이다. 재시작 없이 공유 gateway 의 MCP 설정 맞추기 주기가 반영할 설치(`reload_pending`)를 보냈을 때 적는다. 이 시각이 지나면 Control Plane 이 반영 맞추기를 스스로 한 번 돌리고, 돌리기 전에 비운다. `READY` 가 되면 비운다([ADR-20261007 / connector-live-reload](../../adr/ADR-20261007-connector-live-reload.md))
- `desired_enabled` 는 그 profile 에 설치가 성공해 반영 후보가 되었는가다. 설치를 시작하면 거짓이다. 참이어도 반영 확인 전에는 `PENDING` 이다
- `(agent_id, connection_id)` 가 유니크다(`uk_agent_connector_binding`). 한 에이전트에 같은 연결을 둘 붙이지 못한다
- `agent_id` 는 `agent` 를, `connection_id` 는 `connector_connection` 을 FK 로 가리킨다
- 떼면 행을 지운다. 떼기는 재시작을 기다리지 않아 남길 상태가 없다. 이력은 `connector_action` 이 갖는다
- 해제되지 않은 옛 연결에는 V80 이 그 연결 전용 에이전트와의 바인딩을 만들었다. 그 바인딩의 `restart_required_since` 는 연결의 `updated_at` 이다
- 옛 커넥터 에이전트(`agent.connector_managed` 가 참)의 바인딩만 그 에이전트를 켜고 끈다. `READY` 가 되면 켜고, `PENDING` 이 되면 끄고 사진 받기를 내린다. 다른 에이전트의 바인딩은 에이전트를 건드리지 않는다

## connector_action

커넥터 도구 호출 하나의 판정과, 승인이 필요했던 호출의 승인 줄이다. 근거는 [ADR-049](../../../backend/docs/adr/ADR-049-커넥터-도구-호출은-profile-plugin-의-hook-이-control-plane-에-물어-판정한다.md) 과 [ADR-050](../../../backend/docs/adr/ADR-050-커넥터-쓰기는-control-plane-이-승인-줄을-저장하고-승인한-인자로-한-번만-실행한다.md) 이다.

- `public_id` 는 화면과 모델에 보이는 승인 요청 번호다. 대화의 공개 식별자와 같은 방식이다([ADR-025](../../adr/ADR-025-대화는-주소에-공개-식별자를-쓰고-번호는-안에만-둔다.md))
- `agent_id` 는 판정한 실행의 에이전트다. 그 에이전트에 붙은 바인딩으로 판정했고, 승인하면 그 에이전트의 profile 에서 실행한다. 바인딩 전에 남은 옛 줄은 옛 커넥터 에이전트를 가리킨다
- `tool_name` 은 MCP 서버의 원래 도구 이름이고 `hermes_tool` 은 hook 이 받은 등록 이름이다. 대응 파일에서 찾지 못했거나 등록 이름과 맞는 것을 확인하지 못한 호출은 `tool_name` 을 비운다
- `risk` 와 `approval_mode` 는 판정 당시의 값이다. 정책을 읽지 못했거나, 선언이 없었거나, 연결이 준비되지 않아 거절한 호출은 비운다
- `passed` 는 hook 에 통과로 답했는가다. `decision` 이 `ALLOWED` 일 때만 참이다
- `status`, `args_json`, `expires_at` 은 승인 줄에만 있다. `args_json` 은 hook 이 보낸 글자 그대로다
- `args_sha256` 은 인자 글의 SHA-256 이다. 원문을 두지 않는 줄에서도 무엇을 불렀는지 맞춰 볼 수 있다
- `dedupe_key` 는 `v1-connector`, profile, 루트 session, session, `tool_call_id` 를 줄바꿈으로 이어 SHA-256 한 값이고 유니크다. 같은 호출이 다시 와도 줄이 하나다
- `conversation_id` 는 결과를 돌려줄 대화다. 대화 없는 실행이면 비운다
- `result_text` 의 `FAILED` 줄은 커넥터가 선언한 오류 계약이 있을 때만 `{"kind": "connector_error", "code", "details", "recovery"}` 를 담고 없으면 null 이다([ADR-092](../../adr/ADR-092-승인한-실행의-실패는-커넥터가-선언한-오류-코드와-복구-어휘와-정수-세부만-에이전트까지-전한다.md))
- 사용자가 거절한 것이 아니라 시스템이 실행하지 않고 끝낸 `REJECTED` 줄은 `error_code` 로 까닭을 남긴다. 값과 뜻은 `ConnectorAction` 의 상수가 갖는다. 승인할 때 다시 판정해 실행할 수 없는 줄, 연결을 해제하거나 다시 등록했거나 그 에이전트에서 연결을 뗀 줄, 사람이 다 읽지 못한 인자를 가진 줄이 여기 든다
- `result_delivered_at` 은 결과나 거절, 만료를 대화에 전한 시각이다. `agent_execution` 의 같은 이름 칸과 뜻이 같다
- 허용과 거절도 한 줄씩 남긴다. 사용자 수가 적어 양이 문제가 되지 않는다
- `NEEDS_APPROVAL` 인 줄은 `passed` 가 거짓이고 `status` 가 `PENDING` 으로 시작하며 `args_json` 과 `expires_at` 을 갖는다. 승인 엔진이 켜지기 전에 남은 `NEEDS_APPROVAL` 줄은 `status` 와 `args_json` 이 비어 있어 승인 줄로 다루지 않는다
- 외래 키는 `user_id` 와 `agent_id` 에만 둔다. 실행과 대화는 지워져도 이 줄을 남긴다
- 인자 원문은 주인에게만 보인다. 관리자 목록과 로그에는 싣지 않는다

## connector_tool_grant

사용자가 도구 하나에 준 상시 허락이다. 승인하면서 기간을 골라 준다. 근거는 [ADR-050](../../../backend/docs/adr/ADR-050-커넥터-쓰기는-control-plane-이-승인-줄을-저장하고-승인한-인자로-한-번만-실행한다.md) 이다.

- `expires_at` 은 비지 않는다. 무기한 허락은 없다
- `revoked_at` 은 사용자가 거두었거나 연결을 해제한 시각이다. 선언이 상시 허락을 닫아 정리가 거둔 시각도 이 칸이다
- `revoked_at` 이 비고 `expires_at` 이 지금보다 뒤인 줄만 유효하다
- `approval: always` 인 도구와 선언이 `"grant": false` 인 도구에는 만들지 않는다. 판정할 때도 그 도구는 허락을 보지 않고, 선언이 닫은 도구(또는 `approval` 이 `required` 가 아니게 된 도구)에 남은 줄은 주기 정리가 `revoked_at` 으로 거둔다([ADR-065](../../adr/ADR-065-외부로-나가는-도구는-상시-허락을-닫는-선언을-둔다.md))
- 같은 도구에 허락을 다시 주면 새 줄을 만든다. 유니크 제약은 없다
