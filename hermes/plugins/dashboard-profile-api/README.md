# dashboard-profile-api plugin

Hermes 대시보드에 설치하는 plugin 이다. Control Plane 이 profile 과 도구, 스킬, 플러그인을 HTTP 로 다루는 경로를 연다.
설치와 운영 값은 [`hermes/README.md`](../../README.md), Hermes 쪽 동작은 [`hermes/docs/hermes-contract.md`](../../docs/hermes-contract.md) 가 갖는다.

## dashboard-profile-api 가 여는 것

인증은 모두 `Authorization: Bearer <대시보드 서비스 토큰>` 이고 없거나 틀리면 401 이다.
Control Plane 이 이 경로들을 부르는 순서와 뜻은 부르는 쪽 문서가 갖는다. 플러그인 경로는 [플러그인 설치](../../../docs/features/connector.md) 의 「대시보드 plugin 계약」 이, 에이전트 만들기는 [에이전트](../../../docs/features/agent-skill.md) 가 갖는다.

| 요청 | 쓰임 | 본문이나 query | 성공 | 실패 |
| --- | --- | --- | --- | --- |
| `GET /api/profiles` | 이름이 이미 있는지 본다 | 없음 | 200 | |
| `POST /api/profiles` | profile 을 만들고 같은 요청 안에서 설정 틀과 서명 plugin 과 관리 표식을 둔다 | `name`, `no_skills`, `description` 셋을 받고 다른 키는 거절한다. Control Plane 은 `{name, no_skills: true}` 를 보낸다 | 200. 이때 안전한 도구 목록, Control Plane MCP 등록(토큰 값 없음), 서명 plugin 켜짐, 관리 표식이 모두 있다 | 400 이름 규칙, 이미 있는 이름(Hermes 는 409 가 아니라 400), 허용하지 않는 키. 500 틀 적용 실패(만든 것은 지웠다) |
| `DELETE /api/profiles/<이름>` | 관리 표식이 있는 profile 을 지운다 | 없음 | 200 | 401 관리 표식이 없는 profile. 404 없는 profile. 두 번째 호출의 404 는 「이미 지움」 으로 읽는다 |
| `PUT /api/env` | 그 profile 의 `.env` 에 정해 둔 key 한 줄을 쓴다 | `{profile, key, value}`. key 는 `API_SERVER_KEY`, `API_SERVER_MODEL_NAME`, `MCP_FOS_ASSISTANT_API_KEY` 뿐. 운영자 env 이름은 쓰지 않고 성공으로 답한다 | 200. 운영자 env 이름은 `{profile, key, restart_required: false}` | 400 다른 key, 카탈로그 플러그인의 `fields[].env`(칸 값은 보관 파일에만 둔다), `default`, 형식. 404 없는 profile |
| `DELETE /api/env` | 관리 profile 의 플러그인 칸 key 만 지운다 | `{profile, key}` | `{profile, key, restart_required}` | |
| `GET /api/connectors/catalog` | 운영 목록에 있고 검증을 통과한 플러그인의 manifest 를 낸다 | query `include=standard` 가 있으면 `schema: 3` 항목도 싣는다 | `[{id, schema, title, description, icon, link, readme_url, link_kind, fields[], verify, mcp_server, toolsets, attachments, single_binding, tools, skills, owner_browser, owner_browser_login_url, unknown_errors}]`. 아래 「카탈로그 응답」 이 칸을 갖는다 | |
| `POST /api/connectors/<id>/call` | 후보 값이나 보관 파일의 값으로 그 플러그인의 선택지 도구나 확인 도구를 한 번 부른다 | `{tool, values}` 또는 `{tool, vault}`. 둘 가운데 정확히 하나다. 단추 연결을 선언한 플러그인은 `vault` 와 함께 `oauth` 를 받는다. `current` 면 보관 파일과 OAuth 파일의 통로 주소를, `pending` 이면 대기 보관 파일 값과 대기 OAuth 파일의 통로 주소를 `token_env` 로 넣는다. `pending` 은 본 자리 보관 파일이 없어도 된다. `oauth` 가 없으면 통로 주소는 빈 값이다. 사용자 브라우저를 쓰는 플러그인이면 Control Plane 이 `owner_browser` 를 더한다 | `{ok: true, result}` 또는 `{ok: false, error: <공통 어휘>}` | 400 그 플러그인의 보관 파일이 없다 |
| `POST /api/connectors/<id>/execute` | Control Plane 이 승인한 호출을 그 profile 의 값과 받은 인자로 한 번 실행한다 | `{profile, hermes_tool, args}`. 중계 주소는 본문으로 받지 않고 설치한 서버 정의에서 읽는다 | `{ok: true, result}` 또는 `{ok: false, error: <공통 어휘>}`. 도구를 찾아 부른 응답에는 부르기 직전 `tools/list` 의 `read_only`(그 도구의 `readOnlyHint` 가 참인가)를 더한다 | 504 실행됐는지 모른다 |
| `POST /api/connectors/<id>/events` | 사건 감지를 선언한 플러그인의 조회 도구를 그 profile 의 값으로 불러 새 항목을 낸다 | `{profile, since}`. `since` 는 0 이상의 정수 초다 | `{ok: true, items: [{id, received_at, labels}], truncated}` 또는 `{ok: false, error: <공통 어휘>}` | 404 사건 감지를 선언하지 않았거나 설치하지 않은 플러그인 |
| `POST /api/connectors/<id>/prepare` | Control Plane 이 금융 준비를 위해 그 플러그인의 준비 도구(`execution_guard.prepare_tool`)를 한 번 부른다. 주문을 보내지 않는다 | `{profile, hermes_tool, args}` | `{ok: true, result: {v, executionArgs, summary}}` 또는 `{ok: false, error: <공통 어휘>, stage, code}`. `stage` 는 멈춘 검사의 고정 단계 코드, `code` 는 플러그인이 선언한 오류 코드나 SDK 입력 거절 코드이고 없으면 `-` 다. 인자, 계좌, 원격 응답은 싣지 않는다. Control Plane 은 두 칸을 로그에만 남긴다 | 409 금융 보호 맥락을 확인하지 못했다. 504 시간 제한을 넘겼다 |
| `POST /api/connectors/<id>/tools` | 표준 플러그인(`schema: 3`)의 연결 확인. 후보 값이나 보관 파일의 값으로 MCP 서버에 붙어 `tools/list` 만 받는다. 도구는 부르지 않는다 | `{values}` 또는 `{vault}`. 둘 가운데 정확히 하나다. 시간 제한과 동시 한도는 `call` 과 같다 | `{ok: true, tools: [{name, read_only}]}`. `read_only` 는 `readOnlyHint` 가 참일 때만 참이다. 붙지 못하면 `{ok: false, error: "unavailable"}`, 원격 서버가 401 이나 403 으로 답하면 `credential_rejected` 다 | 400 `schema: 3` 이 아닌 플러그인, 본문 모양 |
| `GET /api/connectors?profile=<p>` | 플러그인의 상태를 읽는다 | query `profile` | `{profile, policy_hook, connectors: [{plugin, enabled, configured, mode}]}`. 설치된 항목의 `mode` 는 항상 `bind` 다 | 401 관리 표식과 플러그인 표식이 모두 없다 |
| `PUT /api/connectors` | profile 에 플러그인을 붙이고 뗀다 | `enabled: true` 는 `{profile, plugin, enabled: true, bind: {vault}, sandbox_owner?, owner_browser?}` 이며 `bind.vault` 가 필수다. Control Plane 이 그 에이전트의 `sandbox_owner` 를 함께 보낸다. `owner_attachments_env` 를 선언한 플러그인은 `sandbox_owner` 가 필요하다. `owner_output_env` 를 선언한 플러그인은 `sandbox_owner` 와 `connector_output_root` 가 있고 profile 이 정책에 등록됐을 때만 출력 디렉터리를 받고, 아니면 빈 값으로 붙는다. 떼면 그 디렉터리를 지운다. `owner_browser_env` 를 선언한 플러그인에는 Control Plane 이 `owner_browser`(그 바인딩의 중계 주소, 중계가 꺼졌으면 빈 값)를 더하고 plugin 이 그 값을 서버 정의에 넣는다. 선언하지 않은 플러그인은 이 값을 쓰지 않는다. `enabled: false` 는 `{profile, plugin, enabled: false}` 로 바인딩을 뗀다 | `{profile, plugin, enabled, changed, restart_required, plugin_updated, reload_pending}`. 붙이기는 `skipped_skills: [{name, reasons}]` 도 낸다(빠진 에이전트용 스킬과 까닭, 이름 순, 「바뀐 것 없음」 에도 있다). 떼기와 옛 대시보드 plugin 은 이 칸을 내지 않는다. 서버 이름만 더하고 뺀다 | 400 보관 파일이 없거나 다른 플러그인의 것이다, `enabled: true` 인데 `bind.vault` 가 없거나 `owner_browser` 가 문자열이 아니거나 중계 주소 모양이 아니다. 401 표식. 409 운영자 설정과 충돌한다, 사진 도구에 실행 공간 정책이나 등록 profile 이 없다, `owner_attachments_env` 를 선언한 플러그인에 실행 공간 정책이 없거나 그 주인의 첨부 디렉터리를 링크 없이 확인하지 못했다, `sandbox_required` 를 선언한 플러그인을 붙이는데 profile 이 실행 공간 정책에 없다. 실행 공간 사유의 409 는 본문 `code` 가 `sandbox_unavailable` 이고, 운영자 설정 충돌은 그 `code` 를 넣지 않는다. 400 그 플러그인에 `sandbox_owner` 가 없다 |
| `PUT /api/connector-vault` | 연결의 칸 값을 보관 파일 하나에 쓴다 | `{vault, connector, values}` 와 선택 키 `oauth`(boolean). `values` 는 `fields[].key` 를 키로 한 값이다. `oauth` 가 참이면 단추로 채우는 칸(`oauth.fields`)이 비어도 받고, 거짓이거나 없으면 그 연결의 OAuth 파일을 지운다(붙여 넣기로 바꾸기) | `{ok: true}` | 400 형식, 운영 목록에 없는 플러그인, 칸 선언과 맞지 않는 값, `oauth` 선언이 없는 플러그인의 `oauth: true`. 409 같은 이름의 보관 파일이 다른 플러그인의 것이다. 503 「key 필수」 인데 보관 파일 key 를 쓸 수 없거나 쓰기가 실패했다 |
| `DELETE /api/connector-vault` | 보관 파일 하나와 그 OAuth 파일, 대기 파일을 지운다 | `{vault}` | `{changed}`. 없었으면 `false` 다 | 400 형식 |
| `POST /api/connector-oauth/authorize-url` | 단추 연결의 제공자 로그인 주소를 만든다 | `{connector, mode, client_id, redirect_uri, state}` 와 선택 키 `app_slug`, `code_challenge`. GitHub `install` 은 `app_slug` 로 App 설치 화면 주소에 `state` 만 붙인다. `authorize` 는 PKCE challenge(`S256`)를 붙이고, Google 은 선언 scope, `access_type=offline`, `prompt=consent`, `include_granted_scopes=false` 를 더한다 | `{url}` | 400 형식, 제공자와 맞지 않는 `mode`. 404 `oauth` 를 선언하지 않은 플러그인 |
| `POST /api/connector-oauth/begin` | 단추 흐름을 시작한다 | `{vault, connector, values}`. `values` 는 단추 밖 칸 값이다 | `{nonce}`. 앞 흐름의 대기 파일은 덮는다 | 400 칸 값, 단추로 채우는 칸(`oauth.fields`)의 값. 409 다른 플러그인의 보관 파일 |
| `POST /api/connector-oauth/exchange` | 받은 코드를 토큰으로 바꾼다 | `{vault, connector, nonce, client_id, client_secret, code, redirect_uri, token_url}` 와 선택 키 `code_verifier` | `{outcome}`. `ok` 면 `access_token`, `expires_at`(epoch 초나 null)을 더하고 대기 OAuth 파일에 refresh token 과 `token_url` 을 쓴다. refresh token 이 없는 응답은 `app_rejected`, Google 이 선언 scope 를 다 주지 않으면 `scope_denied` 이고 파일을 쓰지 않는다 | 409 `nonce` 가 다르다 |
| `POST /api/connector-oauth/commit` | 대기 파일 둘을 본 자리로 옮긴다 | `{vault, nonce, token_url}`. `token_url` 은 본 연결 표식 주소이고 본 자리 OAuth 파일에 쓴다. OAuth 파일을 먼저 쓰고, 보관 파일 쓰기가 실패하면 OAuth 파일을 앞 상태로 되돌린다 | `{ok: true}` | 409 `nonce` 가 다르거나 교환 전이다 |
| `POST /api/connector-oauth/discard` | 실패한 흐름을 접는다 | `{vault, connector, nonce, client_id, client_secret}` 와 선택 키 `access_token`. GitHub 은 그 access token 하나를 취소하고 Google 은 취소하지 않는다 | `{ok: true}` | 409 다른 흐름의 대기 파일 |
| `POST /api/connector-oauth/refresh` | access token 을 새로 받는다 | `{vault, connector, client_id, client_secret}` | `{outcome}`. `ok` 면 `access_token`, `expires_at`. 바뀐 refresh token 은 답하기 전에 OAuth 파일에 쓰고, 쓰지 못하면 `unavailable` 이다. OAuth 파일이 없으면 `missing` | |
| `POST /api/connector-oauth/revoke` | 해제 때 제공자의 허락을 취소한다 | `{vault, connector, client_id, client_secret}` 와 선택 키 `access_token`. GitHub 은 access token 이 없으면 먼저 갱신해 받아 허락(grant) 전체를 취소하고, Google 은 refresh token 을 취소한다 | `{outcome}`. 파일은 지우지 않는다. 보관 파일 `DELETE` 가 지운다 | |
| `POST /api/connector-oauth/token-url` | 토큰 통로 주소만 바꾼다(서명 비밀 교체) | `{vault, token_url}` | `{outcome}`. OAuth 파일이 없으면 `missing` | |
| `POST /api/mcp/servers/<서버>/test?profile=<p>` | 그 profile 에 설치한 플러그인의 MCP 서버만 probe 한다 | 없음 | `{ok, tools: [{name}]}` | |
| `GET /api/gateway` | 공유 gateway 의 기동 시각을 읽는다. Control Plane 의 재시작 감지와 관리자 다시 확인이 재시작 대기 시각과 비교한다([ADR-20261120 / connector-auto-apply](../../../docs/adr/ADR-20261120-connector-auto-apply.md)) | 없음 | `{started_at}`. 기본 root 의 gateway pid 를 정리 없는 부름(`get_running_pid(..., cleanup_stale=False)`)으로 읽고 리눅스 `/proc` 의 시작 tick 과 `btime` 으로 구한 epoch 초다. pid 가 없거나 읽지 못하면 `null` 이다. 파일을 쓰지 않아 profile 쓰기 잠금을 잡지 않는다 | |
| `GET /api/tools/toolsets` | 도구 이름과 설명을 읽는다 | 없음 | 200 | |
| `PUT /api/config` (도구) | 지정한 profile 의 API 도구 목록을 쓴다. `terminal`, `file`, `code_execution`, `vision`, `image_gen`, `video_gen` 가운데 하나라도 있으면 profile 의 `terminal:` 을 실행 공간 설정으로 다시 쓴다. 같은 쓰기에서 `approvals.unattended_mode` 를 `approve` 로 둔다(「셸 실행 공간」) | `{profile, config: {platform_toolsets: {api_server: [...]}}, sandbox_owner?, require_sandbox?, ensure_image_service?}`. `ensure_image_service` 는 참 거짓 값이고, 참이면 같은 쓰기에서 profile 설정에 이미지 서비스를 넣는다(아래 「이미지 서비스」). `sandbox_owner` 는 `^[a-z][a-z0-9-]{0,63}$` 이고 실행 공간 도구를 켜는 등록 profile 에서 필수다. `require_sandbox` 는 참 거짓 값이다. 도구 저장의 판정은 바꾸지 않고 스크립트 스킬 게시의 `skill_root` 검사에만 쓴다. 옛 Control Plane 호환으로 받는다 | 200 | [ADR-029](../../../backend/docs/adr/ADR-029-에이전트-도구는-control-plane-이-등급으로-판정하고-hermes-설정-api-로-쓴다.md) 그대로. 409 소유 기록의 바인딩 서버 이름이 목록에 빠졌다 |
| `PUT /api/config` (스킬 게시) | 지정한 profile 의 올린 스킬 경로를 쓴다 | `{profile, config: {skills: {external_dirs: [<Hermes 쪽 스킬 루트>/<profile>/<버전>]}}, sandbox_owner?, require_sandbox?}`. 버전 이름은 `v[0-9]{13}-[a-z0-9]{4}` 다(`v` 뒤에 UTC 밀리초 13자리와 소문자 영숫자 4자). 목록은 0개나 1개. 0개는 게시 해제. 도구 목록을 같은 본문에 둘 수 있다. `scripts/` 가 든 스킬을 게시할 때 Control Plane 은 지금 도구 목록과 `require_sandbox: true` 를 함께 보낸다. 그때 plugin 은 목록에 실행 공간 도구가 있고, profile 이 정책에 등록돼 있고, 정책에 `skill_root` 가 있어야 받는다. 이 판정이 없는 옛 plugin 은 `require_sandbox` 를 셸 도구에만 적용해 그대로 받으므로, 이 판정을 가진 plugin 을 Control Plane 보다 먼저 배포한다 | 200 | 400 경로 형식, 다른 profile 의 prefix, 둘 이상, 심볼릭 링크, 없는 디렉터리, `skills` 도구가 꺼진 채 게시. 409 운영자가 넣은 다른 외부 경로가 있다. 409 `sandbox_unavailable` 은 `require_sandbox` 인데 위 셋 가운데 하나가 없다. 404 없는 profile |
| `GET /api/skills?profile=<p>` | 지정한 profile 의 스킬 목록을 읽는다 | query `profile` 하나 | 200 `[{name, description, category, enabled, usage, provenance}]`. `enabled` 는 전역 `skills.disabled` 만 반영 | 400 query 누락, 둘 이상, `default`. 404 |
| `PUT /api/skills/toggle` | 지정한 profile 의 스킬 하나를 켜고 끈다 | `{profile, name, enabled}` | 200 `{ok, name, enabled}` | 400, 404 |
| `GET PUT /api/profiles/<이름>/soul` | 그 profile 의 SOUL.md 를 읽고 쓴다 | `PUT` 은 `{content}` | 200 | |
| `GET /api/profiles/<이름>/model-defaults` | 그 profile 설정의 `provider`, `model`, `reasoningEffort` 세 값만 읽는다. 기본 profile 도 읽는다 | 없음 | 200 `{provider, model, reasoningEffort}`. 없는 값은 `null`. 설정 전체와 비밀값은 반환하지 않는다. [모델 단계와 실행 기록](../../../docs/features/model-usage.md) 의 「profile 기본 강도」 를 따른다 | 400 이름 형식. 404 없는 profile. 503 설정을 읽지 못함 |
| `GET /api/profiles/<이름>/sessions/<session id>/provider` | 그 profile 의 자식 session 한 줄에서 provider 와 모델만 읽는다. 기본 profile 도 읽는다 | 없음 | 200 `{provider, model}`. 아래 「자식 session 의 provider」 가 칸을 갖는다 | 400 이름이나 session 번호 형식. 404 없는 profile, 없는 session, 자식이 아닌 session. 503 저장소를 읽지 못함 |

표의 「그 profile」 은 있고 `default` 가 아닌 이름이다.
기본 profile 은 400 이고 없는 profile 은 404 다. `model-defaults` 와 자식 session 의 provider 경로는 기본 profile 도 받는다.
본문과 query 에 profile 이 둘 다 있으면 같아야 한다.

### 자식 session 의 provider

API server 의 session 응답은 provider 를 주지 않는다. 그 값은 Hermes 의 session 저장소에만 있다.
이 경로는 그 값을 Control Plane 에 읽어 준다. 근거는 [ADR-067](../../../backend/docs/adr/ADR-067-native-하위-에이전트의-provider-는-대시보드-plugin-이-session-저장소에서-읽어-준다.md) 에,
저장소의 어느 칸이 무엇을 뜻하는지는 [`hermes/docs/hermes-contract.md`](../../docs/hermes-contract.md) 의 「자식 session 의 provider 는 저장소에만 있다」 에 있다.

| 항목 | 값 |
| --- | --- |
| session 번호 | `[A-Za-z0-9_-]` 1자에서 128자. 아니면 400 |
| 읽는 파일 | 그 profile 디렉터리의 `state.db`. 없으면 404 |
| 여는 방식 | Python `sqlite3` 의 `mode=ro` URI. Hermes 의 저장소 클래스를 쓰지 않는다. 제한 시간은 2초다 |
| 대상 | `sessions` 표에서 `id` 가 같고 `source` 가 `subagent` 인 줄. 없으면 404 |
| `model` | 그 줄의 `model`. 비었으면 `null` |
| `provider` | 그 줄의 `billing_provider`. 비었으면 `null` |
| 짝이 둘 이상 | `session_model_usage` 에서 그 session 의 `task` 가 빈 줄을 `(model, billing_provider)` 로 묶어 둘 이상이면 `provider` 를 `null` 로 준다. 한 금액으로 환산할 수 없는 자식이다 |
| 짝이 하나인데 provider 가 다르다 | 그 짝의 `billing_provider` 가 `sessions` 줄의 값과 다르면 `provider` 를 `null` 로 준다. 어느 쪽이 맞는지 알 수 없다 |
| 읽기 실패 | 파일을 열지 못했거나 표나 칸이 없으면 503. 까닭을 응답과 로그에 싣지 않는다 |

이 둘 말고는 어느 칸도 내보내지 않는다. 대화 본문, system prompt, 토큰 수, 다른 종류의 session 은 이 경로로 읽지 못한다.
저장소를 쓰는 연결을 열지 않는다. 그래서 이 경로는 `state.db` 의 내용을 바꾸지 못한다.
쓰는 연결이 하나도 없을 때 읽으면 SQLite 가 WAL 보조 파일(`state.db-shm`, `state.db-wal`)을 만들 수 있다. 그 디렉터리에 쓸 수 없고 보조 파일도 없으면 열지 못해 503 이다.

**Hermes 버전을 올리면 표 이름과 칸 이름을 다시 확인한다.** 이름이 바뀌면 이 경로가 503 으로 답하고 Control Plane 은 그 자식을 가격 미확인으로 남긴다.

**카탈로그 응답.** `fields[]` 는 manifest 의 칸 그대로(`env`, `options` 포함)이고 `verify` 는 `{tool}` 이다. `schema: 3` 은 `verify` 가 null 이다. `owner_browser` 는 `owner_browser_env` 를 선언했는지이고 env 이름은 내지 않는다. `owner_browser_login_url` 은 선언한 `https` 주소나 null 이다. 옛 대시보드 plugin 은 두 칸을 내지 않고 Control Plane 은 거짓과 null 로 읽는다. `unknown_errors` 는 `outcome_unknown` 코드마다 선언한 `retry_key` 이름이나 null 이다. 옛 대시보드 plugin 은 내지 않고 Control Plane 은 어느 코드도 받지 않는 것으로 읽는다([ADR-20261013 / unknown-retry-key](../../../docs/adr/ADR-20261013-unknown-retry-key.md)).
`toolsets` 와 `attachments` 는 manifest 에 없으면 빈 목록과 거짓이다. 옛 대시보드 plugin 은 두 칸을 내지 않고, Control Plane 은 없는 칸을 같은 기본값으로 읽는다.
`single_binding` 은 boolean 이고 선언이 없으면 거짓이다. 옛 대시보드 plugin 은 이 칸을 내지 않고, Control Plane 은 없는 칸을 거짓으로 읽는다. `sandbox_required` 는 싣지 않는다. 대시보드가 바인딩 설치에서 직접 판정한다([ADR-20261008 / connector-binding-guards](../../../docs/adr/ADR-20261008-connector-binding-guards.md)).
`tools` 는 `{<이름>: {risk, approval, title, grant, outbound, conversation_grant, identifiers, description, arg_labels}}` 이고 `approval`, `grant`, `outbound`, `conversation_grant`, `identifiers` 는 기본값을 채운 값이다. 옛 대시보드 plugin 은 `conversation_grant` 를 내지 않고, Control Plane 은 없는 칸을 거짓으로 읽는다. `attended_only` 는 선언이 참일 때만 싣고, boolean 이 아닌 선언은 그 플러그인을 카탈로그에서 뺀다. `payment_context` 도 참일 때만 싣고, boolean 이 아니거나 `READ` 가 아닌 도구의 선언은 그 플러그인을 카탈로그에서 뺀다. `schema: 1` 은 `verify.tool` 과 `options.tool` 만 `READ` 로 담는다.
`arg_labels`는 도구별 승인 인자 표시 이름이며 누락·불량 선언과 schema 1은 빈 객체다.
정규화 manifest의 `tool_arg_labels`는 정책과 분리하고 카탈로그에서만 합친다. 설치 정책과 guard snapshot에는 넣지 않는다.
검증과 원래 키 호환은 [승인 인자 표시 이름](../../../docs/features/connector-policy.md#승인-인자-표시-이름)이 갖는다.
`schema` 가 없는 응답은 `1` 로 읽는다. `operator_env` 의 이름과 값, `errors` 는 담지 않는다.
`skills` 는 붙인 에이전트에 설치할 수 있는 스킬의 이름 목록이고 이름 순이다. `plugin.json` 의 스킬과 `assistant.json` 의 `agent_skills` 스킬을 합친다. 이름은 `SKILL.md` 앞머리의 `name` 이고, 없으면 디렉터리 이름이다. 본문은 담지 않는다.
`agent_skills` 는 에이전트용 스킬이 있을 때만 응답에 넣는 `[{name, sandbox_env}]` 다. 옛 대시보드 plugin 은 내지 않는다. 설치 조건은 [플러그인 만들기](../../connectors/README.md) 의 「에이전트용 스킬」 이 갖는다.
`fields` 가 빈 목록인 플러그인도 받는다. 값을 받지 않는 일반 MCP 서버다.
`icon`은 `{media_type, data}`이거나 null이다. `link`와 `readme_url`은 각각 500자 이하의 HTTPS·ASCII 주소나 null이다. 주소 하나가 틀려도 그 칸만 비우고 플러그인과 다른 칸은 유지한다. `link_kind`는 `project`거나 `service`이며 없거나 모르는 값은 `service`다. Control Plane은 옛 응답의 주소를 null로 읽고 웹에 `readmeUrl`, `linkKind`로 낸다. 두 링크의 검사와 화면 계약은 [플러그인 카드 ADR](../../../docs/adr/ADR-20261008-connector-card.md)의 「두 링크로 확장한 계약」이 갖는다.

**`POST /api/profiles` 는 틀을 쓰지 못하면 만든 것을 지운다.**
본문은 `name`, `no_skills`, `description` 만 받는다. `clone_from` 은 다른 profile 의 `.env` 를 끌어오므로 400 이다.
clone 없이 만든 profile 은 그대로 두면 API 경로에 거의 모든 toolset 이 열린다.
plugin 이 `default-config.yaml.template` 을 쓰고 `_get_platform_tools` 로 계산해,
`memory`, `terminal`, `file`, `code_execution`, `browser` 가 하나라도 남으면 지우고 500 을 돌려준다.
틀이 켜는 plugin 을 `profile-plugins/` 에서 찾지 못해도 같다. 순서와 분기는 plugin 의 docstring 이 소유한다.

틀에는 Control Plane MCP 등록(토큰은 `${MCP_FOS_ASSISTANT_API_KEY}` 참조)과 `plugins.enabled: [fos-ctx]` 가 들어 있다.
만든 뒤 plugin 이 공유 gateway 에 그 profile 의 plugin 을 다시 읽게 해 첫 실행부터 hook 이 돈다.
MCP 도구는 만든 뒤 1~2분 안에 붙는다. 이유는 운영 저장소의 Hermes 운영 문서가 갖는다.

**틀은 묶음을 만들 때 plugin 옆에 복사된다.** 저장소에는 `profile-template/config.yaml.template` 한 벌만 둔다.
틀이나 fos-ctx 를 고쳤으면 묶음을 다시 만들어 설치하고 대시보드를 다시 띄워야 새 profile 에 반영된다.

**사람이 대시보드에서 만드는 profile 에는 틀을 쓰지 않는다.** 쿠키로 들어온 요청은 plugin 이 손대지 않는다.

**`DELETE` 는 관리 표식 `.fos-assistant-managed` 가 있는 profile 에만 열린다.**
표식은 토큰으로 만든 profile 에 plugin 이 모든 검사를 지난 뒤 마지막에 쓴다.
사람이 만든 profile 과 기본 profile 은 401 이다. 파일이라 대시보드를 다시 띄워도 남는다.

**`PUT /api/env` 는 [plugin 의 허용 목록](common.py) 에 있는 key 만 받는다.**
기본 key 는 plugin 이 갖는다. 플러그인 칸 key(카탈로그 manifest 의 `fields[].env`)는 받지 않는다. 그 값은 보관 파일에만 두고, 로컬 플러그인은 실행기가, 원격 플러그인은 바인딩 설치가 넘긴다. `DELETE /api/env` 는 남은 사본을 지울 수 있게 칸 key 를 받는다.
본문은 `{profile, key, value}` 이고 값은 한 줄이어야 한다.
provider credential 은 이 토큰으로 쓰지 못한다.

### 플러그인

**plugin 은 플러그인의 이름을 코드에 두지 않는다.**
운영 목록의 plugin 디렉터리마다 `assistant.json` 을 읽어 카탈로그로 내고, 선택지와 확인 도구를 대신 부른다. `assistant.json` 이 없으면 옛 이름 `connector.json` 을 같은 규칙으로 읽고, 둘 다 있으면 그 플러그인을 카탈로그에서 뺀다([ADR-20261026 / plugin-standard-format](../../docs/adr/ADR-20261026-plugin-standard-format.md)).
둘 다 없으면 표준 플러그인으로 읽는다. 아래 「`assistant.json` 이 없는 플러그인」 이 규칙을 갖는다.
`assistant.json` 의 형식은 [플러그인 연결](../../connectors/README.md) 의 「assistant.json」 이 소유한다. 플러그인 경로가 설치와 확인에서 뜻하는 것은 [플러그인 설치](../../../docs/features/connector.md) 의 「대시보드 plugin 계약」 이 소유한다. 근거는 [ADR-043](../../../backend/docs/adr/ADR-043-커넥터는-plugin-의-connector-json-으로-선언하고-control-plane-은-범용-흐름만-갖는다.md) 에 있다.

**운영 목록은 환경 변수 `FOS_ASSISTANT_CONNECTOR_ROOTS` 로 받는다.** 플러그인 이름마다 값 하나를 둔 JSON object 다.

```json
{
  "<플러그인 이름>": {
    "root": "<plugin 디렉터리>",
    "command": "<MCP 서버를 실행할 파일>",
    "env": { "<operator_env 이름>": "<값>" }
  },
  "<원격 MCP 플러그인 이름>": {
    "root": "<plugin 디렉터리>",
    "url": "<원격 MCP 일 때 필수 주소>"
  },
  "<다른 플러그인 이름>": "<plugin 디렉터리>"
}
```

- 값이 문자열이면 `{"root": 그 값}` 으로 읽는다
- `command` 가 없으면 `FOS_ASSISTANT_CONNECTOR_COMMAND` 를 쓴다. 둘 다 없거나 실행 권한이 없으면 그 플러그인은 쓸 수 없다
- `assistant.json` 이 `operator_env` 로 적은 이름은 모두 `env` 에 값이 있어야 한다. 그 값은 설치할 때 서버 정의에 직접 들어가고 profile `.env` 를 거치지 않는다
- 경로는 절대 경로다. 모양이 틀린 항목은 그 항목만 버린다
- `url` 은 MCP 정의의 서버가 `type: http` 나 `streamable-http` 인 플러그인만 받는다. 그 플러그인의 항목에 `command` 나 `env` 가 있으면 그 항목을 버린다. 규칙은 [ADR-20261026 / remote-mcp-plugin](../../docs/adr/ADR-20261026-remote-mcp-plugin.md) 이 갖는다

**plugin 디렉터리에서 manifest, MCP 정의, assistant 선언, 스킬을 읽는다.** 플러그인 manifest 는 `.claude-plugin/plugin.json`, root `plugin.json`, `.codex-plugin/plugin.json` 이다. MCP 정의는 manifest 의 `mcpServers` 경로나 root `.mcp.json`, `mcp.json` 이다. assistant 선언은 `assistant.json` 이다.
하나라도 검증에 실패하면 그 플러그인은 카탈로그에서 빠지고 경고 로그 한 줄이 남는다. 대시보드와 다른 플러그인은 그대로 돈다.

- manifest 가 여럿이면 `name` 이 모두 같아야 한다. 위 목록의 순서로 앞선 manifest 의 스킬과 MCP 정의 경로를 쓴다. `hooks`, `commands`, `agents` 같은 다른 칸은 읽지 않는다
- MCP 정의는 서버 하나만 둔다. 그 이름이 플러그인의 MCP 서버 이름이다. `.mcp.json` 과 `mcp.json` 이 함께 있으면 명시한 경로와 상관없이 카탈로그에서 뺀다. 이식형 MCP 정의의 `$schema` 는 실행 설정에 쓰지 않는다
- 서버의 `type` 이 `http` 나 `streamable-http` 이면 원격 MCP 다. 아래 로컬 실행형의 `command`, `args`, `env` 규칙 대신 「원격 MCP 플러그인」 의 규칙을 따른다
- 서버의 `command` 는 쓰지 않고 운영 목록의 실행 파일로 바꾼다
- 서버의 `args` 가운데 `${CLAUDE_PLUGIN_ROOT}/` 로 시작하는 것은 plugin 디렉터리 안의 링크 없는 파일이어야 하고 실제 경로로 바꾼다. 그 밖의 `$` 치환은 받지 않는다
- 서버의 `env` 는 자기 이름의 `${이름}` 참조만 둔다. 선택 칸은 `${이름:-}` 도 된다
- 값이 없는 선택 칸은 설치할 때 서버 정의에 빈 값을 명시한다. Hermes 는 빈 변수의 참조를 그대로 남기기 때문이다

**`assistant.json` 이 없는 플러그인은 카탈로그에 `schema: 3` 으로 낸다**([ADR-20261101 / plugin-without-assistant-json](../../docs/adr/ADR-20261101-plugin-without-assistant-json.md)).
선언은 `connector_standard.py` 가 manifest 와 MCP 정의에서 만들고, 그 뒤 검증은 `assistant.json` 과 같다.

- 제목은 manifest 의 `displayName`, 없으면 `name` 이다. 설명은 `description` 이다
- 로컬 서버의 `env` 는 `${이름}` 이면 필수 칸, `${이름:-}` 이면 선택 칸이 된다. 다른 값이 하나라도 있으면 카탈로그에서 뺀다. 원격 서버는 헤더 값의 `${이름}` 마다 필수 칸이다
- 칸은 모두 `secret: true` 이고 key 는 env 이름의 소문자, 표시 이름은 env 이름이다. env 이름은 원격 칸과 같은 규칙으로 Hermes 전역 이름을 거절한다
- 운영 목록 항목에 `env` 가 있으면 뺀다. 확인 도구와 선택지 도구가 없어 `call` 이 부를 도구가 없다. `tools` 는 비고 `tools.exclude` 도 없다
- 카탈로그 응답은 query `include=standard` 가 있을 때만 이 항목을 싣고, `verify` 는 null 이다

**원격 MCP 플러그인은 자식 프로세스를 띄우지 않고 Streamable HTTP 로 붙는다.**

- 이식형 서버의 `extensions["com.openai"].auth` 는 생략하거나 `type: none`, `api_key` 를 쓴다. `api_key` 의 `headerScheme: bearer` 는 `Authorization: Bearer ${<이름>}`, `custom_header` 는 `headerName` 의 `${<이름>}` 헤더로 바꾼다. 칸 env 이름은 서버 이름을 대문자로 바꾸고 하이픈을 `_` 로 바꾼 뒤 `_API_KEY` 를 붙인다. 기존 원격 칸·헤더 검증을 그대로 거친다
- `oauth`, `mixed`, `basic` 과 알 수 없는 인증 방식, 잘못된 인증 선언은 항목 전체를 카탈로그에서 뺀다. `.app.json`, `interface`, `onboardingSkill`, `hooks` 와 위젯 UI 는 읽거나 실행하지 않는다. 서버의 `_meta` 와 인증 이외의 `extensions` 설정도 설치할 서버 정의에 싣지 않는다
- 주소는 운영 목록의 `url` 이 필수이고 MCP 정의의 `url` 은 무시한다. 플러그인 갱신으로 토큰의 목적지가 바뀌지 않는다. 계정 정보, 쿼리, fragment, `$` 치환이 있으면 그 플러그인을 카탈로그에서 뺀다
- 헤더는 MCP 정의의 `headers` 와 `assistant.json` 의 `headers` 를 합친 것이다. 값은 `${이름}` 이나 `Bearer ${이름}` 이고 `${이름}` 은 필수 칸 하나다. 같은 헤더 이름이 둘에 있으면 카탈로그에서 뺀다
- `operator_env`, `owner_attachments_env`, `owner_output_env`, `owner_browser_env`, `snapshot`, `execution_guard` 를 선언하면 카탈로그에서 뺀다
- 설치하는 서버 정의는 `{"url", "headers", "strict_redirect_headers", "enabled"}` 와 `tools.exclude` 다. 헤더의 `${이름}` 은 profile `.env` 가 채운다. `strict_redirect_headers` 는 참이고, Hermes 가 다른 origin 으로 가는 redirect 에서 설정 헤더를 모두 지우게 한다
- 아래 `call` 과 `execute` 는 같은 주소에 직접 붙고, 칸 값은 헤더에만 넣는다. redirect 는 같은 호스트로 가는 것도 따라가지 않는다. 주소와 헤더 값, 인자를 로그에 싣지 않는다
- 도구 오류는 `{"error": {...}}` 와 함께 맨 위의 `{code, message, details}` 모양도 읽는다. `error` 키가 있으면 그것만 보고, 없으면 맨 위 `code` 와 `details` 객체의 값으로 오류 객체를 만든다. `message` 는 버린다. 모든 플러그인에 같다
- 칸 env 이름은 `^[A-Z][A-Z0-9_]{0,127}$` 이고 Hermes 전역 이름과 접두는 거절한다. 목록과 근거는 원격 MCP ADR 이 갖는다
- `Accept-Encoding: identity` 를 보내고 이 헤더를 플러그인이 덮어쓰지 못하게 한다. `content-encoding` 이 없거나 `identity` 인 응답만 받고 다른 압축 응답은 읽기 전에 닫는다
- 허용한 응답은 SDK 가 모으기 전에 일반 본문과 SSE 이벤트 하나마다 10 MiB 로 제한한다. SSE 연결의 누적 크기는 제한하지 않는다
- 원격 호출 동안 SDK 와 HTTP 라이브러리의 로그는 수준과 상관없이 남기지 않는다. 주소와 인자가 WARNING 이상의 예외 문구에도 실리기 때문이다
- probe 는 Hermes 가 붙는다. redirect 처리는 [Hermes 계약](../../docs/hermes-contract.md) 의 「원격 MCP 서버」 를 따른다

**`POST /api/connectors/<id>/call` 은 자식 프로세스를 띄운다.** 원격 MCP 플러그인은 위 규칙대로 그 주소에 붙는다.

- 자식이 받는 환경 변수는 요청의 칸 값, 운영 목록의 `env`, MCP SDK 가 늘 더하는 기본 env(`HOME`, `LOGNAME`, `PATH`, `SHELL`, `TERM`, `USER`)뿐이다. `owner_attachments_env` 와 `owner_output_env` 를 선언한 플러그인에는 그 이름을 빈 값으로 준다. 이 경로에는 바인딩 주인이 없기 때문이다.
  `owner_browser_env` 를 선언한 플러그인에는 요청의 `owner_browser`(요청자의 호출 표식을 실은 중계 주소)를 준다. 없거나 중계 주소 모양이 아니면 빈 값이다. 선언하지 않은 플러그인은 그 값을 받아도 넘기지 않는다.
  `vault` 로 부르면 보관 파일의 키 가운데 지금 manifest 의 칸에 없는 것은 버린다. 칸을 뺀 플러그인의 옛 연결도 확인되게 하려는 것이다. `values` 의 모르는 칸은 그대로 `invalid_input` 이다.
  대시보드 프로세스의 다른 env(서비스 토큰, 다른 플러그인의 값)는 넘어가지 않는다. `PATH` 는 대시보드의 값 대신 실행 파일이 있는 디렉터리만 준다
- 후보 값은 디스크, 응답, 로그에 남기지 않는다. 자식의 stderr 도 대시보드 로그로 보내지 않는다
- 도구는 인자 없이 부른다. 값은 환경 변수로만 간다
- profile 쓰기 잠금 밖에서 돈다. 도구를 기다리는 동안 다른 profile 요청이 멈추지 않는다
- 시간을 넘기거나 예외가 나면 SDK 의 정리 구간이 자식의 stdin 을 닫고, 끝나지 않으면 프로세스 묶음을 죽인 뒤에 응답한다
- `mcp` SDK 는 이 경로 안에서 import 한다. SDK 가 없으면 이 경로만 `unavailable` 이고 나머지는 그대로 돈다

**`POST /api/connectors/<id>/execute` 는 Control Plane 이 승인한 호출만 부른다. 대시보드는 승인 여부를 다시 확인하지 않는다.**

- 그 profile 에 관리 표식이나 플러그인 표식과 그 플러그인의 소유 기록이 있어야 한다. 플러그인 표식만 있는 profile 은 소유 기록의 그 항목이 바인딩 설치여야 한다. 자식의 env 는 그 바인딩의 칸 값과 운영 목록의 `env` 다. 칸 값은 실행기 정의면 소유 기록의 보관 파일에서, 옛 정의와 원격 정의면 그 profile `.env` 에서 읽는다. 보관 파일을 풀지 못하면 자식을 띄우지 않고 `{ok: false}` 다. `owner_attachments_env` 를 선언한 플러그인은 설치한 서버 정의의 그 값을 더한다. `owner_output_env` 는 빈 값이다. `owner_browser_env` 를 선언한 플러그인은 설치한 서버 정의의 중계 주소를 더하고, 그 값이 중계 주소 모양이 아니면 빈 값이다
- 인자와 결과를 로그에 싣지 않는다
- 자식의 표준 오류는 원문을 옮기지 않는다. 진단 줄 모양(`{"<이름>_diagnostic": {...}}` 한 칸짜리 JSON 으로 값이 낱말뿐인 것, 실행기의 `connector-launcher: <이유>`, `_` 로 이은 대문자 코드 한 낱말)만 골라 그 profile 의 MCP 표준 오류 로그(`logs/mcp-stderr.log`)에 더한다. 시간 초과로 끊겨도 그때까지 나온 줄을 남긴다. 로그 디렉터리가 없거나 링크면 남기지 않는다. 금융 준비(`prepare`)도 같다. 고르는 규칙은 `connector_child_log.py` 가 갖는다
- 실행되지 않은 것이 분명한 실패는 `{ok: false}` 로, 시간 초과와 도구 호출을 보낸 뒤의 실패는 504 로 답한다. 504 는 실행됐는지 모른다는 뜻이다
- 도구가 `errors` 표에서 `outcome_unknown` 인 코드로 실패해도 504 로 답한다. 본문은 `{detail, code}` 이고, 그 코드가 `retry_key` 를 선언했으면 `retry: {<retry_key>: <값>}` 을 더한다([ADR-20261013 / unknown-retry-key](../../../docs/adr/ADR-20261013-unknown-retry-key.md)). `call` 에서는 그 코드를 `unavailable` 로 돌려준다
- 요청과 응답은 [플러그인 연결](../../../docs/features/connector-policy.md) 의 「플러그인 승인」 이 소유한다. 근거는 [ADR-050](../../../backend/docs/adr/ADR-050-커넥터-쓰기는-control-plane-이-승인-줄을-저장하고-승인한-인자로-한-번만-실행한다.md) 에 있다

**`POST /api/connectors/<id>/events` 는 Control Plane 이 실행 밖에서 새 항목이 있는지만 보는 경로다([ADR-20261105 / event-watch](../../../docs/adr/ADR-20261105-event-watch.md)).**

- profile 확인과 자식의 env 는 `execute` 와 같다. 다만 첨부, 출력, 브라우저 env 는 모두 빈 값이다
- 선언의 `tool` 이 `tools/list` 에서 읽기 전용이 아니면 부르지 않는다. 쪽은 4쪽까지 읽고 전체 제한은 30초다. 동시 호출 한도는 `call`, `execute` 와 함께 쓴다
- 항목마다 식별자, 받은 시각, 라벨만 돌려주고 `skip_labels` 가 붙은 항목은 뺀다. 도구 결과의 다른 칸은 응답과 로그에 싣지 않는다
- 선언 칸은 [플러그인 만들기](../../connectors/README.md) 의 「사건 감지 선언」 이 갖는다

**소유 기록 `.fos-connectors.json` 은 바인딩할 때의 서버 정의를 갖는다.**

| 상태 | 설치한 적 없는 profile | 이미 설치한 profile |
| --- | --- | --- |
| 운영 목록에서 빠졌다 | 상태 조회에 나오지 않고 설치는 400 이다. 제거는 끌 것이 없어 `changed: false` 로 성공한다 | 상태 조회는 `configured: false` 다. 제거는 되고 probe 는 404 다 |
| 실행 파일을 받지 못했다 | 설치가 503 이다 | 상태 조회, 제거, probe 가 모두 503 이다 |
| 설치한 뒤 실행 파일, plugin 경로, 실행 정의의 command·args, 운영자 env 값이 바뀌었다 | 해당 없음 | 상태 조회, 제거, probe 가 모두 503 이다 |

운영 목록에서 빠진 플러그인을 제거하면 plugin 이 그 기록의 서버 env 가 `${이름}` 으로 참조하던 key 를 profile `.env` 에서 함께 지운다. Control Plane 이 그 이름을 더는 알 수 없기 때문이다.
값이 바뀌어 503 이 된 profile 은 값을 되돌리면 다시 읽힌다.
바인딩의 실행과 probe 는 요청이 가리키는 플러그인의 항목만 견준다. 상태 조회는 바뀐 바인딩 항목만 `configured: false` 로 답하고, 같은 profile 의 다른 플러그인은 probe 와 실행과 붙이기가 그대로 된다.
바인딩 설치는 기록의 형식과 실제 설정의 소유권을 확인한 뒤, command, args, env 가 바뀌었어도 새 manifest 로 다시 설치한다. 보관 파일과 연결 값은 유지한다. 떼기는 바인딩 항목을 지금 manifest 와 견주지 않는다.

바인딩 설치는 `config.yaml`, `.fos-connectors.json`, `.env`, 뗀 서버 기록, 도구 이름 대응, 스킬, 정책 plugin 파일을 차례로 교체한다. 로컬 플러그인의 설치는 `.env` 에서 그 칸 줄을 지우기만 한다.
파일마다 임시 파일을 원자적으로 교체하며, 예외가 나면 이번 요청이 쓴 값이 남은 파일만 역순으로 복원한다.
교체 직후 예외가 난 파일도 복원하고, 한 파일의 복원이 실패해도 나머지 파일을 계속 복원한다.
복원 실패 로그에는 예외 종류만 남긴다. 여러 파일 전체의 교체는 원자적이지 않으며 프로세스 강제 종료나 지속적인 디스크 오류는 복원을 막을 수 있다.

설정만 새 정의로 남았으면 재설치가 소유 기록을 복구할 수 있다.
기존 바인딩 소유 기록이 있고, 현재 서버 설정이 이번 요청으로 만들 새 정의와 정확히 같으며, 기록과의 차이가 plugin이 관리하는 `tools.exclude`뿐인 경우다.
이때 `restart_required: true`로 답한다. gateway가 옛 서버를 쥐고 있을 수 있기 때문이다.
실행기 정의의 보관 파일 리비전(`FOS_ASSISTANT_CONNECTOR_VAULT_REVISION`)은 이 견줌에서 뺀다. 소유 기록 부재나 command, args, env, enabled, 다른 tools 설정의 차이는 계속 409다.
연결 확인과 관리자 반영 완료의 재설치·재시작 대기 흐름은 [플러그인 설치](../../../docs/features/connector.md) 의 「바인딩의 반영 맞추기」 가 갖는다.
**운영은 환경 변수를 먼저 준 뒤 plugin 을 올린다.**

**플러그인은 바인딩으로만 붙인다.** 관리 표식이나 플러그인 표식이 있고, `platform_toolsets.api_server` 목록에 Control Plane MCP 가 있고, `fos-ctx` 가 켜진 profile 이 받는다. `enabled: true` 요청은 로컬 플러그인이면 실행기 정의를 쓰고 칸 값을 어디에도 복사하지 않는다. 원격 플러그인이면 보관 파일의 값을 profile `.env` 에 복사한다. 둘 다 서버 이름만 더한다. 다른 API 도구와 Control Plane MCP 등록은 그대로 두며, 스킬 디렉터리를 그 profile 의 `skills/` 로 복사한다. `SOUL.md` 는 읽지도 쓰지도 않는다.

대응 파일은 `isolated: false` 를 싣고 소유 기록의 모든 서버와 뗀 서버 기록 `.fos-connector-detached.json` 의 서버를 싣는다.
이미 있던 서버의 정의(로컬 플러그인은 보관 파일 리비전 포함)나 그 서버의 `.env` 값이 바뀌었거나, 뗀 서버 기록에 남은 이름을 다시 붙이면 붙이기의 `restart_required` 가 참이다. 공유 gateway 의 MCP 설정 맞추기는 이름만 비교해 같은 이름을 다시 연결하지 않는다. 뗀 기록에 없는 새 이름, 스킬, 이름 대응만 바뀐 붙이기는 `restart_required` 가 거짓이고 `reload_pending` 이 참이다([ADR-20261007 / connector-live-reload](../../../docs/adr/ADR-20261007-connector-live-reload.md)).
바인딩을 떼면 `restart_required` 는 거짓이고, 바뀐 것이 있으면 `reload_pending` 이 참이다. 도구 목록에서 이름을 빼므로 다음 실행부터 막힌다. 떼기 전에 시작한 실행의 호출은 대응에 남은 서버로 판정이 막는다.
`reload_pending` 은 바뀐 것이 있고 `restart_required` 와 `plugin_updated` 가 모두 거짓일 때 참이다. `plugin_updated` 가 참이면 그것만으로 재시작을 기다린다.
재시작이 필요한 까닭이 정의나 칸 값 변경, 뗀 이름 다시 붙이기뿐이면 붙이기는 `cycle_available: true` 도 답한다. `plugin_updated` 가 참이면 거짓이다.
**그 서버만 껐다 켜서 재시작 없이 반영할 수 있다**([ADR-20261120 / connector-auto-apply](../../../docs/adr/ADR-20261120-connector-auto-apply.md)). `bind.cycle: "off"` 를 받으면 새 정의를 소유 기록에 쓰고, 설정의 그 서버 항목에만 `enabled: false` 를 넣는다. 소유 기록의 정의는 늘 켜진 정의다. 끈 시각은 profile 의 `.fos-connector-cycle.json`(`{"<플러그인 id>": <epoch 초>}`)에 적고 답은 `cycle_off: true`, `reload_pending: false` 다.
끈 지 120초(gateway 맞추기 주기 둘) 전에 온 설치는 꺼진 채 새 정의를 쓰고 `cycle_off: true` 로 답한다. 지난 뒤의 보통 설치는 켜고 끄기 기록에서 지우며, 끈 동안 gateway 가 옛 연결을 끊었으므로 `restart_required: false`, `reload_pending: true` 로 답한다. 떼기는 꺼진 항목도 지우고 끄기 기록에서 뺀다.
설정 항목이 소유 기록과 `enabled` 만 다르고 끄기 기록에 그 플러그인이 있을 때만 이 plugin 이 끈 것으로 본다. 끄기 기록이 없으면 지금처럼 운영자가 바꾼 서버로 보고 409 다.
스킬 파일을 바꾼 붙이기는 `skills.disabled` 에서 `fos-skill-index-` 로 시작하는 항목을 빼고 새 표식 하나를 더한다. gateway 가 그 profile 의 스킬 색인을 새로 만든다. 다른 이름은 그대로 두고, 문자열이면 Hermes 가 읽는 대로 목록으로 바꿔 쓴다. 고칠 수 없는 모양이면 붙이기는 409 이고 떼기는 표식 없이 뗀다.

**보관 파일은 연결의 칸 값을 연결마다 하나씩 둔다.**
대시보드의 HERMES_HOME 아래 `connector-vault/<vault>.json` 이고, 디렉터리는 700, 파일은 600 이다.
`vault` 는 `^c[1-9][0-9]{0,18}$` 이다. Control Plane 의 연결 번호 앞에 `c` 를 붙인 것이다.
보관 파일 key 가 준비됐으면(두 환경 변수가 있고 key 파일을 읽었으며 활성 id 가 파일에 있다) 본문은 `{"v": 2, "connector": "<id>", "key_id": "<key id>", "sealed": "v1.<IV>.<암호문과 태그>"}` 다. `sealed` 는 AES-256-GCM 이고 풀면 `{"<field key>": "<값>"}` 이며, AAD 는 `connector-vault:<vault>:<connector>` 라 파일을 다른 연결 이름으로 옮기거나 `connector` 를 바꾸면 풀리지 않는다.
key 가 준비되지 않고 「key 필수」 가 꺼져 있으면 `{"v": 1, "connector": "<id>", "values": {"<field key>": "<값>"}}` 평문이다. 빈 선택 칸은 넣지 않는다. 칸이 없는 플러그인은 빈 `values` 다.
읽기는 둘을 모두 받는다. v2 는 그 `key_id` 의 key 가 key 파일에 있으면 활성 key 가 아니어도 푼다. 쓰기 전의 소유 확인은 풀지 않고 `connector` 만 본다. 모양을 읽지 못하는 파일은 경고를 남기고 덮어쓴다.
plugin 이 올라올 때 평문 파일과 옛 key 의 파일을 활성 key 로 다시 쓰고, 다시 읽어 값이 같은지 본다. 깨진 파일과 풀지 못하는 파일은 건드리지 않는다. 쓰다 남은 임시 파일은 지운다. 남은 평문 파일, 옛 key 파일, 풀지 못한 파일의 수를 info 한 줄로, 그 파일 이름을 warning 한 줄로 남긴다. 운영 값과 동작은 [`hermes/README.md`](../../README.md) 의 「운영 값」 과 [ADR-20261103 / connector-vault-key](../../docs/adr/ADR-20261103-connector-vault-key.md) 가 갖는다.
값과 경로는 응답, 로그, 예외 메시지, 설정 백업에 넣지 않는다. 쓰기와 지우기는 그 보관 파일의 잠금 다음 profile 쓰기 잠금 안에서 하고, 바인딩 설치는 profile 쓰기 잠금만 잡고 보관 파일을 읽는다.

**`connector-oauth` 경로의 `outcome` 은 `ok`, `grant_revoked`, `app_rejected`, `scope_denied`, `unavailable`, `missing` 가운데 하나다.**
연결 실패, 10초 시간 초과, 5xx 는 본문과 상관없이 `unavailable` 이다. 그 밖에는 서비스 응답 본문의 `error` 낱말을 제공자 표(`connector_oauth_providers.py`)의 허락 오류와 앱 오류에 대조한다. GitHub 은 오류도 HTTP 200 본문으로 오므로 상태 코드보다 본문을 먼저 본다. 표에 없는 낱말은 `unavailable` 이다.
모든 경로는 보관 파일 잠금 안에서 파일을 읽고 쓰며, 본 보관 파일을 쓰는 commit 만 그 다음 profile 쓰기 잠금을 잡는다. client secret, refresh token, access token, 서비스 응답 원문은 파일(OAuth 파일의 refresh token 빼고)과 로그에 쓰지 않는다.

**단추로 연결한 연결은 보관 파일 옆에 OAuth 파일과 대기 파일을 둔다**([ADR-20261120 / connector-oauth](../../../docs/adr/ADR-20261120-connector-oauth.md)).

| 파일 | 담는 것 | 지우는 때 |
| --- | --- | --- |
| `<vault>.oauth` | `{provider, refresh_token, granted_scopes, token_url}` | 보관 파일 `DELETE`, `oauth` 없는 `PUT` |
| `<vault>.oauth.pending` | 단추 흐름 하나의 OAuth 칸과 `nonce`. 교환 전에는 `refresh_token` 이 빈 값이다 | commit, discard, 보관 파일 `DELETE`, 10분 뒤 |
| `<vault>.pending` | 단추 밖 칸 값. 보관 파일과 같은 형식이다 | 위와 같다 |

OAuth 파일은 보관 파일과 같은 key 로 암호화하고 AAD 는 `connector-oauth:<vault>:<connector>` 다. key 가 준비되지 않았으면 `{"v": 1, "connector", "oauth"}` 평문이다. 기동 이관은 세 파일도 새 key 로 다시 쓰고 같은 수에 센다. 기동할 때와 대기 파일을 쓸 때 10분 지난 대기 파일을 지운다.
OAuth 파일은 실행기 정의의 보관 파일 리비전에 들어가지 않는다. 그래서 갱신으로 refresh token 이 바뀌어도 재시작 대기가 되지 않는다. commit 은 대기 보관 파일 값을 보관 파일 쓰기로 다시 써서 값이 같으면 보관 파일을 바꾸지 않는다.
보관 파일 id 마다 잠금이 있고 profile 쓰기 잠금보다 먼저 잡는다. 갱신과 취소는 보관 파일 잠금만 잡고 서비스를 부른다.
이미 있는 파일이 지금 쓸 모양과 같은 값이면 다시 쓰지 않는다. 같은 값으로 다시 등록해도 실행기 정의의 보관 파일 리비전이 바뀌지 않는다.

**로컬 플러그인의 서버는 실행기(`connector_launcher.py`)로 띄운다.**
서버 정의는 `command: <대시보드의 python>`, `args: ["-I", <실행기>, <vault>, <플러그인 id>, "<칸 key>=<env>", ..., "--", <실제 command>, <실제 args>...]` 이고, `env` 에는 운영자 env, 주인 env, 보관 파일 디렉터리와 key 파일 위치, 보관 파일 리비전이 있다. 칸 값은 없다.
실행기는 보관 파일을 풀어 칸 값을 자식 env 에만 넣고 `FOS_ASSISTANT_CONNECTOR_VAULT_` 로 시작하는 이름을 지운 뒤 `execve` 한다. 풀지 못하면 값과 경로 없이 이유 단어 하나를 stderr 에 쓰고 78 로 끝난다.
plugin 이 올라올 때 보관 파일 이관 뒤에 옛 정의의 로컬 바인딩을 실행기 정의로 옮기고 `.env` 사본을 지운다. 풀지 못한 바인딩은 그대로 둔다. 수를 info 한 줄로 남기고, 옮긴 것이 있으면 「gateway 재시작이 필요하다」 를 남긴다. 정의 모양, 이관 순서, 되돌리기는 아래 「보관 파일 암호화와 실행기 설계」 의 「PR2: 실행기와 사본 제거(REQ6, REQ7, REQ8)」 가 갖는다.

**플러그인 표식 `.fos-connector-host` 는 사람이 만든 profile 이 바인딩 설치를 받는다는 표시다.**
운영자가 그 profile 에 두고 plugin 은 쓰지 않는다. 표식만 있는 profile 은 바인딩 설치와 떼기, 상태 조회, probe, 바인딩 항목의 실행만 받는다. 소유 기록에 없는 플러그인의 떼기도 바인딩 떼기로 받아 `changed: false` 로 답한다. 플러그인 칸 key 의 `PUT /api/env` 는 400, `DELETE /api/env` 는 401 이다. 바인딩의 env 는 설치와 떼기가 쓰고 지운다.

설치와 제거는 Hermes 등록 이름과 원래 도구 이름의 대응을 그 profile 의 `.fos-connector-tools.json` 에 다시 쓰고, 설치는 `approval: always` 인 도구를 서버 정의의 `tools.exclude` 에 넣고 profile 의 `fos-ctx` 를 묶음의 판으로 맞춘 뒤 파일이 바뀌었는지를 `plugin_updated` 로 답한다.
`GET /api/connectors` 는 `policy_hook` 을 함께 낸다. `fos-ctx` 가 켜져 있고 묶음의 판과 같고 대응 파일의 모양(서버 목록, `connector`, `prefix`)이 지금 manifest 로 계산한 것과 맞을 때만 참이다. 서버 정의의 `tools.exclude` 와 대응 파일의 서버별 `tools` 가 어긋나면 그 플러그인 항목만 `configured` 가 거짓이며, 조건은 [플러그인 도구 정책](../../../docs/features/connector-policy.md) 의 「hook 이 켜져 있는지」 가 갖는다.

**한 배포 동안 옛 소유 기록의 필드 모양·운영자 env 표현을 받는다**([ADR-041](../../../docs/adr/ADR-041-hermes-에-설치하는-plugin-과-profile-틀은-이-저장소가-소유한다.md)). 바인딩은 위 재설치 경로로 새 정의를 적용한다.

- 옛 기록은 운영자 env 를 `${이름}` 참조로 갖고 MCP 서버 이름 칸이 없다. 운영자 env 는 그 참조와 지금의 직접 값을 같다고 본다. 다음 설치 요청이 기록을 새 모양으로 다시 쓴다
- 운영자 env 이름의 `PUT /api/env` 와 `DELETE /api/env` 는 성공으로 답하고 아무것도 쓰지 않는다

**토큰으로 부른 `PUT /api/config` 는 본문을 검사한 뒤 Hermes 처리기에 넘긴다.**
최상위에는 `profile` 과 `config` 를 두고, `sandbox_owner` 를 더할 수 있다. 형식은 `^[a-z][a-z0-9-]{0,63}$` 이다.
`terminal`, `file`, `code_execution`, `vision`, `image_gen`, `video_gen` 을 켜면 `sandbox_owner` 가 필수다.
미등록 profile 에서 이 도구를 켜는 저장은 local 로 두지 않고 409 `sandbox_unavailable`, `reason: unlisted` 로 거절한다. local 셸은 Hermes 프로세스의 사용자로 돌아 다른 profile 의 `.env`, 플러그인 값 파일, 다른 사용자의 첨부에 닿는다(ADR-20261120 / no-local-shell).
`terminal:` 은 plugin 이 직접 쓰므로 본문의 `config` 에 두지 않는다.
`config` 에는 `platform_toolsets.api_server` 와 `skills.external_dirs` 가운데 하나나 둘을 둔다.

도구 목록은 이렇게 본다.

- `agent` 키는 받지 않는다. `agent.disabled_toolsets` 는 아래 「켠 API 도구는 `disabled_toolsets` 에서 뺀다」 대로 plugin 이 고친다
- 목록의 이름은 Hermes toolset 또는 그 profile 에 등록된 MCP 서버여야 한다
- `api_server` 에는 `memory` 를 넣지 않고 Control Plane MCP `fos-assistant` 를 남긴다
- Control Plane MCP 가 아직 등록되지 않은 profile 은 그 이름과 알려진 내장 toolset 을 함께 넣는다
- Hermes 가 계산한 실제 API 도구에 요청 목록 밖의 이름이 있으면 저장하지 않는다
- 다른 platform 의 계산 결과가 바뀌어도 저장하지 않는다
- 목록에 `terminal`, `file`, `code_execution`, `vision`, `image_gen`, `video_gen` 가운데 하나라도 있으면 `FOS_ASSISTANT_SANDBOX` 를 검사한다. 값이 없거나 틀리면 409 와 본문 `code: sandbox_unavailable` 이다. 정책에 등록된 profile 만 `terminal:` 전체를 docker 설정으로 다시 쓰고 `approvals.unattended_mode` 를 `approve` 로 둔다. 미등록 profile 은 409 와 `reason: unlisted` 다

#### 이미지 서비스

`image_gen` toolset 만 켜면 Hermes 가 `image_generate` 를 모델에 보이지 않는다. profile 설정에 이미지 서비스(`image_gen.provider`)가 있어야 한다([`hermes-contract.md`](../../docs/hermes-contract.md) 의 「`image_generate` 의 결과와 그림 파일」).
Control Plane 은 `image_gen` 이 든 도구 저장에 `ensure_image_service: true` 를 넣어 보낸다(fos-assistant [ADR-20261119 / image-gen-defaults](../../../docs/adr/ADR-20261119-image-gen-defaults.md)).

- 참이면 `api_server` 목록에 `image_gen` 이 있어야 한다. 없거나 도구 목록 없이 오면 400 이다. 참 거짓 값이 아니어도 400 이다
- 저장된 `image_gen.provider` 가 비었을 때만 `IMAGE_SERVICE_PROVIDER`(`image_service.py`)를 넣는다. 이미 있는 서비스와 `image_gen` 의 다른 키는 덮어쓰지 않는다
- `image_gen` 이 객체가 아니면 덮어쓰지 않고 500 으로 답한다. 도구도 켜지지 않는다
- 실행 공간 판정이 먼저다. 정책에 없는 profile 은 409 이고 서비스도 쓰지 않는다
- 서비스는 plugin 이 Hermes 처리기보다 먼저 쓰고, 처리기가 실패하면 원래 바이트로 되돌린다
- 칸이 없는 요청은 지금처럼 toolset 만 쓴다. 이 칸을 모르는 옛 plugin 은 본문을 400 으로 거절하므로 이 plugin 을 Control Plane 보다 먼저 배포한다

올린 스킬 경로는 이렇게 본다. 루트는 대시보드 프로세스의 환경 변수 `FOS_ASSISTANT_SKILL_AGENT_ROOT` 다.

- `skills` 에는 `external_dirs` 만 둔다. `create_dir` 같은 다른 키는 모델이 스킬을 쓰는 자리를 바꾸므로 400 이다
- 목록은 경로 0개나 1개다. 0개는 게시를 거둔다
- 경로는 정확히 `<루트>/<profile>/<버전>` 이다. `~`, `$`, 역슬래시, 빈 조각, `.`, `..` 는 400 이다
- 버전 디렉터리가 있어야 한다. Hermes 는 없는 디렉터리를 오류 없이 건너뛰어 올린 스킬이 말없이 사라진다
- 경로와 그 아래 어디에도 심볼릭 링크가 없어야 한다. `skill_view` 가 파일을 그대로 읽어, 링크가 다른 profile 의 `.env` 를 가리키면 그 토큰이 모델에게 간다
- 경로 0개가 아니면 계산한 API 도구에 `skills` 가 있어야 한다. 같은 요청에서 켜도 된다
- 이미 저장된 `external_dirs` 에 그 profile 의 루트 밖 경로가 있으면 409 다. 목록을 통째로 바꾸면 운영자가 넣은 경로가 지워진다

경로 검사는 profile 설정을 읽기 전에 돈다. 운영 검사가 없는 profile 이름으로 거절 분기를 본다.

**켠 API 도구는 `disabled_toolsets` 에서 뺀다.**
Hermes 는 허용 목록을 계산한 뒤 `agent.disabled_toolsets` 를 마지막에 빼므로, 설정 틀이 막아 둔 `code_execution` 을 목록에 넣어도 API 실행에서 빠진다.
그래서 요청 목록에 있는 이름이 `disabled_toolsets` 에 있으면 plugin 이 그 이름을 거기서 뺀다. API 경로의 도구는 `api_server` 목록 하나가 정한다.

- `disabled_toolsets` 는 모든 platform 에 걸린다. 목록이 없어 기본 toolset 을 쓰는 platform 가운데 계산 결과가 바뀌는 것은 지금 계산 결과를 명시 목록으로 먼저 고정한다
- 목록이 이미 있는 platform 에 그 도구가 열리게 되면 위의 다른 platform 검사가 거절한다. 운영자가 그 목록을 먼저 고친다
- 요청 목록에 없는 이름은 그대로 둔다. 도구를 끌 때 다시 넣지 않는다. API 는 허용 목록이 막는다
- `memory` 는 요청 목록에 들어올 수 없으므로 `disabled_toolsets` 에 남는다

Hermes 처리기의 병합은 본문의 키만 쓰므로, plugin 이 검사를 마친 설정 전체를 처리기보다 먼저 쓴다.
검사한 뒤 파일이 바뀌었으면 쓰지 않고 409 를 돌려준다. 처리기가 실패하면 파일이 plugin 이 쓴 그대로일 때만 원래 바이트로 되돌린다.
Control Plane 은 사용자 소유권과 도구 등급을 판정한다. plugin 은 도구 등급을 판정하지 않는다.

**`GET /api/skills` 는 query 에 `profile` 하나만 받는다.** 없으면 대시보드 자기 profile 의 스킬이 읽히기 때문이다.
**`PUT /api/skills/toggle` 은 본문 `{profile, name, enabled}` 만 받는다.** 켜고 끄기는 Hermes 의 전역 `skills.disabled` 를 바꾼다.

### 올린 스킬의 이름은 다른 스킬 경로와 겹치지 않아야 한다

v2026.9.24 는 스킬 이름 하나를 아래 경로에서 찾고, 위의 것이 먼저다. 파일은 이미지의 `/opt/hermes` 기준이다.

| 순서 | 경로 | 보는 호출 |
| --- | --- | --- |
| 1 | `skills.trusted_project_dirs` 로 신뢰한 저장소의 `.hermes/skills`, `.agents/skills`. 작업 디렉터리가 그 저장소일 때만 | 모두 |
| 2 | profile 의 `skills/`. bundled 스킬도 동기화로 여기 들어온다 | 모두 |
| 3 | `skills.create_dir` | 시스템 프롬프트 색인만 |
| 4 | `skills.external_dirs`. 올린 스킬이 여기 있다 | 모두 |

경로 목록은 `agent/skill_utils.py` 의 `get_project_skills_dirs`, `get_all_skills_dirs` 와 `tools/skills_tool.py` 의 `_skill_search_dirs` 가 만든다.
올린 경로 밖에 같은 이름이 있으면 올린 스킬이 실행되지 않는다. 일회용 컨테이너에서 확인했다.

- 시스템 프롬프트의 스킬 색인, `skills_list`, 슬래시 명령은 위 경로의 스킬을 고른다.
  `agent/prompt_builder.py` `build_skills_system_prompt`, `tools/skills_tool.py` `_find_all_skills`, `agent/skill_commands.py` `scan_skill_commands` 다
- `skill_view(이름)` 은 후보가 둘 이상이면 이름 충돌로 거절한다. `tools/skills_tool.py` `_locate_skill` 이다.
  후보는 디렉터리 이름과 frontmatter `name` 가운데 어느 쪽이 같아도 모인다

plugin 스킬은 `plugin:이름` 으로만 불려 겹치지 않는다.

**새 이미지의 bundled 스킬은 올린 스킬을 가리지 않는다.**
`tools/skills_sync.py` `sync_skills` 는 `external_dirs` 에 같은 이름이 있으면 profile 로 복사하지 않고,
전에 복사한 것이 새 이미지의 원본과 바이트까지 같을 때만 지운다.
가리는 것은 사람이 둔 로컬 스킬, 고쳤거나 이전 판인 bundled 복사본, hub 로 설치한 스킬, `create_dir` 과 신뢰한 저장소다.

운영 저장소의 스킬 이름 충돌 검사 스크립트가 profile 마다 이것을 본다.
판정은 `skill_view` 가 후보를 모으는 `_collect_skill_candidates` 를 부르고, 위 표의 경로를 모두 넘긴다.
올린 스킬이 있는 profile 은 설정의 스킬 경로가 절대 경로이고 실제로 있어야 판정한다. 아니면 판정하지 못함으로 실패한다.
정기 live 검사와 업그레이드 검사가 부르고, 업그레이드 전에는 새 이미지로 부른다.
순서는 운영 저장소의 운영 절차 문서가 소유한다.

**여기 없는 것은 맞는 토큰으로도 열리지 않는다.**
`GET /api/config` 와 이름을 바꾸는 `PATCH`, 스킬을 만들고 고치는 `POST /api/skills`, `PUT /api/skills/content` 가 여기 해당한다.
`DELETE /api/env` 는 관리 profile 의 플러그인 칸 key 만 받는다.

## 도구 상세 설명과 요약

schema 2 `tools.<이름>.description`과 `tools.<이름>.summary`를 선택 평문으로 받는다. 설명은 `tool_descriptions`, 요약은 `tool_summaries`에 정책 dict와 분리하여 보관하고 카탈로그 응답에만 합친다. 불량 설명의 fallback과 전체 manifest SHA, 옛 엄격 reader와의 호환 순서는 [플러그인 도구 정책](../../../docs/features/connector-policy.md)의 「표시 메타데이터와 보호 해시」를 따른다. 표시 계약은 [플러그인 연결](../../../docs/features/connector.md)의 「도구 이름과 요약」이 갖는다.

## 보관 파일 암호화와 실행기 설계

보관 파일 암호화(2026-11-03 확인)의 기술 설계다. 기능 폴더의 설계 자리를 다음 변경에 넘기면서 이 plugin 이 지키는 계약이라 여기로 옮겼다.
본문의 `REQ`, 「스펙 DECISION」 번호는 그때 스펙의 번호이고, 그 스펙은 git 이력의 `docs/features/connector/spec.md`(커밋 `d5a72ee4d`)에 있다. 장기 결정은 [ADR-20261103 / connector-vault-key](../../docs/adr/ADR-20261103-connector-vault-key.md) 가 갖는다.

### 책임과 경계

- **대시보드 plugin(`hermes/plugins/dashboard-profile-api`)이 key 를 읽고 암호화한다.** 보관 파일의 주인이 이 plugin 이기 때문이다. Control Plane 과 Hermes core 는 바뀌지 않는다.
- 새 모듈 둘로 나눈다. 파일 길이 상한(rule-44)과 책임 때문이다.

  | 모듈 | 맡는 것 |
  | --- | --- |
  | `connector_vault_key.py` | 운영 환경 변수와 key 파일 읽기, key 상태, AES-256-GCM 의 봉인과 풀기 |
  | `connector_vault.py` | 보관 파일의 경로, 모양 검사, 읽기와 쓰기, `PUT`·`DELETE` 처리. 지금 자리 그대로다 |
  | `connector_vault_migration.py` | 기동 이관. 파일을 하나씩 분류해 다시 쓰고 수를 센다 |
  | `connector_vault_format.py` | (PR2) 보관 파일의 모양 검사, AAD, 풀기. 실행기가 plugin 패키지 없이 읽도록 `connector_vault.py` 에서 옮긴다 |
  | `connector_launch.py` | (PR2) 실행기 정의를 만들고 알아보고 펼친다. 바인딩의 칸 값을 정의의 종류에 맞는 자리에서 읽는다 |
  | `connector_launcher.py` | (PR2) 실행기 스크립트. 보관 파일을 풀어 자식 env 를 만들고 실제 서버가 된다 |
  | `connector_binding_migration.py` | (PR2) 이미 붙은 stdio 바인딩을 실행기 정의로 다시 설치하는 기동 이관 |

- **key 는 Control Plane KEK 와 다른 key 다.** 파일 모양만 같다. Hermes 쪽이 Control Plane KEK 를 갖지 않게 한다(ADR).
- 암호화는 Hermes 이미지가 core 의존으로 고정한 `cryptography` 의 `AESGCM` 을 쓴다. 새 의존을 더하지 않는다. `hermes/tests/hermes_contract.py` 의 제3자 패키지 목록에 `cryptography` 를 더해, plugin 이 기대는 패키지를 Hermes 를 올릴 때 다시 확인하게 한다.

### 인터페이스와 데이터

#### 운영 환경 변수

대시보드 프로세스의 환경 변수다. 값과 파일의 자리는 `fos-home-infra` 가 정한다. 코드에 기본값이나 경로 상수를 두지 않는다.

| 이름 | 뜻 |
| --- | --- |
| `FOS_ASSISTANT_CONNECTOR_VAULT_KEY_FILE` | key 파일의 절대 경로 |
| `FOS_ASSISTANT_CONNECTOR_VAULT_ACTIVE_KEY_ID` | 새로 암호화할 key 의 id |
| `FOS_ASSISTANT_CONNECTOR_VAULT_KEY_REQUIRED` | 「key 필수」. `true` 면 key 를 쓸 수 없을 때 보관 파일 쓰기를 거절한다. 비었거나 `false` 면 끈다. 그 밖의 값은 켠 것으로 본다(틀린 값이 평문 쓰기로 이어지지 않게) |

#### key 파일

Control Plane 의 KEK 파일(`FileKeyEncryptionKeys`)과 같은 모양이다. 운영이 한 절차로 만들게 하려는 것이다.

- 한 줄에 `<id>:<base64 32바이트>`. id 는 `[a-z0-9][a-z0-9-]{0,31}` 이다. 빈 줄과 `#` 로 시작하는 줄은 건너뛴다.
- id 가 겹치거나, base64 가 아니거나, 32바이트가 아니거나, 활성 id 가 파일에 없으면 파일 전체를 「모양이 틀림」 으로 본다.
- 파일을 그룹이나 다른 사용자가 읽을 수 있으면 기동 때 경고만 남긴다.
- 예외 메시지와 로그에 key 값과 파일 경로를 넣지 않는다.

#### key 상태

`_vault_keys()` 가 부를 때마다 환경 변수와 파일을 읽어 아래 셋 가운데 하나와, 파일에서 읽은 key 들을 낸다. 파일은 작고 쓰기와 읽기는 드물어 캐시를 두지 않는다. 그래서 key 파일을 바꾸면 다음 요청부터 쓰인다. 다만 옛 key 파일의 이관은 기동 때만 돈다.

| 상태 | 조건 |
| --- | --- |
| 준비됨 | 두 환경 변수가 있고 파일을 읽었으며 활성 id 가 파일에 있다 |
| 없음 | 두 환경 변수가 모두 비었다 |
| 틀림 | 하나만 있거나, 파일을 읽지 못했거나, 파일 모양이 틀렸거나, 활성 id 가 파일에 없다 |

**읽기 key 와 쓰기 key 를 나눈다.** 파일을 읽었으면 활성 id 가 틀려도 파일의 key 로 v2 를 푼다. 활성 id 오타나 회전 순서 실수 하나로 모든 연결의 확인과 바인딩 설치가 멈추지 않게 하려는 것이다. 쓰기만 「준비됨」 을 요구한다.

#### 보관 파일 v2

```json
{"v": 2, "connector": "<플러그인 id>", "key_id": "<key id>", "sealed": "v1.<IV>.<암호문과 태그>"}
```

- `sealed` 는 Control Plane 의 `AesGcm` 과 같은 모양이다. IV 는 매번 새로 뽑는 12바이트, 태그는 128비트, 두 조각은 패딩 없는 base64url 이다.
- 평문은 `values` 의 JSON(`sort_keys`, UTF-8)이다.
- AAD 는 `connector-vault:<vault>:<connector>` 의 UTF-8 이다. `<vault>` 는 파일 이름의 연결 번호(`c<번호>`), `<connector>` 는 파일의 `connector` 값이다.
- `connector` 와 `key_id` 는 평문으로 둔다. 쓰기 전 소유 확인과 기동 이관이 풀지 않고 읽는다. 어느 연결이 어느 플러그인인지는 Control Plane DB 에도 있어 비밀이 아니다.
- 모양 검사는 v1 과 같이 엄격하다. 키 집합이 정확히 위 넷이고 `key_id` 는 id 모양, `sealed` 는 문자열이어야 한다.

#### 읽기와 쓰기

| 동작 | 준비됨 | 없음 | 틀림 |
| --- | --- | --- | --- |
| `PUT`(쓰기) | v2 로 쓴다 | 「key 필수」 면 503, 아니면 v1 로 쓰고 경고 로그 | 「key 필수」 면 503, 아니면 v1 로 쓰고 경고 로그 |
| 읽기 v1 | 읽는다 | 읽는다 | 읽는다 |
| 읽기 v2 | 그 `key_id` 의 key 로 푼다. 활성 key 가 아니어도 파일에 있으면 푼다 | 실패 | 파일을 읽었으면 준비됨과 같다. 못 읽었으면 실패 |

- 「틀림」 에서 「key 필수」 가 꺼져 있으면 평문으로 쓰는 것은 스펙 REQ4 의 「끄면 평문으로 쓰고 경고를 남긴다」 를 따른 것이다. 운영은 「key 필수」 를 켠다.
- `_read_vault(vault)` 의 반환은 지금처럼 `{"v", "connector", "values"}` 다. `v` 는 파일의 버전이다. 부르는 쪽(`connector_run.py`, `connector_install.py`)은 바뀌지 않는다.
- 읽기 실패는 지금의 「모양이 틀림」 과 같이 `ValueError` 다. 메시지에 값, key, 경로가 없다. `call`·`tools` 는 `unavailable` 로, 바인딩 설치는 503 으로 답한다. 지금 동작 그대로다.
- `_write_vault` 의 소유 확인(같은 이름의 파일이 다른 플러그인의 것이면 409)은 **풀지 않고** 파일의 `connector` 만 읽는다. key 를 잃어 풀 수 없는 파일도 같은 플러그인의 새 값으로 덮어쓸 수 있어야 「다시 등록」 이 된다. Control Plane 은 같은 사용자의 같은 플러그인 연결에 같은 보관 파일 이름을 다시 쓴다.
- 모양을 읽지 못하는 파일(JSON 이 아니거나 키가 다름)은 주인을 알 수 없다. 경고를 남기고 덮어쓴다. 보관 파일 이름은 연결마다 하나라 다른 플러그인과 부딪히지 않고, 막으면 그 연결은 다시 등록으로 고칠 수 없다.
- 보관 파일 쓰기는 임시 파일을 `fsync` 한 뒤 바꿔 넣고 디렉터리도 `fsync` 한다. 이관이 모든 파일을 한 번에 다시 쓰므로 전원이 나가도 옛 파일이나 새 파일 가운데 하나가 남게 한다.
- 「key 필수」 로 거절할 때는 기존 503 경로를 쓰되 로그에 「key 를 쓸 수 없어 보관 파일을 쓰지 않았다」 와 key 상태만 남긴다. Control Plane 은 503 을 쓰기 실패로 받아 연결을 `PENDING` 으로 남기고 `CONNECTOR_OPERATION_FAILED` 로 답한다(`ConnectorConnectionService` 의 보관 파일 단계, [`connector.md`](../../../docs/features/connector.md) 의 「연결의 등록, 확인, 해제」 표). 그래서 화면에 실패로 보인다.

### 기동 이관(REQ3, DECISION3, DECISION5, DECISION7)

**`register` 가 비밀값의 강도를 확인한 직후, 경로를 감싸는 `_install_gate()` 와 provider 등록보다 먼저 한 번 돈다.** 이 plugin 의 보관 파일 경로는 그 뒤에야 열리므로 이관은 요청과 겹치지 않고 profile 쓰기 잠금이 필요 없다. 비밀값이 없거나 약해 경로를 열지 않는 설치에서는 돌지 않는다.
이관 전체를 예외로 감싸 어떤 실패도 기동을 멈추지 않게 한다. 바깥 처리는 예외 클래스 이름만 남긴다. key 파일을 열다 나는 `OSError` 는 메시지에 경로가 있으므로 「틀림」 상태로만 바꾸고 메시지를 쓰지 않는다.

순서는 이렇다.

1. key 상태를 읽어 로그에 남긴다. 상태 이름과 「key 필수」 여부만 남긴다. 「key 필수」 인데 준비되지 않았으면 error 로 남긴다. key 파일 권한이 넓으면 경고한다.
2. 보관 파일 디렉터리가 없으면 수를 0 으로 남기고 끝낸다. 디렉터리가 링크면 경고하고 끝낸다.
3. 쓰기 중에 프로세스가 죽어 남은 임시 파일(`.connector-` 로 시작)은 평문일 수 있으므로 지우고 그 수를 센다. 기동 중에는 쓰는 쪽이 없다.
   그 밖에는 이름이 `c<번호>.json` 인 파일만 이름 순서로 본다. 다른 이름은 건너뛴다. 링크인 파일은 「평문 파일」 로 세고 건드리지 않는다.
4. 파일마다 아래 표로 분류해 처리한다. key 가 준비되지 않았으면 다시 쓰지 않고 분류해 세기만 한다.
5. 끝나면 info 한 줄에 수를 남긴다. 남은 파일이 있으면 그 이름(`c<번호>`)만 warning 한 줄로 남긴다.

| 분류 | 처리 | 남으면 세는 칸 |
| --- | --- | --- |
| 모양이 맞는 v1 | 활성 key 로 v2 를 써서 바꾼다 | 평문 파일 |
| 읽지 못하거나 모양이 틀린 파일(JSON 아님, 키가 다름) | 건드리지 않는다(DECISION5) | 평문 파일 |
| 활성 key 의 v2 | 풀어 본다. 풀리면 그대로 둔다 | 풀리지 않으면 풀지 못한 파일 |
| 다른 key 의 v2, 그 key 가 파일에 있다 | 풀어서 활성 key 로 다시 쓴다(DECISION7) | 풀리지 않으면 옛 key 파일 |
| 다른 key 의 v2, 그 key 가 파일에 없다 | 건드리지 않는다 | 옛 key 파일 |

- **다시 쓴 파일은 곧바로 다시 읽어 푼 값이 옮기기 전 값과 같은지 본다.** 다르거나 실패하면 원래 바이트로 되돌리고 남은 칸에 센다. 이관이 값을 잃지 않게 하려는 것이다.
- 쓰기는 지금 쓰기와 같은 원자적 쓰기와 600 권한이다.
- 다른 연결에서 복사해 온 v2 는 AAD 가 맞지 않아 「풀지 못한 파일」 이나 「옛 key 파일」 로 남는다. 다른 연결에서 복사해 온 v1 은 구별할 수 없어 그대로 v2 로 바뀐다. v1 에는 묶을 정보가 없기 때문이고, 이것은 지금과 같다.
- 로그는 `보관 파일 이관: key=<상태> 필수=<참/거짓>, 그대로 둔 암호화 파일 N, v1 에서 옮김 N, 옛 key 에서 옮김 N, 남은 평문 파일 N, 남은 옛 key 파일 N, 풀지 못한 파일 N, 지운 임시 파일 N` 꼴이다. 운영은 배포 뒤 평문 파일과 옛 key 파일이 모두 0 인지 본다(REQ3). 옛 key 파일 수는 `key=ready` 일 때만 근거가 된다. key 가 준비되지 않으면 암호화한 파일을 모두 「그대로 둔 암호화 파일」 로 세고 옛 key 여부를 세지 않았다는 경고를 남긴다. `key=ready` 이고 옛 key 파일이 0 이면 그 key 를 key 파일에서 빼도 된다.

#### key 회전(DECISION7)

1. 운영이 key 파일에 새 줄을 더하고 활성 id 를 새 id 로 바꾼 뒤 대시보드를 다시 띄운다.
2. 기동 이관이 옛 key 의 파일을 모두 새 key 로 다시 쓰고, 남은 옛 key 파일 수를 남긴다.
3. 그 수가 0 이면 옛 줄을 지운다. 0 이 아닌데 옛 줄을 지우면 남은 파일은 풀리지 않아 그 연결을 다시 등록해야 한다.

### 실패와 일관성

- **이관 중 파일 하나가 실패해도 기동은 잇는다(DECISION5).** 그 파일은 원래 바이트로 남고 이름만 로그에 남는다.
- 기동 이관과 요청이 겹치지 않는다. `register` 가 끝난 뒤에 경로가 열린다.
- key 를 잃으면 v2 파일을 풀 수 없다. 평문으로 되돌리는 스크립트는 두지 않고 연결을 다시 등록한다(스펙 DECISION2). 이 PR 의 코드는 같은 이름의 보관 파일을 새 값으로 덮어쓴다.
- **이전 이미지로 되돌리면 연결을 해제한 뒤 다시 등록한다(스펙 DECISION6).** 이전 이미지의 쓰기는 소유 확인에서 v2 파일을 읽지 못해 503 이므로, 값만 다시 넣는 것으로는 풀리지 않는다. 해제는 이전 이미지에서도 보관 파일을 지우므로 그 뒤의 등록은 새 파일을 쓴다.
- PR1 배포 순서: 운영이 key 파일과 세 환경 변수를 넣는다 → 배포 → 기동 로그의 평문 파일 수와 옛 key 파일 수가 0 인지 본다 → 도구 호출을 한 번 왕복시킨다. key 없이 배포해도 지금처럼 평문으로 돈다(「key 필수」 를 켜지 않았다면).
- PR1 은 profile `.env` 사본을 바꾸지 않는다. 그래서 「key 필수」 로 쓰기를 거절해도 이미 붙은 바인딩의 Hermes 쪽 도구 호출은 `.env` 사본으로 그대로 돈다(REQ4). 대시보드의 `call`·`tools` 는 v1 파일과 풀 수 있는 v2 파일을 그대로 읽는다.

### PR2: 실행기와 사본 제거(REQ6, REQ7, REQ8)

PR2 는 위 설계를 다시 쓰지 않고 이어서 쓴다. 보관 파일의 모양, key 상태, 기동 이관은 그대로다.

#### 실행기 정의(DECISION12)

stdio 플러그인의 바인딩 설치는 서버 정의를 아래 모양으로 쓴다. 원격 플러그인은 지금처럼 `.env` 에 쓴다(DECISION8).

```yaml
command: <대시보드 프로세스의 python>
args: ["-I", <plugin 의 connector_launcher.py>, <보관 파일 id>, <플러그인 id>, "<칸 key>=<env 이름>", ..., ["@oauth=<token_env>"], "--", <실제 command>, <실제 args>...]
env:
  <운영자 env>: <운영 목록의 값>
  <주인 env>: <그 바인딩의 값이나 빈 값>
  <oauth.token_env>: ""
  FOS_ASSISTANT_CONNECTOR_VAULT_DIR: <보관 파일 디렉터리>
  FOS_ASSISTANT_CONNECTOR_VAULT_KEY_FILE: <대시보드 프로세스의 같은 이름의 값. 없으면 빈 값>
  FOS_ASSISTANT_CONNECTOR_VAULT_REVISION: <보관 파일의 판>
enabled: true
```

- 칸 env 는 정의에도 `.env` 에도 없다. 실행기가 띄울 때 채운다.
- **단추 연결을 선언한 플러그인은 연결 방식과 상관없이 `@oauth=<token_env>` 인자를 둔다**([ADR-20261120 / connector-oauth](../../../docs/adr/ADR-20261120-connector-oauth.md)). 실행기가 OAuth 파일의 토큰 통로 주소를 그 env 에 넣고, 파일이 없으면(붙여 넣기 연결) 빈 값이다. 정의 env 의 그 이름은 빈 값이라 profile `.env` 를 거치지 않는다. 방식을 바꿔도 정의가 같고, refresh token 이 바뀌어도 리비전이 그대로라 재시작 대기가 생기지 않는다. 직접 실행과 금융 준비(`_binding_env`)도 같은 주소를 넣는다.
- 경로 값은 모두 설치할 때 대시보드 프로세스에서 읽는다(`sys.executable`, 이 plugin 의 디렉터리, `_vault_dir()`, 운영 env). 코드에 경로 상수나 기본값을 두지 않으므로 공개 저장소에는 이름만 있다.
- **보관 파일 디렉터리를 정의로 넘긴다.** Hermes 는 자식 env 를 `PATH`, `HOME` 같은 몇 이름과 정의의 `env` 로만 만든다(`_build_safe_env`, [`hermes-contract.md`](../../docs/hermes-contract.md) 의 「MCP 프로세스의 환경 값」). `HERMES_HOME` 이 오지 않아 실행기가 Hermes 루트를 스스로 구하지 못한다.
- **활성 key id 는 넘기지 않는다.** 실행기는 읽기만 하고, 읽기는 활성 id 와 상관없이 key 파일의 key 로 푼다(위 DECISION7). 넘기면 key 를 회전할 때마다 모든 정의가 낡는다.
- **보관 파일 리비전(`REVISION`)을 정의에 둔다(REQ8).** 리비전은 파일의 inode, 수정 시각(ns), 크기의 SHA-256 이다. 파일 내용을 해시하지 않아 v1 평문에서도 값의 단서가 정의와 설정 백업에 남지 않는다. 토큰을 바꾸면 파일이 바뀌어 리비전이 달라지고, 다시 설치가 쓰는 정의가 달라져 지금 규칙(`previous != server`) 그대로 `restart_required` 가 참이다. 소유 기록의 모양은 바꾸지 않는다. 그래서 이전 이미지도 그 기록을 읽는다(아래 「되돌리기」).
- **같은 값의 보관 파일은 다시 쓰지 않는다.** v2 는 IV 를 매번 새로 뽑아 같은 값도 다른 바이트가 된다. `_write_vault` 는 이미 있는 파일이 활성 key 의 v2(key 가 준비되지 않았으면 v1)이고 값이 같으면 쓰지 않는다. 같은 값으로 다시 등록해도 리비전이 그대로라 붙은 바인딩이 재시작 대기가 되지 않는다. key 회전과 v1 이관은 파일을 다시 쓰므로 그 뒤 첫 다시 설치는 재시작 대기를 답한다.
- 정의는 그 profile 의 `config.yaml` 과 설정 백업에만 남는다. 칸 값은 어디에도 없다.

#### 실행기(`connector_launcher.py`)

대시보드 plugin 디렉터리 안의 독립 스크립트다. Hermes 의 python 이 스크립트로 연다.

- python 은 격리 모드(`-I`)로 연다. `PYTHON` 으로 시작하는 env, 사용자 site, 스크립트 디렉터리를 import 경로에 넣지 않는다. 실행기는 표준 라이브러리와 venv 의 `cryptography` 만 쓴다. Hermes 가 자기 프로세스에 더하는 site 경로에 기대지 않는다.

- plugin 패키지의 `__init__.py` 를 실행하지 않는다. 자기 디렉터리를 하위 모듈 검색 경로로 둔 빈 패키지를 만들어 `connector_vault_format.py`(보관 파일 모양, AAD, 풀기)와 `connector_vault_key.py`(key 파일, AES-GCM)만 읽는다. 두 모듈은 표준 라이브러리, `cryptography`, `connector_schema.py` 만 쓴다. 대시보드와 같은 풀기 코드를 쓰려는 것이다.
- 순서는 이렇다.
  1. 인자를 본다. 보관 파일 id, 플러그인 id, 칸 key, env 이름은 대시보드와 같은 정규식이고, `--` 뒤에 command 가 있어야 한다. 칸 대응의 순서는 manifest 의 칸 순서다.
  2. `FOS_ASSISTANT_CONNECTOR_VAULT_DIR` 아래 `<id>.json` 을 읽는다. 디렉터리나 파일이 링크면 거절한다.
  3. v1 이면 그대로, v2 면 `FOS_ASSISTANT_CONNECTOR_VAULT_KEY_FILE` 을 `_parse_key_file` 로 직접 읽은 key 묶음으로 푼다. `_vault_keys()` 는 활성 id 가 없으면 key 를 내지 않으므로 쓰지 않는다. AAD 는 대시보드와 같다.
  4. 파일의 `connector` 가 인자의 플러그인 id 와 같은지 본다.
  5. 자식 env 는 받은 env 에서 `FOS_ASSISTANT_CONNECTOR_VAULT_` 로 시작하는 이름을 모두 지우고 칸 값을 넣은 것이다. 보관 파일에 없는 칸은 빈 값이다. 플러그인 코드가 key 위치를 받지 않게 한다.
  6. `PATH` 앞에 실제 command 의 디렉터리를 붙인다. Hermes 가 정의의 command 에 하는 것과 같다. 그 뒤 `os.execve` 로 실제 서버가 된다.
- 칸 값은 칸 대응에 있는 칸만 넘긴다. 대응은 설치 때 지금 manifest 의 칸으로 만든 것이다. 값의 모양은 보관 파일을 쓸 때 이미 검사했다.
- 모듈 읽기와 `execve` 까지 모두 예외 처리 안에 둔다. 잡지 못한 예외의 traceback 이 경로를 stderr 에 쓰지 않게 한다.
- 실패하면 값과 경로 없이 이유 단어 하나(`arguments`, `vault_missing`, `vault_unreadable`, `connector_mismatch`, `exec_failed`)를 stderr 에 쓰고 78 로 끝난다. 그 MCP 서버만 연결되지 않고, stderr 는 그 profile 의 MCP stderr 기록에 모인다.

#### 정의 비교와 옛 정의 호환

- **실행기 정의를 알아보는 규칙**은 args 가 `-I`, 파일 이름이 `connector_launcher.py` 인 경로, 보관 파일 id, 플러그인 id 로 시작하고 `--` 와 그 뒤의 command 가 있는 것이다(`_launcher_parts`). 그 밖은 옛 정의나 원격 정의로 본다.
- **`_server_matches` 는 실행기 정의를 옛 모양으로 펼쳐 지금 규칙으로 비교한다.** 펼친 모양(`_launcher_inner`)은 command 가 실제 command, args 가 실제 args, env 가 실행기 env 셋을 뺀 env 에 칸 env 를 `${이름}` 으로 더한 것이다.
- 실행기 칸은 따로 본다. python, 실행기 경로, 보관 파일 디렉터리, key 파일 값이 지금 대시보드의 값과 같고, 칸 대응이 지금 manifest 의 칸 key 와 env 이름과 같고, 플러그인 id 가 manifest 의 id 와 같아야 한다. 정의 env 에 칸 env 이름이 없어야 한다. `REVISION` 은 64자리 16진수인지만 본다.
- 경로가 바뀌면(이미지의 python 자리, key 파일 자리) 맞지 않음으로 본다. 상태 조회가 `configured: false` 로 답하고, Control Plane 의 연결 확인이 다시 설치해 새 정의를 쓴다. 이 비교가 없으면 재시작 뒤 서버가 조용히 뜨지 않는다.
- 옛 정의(`${이름}` 참조)는 지금 규칙 그대로 맞다고 본다. 보관 파일을 풀지 못해 남긴 바인딩(DECISION10)과 원격 플러그인이 끊기지 않는다.
- `_connector_state` 의 모양 검사는 실행기 정의의 보관 파일 id 가 기록의 `vault` 와 같은지도 본다.
- `_recoverable_server`(설정만 새 `tools.exclude` 인 정의의 복구)는 `REVISION` 을 빼고 비교한다. 금융 도구를 숨긴 profile 에서 보관 파일이 바뀌어도 다시 설치가 거절되지 않는다.
- `_entry_manifest_mismatches`, `_bind_entry_env`, `_entry_field_env` 는 펼친 모양으로 본다. 그래서 떼기는 실행기 정의를 지우고, `.env` 에 남은 그 칸 줄(옛 사본)도 지운다.

#### 바인딩 설치

- stdio 는 실행기 정의를 쓰고 `.env` 에서 이 플러그인의 칸 줄을 지운다. 칸 env 이름의 겹침 검사는 지금과 같다.
- 설치 요청은 보관 파일을 읽는 같은 잠금 안에서 리비전을 구해 넘긴다.
- python 경로(`sys.executable`)가 비었거나 절대 경로가 아니면 설치를 거절한다(503).
- `restart_required` 는 지금 규칙 그대로다. 옛 정의에서 실행기 정의로 바뀌거나 `REVISION` 이 바뀌면 참이다.

#### 원래 값을 받는 다른 자리

`_binding_env(profile_dir, manifest, entry)` 하나가 바인딩의 칸 env 값을 낸다. 실행기 정의면 기록의 `vault` 를 `_read_vault` 로 읽고 `connector` 가 같은지 본다. 옛 정의와 원격이면 지금처럼 `.env` 를 읽는다. 값의 자리가 정의의 종류를 따라가므로, 옛 방식으로 남은 바인딩도 지금처럼 돈다.

| 자리 | 쓰는 곳 | 풀지 못하면 |
| --- | --- | --- |
| 직접 실행 | `connector_run.py` 의 execute 와 prepare | 지금의 `profile` 단계 실패(`unavailable`) |
| 실행 가드 scope 검사 | `connector_prepare.py` 가 `_binding_env` 를 다시 불러 같은 값인지 본다 | `scope` 단계 거절 |
| 스냅샷 | `connector_snapshot.py` | 503 |

#### `PUT /api/env`

칸 env 이름(카탈로그 플러그인의 `fields[].env`)을 400 으로 거절한다. Control Plane 은 이 경로로 칸 값을 쓰지 않는다. 기본 key 셋만 쓴다(`HermesProfileProvisioner`). 운영자 env 는 지금처럼 쓰지 않고 성공으로 답한다(ADR-041). `DELETE /api/env` 는 남은 사본을 지울 수 있게 그대로 받는다.

#### 기동 이관(REQ7, DECISION10)

`register` 가 보관 파일 이관 바로 뒤, 경로를 감싸기 전에 한 번 돌린다(위 DECISION8 과 같은 까닭). 둘을 따로 예외로 감싸 하나가 멈춰도 다른 것은 돌고, 기동은 잇는다.

**바인딩 설치(`_connector_bind_config`)를 다시 돌리지 않는다.** 설치는 스킬, hook plugin 파일, API 도구 목록도 검사해 거절할 수 있고, 그러면 풀 수 있는 바인딩의 사본이 남는다. 이관은 그 서버 정의, 소유 기록의 그 항목, 이름 대응, `.env` 의 그 칸 줄만 바꾼다.

1. `list_profile_names()` 의 profile 마다 소유 기록이 있고 관리 표식이나 플러그인 표식이 있는 것만 본다. 소유 기록을 읽지 못하면 세고 넘어간다.
2. 항목마다 원격이면 건너뛰고 원격 수에 센다.
3. 실행기 정의이고, `.env` 에 그 칸 줄이 없고, 실행기 칸이 지금 값과 맞으면(manifest 가 없으면 그것만 빼고) 이미 옮긴 것으로 두고 손대지 않는다.
4. 펼친 모양이 지금 manifest 와 맞지 않으면 남긴다. 칸 대응을 알 수 없다. 다시 연결하면 새 정의로 설치된다.
5. `config.yaml` 의 그 서버가 소유 기록과 다르면 남긴다. 운영자가 바꾼 정의는 `.env` 를 참조할 수 있다.
6. 보관 파일이 없거나 풀지 못하거나 그 플러그인의 것이 아니면 남긴다(DECISION10). 옛 정의와 `.env` 사본으로 계속 돈다.
7. 그 밖이면 펼친 모양에서 실행기 정의를 만들어 `config.yaml`, 소유 기록, 이름 대응을 쓰고, 마지막에 `.env` 의 그 칸 줄을 지운다. 이미 지금 값의 실행기 정의에 사본만 남았으면 사본만 지우고 「그대로 둔 실행기 바인딩」 에 센다. 정의가 그대로라 재시작이 필요 없다. 판은 이관이 푼 바로 그 파일로 구한다. 쓰기 전에 설정 백업을 남기고, 쓰기가 실패하면 쓴 파일을 되돌린다. `.env` 를 맨 뒤에 써서 앞에서 멈추면 사본이 남고 다음 기동이 다시 지운다.
8. 끝나면 info 한 줄을 남긴다. `바인딩 이관: 실행기로 옮김 N, 그대로 둔 실행기 바인딩 N, 남은 사본 N, 원격 N, 읽지 못한 소유 기록 N` 꼴이다. 남은 바인딩은 플러그인 id 와 수만 warning 한 줄로 남기고, profile 이름과 경로는 남기지 않는다. 옮긴 것이 있으면 「gateway 재시작이 필요하다」 를 warning 으로 남긴다. 이관은 요청이 아니라 Control Plane 에 신호가 가지 않는다. 운영이 이 줄을 보고 재시작한다.

- 다시 돌리면 옮긴 바인딩은 3 에서 그대로 두므로 바뀌는 파일이 없다.
- 「남은 사본」 은 4 부터 6 으로 남긴 stdio 바인딩 수다. 운영은 배포 뒤 이 수가 0 인지 본다.
- 이미지를 바꿔 python 이나 plugin 경로가 바뀐 실행기 정의도 3 을 지나지 않으므로 7 에서 지금 경로로 다시 쓴다.

#### 되돌리기

- PR1 이미지로 되돌리면 그 이미지에는 실행기가 없어 실행기 정의의 서버가 뜨지 않는다. 소유 기록의 모양은 같으므로 그 이미지의 바인딩 설치가 기록을 읽는다. 모든 연결의 연결 확인을 돌리면 다시 설치가 옛 정의와 `.env` 사본을 다시 쓰고 `restart_required` 로 답한다. 그 뒤 gateway 를 재시작한다.
- 이미 재시작 대기로 남은 바인딩은 연결 확인이 설치를 다시 보내지 않는다. 그런 바인딩과 다시 설치가 거절된 바인딩은 떼고 다시 붙인다.
- 실행기 정의를 평문 방식으로 되돌리는 스크립트는 두지 않는다.

#### 운영 순서

배포 → 대시보드 기동 로그의 「바인딩 이관」 줄에서 남은 사본이 0 인지 본다 → gateway 재시작 → stdio 연결마다 도구 호출을 한 번 왕복시킨다. 남은 사본이 있으면 그 연결을 다시 등록한다. 확인 절차는 `fos-home-infra` 가 갖는다.
대시보드와 gateway 가 같은 파일 시스템과 계정에서 돈다는 것이 전제다. 정의의 경로를 gateway 의 MCP 자식이 그대로 연다(운영 확인 2026-11-04: 같은 컨테이너, 같은 계정, 같은 venv python).
배포와 gateway 재시작 사이에는 떠 있는 서버가 옛 값을 쥔 채 돈다. 그 사이 서버가 다시 뜨면 옛 정의의 `${이름}` 이 지워진 사본을 가리켜 값 없이 뜰 수 있으므로, 재시작은 배포 직후에 한다.

### 검증 전략

PR1 의 요구사항은 대시보드 plugin 의 Python 단위 테스트로 실제 파일을 만들어 확인한다. Hermes 없이 도는 기존 테스트 기반(`dashboard_profile_api_support.ProfileApiRouteTest`)을 쓴다.

| REQ | 경계 | 확인 |
| --- | --- | --- |
| REQ1 | `PUT /api/connector-vault`, `call`, 바인딩 설치 | key 를 준 PUT 뒤 파일이 v2 이고 파일 글에 칸 값이 없다. 같은 vault 로 `call`·`tools` 의 자식 env 와 바인딩 설치의 `.env` 가 원래 값이다 |
| REQ2 | `_read_vault` | 다른 연결 이름으로 복사한 파일, `connector` 값만 고친 파일이 읽기에 실패하고, 예외 메시지와 로그에 값이 없다 |
| REQ3 | 기동 이관 함수 | v1 파일, 옛 key 파일, 깨진 파일, 다른 연결의 파일, 모르는 key 의 파일, 남은 임시 파일을 두고 이관한 뒤 파일 모양과 로그의 수를 본다. 다시 돌리면 아무것도 바뀌지 않는다 |
| REQ4 | `PUT`, `call` | 「key 필수」 와 key 없음·틀림에서 PUT 이 503 이고 파일이 바뀌지 않는다. 같은 상태에서 v1 보관 파일의 `call` 은 돈다. 끄면 v1 로 쓰고 경고 로그가 남는다 |
| REQ5 | 문서 | 공개 정보 검사(`check-public-safe.sh`)와 리뷰 |

PR2 의 요구사항도 같은 테스트 기반으로 실제 파일과 실제 자식 프로세스를 만들어 확인한다. 실행기는 테스트의 python 으로 실제로 띄워, 받은 env 를 파일로 내는 대역 서버로 바꿔 끼운다.

| REQ | 경계 | 확인 |
| --- | --- | --- |
| REQ6 | 바인딩 설치, 실행기, 직접 실행, prepare, 스냅샷, `PUT /api/env` | 설치 뒤 `.env` 와 `config.yaml` 에 칸 값이 없고 정의가 실행기 모양이다. 그 정의의 command 와 args, env 로 실행기를 띄우면 대역 서버가 원래 값을 받고 `FOS_ASSISTANT_CONNECTOR_VAULT_` 이름은 받지 않는다. 직접 실행, prepare 의 scope 검사, 스냅샷의 자식 env 가 원래 값이다. 보관 파일을 지우거나 다른 연결로 바꾸면 실행기가 78 로 끝나고 stderr 에 값과 경로가 없다. 떼면 실행기 정의와 남은 사본이 지워진다. `PUT /api/env` 의 칸 key 가 400 이다. `_server_matches` 가 옛 정의와 실행기 정의를 모두 맞다고 보고, 경로가 바뀐 실행기 정의는 맞지 않다고 본다 |
| REQ7 | 기동 이관 함수 | 옛 정의와 `.env` 사본을 가진 바인딩, 보관 파일을 풀지 못하는 바인딩, 원격 바인딩을 두고 이관한 뒤, 앞의 것은 실행기 정의이고 사본이 없으며, 가운데 것은 옛 정의와 사본이 그대로이고, 원격은 그대로인지와 로그의 수를 본다. 다시 돌리면 파일이 바뀌지 않는다 |
| REQ8 | 보관 파일 PUT 과 다시 설치 | 같은 값으로 다시 설치하면 `changed: false` 이고, 값을 바꿔 PUT 한 뒤 다시 설치하면 `restart_required: true` 다 |
| 원격 | 바인딩 설치 | 원격 플러그인은 지금처럼 `.env` 에 쓴다 |

예외와 로그에 값이 없다는 것은 모든 실패 테스트에서 칸 값과 key 의 base64 가 로그 출력과 응답에 없는지로 본다.
운영의 수(평문 파일 0, 옛 key 파일 0, 남은 사본 0)와 gateway 재시작 뒤 도구 호출 왕복은 배포 뒤 코디네이터가 `fos-home-infra` 의 절차로 본다.

### 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | 환경 변수 이름 | 환경 변수 이름을 `FOS_ASSISTANT_CONNECTOR_VAULT_` 로 시작한다 | 작성자 | 대시보드 운영 값의 접두 `FOS_ASSISTANT_` 를 따르고, Control Plane 의 데이터 암호화 환경 변수와 이름으로 구분된다 |
| DECISION2 | key 캐시 | key 를 캐시하지 않고 부를 때마다 읽는다 | 작성자 | 파일이 작고 쓰기가 드물다. 캐시를 두면 테스트와 key 교체 때 낡은 값을 쓴다 |
| DECISION3 | 「key 필수」 의 모르는 값 | 「key 필수」 의 모르는 값은 켠 것으로 본다 | 작성자 | 오타가 평문 쓰기로 이어지지 않게 한다 |
| DECISION4 | 쓰기 전 소유 확인 | 쓰기 전 소유 확인은 풀지 않고 `connector` 만 본다 | 작성자 | key 를 잃은 파일도 같은 플러그인의 새 값으로 덮어써야 다시 등록이 된다 |
| DECISION5 | 깨진 파일을 세는 칸 | 깨진 파일은 「평문 파일」 칸에 센다 | 작성자 | 스펙 DECISION5 의 「평문 파일 수로 드러낸다」 를 따른다. 배포 뒤 0 이면 깨진 파일도 없다 |
| DECISION6 | 이관 쓰기의 대조 | 다시 쓴 파일은 다시 읽어 값을 대조하고, 다르면 원래 바이트로 되돌린다 | 작성자 | 기억 이관과 같이 이관이 값을 잃지 않게 한다 |
| DECISION7 | 활성 id 가 틀릴 때의 읽기 | 읽기는 활성 id 와 상관없이 파일의 key 로 푼다 | 작성자 | 활성 id 하나가 틀려 모든 연결이 멈추지 않게 한다. 쓰기만 활성 key 를 요구한다 |
| DECISION8 | 이관을 돌리는 때 | 이관을 경로 감싸기와 provider 등록보다 먼저 돌린다 | 작성자 | 요청과 겹치지 않음을 Hermes 의 호출 순서 가정 없이 보장한다 |
| DECISION9 | 모양을 읽지 못하는 파일의 덮어쓰기 | 모양을 읽지 못하는 파일은 덮어쓴다 | 작성자 | 주인을 알 수 없고 연결마다 이름이 하나라, 막으면 다시 등록으로 고칠 수 없다 |
| DECISION10 | 토큰 교체를 알리는 자리 | 보관 파일의 판(파일 stat 의 해시)을 소유 기록이 아니라 실행기 정의의 env(`REVISION`)에 둔다. 같은 값은 다시 쓰지 않는다 | 작성자 | 정의가 바뀌어 지금 규칙으로 `restart_required` 가 참이 된다. 소유 기록의 모양이 그대로라 이전 이미지가 기록을 읽어 되돌리기가 다시 설치로 끝난다 |
| DECISION11 | 실행기에 주는 key 정보 | key 파일 위치만 주고 활성 key id 는 주지 않는다 | 작성자 | 읽기는 활성 id 를 쓰지 않는다. 주면 key 회전마다 모든 정의가 낡는다 |
| DECISION12 | 보관 파일 디렉터리 | 설치 때 대시보드가 구한 디렉터리를 정의의 env 로 준다 | 작성자 | Hermes 가 자식에게 `HERMES_HOME` 을 넘기지 않아 실행기가 루트를 구하지 못한다 |
| DECISION13 | 대시보드 직접 실행이 읽는 값의 자리 | 실행기 정의면 보관 파일, 옛 정의와 원격이면 `.env` | 작성자 | 이관하지 못해 남긴 바인딩(스펙 DECISION10)이 지금처럼 돈다 |
| DECISION14 | 실행기 정의의 경로 비교 | python, 실행기, 보관 파일 디렉터리, key 파일 값을 지금 값과 견준다 | 작성자 | 경로가 바뀐 정의는 재시작 뒤 서버가 뜨지 않는다. 맞지 않음으로 드러내야 연결 확인이 다시 설치한다 |
| DECISION15 | 실행기가 읽는 코드 | plugin 패키지를 실행하지 않고 빈 패키지로 `connector_vault_format.py` 와 `connector_vault_key.py` 만 읽는다 | 작성자 | 대시보드와 같은 풀기 코드를 쓰되 Hermes 와 starlette 를 읽지 않아 자식이 가볍고 실패할 자리가 적다 |
| DECISION16 | 기동 이관의 범위 | 바인딩 설치를 다시 돌리지 않고 정의, 소유 기록, 이름 대응, `.env` 칸 줄만 바꾼다 | 작성자 | 설치의 다른 검사가 거절하면 풀 수 있는 바인딩의 사본이 남아 REQ7 을 어긴다. 기동 때 hook plugin 파일이 바뀌는 부작용도 없앤다 |
