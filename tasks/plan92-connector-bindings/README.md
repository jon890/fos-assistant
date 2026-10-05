# plan92 연결과 에이전트 바인딩

사용자가 커넥터 계정을 한 번 연결하고, 그 연결을 자기 에이전트에 붙여 그 에이전트가 도구를 직접 부르게 한다.
먼저 살펴보기도 붙은 연결의 도구를 직접 부른다. 이미 있는 커넥터 에이전트는 사용자가 옮긴 뒤 지울 때까지 지금처럼 돈다.
결정과 근거는 `docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md` 에 있다.

이 plan 은 `plan91-connector-binding-hermes` 가 머지되고 배포된 뒤 시작한다. 그 plan 이 연 대시보드 경로를 부른다.

## 순서

| phase | 층 | 하는 일 |
| --- | --- | --- |
| 01 | backend | 바인딩 표와 마이그레이션, 엔티티와 저장소 |
| 02 | backend | 연결과 바인딩 서비스. 보관 파일, 붙이기와 떼기, 연결 확인, 관리자 반영 완료 |
| 03 | backend | 판정과 승인 실행, 에이전트의 공개와 삭제와 주인 변경, 도구 저장, 실행 기록 가림, 연결 엔티티의 옛 칸 매핑 빼기 |
| 04 | backend | 먼저 살펴보기의 시작 전 점검과 지시 |
| 05 | backend, web | API 와 화면, Hermes 대역, 브라우저 시험 |
| 06 | e2e | 새 흐름의 e2e 와 옛 흐름을 전제한 시나리오 |
| 07 | docs | 책임 문서, 옛 ADR 의 대체 표시, 용어 |

phase 는 앞 phase 의 결과에 기댄다. 차례로 한다.
**e2e 와 브라우저 검사는 phase 05 와 06 에서 다시 통과한다.** 그 앞 phase 의 검증은 backend 검사로 한다. 연결 등록이 에이전트를 만들지 않게 되는 phase 02 부터 옛 흐름을 전제한 e2e 시나리오가 깨지기 때문이다.
브랜치는 `plan92-connector-bindings` 이고 PR 하나로 올린다.

## 말

| 말 | 뜻 | 코드 |
| --- | --- | --- |
| 커넥터 | 에이전트에게 쥐어 주는 도구 묶음이다. 운영자가 올린 plugin 의 `connector.json` 이 선언한다 | manifest |
| 연결 | 사용자가 커넥터 하나에 계정을 연결한 것. 사용자와 커넥터마다 하나다 | `connector_connection`, `ConnectorConnection` |
| 바인딩 | 에이전트에 연결을 붙인 것. 에이전트와 연결의 다대다다 | `agent_connector_binding`, `ConnectorBinding` |
| 보관 파일 | 연결의 칸 값을 대시보드 plugin 이 Hermes 쪽에 두는 파일. 연결마다 하나다 | vault, 이름은 `c<연결 id>` |
| 옛 커넥터 에이전트 | 이 plan 전에 커넥터마다 만든 전용 에이전트. `agent.connector_managed` 가 참이다 | `Agent.connectorManaged()` |

화면에서는 「붙이기」, 「떼기」, 「이 에이전트가 쓰는 연결」, 「반영 대기」 라 쓴다. 「바인딩」 은 화면에 보이지 않는다.
「커넥터 에이전트」 는 옮겨 가기 설명 밖에서 쓰지 않는다. 화면의 옛 에이전트 표시는 「예전 방식의 연결 에이전트」 다.

## 저장 모델

정본은 phase 01 과 03 이 고칠 `docs/backend/schema/connector.md` 다. 여기에는 phase 사이에 맞춰야 할 이름만 둔다.

- `connector_connection`: `(user_id, connector_id)` 유일은 그대로다. `vault_stored BOOLEAN NOT NULL DEFAULT FALSE` 를 더하고 `agent_id` 를 비워도 되게 한다. `agent_id`, `restart_required`, `desired_enabled` 는 쓰지 않는 칸으로 남는다. 칸을 지우는 마이그레이션은 옛 커넥터 에이전트를 정리하는 다음 작업이 둔다. `status` 는 `DISCONNECTED`, `PENDING`, `READY` 그대로이고 뜻이 「값이 확인돼 쓸 수 있는가」 로 바뀐다
- `agent_connector_binding`: `id`, `agent_id`, `connection_id`, `mcp_server`(붙일 때의 서버 이름, 옛 바인딩은 확인 때 채움), `status`(`PENDING`, `READY`), `restart_required`, `desired_enabled`, `checked_at`, `created_at`, `updated_at`. `(agent_id, connection_id)` 유일. 떼면 행을 지운다
- `connector_action.agent_id` 는 「판정한 실행의 에이전트」 다. 옛 줄은 옛 커넥터 에이전트를 가리킨 채 남는다
- `connector_tool_grant` 는 바꾸지 않는다. 상시 허락은 지금처럼 사용자와 커넥터에 묶인다

## 맞춰 쓸 이름

| 무엇 | 이름 | 만드는 phase |
| --- | --- | --- |
| 바인딩 엔티티와 저장소 | `ConnectorBinding`, `BindingStatus`, `ConnectorBindingRepository` | 01 |
| 바인딩 서비스 | `ConnectorBindingService` 의 `listForAgent`, `bind`, `unbind`, `confirmApplied`. 붙이기와 떼기는 사용자 행과 에이전트 행을 차례로 잠근다 | 02 |
| `agent` 의 읽기 port | `AgentConnectorBindings` 의 `hasBindings`, `connectorServers`, `connectorToolPrefixes`. 구현은 `ConnectorBindingLookup` | 02 |
| `agent` 의 떼기 port | `AgentConnectorDetacher` 의 `detachAll`. 구현은 `ConnectorBindingService` | 02 |
| 에이전트의 연결 목록 응답 | `AgentConnectionsView(connections, blockedReason)`, `AgentConnectionView` | 02 |
| 새 오류 코드 | `AGENT_CONNECTIONS_REQUIRE_PRIVATE`, `CONNECTOR_NOT_CONNECTED`, `CONNECTOR_BIND_CONFLICT`, `CONNECTOR_PROFILE_NOT_READY` | 02 |
| 새 오류 코드 | `AGENT_HAS_CONNECTIONS`(바인딩이 있는 에이전트의 주인 변경) | 03 |
| 에이전트 경로 | `GET`, `PUT`, `DELETE /api/v1/agents/{code}/connections[/{connectorId}]` | 05 |
| 관리자 반영 완료 | `POST /api/v1/admin/agents/{code}/connections/{connectorId}/confirm`. 컨트롤러는 `AdminAgentConnectionController` | 02 |
| 대시보드 경로 | `docs/backend/connector-install.md` 의 「대시보드 plugin 계약」 | `plan91-connector-binding-hermes` |

## 모든 phase 에 걸리는 규칙

- 공개 저장소다. 홈서버 주소, 포트, 컨테이너 이름, 경로, 운영 저장소의 구조를 코드, 문서, 커밋, PR 본문에 적지 않는다. `scripts/check-public-safe.sh` 로 확인한다
- `backend/src/main`, `web/src`, `hermes/plugins` 에 서비스 이름을 두지 않는다. `test/unit/connector-neutral.test.ts` 가 본다
- 비밀 칸의 원문은 DB, 로그, 응답, 예외 메시지에 없다. 보관 파일과 profile `.env` 에만 있다
- 옛 커넥터 에이전트는 이 plan 이 끝나도 지금처럼 돈다. 그 에이전트의 경계(ADR-045)를 지키는 코드(`McpCallerResolver`, `AgentRunner` 와 `ChatService` 의 Memory 생략, `ExternalData` 감싸기, 도구 내용 가림)는 지우지 않는다
- 관리자는 남의 에이전트에 연결을 붙이거나 떼지 못한다. 반영 완료만 누른다
- Flyway 번호는 머지 직전 main 의 다음 번호로 옮긴다. 이 계획서는 `V77`(표), `V78`(옛 연결의 바인딩 채우기)로 적는다. `V74` 부터 `V76` 까지는 다른 작업이 쓴다
- 도구 저장과 스킬 게시는 붙은 커넥터 서버 이름을 함께 보낸다. 빠지면 대시보드가 409 로 거절한다
- 머지 전에는 `scripts/check-local.sh <고친 화면의 spec>` 을 돌린다(phase 07 의 검증). 단계를 건너뛰지 않는다. 전체 브라우저 검사는 PR 의 CI 가 맡는다
- 기능 변경과 포맷은 다른 커밋이다. 커밋 메시지는 `<type>(<범위>): <메시지>`, 범위는 `backend`, `web`, `docs`, `hermes`, `infra`

## 배포 순서와 운영 반영

1. `plan91-connector-binding-hermes` 의 Hermes 묶음이 이미 배포돼 있어야 한다
2. backend 와 web 을 함께 올린다. 마이그레이션이 옛 연결마다 옛 커넥터 에이전트의 바인딩을 만든다. 옛 에이전트는 그대로 돈다
3. 사람이 만든 profile 의 에이전트에 붙이려면 운영자가 그 profile 에 커넥터 표식을 두고 `fos-ctx` 를 켠다. 절차의 문장은 운영 저장소가 갖는다
4. 붙인 바인딩마다 관리자가 공유 gateway 를 재시작하고 반영 완료를 누른다

**backend 이미지를 되돌릴 때**: 이 plan 의 마이그레이션은 표와 칸을 더하기만 해서 이전 이미지가 뜬다. 다만 새 경로로 만든 연결은 `agent_id` 가 비어 있어 이전 이미지가 그 행을 읽으면 실패한다. 해제해도 행은 `DISCONNECTED` 로 남고 이전 이미지는 상태와 상관없이 `connection.agent()` 를 읽는다. 그래서 되돌리기 전에 `agent_id` 가 빈 연결 행과 그 연결의 바인딩 행을 지운다. 그 사용자는 되돌린 뒤 옛 방식으로 다시 연결한다. 실행 명령은 운영 저장소에 둔다.

## 옮겨 가기

사용자마다 연결마다 한다. 끊김이 없고, 옛 에이전트를 지우기 전까지 되돌릴 수 있다.

1. 「연결」 화면에서 연결 확인을 누른다. 옛 에이전트의 profile 에 있던 값이 보관 파일로 옮겨진다
2. 원래 쓰던 에이전트의 상세에서 그 연결을 붙인다. 바인딩은 「반영 대기」 다
3. 관리자가 공유 gateway 를 재시작하고 반영 완료를 누른다
4. 그 에이전트로 커넥터 도구를 한 번 불러 본다. 쓰기 도구는 승인 카드가 뜨는지 본다
5. 옛 에이전트를 지운다. 지우기가 그 바인딩을 떼고 profile 을 거둔다

되돌리기: 5 전에는 새 바인딩을 떼면 옛 에이전트로 그대로 쓴다. 5 뒤에는 다시 붙이면 된다. 값은 보관 파일에 남아 있다.

## 범위 밖

- 옛 커넥터 에이전트의 격리 경로 코드를 지우는 일. 운영에서 옛 에이전트가 모두 지워진 뒤 따로 한다
- 화면에서 임의 MCP 서버나 Hermes 코드 plugin 을 붙이는 길. ADR-083 이 두지 않기로 했다
- 재시작 없이 profile 하나의 MCP 를 다시 발견하는 길
- 커리어 패키지의 `proactive-check` 스킬 본문. 이 저장소 밖에 있다. 살펴보기 지침이 커넥터 에이전트에 맡기던 질의를 커넥터 도구의 직접 호출로 바꿔야 한다

## 계획서 삭제와 「아직 구현 전」 표시

phase 07 이 ADR-083 과 `docs/adr/INDEX.md` 의 「아직 구현 전이다」 를 지운다. 이 디렉터리는 이 plan 의 구현 PR 이 지운다.
