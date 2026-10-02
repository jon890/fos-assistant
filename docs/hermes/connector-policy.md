# 도구 hook 과 승인

`pre_tool_call` hook 으로 MCP 도구 호출을 막을 때 Hermes 가 지키는 것과 지키지 않는 것이다.
2026-10-01 에 `v2026.9.24`(제품 판 `0.21.5`)의 소스를 읽고, 격리한 환경에서 stdio 대역 MCP 서버로 실행해 확인했다.
이 계약 위에 세운 결정은 [ADR-049](../adr/ADR-049-커넥터-도구-호출은-profile-plugin-의-hook-이-control-plane-에-물어-판정한다.md) 과 [ADR-050](../adr/ADR-050-커넥터-쓰기는-control-plane-이-승인-줄을-저장하고-승인한-인자로-한-번만-실행한다.md) 에 있다.

실제 모델 turn, native `delegate_task` 의 전체 왕복, 공유 gateway 의 전체 HTTP 왕복은 실행하지 않았다. 그 셋은 소스로만 확인했다.

## MCP 도구의 등록 이름

Hermes 는 MCP 도구를 `mcp__<서버>__<도구>` 로 등록한다.

- 서버 이름과 도구 이름에서 `[A-Za-z0-9_]` 밖의 글자를 모두 `_` 로 바꾼다. `-` 도 바뀐다
- 이은 이름이 64자를 넘으면 앞 55자에 `_` 와 그 이름 전체를 SHA-256 한 16진수의 앞 8자를 붙인다

**등록 이름에서 원래 이름을 되찾을 수 없다.** 글자를 바꾸고 줄이므로 서로 다른 도구가 같은 등록 이름이 될 수 있다.
그래서 원래 이름에서 등록 이름을 계산해 대응을 적어 두고, 등록 이름으로 그 표를 찾는다.

근거: [`tools/mcp_tool_schema.py` 의 `mcp_prefixed_tool_name`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/mcp_tool_schema.py#L150)

## hook 이 받는 것

`pre_tool_call` 은 `tool_name`, `args`, `task_id`, `session_id`, `tool_call_id` 를 받는다. `turn_id`, `api_request_id`, `middleware_trace` 도 온다.
`args` 는 JSON 문자열이 아니라 `dict` 다. callback 에 `**kwargs` 를 두면 칸이 늘어도 깨지지 않는다.

| 호출 경로 | hook 이 받는 이름과 인자 | 확인 |
| --- | --- | --- |
| 모델이 MCP 도구를 직접 부른다 | 등록 이름과 그 도구의 인자 | 실행 |
| 중계 도구 `tool_call` 로 MCP 도구 하나를 부른다 | 바깥 이름이 아니라 안쪽 등록 이름과 `arguments` | 실행 |
| `tool_search`, `tool_describe` | 그 이름 그대로. MCP `tools/call` 은 일어나지 않는다 | 소스 |
| native `delegate_task` 의 자식 | 자식도 같은 executor 를 쓴다. `session_id` 는 자식의 것이다 | 소스 |
| 공유 gateway 의 `/v1/runs` | 요청 profile 의 plugin manager 를 고른다 | 소스, profile 둘의 hook 문맥 실행 |
| `execute_code` 안의 도구 호출 | hook 을 거친다. 넘어오는 식별자는 `task_id` 뿐이다 | 소스 |
| plugin 의 `PluginContext.call_mcp` | **hook 을 거치지 않는다.** 서버 정의의 `mcp_allowlist` 만 본다 | 소스 |

중계 한 건에 hook 이 두 번 걸리지 않는다. executor 가 중계를 먼저 풀고 hook 을 적용한 뒤 dispatcher 에는 hook 을 건너뛰라고 넘긴다.

근거: [executor 의 중계 해석](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/agent/tool_executor.py#L392),
[dispatcher 의 중계 처리](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/model_tools.py#L707),
[plugin 의 직접 MCP 호출](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/hermes_cli/plugins.py#L509),
[코드 실행 RPC](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/code_execution_rpc.py#L28)

## 막히는 것과 통과하는 것

같은 stdio MCP 서버에서 실행으로 확인했다.

| hook 의 결과 | MCP 요청 | 모델이 받는 도구 결과 |
| --- | --- | --- |
| `{"action": "block", "message": "<글>"}` | 없다 | `{"error": "<글>"}` |
| `None` | 나간다 | 서버의 결과 |
| `{"action": "block"}` (글 없음) | **나간다** | 서버의 결과. 유효한 차단이 아니라 무시된다 |
| callback 이 예외를 던진다 | 없다 | callback 이름, 예외 종류와 본문 일부가 든 오류 |
| callback 이 제한 시간을 넘긴다 | 없다 | 시간 초과 오류 |
| hook dispatcher 자체가 예외를 낸다 | **나간다** | 서버의 결과. 바깥 처리가 예외를 삼킨다 |

**hook 은 모든 실패에서 닫히지 않는다.**
callback 안의 예외는 호출을 막지만, plugin 을 읽지 못했거나 dispatcher 가 실패하면 호출이 판정 없이 나간다.

`block` 은 도구 한 번의 오류 결과다. run 을 실패로 바꾸지 않고 자식을 끝내지도 않는다. 모델은 그 결과를 읽고 turn 을 이어 간다.

예외의 본문 일부가 모델에게 가므로 callback 은 비밀값이 섞일 수 있는 예외를 그대로 던지지 않는다. 잡아서 정해 둔 글로 막는다.

## 제한 시간

| 설정 또는 자원 | 뜻 |
| --- | --- |
| `plugins.hook_callback_timeout` | callback 하나의 제한. 기본 30초, 최대 600초. 0 은 제한 없음이다 |
| callback 여럿 | 차례로 돈다. 30초가 hook 전체의 상한은 아니다 |
| 제한을 넘긴 callback | 스레드를 강제로 끝내지 않는다. 그 안의 HTTP 요청은 계속 갈 수 있다 |
| 넘긴 뒤 | 같은 manager 의 callback 을 기본 60초 동안 억제하고 그동안 사전 hook 은 막는다 |
| run executor | API run 은 공유 thread pool 의 자리를 쥔다. hook 이 오래 기다리면 pool 과 run 한도가 준다 |

제한을 넘긴 뒤에도 요청이 서버에 닿을 수 있으므로, hook 이 부르는 쪽은 같은 호출이 두 번 와도 줄이 하나여야 한다.
profile 둘의 callback 을 동시에 돌렸을 때 한쪽이 0.4초 걸리는 동안 다른 쪽은 기다리지 않았다.

근거: [callback 차단과 시간 제한](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/hermes_cli/plugins_dispatch.py#L209),
[dispatcher 바깥 예외](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/model_tools.py#L765),
[executor 바깥 예외](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/agent/tool_executor.py#L651)

## 도구를 모델에게서 빼는 설정

`mcp_servers.<서버>.tools.include` 와 `tools.exclude` 가 있다. 접두사가 붙기 전의 원래 도구 이름과 glob 을 받는다.

| 설정 | `read_item`, `write_item` 의 등록 |
| --- | --- |
| `include: []` | 둘 다 등록하지 않는다 |
| `exclude: ["write*"]` | `read_item` 만 등록한다 |
| `include` 와 `exclude` 에 같은 이름 | `include` 가 이긴다 |

이 설정은 모델에 등록할 도구만 줄인다. MCP 서버의 `tools/call` 권한은 바꾸지 않는다.

근거: [도구 필터](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/mcp_tool_registration.py#L210)

## 내장 승인

hook 이 `{"action": "approve", "message": "<사유>", "rule_key": "<키>"}` 를 돌려주면 Hermes 가 그 도구를 사람 승인으로 넘긴다.

| 확인 | 결과 |
| --- | --- |
| 승인 대기 | `approval.request` 사건이 나오고 run 이 `waiting_for_approval` 이 된다 |
| 한 번 승인 | 원래 호출이 나간다 |
| 거절, 시간 초과 | 도구 결과가 차단 글이 된다. run 은 실패가 아니다 |
| `approvals.mode: off` | **같은 hook 이 사람 없이 통과한다** |
| 기다리는 시간 | `approvals.timeout`, 기본 300초 |

- 승인 함수는 도구 이름, plugin 의 글, 승인 키만 받는다. **도구 인자를 받지 않는다**
- session 승인과 영구 승인이 `rule_key` 단위로 쌓인다. 키를 주지 않으면 도구 이름이 키다
- 시간을 넘기면 그 도구는 거절되고 다시 실행되지 않는다

그래서 내장 승인으로는 「승인한 인자 그대로 한 번만 실행한다」 를 보장하지 못한다.

근거: [approve directive](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/hermes_cli/plugins.py#L1934),
[임의 도구 승인 함수](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/approval.py#L1095),
[승인 우회와 저장된 승인](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/approval.py#L951)

## 배포한 뒤 확인할 것

아직 확인하지 못했다. 실행 방법은 운영 저장소가 갖는다.

- 떠 있는 공유 gateway 가 profile 의 `fos-ctx` 를 새 판으로 바꾼 뒤 재시작 없이 새 코드를 읽는지
- native 자식과 공유 gateway 에서 실제 커넥터 호출이 hook 을 거치는지. 대역 서버의 호출 기록과 `connector_action` 을 견준다
- Control Plane 이 내려가 있을 때 연결용 에이전트의 도구가 막히는지

## 승인 방식 `smart` 는 추론 모델에서 `manual` 과 같아진다

`approvals.mode` 가 `smart` 이면 위험한 모양으로 분류된 명령마다
보조 LLM 에 한 번 물어 `APPROVE`, `DENY`, `ESCALATE` 가운데 하나를 받는다.
`tools/approval.py` 의 `_smart_approve` 가 그 호출을 갖는다.

**그 호출이 `max_tokens=16` 으로 걸려 있다.**
받은 내용을 대문자로 바꿔 세 낱말과 정확히 비교하고, 어느 것과도 맞지 않으면 `escalate` 로 읽는다.

추론 모델은 답을 내기 전에 생각을 먼저 내보낸다.
그 문장이 16 토큰을 넘으면 거기서 잘리고, 잘린 문장은 세 낱말 어느 것과도 맞지 않는다.
**그래서 모든 판정이 `escalate` 가 된다.**

`nvidia/nemotron-3-super-120b-a12b` 로 실측했다. 받은 내용이 이것이다.

```text
We need to decide: The user gave a command: python3 -c print
```

위험한 모양으로 분류된 명령 여덟 가지를 넣어 모두 `escalate` 를 받았다.
`python3 -c "print(1+1)"` 처럼 무해한 것도, `curl ... | sh` 처럼 실제로 위험한 것도 같았다.
요청이 모델을 OpenAI codex 계열로 덮어쓰면 같은 명령이 `approve` 로 나온다.

### 알아채기 어려운 이유

오류가 아니다. 예외도 로그의 실패 표시도 나지 않는다.
`escalate` 는 「사람에게 물어라」라는 정상 판정이고, `smart` 는 그때 `manual` 과 같은 길로 간다.
**설정에는 `smart` 라고 적혀 있으므로 설정만 읽어서는 알 수 없다.**

보조 LLM 호출 자체가 실패할 때도 같은 모양이 된다.
`_smart_approve` 의 예외 처리가 `escalate` 를 돌려주기 때문이다.
그쪽은 경고 한 줄을 남기지만, 토큰이 잘리는 쪽은 그 줄도 남기지 않는다.

### 부르는 쪽이 보는 것

사람이 승인할 자리가 없는 경로에서는 실행이 `waiting_for_approval` 로 멈춘다.
`approvals.timeout` 이 지나면 그 명령이 거절되고, 에이전트는 다른 길을 찾아 실행을 마친다.

**그래서 최종 상태가 `failed` 가 아니라 `completed` 다.**
호출한 쪽은 성공으로 받지만, 실제로는 에이전트가 하려던 것을 하지 못하고 우회한 결과다.
실행 시간이 `approvals.timeout` 만큼 길어지는 것이 유일하게 겉으로 드러나는 신호다.

### 같은 부류의 계약 둘

**`command_allowlist` 는 프로세스 전역이고 import 시점에 한 번만 읽는다.**
`tools/approval.py` 가 모듈을 읽을 때 한 번 불러 결과를 프로세스 전역 집합에 담는다.
실행마다 다시 읽지 않는다.

한 프로세스가 여러 profile 을 서비스하는 구성이면,
그 프로세스의 Hermes home 이 아닌 profile 의 설정에 적은 항목은 실리지 않고,
그 home 의 설정에 적은 항목은 모든 profile 에 적용된다.
**적어 두어도 아무 일도 일어나지 않으므로 설정을 읽어서는 어느 쪽인지 알 수 없다.**

반면 `approvals.mode` 와 `approvals.deny` 는 판정할 때마다 그 실행의 profile 설정을 다시 읽는다.
값을 바꾸면 프로세스를 다시 띄우지 않아도 반영된다.

**`approvals.mode` 의 값 `off` 는 따옴표가 없으면 YAML 이 거짓으로 읽는다.**
Hermes 가 그 거짓을 다시 `off` 로 되돌려 주므로 결과는 같다.
받는 값은 `manual`, `smart`, `off` 셋뿐이고, 그 밖의 문자열은 경고를 남기고 `manual` 이 된다.
