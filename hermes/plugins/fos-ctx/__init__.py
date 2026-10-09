"""Control Plane MCP 호출 인자에 run 맥락 `_fos_ctx` 를 덮어쓰고 HMAC 으로 서명한다.

Control Plane 의 `agent_*` 도구는 이 값으로 부모 실행을 찾는다. 계약의 정본은 fos-assistant
`hermes/plugins/fos-ctx/README.md` 의 「`_fos_ctx` 계약」 이고, 이 파일은 그 계약을 그대로 따른다.

- key 는 그 profile 의 MCP 토큰을 SHA-256 한 소문자 16진수 문자열의 UTF-8 바이트다.
  서버는 토큰 원문 대신 이 해시만 저장하므로 같은 key 를 갖는다.
- 서명할 글은 `v1`, 서버 쪽 도구 이름, 루트 session, session, tool_call_id 를 줄바꿈 하나로 잇는다.
  도구 인자는 넣지 않는다. Python 과 Java 의 JSON 직렬화를 글자까지 맞추기 어렵다.

Hermes 는 hook 이 돌려준 `args` 를 원래 인자에 얕게 병합하고 hook 의 키가 뒤에 온다.
그래서 모델이 같은 키를 넣어도 이 값이 이긴다. hook 이 `None` 을 돌려주면 서명 없이 나가고,
hook 이 예외를 던지면 호출이 막힌다.

`agent_*` 와 `memory_read`, `artifact_write`, `follow_up_propose`, `memory_remember` 는 서명하지 못하면 막는다.
서버도 서명 없는 호출을 거절하지만, 여기서 막으면 모델이 받는 오류가 원인을 말한다.
그 밖의 Control Plane 도구는 서명할 수 있으면 붙이고 없으면 원래 인자 그대로 보낸다.

## 자식 session 등록

`delegate_task` 의 자식은 부모 run 이 끝난 뒤에도 백그라운드로 돌 수 있다.
그때 Control Plane 은 자식의 MCP 호출에서 요청자를 부모 run 으로 찾지 못하므로,
자식을 만드는 순간 `subagent_start` hook 이 Control Plane 에 자식 session 의 부모와 루트를 등록한다.

- Hermes 는 자식을 만드는 `_build_children` 안에서 부모 스레드로 이 hook 을 동기로 부른다.
  그래서 등록은 자식의 첫 도구 호출보다 먼저 끝난다
- 주소는 환경 변수 `FOS_CTX_SUBAGENT_URL` 이 갖는다. 없으면 등록하지 않는다
- 서명 key 는 `_fos_ctx` 와 같다. 서명할 글은 `v1-subagent`, 부모의 루트 session, 부모 session,
  자식 session 을 줄바꿈 하나로 잇는다. 인증 헤더는 MCP 와 같은 profile 토큰이다
- 제한 시간 `REGISTER_TIMEOUT` 초로 부르고, 연결 실패와 5xx 에만 한 번 더 부른다
- 실패하면 로그만 남긴다. 예외를 내지 않는다. 등록이 없는 자식의 호출은 Control Plane 이 거절한다
- 토큰, 서명, 본문은 로그에 남기지 않는다

## 커넥터 정책

profile 디렉터리에 이름 대응 파일 `.fos-connector-tools.json` 이 있으면 그 profile 에서는 커넥터 MCP 도구 호출마다
Control Plane 에 묻고 답대로 한다(fos-assistant ADR-049, ADR-083).
계약의 정본은 fos-assistant `backend/docs/flow.md` 의 「도구 호출 판정」 이고, 이 파일은 그 계약을 그대로 따른다.

대응 파일의 `isolated` 칸이 profile 의 방식을 정한다. 칸이 없으면 참으로 읽는다.

- 옛 설치 profile(`isolated` 가 참): 커넥터마다 만든 전용 profile 이다. Control Plane MCP 가 없고 커넥터 서버만 있다.
  그래서 대응에 없는 `mcp__` 도구와 `execute_code` 를 막는다
- 바인딩 profile(`isolated` 가 거짓): 일반 에이전트의 profile 에 커넥터를 붙인 것이다.
  Control Plane MCP 와 운영자가 넣은 다른 MCP 서버가 함께 있으므로 대응의 서버와 맞는 도구만 묻는다.
  나머지 도구는 대응 파일이 없는 profile 과 같게 둔다. 대응에 그 profile 의 모든 커넥터 서버가 실려 있다는
  대시보드 plugin 의 약속에 기댄다

두 방식에 같이 걸리는 것이다.

- 대응 파일이 없으면 이 절의 처리를 하지 않는다. 일반 에이전트의 도구는 건드리지 않는다
- 대응 파일을 읽지 못하면 `mcp__` 도구와 `execute_code` 를 모두 막는다. Control Plane MCP 의 도구도 막는다.
  옛 설치 profile 일 수 있고, 옛 설치 profile 에는 Control Plane MCP 가 없다. `isolated` 가 boolean 이 아닌 것도 읽지 못한 것이다
- 대응 파일의 서버를 Control Plane MCP 의 접두사보다 먼저 본다. 대응 파일의 서버와 맞는 도구는 등록 이름이
  Control Plane MCP 의 접두사로 시작해도 판정으로 보내고 `_fos_ctx` 를 붙이지 않는다
- 대응 파일의 어느 서버와도 맞지 않는 Control Plane MCP 도구는 위와 같이 `_fos_ctx` 를 붙인다
- 서버는 등록 이름이 `tools` 에 있는 서버를 먼저 고르고, 없을 때만 `prefix` 가 맞는 서버를 고른다.
  `prefix` 가 여럿 맞으면 가장 긴 것을 고른다. 서버 `a` 와 `a__b` 가 함께 있을 때 `a__b` 의 도구가 `a` 로 읽히지 않는다
- session 이나 tool_call_id 가 없으면 막는다
- 주소는 환경 변수 `FOS_CTX_POLICY_URL` 이 갖는다. 주소나 토큰이 없으면 막는다
- 인자는 키를 정렬하고 공백 없이 직렬화한 글로 보내고 그 글을 서명한다. 서명할 글은 `v1-connector-policy`,
  등록 이름, 루트 session, session, tool_call_id, 인자 글의 SHA-256 을 줄바꿈 하나로 잇는다
- 인자 글의 UTF-8 바이트가 `POLICY_ARGS_MAX_BYTES` 를 넘으면 Control Plane 에 보내지 않고 막는다
- 제한 시간 `POLICY_TIMEOUT` 초로 한 번만 부른다. 기다리는 동안 run 의 스레드가 묶이므로 다시 부르지 않는다
- 답이 200 의 `allow` 일 때만 통과한다. 200 의 `block` 이고 글이 있으면 그 글로 막고, 그 밖은 정해 둔 글로 막는다
- 막을 때는 늘 비지 않은 글이 든 `block` 을 돌려준다. Hermes 는 글이 없는 `block` 과 `None` 을 통과로 읽는다
- 어떤 예외든 잡아 정해 둔 글로 막는다. 예외를 던지면 본문 일부가 모델에게 간다
- 토큰, 서명, 인자, 응답 본문은 로그에 남기지 않는다

옛 설치 profile 에만 걸리는 것이다.

- `execute_code` 는 막는다. 실행 맥락 없이 도구를 부르는 경로다
- 대응 파일의 어느 서버 `prefix` 와도 맞지 않는 `mcp__` 도구는 막는다

바인딩 profile 에만 걸리는 것이다.

- `execute_code`, 다른 MCP 서버의 도구, 내장 도구는 건드리지 않는다. 관리자가 켠 코드 실행을 커넥터 때문에 끄지 않는다.
  `execute_code` 안에서 부른 커넥터 도구는 hook 에 session 이 오지 않아 위의 규칙으로 막힌다
- 커넥터 도구의 결과가 글이면 `transform_tool_result` hook 이 `<external-data>` 로 감싼다. 모양은 Control Plane 의
  `ExternalData.wrap` 과 같다. 판정이 막은 호출은 이 hook 에 닿지 않으므로, 여기 닿은 오류 글은 커넥터 서버가 낸 외부 데이터다.
  글이 아닌 결과는 그대로 둔다. 예외는 잡아 None 을 돌려준다. Hermes 는 그때 원래 결과를 그대로 넘기고,
  MCP 결과에 거는 `<untrusted_tool_result>` 감싸기는 남는다

`skill_manage` 는 막는다. 올린 스킬은 Control Plane 이 쓰고 Hermes 는 읽기만 하는데,
모델이 같은 이름의 로컬 스킬을 만들면 로컬이 먼저 선택되어 올린 스킬이 가려진다.
읽기 전용 마운트로는 이것을 막지 못한다. 근거는 fos-assistant ADR-034 가 갖는다.

이 plugin 은 profile 마다 `profiles/<이름>/plugins/fos-ctx/` 에 두고 그 profile 에서 켠다.
Hermes 는 plugin 을 HERMES_HOME 마다 따로 읽어, root 에 두면 기본 profile 에만 걸린다.
"""

from __future__ import annotations


from .connector_policy import (
    ARGS_TOO_LARGE_MESSAGE,
    CODE_EXECUTION_MESSAGE,
    CODE_EXECUTION_TOOL,
    CONNECTOR_TOOL_MAP,
    CONTEXT_BLOCK_MESSAGE,
    MCP_PREFIX,
    POLICY_ARGS_MAX_BYTES,
    POLICY_BLOCK_MESSAGE,
    POLICY_TIMEOUT,
    POLICY_URL_ENV,
    UNKNOWN_SERVER_MESSAGE,
    _block,
    _connector_server,
    _post_json,
    connector_policy,
    read_tool_map,
)

from .context import (
    CTX_VERSION,
    KEY_NAME,
    MAX_DEPTH,
    POLICY_VERSION,
    REGISTER_VERSION,
    _read_token,
    _state_db_path,
    build_context,
    logger,
    root_session,
    sign,
    sign_policy,
    sign_subagent,
    signing_key,
)

from .hooks import (
    BLOCK_MESSAGE,
    DELEGATE_IMAGE_MESSAGE,
    EXTERNAL_DATA_CLOSE,
    EXTERNAL_DATA_NOTICE,
    REQUIRED_PREFIX,
    REQUIRED_TOOLS,
    SKILL_MANAGE_MESSAGE,
    SKILL_MANAGE_TOOL,
    TOOL_PREFIX,
    _control_plane_context,
    _delegate_images_allowed,
    _remote_images_only,
    pre_tool_call,
    transform_tool_result,
    wrap_external_data,
)

from .subagent import (
    REGISTER_TIMEOUT,
    REGISTER_URL_ENV,
    _post,
    build_registration,
    subagent_start,
)




def register(ctx):
    ctx.register_hook("pre_tool_call", pre_tool_call)
    ctx.register_hook("subagent_start", subagent_start)
    ctx.register_hook("transform_tool_result", transform_tool_result)
    logger.info("fos-ctx: Control Plane MCP 호출에 _fos_ctx 를 서명해 붙이고 skill_manage 를 막고 자식 session 을 등록하고 "
                "커넥터 도구 호출을 Control Plane 에 묻고 바인딩 profile 의 커넥터 도구 결과를 감싼다")
