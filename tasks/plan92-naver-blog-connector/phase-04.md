# Phase 04. 바인딩 주인의 첨부 디렉터리만 읽게 하는 커넥터 계약

**Execution profile**: deep

## 목표

`connector.json` 에 `owner_attachments_env` 를 두고, 바인딩 설치가 그 env 에 에이전트 주인의 첨부 디렉터리를 넣게 한다. 네이버 블로그 커넥터는 그 디렉터리 아래의 사진만 읽는다.
커넥터 MCP 서버가 다른 사용자의 첨부를 읽어 이 사용자의 임시저장 글로 옮기는 길을 막는다.

**범위 외**: 실행 공간(terminal) 설정, 옛 설치(`toolsets` 의 사진 도구) 경로의 동작.

## 컨텍스트

- 결정과 계약: `docs/adr/ADR-20261007-connector-owner-attachments.md`, `docs/connectors.md` 의 「connector.json」 표와 「승인」 끝의 실행 경로 목록, `docs/backend/connector-install.md` 의 「바인딩 설치」, `hermes/README.md` 의 「커넥터」 에서 `call` 과 `execute` 의 자식 env, `docs/connector-authoring.md` 의 「사용자 첨부를 읽는 커넥터」. 이 문서들은 이미 이 계약으로 고쳐져 있다
- 대시보드 plugin: `hermes/plugins/dashboard-profile-api/__init__.py`
  - manifest 검증 `_load_connector` (약 930행). `operator_env` 검사(958행부터 965행까지)와 `.mcp.json` env 합 검사(1006행)가 본보기다. 결과 사전에 `operator_env` 를 담는 곳(1063행)
  - 실행 공간 경로 함수 `_sandbox_policy`, `_sandbox_attachment_agent_directory(policy, owner)`, `_sandbox_attachment_path_identity(root, owner)`(중간 링크 거절), `_sandbox_unavailable`, `SANDBOX_OWNER_RE`
  - `PUT /api/connectors` 처리(약 2050행부터). 본문 키 검사에 이미 `sandbox_owner` 가 있다. 바인딩 설치는 `_connector_bind_config(profile_dir, plugin, True, vault, values)` 를 부른다(1732행)
  - `_connector_call_request`(2280행)와 `_connector_execute_request`(2403행)가 자식 env 를 만든다(2325, 2457행)
- backend: `HermesConnectorClient.bindConnector(String profile, String connectorId, String vault)` 와 `HttpHermesConnectorClient.bindConnector`(본문 `{profile, plugin, enabled: true, bind: {vault}}`). 부르는 곳은 `ConnectorBindingService` 의 세 곳(약 199, 389, 516행)이고 각 자리에 `Agent` 가 있다. `Agent.sandboxOwner()` 가 있다(`agent/domain/Agent.java`). `putConnector` 는 이미 `sandbox_owner` 를 보낸다
- backend 시험: `HttpHermesConnectorClientTest`, `ConnectorBindingServiceTest`, `ConnectorBindingServiceLockTest`, `AgentLifecycleServiceTest`, `AgentVisibilityLockTest`, `AgentConnectionControllerTest`, `ConnectorConnectionServiceTest` 가 `bindConnector` 를 흉내 내거나 검증한다
- e2e 대역: `test/e2e/fake-hermes.ts`, `test/e2e/connector-support.ts` 가 바인딩 설치 본문을 받는다
- plugin 시험: `hermes/tests/test_connector_manifest.py`, `test_dashboard_profile_api.py`, `test_connector_execute.py`, `test_connector_call.py`, `test_connectors_contract.py`
- 커넥터: `hermes/connectors/naver-blog/src/draft.ts` 의 `checkPhotoFiles`, `readPhoto`, `src/render.ts`, `src/server.ts`, `src/worker.ts`(작업 프로세스에 넘기는 env)

## 의도 메모

- 값은 운영 정책과 Control Plane 이 정한 `sandbox_owner` 에서만 온다. 요청 본문의 다른 칸, 모델, manifest 가 경로를 정하지 못한다
- 값은 `operator_env` 처럼 서버 정의에 직접 넣는다. profile `.env` 에 두지 않는다. 셸이 그 값을 바꿔 다른 디렉터리를 가리키게 하지 못하게 한다
- 에이전트 주인은 바인딩이 있는 동안 바뀌지 않는다(`AGENT_HAS_CONNECTIONS`). 그래도 다시 설치할 때마다 요청의 주인으로 값을 다시 쓴다
- 기각: 커넥터가 첨부 루트 아래의 모양만 보는 것(다른 사용자의 키도 같은 모양이다)

## 작업 항목

### 1. plugin 의 manifest 검증

`_load_connector` 가 선택 칸 `owner_attachments_env` 를 읽는다. 값은 `^[A-Z][A-Z0-9_]{0,63}$` 인 문자열 하나다. `fields[].env`, `operator_env` 와 겹치면 안 된다. `.mcp.json` 서버 env 의 키 집합이 세 이름의 합과 같아야 하고, 그 키의 값은 `${이름}` 이어야 한다. 어기면 그 커넥터를 카탈로그에서 뺀다. 결과 사전에 `owner_attachments_env`(없으면 `None`)를 담는다. manifest 의 알려진 키 목록이 있으면 더한다.
카탈로그 응답(`_connector_catalog_response`)에는 싣지 않는다.

### 2. plugin 의 바인딩 설치

`PUT /api/connectors` 의 바인딩 설치에서 manifest 가 `owner_attachments_env` 를 선언했으면:

- 본문의 `sandbox_owner` 가 없으면 400(`_rejected`)
- `_sandbox_policy()` 가 없으면 `_sandbox_unavailable()`
- `_sandbox_attachment_path_identity(pathlib.Path(policy["attachment_agent_root"]), owner)` 로 디렉터리를 링크 없이 확인하고 실패하면 `_sandbox_unavailable()`
- 값 `_sandbox_attachment_agent_directory(policy, owner)` 를 `_connector_bind_config` 에 넘겨 서버 정의의 env 에 그 이름으로 직접 넣는다. 소유 기록의 서버 정의에도 같은 값이 남는다
- `_entry_matches_manifest` 처럼 소유 기록과 지금 manifest 를 견주는 곳이 이 값 때문에 늘 거짓이 되지 않게 한다. 그 env 의 값은 견주기에서 빼거나 `attachment_agent_root/users/<64자리 16진수>` 모양인지만 본다
- 선언하지 않은 커넥터는 `sandbox_owner` 를 받아도 쓰지 않는다

### 3. plugin 의 실행 경로

- `_connector_execute_request`: 선언한 커넥터면 그 profile 의 설치된 서버 정의(`config.yaml` 의 `mcp_servers`)에서 그 env 값을 꺼내 자식 env 에 더한다. 값이 없거나 모양이 틀리면 빈 값이다
- `_connector_call_request`(확인 도구, 선택지): 선언한 커넥터면 그 env 를 빈 글로 준다

### 4. backend

- `HermesConnectorClient.bindConnector(String profile, String connectorId, String vault, String sandboxOwner)` 로 바꾸고, `HttpHermesConnectorClient` 가 본문에 `sandbox_owner` 를 더한다. `putConnector` 처럼 보내기 전에 `attachmentDirectory.ensure(sandboxOwner)` 를 부른다
- `ConnectorBindingService` 의 세 호출이 그 에이전트의 `sandboxOwner()` 를 넘긴다
- 시험 대역과 검증을 새 시그니처로 고친다. `HttpHermesConnectorClientTest` 에 「바인딩 설치 본문에 `sandbox_owner` 가 든다」 를, `ConnectorBindingServiceTest` 에 「붙이기와 다시 설치가 그 에이전트 주인의 `sandboxOwner` 를 보낸다. 다른 사용자의 에이전트에 붙이면 그 사용자의 값이다」 를 더한다
- e2e 의 `fake-hermes.ts` 가 바인딩 본문의 `sandbox_owner` 를 받게 고친다

### 5. 커넥터

- `connector.json` 에 `"owner_attachments_env": "NAVER_BLOG_ATTACHMENT_DIR"`, `.mcp.json` env 에 `"NAVER_BLOG_ATTACHMENT_DIR": "${NAVER_BLOG_ATTACHMENT_DIR}"`
- `checkPhotoFiles` 와 `readPhoto`: env `NAVER_BLOG_ATTACHMENT_DIR` 가 비었으면 사진을 하나도 받지 않는다. `photo_dir` 의 실제 경로가 그 디렉터리의 실제 경로 아래여야 하고, 그 디렉터리부터 사진 파일까지 조각마다 `lstat` 으로 링크가 아니어야 한다
- 경로 문제는 원인과 상관없이 같은 문장 하나다: 사진 디렉터리 문제는 「사진 디렉터리를 쓸 수 없다」, 파일 문제는 「<N>번째 사진 자리의 사진을 쓸 수 없다」. 없음, 링크, 디렉터리 밖, 크기, 서명을 나누지 않는다. `render_draft` 와 `save_draft` 가 같다
- 작업 프로세스에 그 env 를 넘긴다(`readPhoto` 가 작업 프로세스에서도 같은 검사를 한다)
- `docs/connectors/naver-blog.md` 의 「등록 칸」 아래에 설치가 주는 env 한 줄, 「초안」 의 검사 목록, 「보안」 의 「파일」 행을 이 계약으로 고친다. ADR-092 의 「감당할 것」 중 「다른 사용자의 첨부 디렉터리를 가리키는 것은 막지 못한다」 를 ADR-093 이 막는다고 고친다

### 6. 이 phase 를 검증하는 시험

- `hermes/tests/test_connector_manifest.py`: 선언이 `fields` 와 겹치거나, `.mcp.json` env 에 없거나, 모양이 틀리면 카탈로그에서 빠진다. 바르면 결과에 담긴다
- `hermes/tests/test_dashboard_profile_api.py`: 선언한 커넥터의 바인딩 설치가 서버 정의 env 에 정책 루트 아래 그 주인의 디렉터리를 넣는다. `sandbox_owner` 가 없으면 400, 디렉터리가 링크면 409. 다른 `sandbox_owner` 로 다시 설치하면 그 값으로 바뀐다. 선언하지 않은 커넥터의 설치는 지금과 같다
- `hermes/tests/test_connector_execute.py` 와 `test_connector_call.py`: 실행 경로의 자식 env 에 설치된 값이 들고, 확인 경로에는 빈 값이 든다
- 커넥터 `tests/draft.test.ts`: 그 디렉터리 아래의 사진은 받는다. 밖의 디렉터리, 중간 조각이 링크인 경로, env 가 빈 경우는 모두 같은 문장으로 거절한다. 없는 파일과 서명이 틀린 파일의 문장이 같다
- 커넥터의 다른 시험(`render.test.ts`, `save-draft.test.ts`, `worker.test.ts`)은 시험용 사진 디렉터리를 `NAVER_BLOG_ATTACHMENT_DIR` 아래에 두도록 고친다
- backend 시험은 4 의 둘

## 검증

```bash
cd hermes/connectors/naver-blog && bun install --frozen-lockfile && bun run typecheck && bun test ./src ./tests ./scripts && bun run build && bun run check:bundle
bash scripts/check-connectors.sh
python3 -m unittest discover -s hermes/tests
cd backend && ./gradlew test --tests '*HttpHermesConnectorClientTest' --tests '*ConnectorBindingService*' --tests '*AgentLifecycleServiceTest' --tests '*AgentVisibilityLockTest' --tests '*AgentConnectionControllerTest' --tests '*ConnectorConnectionServiceTest'
node --test test/unit/connector-neutral.test.ts
git add -N hermes backend test && bash scripts/check-public-safe.sh
```

기대값: 모두 종료 코드 0. e2e 대역 변경은 PR 의 CI(e2e)가 확인한다. 로컬에서 돌리려면 `scripts/check-local.sh` 의 e2e 단계를 쓴다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/__init__.py` | 수정 |
| `hermes/tests/test_connector_manifest.py` | 수정 |
| `hermes/tests/test_dashboard_profile_api.py` | 수정 |
| `hermes/tests/test_connector_execute.py` | 수정 |
| `hermes/tests/test_connector_call.py` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesConnectorClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesConnectorClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorBindingService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/HttpHermesConnectorClientTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingServiceLockTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentLifecycleServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentVisibilityLockTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/AgentConnectionControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionServiceTest.java` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `hermes/connectors/naver-blog/connector.json` | 수정 |
| `hermes/connectors/naver-blog/.mcp.json` | 수정 |
| `hermes/connectors/naver-blog/src/draft.ts` | 수정 |
| `hermes/connectors/naver-blog/src/render.ts` | 수정 |
| `hermes/connectors/naver-blog/src/server.ts` | 수정 |
| `hermes/connectors/naver-blog/src/worker.ts` | 수정 |
| `hermes/connectors/naver-blog/dist/naver-blog-mcp.js` | 수정 |
| `hermes/connectors/naver-blog/tests/draft.test.ts` | 수정 |
| `hermes/connectors/naver-blog/tests/render.test.ts` | 수정 |
| `hermes/connectors/naver-blog/tests/save-draft.test.ts` | 수정 |
| `hermes/connectors/naver-blog/tests/worker.test.ts` | 수정 |
| `docs/connectors/naver-blog.md` | 수정 |
| `hermes/tests/fixtures/demo-connector/server.py` | 수정 |
| `hermes/connectors/naver-blog/src/editor/photos.ts` | 수정 |
| `hermes/connectors/naver-blog/src/editor/run.ts` | 수정 |
| `hermes/connectors/naver-blog/tests/editor-photos.test.ts` | 수정 |
| `hermes/connectors/naver-blog/tests/editor-run.test.ts` | 수정 |
| `docs/connectors.md` | 수정 |
| `docs/backend/connector-install.md` | 수정 |
| `hermes/README.md` | 수정 |
| `docs/adr/ADR-20261007-connector-owner-attachments.md` | 수정 |
| `docs/adr/ADR-20261007-naver-blog-connector.md` | 수정 |
