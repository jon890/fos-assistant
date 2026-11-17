# 에이전트 팀 기술 설계

스펙: [`spec.md`](spec.md).

스펙의 DECISION2 대로 PR 다섯으로 나눈다. 이 문서는 PR1 부터 PR5 까지를 구현할 깊이로 쓴다. 뒤 PR 은 시작할 때 이 문서의 해당 절을 고치고 검토를 다시 받았다.
스펙 범위 6(내장 위임 결과)은 스펙 DECISION36 대로 PR 둘이고, 이 문서에서는 PR6 과 PR7 로 부른다.

| PR | 요구사항 | 내용 |
| --- | --- | --- |
| PR1 | REQ1, REQ2 | 에이전트 소개·아이콘·색과 할 수 있는 일, 답 이름표, 기본 비서 이름 |
| PR2 | REQ3, REQ4 | 맡긴 줄, 결과 도착 줄과 승인 카드의 이름표 |
| PR3 | REQ5, REQ6 | 전문 에이전트 만들기 마법사 |
| PR4 | REQ7 | 대화 중 `@` 로 바로 맡기기 |
| PR5 | REQ8, REQ9 | 기본 비서와 이어지는 대화방과 날짜별 요약 |
| PR6 | REQ10~REQ14 | 내장 위임의 끝 소식 받는 경로, 자동 답, 기한·중지 알림, 결과 도착 줄 |
| PR7 | REQ10 | fos-ctx 의 `subagent_stop` hook 이 끝 소식을 보내고 등록에 `reports_stop`, `child_goal` 을 싣는다 |

## 지금 구조

- 에이전트 행(`agent`)은 `code`, `name`, profile, 공개 범위, 주인을 갖는다. 소개, 아이콘, 색 칸이 없다([`data-schema.md`](../../../backend/docs/data-schema.md) 「agent」). 주인이 이름을 바꾸는 경로도 없다. 관리자 수정(`AgentAdminDtos.UpdateAgentRequest`)도 이름을 받지 않는다.
- `GET /api/v1/agents` 의 `AgentView` 는 `code`, `name`, `visibility`, `acceptsAttachments`, `editable`, `ownedByMe` 다(`AgentDtos`). 화면 타입은 `web/src/lib/agent.ts` 의 `AgentView` 다.
- `agent_list` 는 `McpToolService.listAgents` 가 `AgentDelegationService.list` 의 에이전트를 `code`, `name` 으로만 낸다.
- 답 말풍선 `AssistantRow`(`web/src/components/chat/message-bubble.tsx`)가 「비」 머리글자와 「비서」 이름을 고정으로 그린다. 대화 머리의 에이전트 이름은 잠긴 대화의 좁은 화면에서 숨는다(`conversation-session-view.tsx`).
- 지금은 한 대화의 `ASSISTANT` 답을 모두 그 대화의 에이전트가 쓴다. 대화의 에이전트는 바뀌지 않는다(`docs/features/chat.md` 「요구」).
- 첫 로그인의 에이전트는 `FirstAgentCreator` 가 `code` 와 profile 을 사용자의 기본 profile 이름으로, `name` 을 `allowed_person.display_name` 으로 만든다.
- 올린 스킬 이름은 `SkillStore.readCurrent(profile)` 이 Control Plane 이 붙인 스킬 디렉터리에서 읽는다. Hermes 를 부르지 않는다. 플러그인 이름은 카탈로그(`ConnectorCatalogCache`)의 manifest 가 갖는다.
- 최상위 패키지 순서는 `agent` 가 `skill`, `mcp`, `connector` 보다 아래다(`TopLevelPackageOrder.ORDER`). `agent` 가 위를 읽을 때는 `agent.application.port` 에 port 를 두고 위 패키지가 구현한다. 지금 `AgentConnectorBindings` 가 그 모양이다.

## 요구사항 연결

| ID | 해결 방법 |
| --- | --- |
| REQ1 | `agent` 에 `description`, `icon`, `color` 컬럼을 더한다. 주인과 `ADMIN` 이 `PATCH /api/v1/agents/{code}/introduction` 으로 이름과 셋을 고친다. `ADMIN` 은 지우기처럼 남의 비공개 에이전트에도 닿는다. 아이콘과 색은 Control Plane 의 고정 목록으로 검사한다. 할 수 있는 일은 `AgentCapabilitiesReader` 가 연결과 바인딩이 모두 READY 인 플러그인 이름과 올린 스킬 이름으로 모은다 |
| REQ2 | `AgentView` 에 소개, 아이콘, 색, 할 수 있는 일을 싣는다. 화면은 부품 `AgentBadge` 하나로 답, 대화 머리, 목록, 새 대화 카드, `@` 목록을 그린다. 답 이름표는 대화 줄(`ConversationView`)의 에이전트 칸으로 그린다(PR4 부터 `@` 맡김 답은 `answeredBy`). `agent_list` 결과에도 소개와 할 수 있는 일을 싣는다. 첫 에이전트는 이름 「비서」 와 고정 소개로 만들고, 이미 있는 첫 에이전트는 마이그레이션이 바꾼다 |
| REQ3 | (PR2) 이력 API 의 `ASSISTANT` 줄에 그 답의 실행이 바로 맡긴 위임 자식(`delegation_key` 가 있고 부모가 그 실행인 실행 줄)의 에이전트와 상태를 싣는다. 도는 동안은 위임이 시작하고 끝날 때 새 사건 `delegation` 을 대화 단위 SSE 로 그 대화의 모든 구독에 보낸다. 아래 「PR2 협업 보이기」 |
| REQ4 | (PR2) 위임 결과 알림 줄(`SYSTEM`)에 그 줄이 전한 자식 실행의 에이전트와 상태를 싣는다. 줄과 결과는 전달 묶음(`result_delivery`)의 새 칸 `delegation_notice_message_id` 와 `items_json` 으로 잇는다. 승인 줄(`connector_action.agent_id`)이 대화의 에이전트와 다르면 승인 카드 응답에 그 에이전트를 싣는다 |
| REQ5 | (PR3) 화면이 연결 완료 뒤 제안을 띄우고, Control Plane 의 한 요청이 에이전트 만들기(소개 포함), 성격 틀 쓰기, 바인딩을 차례로 하고 실패하면 역순으로 거둔다. 순서는 `agent` 위의 패키지(`connector`)가 갖는다. 아래 「PR3 마법사」 |
| REQ6 | (PR3) 같은 요청이 「떼기」 칸이면 그 연결이 지금 붙은 다른 에이전트(기본 비서 또는 다른 에이전트)의 바인딩을 모두 뗀다. `single_binding` 은 화면이 칸을 잠그고 서버도 떼기 없이 붙이기를 거절한다. 제안은 연결에 바인딩이 하나도 없을 때만 뜨므로 「그 서비스가 붙은 전문 에이전트」 가 있으면 뜨지 않는다 |
| REQ7 | (PR4) 보내기 요청의 `delegateTo` 로 Control Plane 이 고른 에이전트의 turn 을 새 session 으로 바로 돌린다. 그 실행은 대화의 루트 실행이고 `delegation_key` 가 없어 자동 결과 전달을 타지 않는다. 맡은 에이전트의 입력에는 그 대화의 최근 `USER`·`ASSISTANT` 줄을 출처 머리줄과 함께 싣는다. 답은 그 실행 번호의 `ASSISTANT` 줄이고 이력 API 가 질문 줄에 `delegatedTo`, 답 줄에 `answeredBy` 를 싣는다. 대화 에이전트의 다음 turn 입력 앞에 위임 결과 전달과 같은 머리줄로 그 주고받음을 싣는다. 무엇을 할지 Control Plane 이 정하는 예외라 ADR-017 을 고치는 ADR 을 둔다. 스펙의 「위임 실행」 은 사용자 쪽에서 본 말이고 구현은 `agent_delegate` 의 자식이 아닌 루트 실행이다(DECISION18). 아래 「PR4 @ 멘션」 |
| REQ8 | (PR5) 대화방은 `conversation.purpose` 의 새 값 `ROOM` 이고 사용자마다 하나다. `/` 가 그 방을 열어(없으면 만들어) `/chat/{id}` 로 넘기고, 새 대화 화면은 `/new` 로 옮긴다. 목록 API 는 대화방을 빼고 사이드바가 맨 위에 따로 그린다. 기본 비서는 「주인이 요청자이고 profile 이 요청자의 기본 profile 인 에이전트」 로 정하고 새 칸을 두지 않는다. 지우기 경로가 기본 profile 을 가리키는 에이전트를 거절한다. 아래 「PR5 대화방」 |
| REQ9 | (PR5) 대화방에서 사용자가 보낸 turn 이 질문을 저장하기 전에, 대화의 지금 루트 session 으로 돈 첫 실행의 Asia/Seoul 날짜가 오늘보다 앞이면 옛 session 에 요약 실행(대화 밖 실행)을 돌린다. 요약을 알림 줄(`SYSTEM`)로 남기고 두 session 칸을 새 `fos-<uuid>` 로 바꾼다(`ConversationSessions.renew`). 요약은 새 루트 session 에 성공한 실행이 생길 때까지 turn 입력 앞에 싣는다. instructions 는 실행마다 조립하므로 거기에 두지 않는다. 요약이 실패하면 두 칸을 그대로 두고 다음 메시지에서 다시 한다. 아래 「PR5 대화방」 |
| REQ10 | (PR6, PR7) fos-ctx 의 `subagent_stop` 끝 소식을 Control Plane 이 하위 에이전트 등록 줄에 적고, 새 `AutoTurnResultSource` 가 ADR-040 의 자동 turn 에 넣는다. 아래 「PR6 내장 위임 결과」 |
| REQ11 | (PR6) 끝 소식 뒤 5초 기다렸다 깨워 한 호출의 자식을 한 turn 에 모은다. 도는 turn 과 상한은 `DelegationWakeService` 그대로다 |
| REQ12 | (PR6) 실패는 실패 결과로 깨운다. 1시간 기한과 중지한 turn 은 1분마다 도는 훑기가 알림 줄로 남긴다 |
| REQ13 | (PR6) 대기 줄과 steer 는 `NextTurnDispatcher.tryNext` 그대로다. 끝 소식 뒤에 돈 사용자 turn 이 있어도 결과를 빼지 않는다(2026-11-17 고침) |
| REQ14 | (PR6) 이력 API 의 `SYSTEM` 줄에 `nativeResult` 를 실어 화면이 대화 에이전트의 이름표로 그린다. 등록 본문의 `reports_stop` 이 참인 자식만 다뤄 PR7 전에는 아무 일도 없다 |

## 책임과 경계

PR1 의 책임이다.

| 자리 | 맡는 것 |
| --- | --- |
| `agent.domain.Agent` | 소개, 아이콘, 색 칸과 `changeIntroduction(introduction)` |
| `agent.domain.type.AgentColor` | 색 이름 enum |
| `agent.domain.AgentIntroduction` | 이름(100자), 소개(60자) 상한과 아이콘 목록, 색 목록을 갖는 값. 앞뒤 공백을 떼고 빈 소개·아이콘·색은 null 로 둔다. 지금 `AgentLifecycleService.requireName` 의 이름 판정을 여기로 옮기고 만들기도 이것을 부른다 |
| `agent.application.AgentLifecycleService` | `changeIntroduction(user, code, introduction)`. 공개 범위 바꾸기와 같이 트랜잭션 밖에서 번호를 찾고 안에서 `findByIdForUpdate` 로 행을 잠가 다시 읽은 뒤, 지우기와 같은 판정(`ADMIN` 이거나 읽을 수 있고, `ADMIN` 이거나 주인)을 한다. `Agent` 는 `@DynamicUpdate` 가 없어 저장할 때 모든 칸을 쓰므로, 잠그지 않으면 그 사이 바뀐 공개 범위·사용 여부·주인·`deleted_at` 을 옛 값으로 덮는다 |
| `admin.agent.presentation.AgentAdminDtos` | `AdminAgentView` 에 소개, 아이콘, 색을 더한다. 관리자 영역 상세가 남의 비공개 에이전트의 값을 읽는 자리다. 쓰기는 일반 경로를 쓴다 |
| `agent.application.AgentCapabilitiesReader` | 에이전트 목록의 할 수 있는 일을 한 번에 모은다. 두 port 를 부른다 |
| `agent.application.port.AgentConnectorNames` (새 port) | `readyConnectorNames(Collection<Long> agentIds)`. `connector` 가 연결과 바인딩이 모두 READY 인 줄을 한 번에 읽고 플러그인 이름을 카탈로그 캐시로 찾는다. 대시보드를 부를 수 있어 「대시보드를 부르지 않는다」 가 약속인 `AgentConnectorBindings` 와 나눈다 |
| `agent.application.port.ProfileSkillFiles` | `uploadedNames(String profile)` 을 더한다. 지금 버전과 표식 없이 남은 더 새 버전의 이름을 합친다(`NewSkillRules.uploadedNames` 와 같은 기준). `SkillStore` 에 번들 본문을 읽지 않고 디렉터리 이름만 읽는 메서드를 더한다 |
| `agent.presentation.AgentController`, `AgentDtos` | 새 PATCH 경로와 `AgentView` 의 새 칸 |
| `mcp.application.McpToolService` | `agent_list` 가 `AgentCapabilitiesReader` 로 같은 값을 싣는다. `McpToolCatalog` 의 설명을 고친다 |
| `people.application.FirstAgentCreator` | 새 첫 에이전트를 「비서」 와 고정 소개로 만든다 |
| `web/src/app/globals.css` | 색 여덟의 밝은 화면·어두운 화면 토큰 쌍([rule-56](../../code-rules/rule-56.md)) |
| `web/src/lib/agent-badge.ts` | 색 목록과 토큰 class 대응, 자동 색, 머리글자. 서버 목록과 같은 값을 갖는다 |
| `web/src/components/agent/agent-badge.tsx` | 아이콘이나 머리글자를 색 원 안에 그리는 부품 하나와, 답 줄에 그 대화의 에이전트를 알리는 context(`AnswerAgentProvider`). 답 말풍선과 기다림 줄이 같은 값을 props 를 거치지 않고 읽는다 |
| `web/src/components/agent/agent-introduction-section.tsx` | 상세의 「소개」 절. 이름, 소개, 아이콘, 색을 고치고 고칠 수 없는 사람에게는 읽기만 보인다 |

상세 화면의 「소개」 절은 일반 상세와 관리자 영역 상세가 같은 부품과 같은 쓰기 경로를 쓴다. 읽는 값만 관리자 영역은 `AdminAgentView` 에서 온다.

## 인터페이스와 데이터

### 저장

`agent` 에 컬럼 셋을 더한다. 마이그레이션 번호는 구현할 때 `orch queue reserve` 로 받는다.

| 컬럼 | 타입 | 뜻 |
| --- | --- | --- |
| `description` | `VARCHAR(60) NULL` | 한 줄 소개. 비면 할 수 있는 일로 대신한다 |
| `icon` | `VARCHAR(16) NULL` | 아이콘 목록의 이모지 하나. 비면 이름 첫 글자 |
| `color` | `VARCHAR(16) NULL` | 색 목록의 이름. 비면 `code` 로 정한 자동 색 |

파일 이름 형식은 [`data-schema.md`](../../../backend/docs/data-schema.md) 「새 버전은 UTC 작성 시각으로 정한다」 를 따르고, 번호는 다른 워크트리와 겹치지 않게 `orch queue reserve` 로 예약한다. 컬럼마다 한국어 COMMENT 를 단다.
DDL 과 DML 을 나눈다([`data-schema.md`](../../../backend/docs/data-schema.md) 「DDL 과 DML 을 한 파일에 섞지 않는다」).
DML 은 이미 있는 첫 에이전트를 바꾼다. 아래 조건을 모두 만족하는 줄만 바꾼다. H2 와 MySQL 에서 같은 문법이 되도록 `UPDATE ... WHERE EXISTS` 로 쓴다.

- `allowed_person.hermes_profile = agent.hermes_profile` 이고 `allowed_person.display_name = agent.name`
- 주인이 그 사람이다. `agent.owner_user_id` 가 `allowed_person.email` 로 찾은 `app_user.id` 다. 관리자가 주인을 바꾼 에이전트는 바꾸지 않는다
- `deleted_at` 이 비었다

바꾸는 값은 `name = '비서'` 와, `description` 이 비었을 때 고정 소개다. 사람이 저장한 것이 아니어서 `updated_at` 은 바꾸지 않는다.

### 값의 목록

아이콘 목록과 상한은 `AgentIntroduction`, 색 이름은 enum `AgentColor` 가 원본이고 화면의 `agent-badge.ts` 가 같은 값을 갖는다. 둘이 어긋나면 서버가 거절하므로, 단위 테스트 `test/repo/agent-badge.test.ts` 가 두 Java 파일과 화면 목록의 글자를 대조한다.

- 색: `VIOLET`, `BLUE`, `TEAL`, `GREEN`, `AMBER`, `ORANGE`, `ROSE`, `SLATE`. 테마의 밝은 화면과 어두운 화면 모두에서 글자 대비가 맞는 class 를 `agent-badge.ts` 가 고른다([rule-56](../../code-rules/rule-56.md))
- 아이콘: 쇼핑, 기차, 메일, 일정, 돈, 글쓰기, 책, 집, 요리, 건강, 여행, 음악, 사진, 코드, 별, 비서 같은 뜻의 이모지 24개 안쪽. 코드에 목록 하나로 둔다
- 자동 색: `code` 글자의 해시를 색 수로 나눈 나머지. 화면이 계산하고 서버는 저장하지 않는다
- 이름 상한은 지금 만들기와 같은 100자, 소개 상한은 60자다. 판정은 `AgentIntroduction` 하나다

### API

`PATCH /api/v1/agents/{code}/introduction`

```json
{ "name": "쇼핑 담당", "description": "쇼핑 검색과 장바구니를 맡아요", "icon": "🛍️", "color": "ROSE" }
```

- 네 칸을 모두 보낸다. `description`, `icon`, `color` 의 null 이나 빈 글은 비우기다. `name` 은 비울 수 없다
- 응답은 바뀐 `AgentView` 다
- 고칠 수 없는 사용자, 볼 수 없는 에이전트는 지금 다른 쓰기 경로와 같은 오류다. 상한과 목록 밖 값은 `VALIDATION_FAILED` 다

`AgentView` 에 더하는 칸이다. 화면 타입도 같이 바꾼다.

| 칸 | 뜻 |
| --- | --- |
| `description` | 소개. 없으면 null |
| `icon` | 아이콘. 없으면 null |
| `color` | 색 이름. 없으면 null |
| `capabilities` | `{ "connectors": ["네이버 쇼핑"], "skills": ["장보기"] }`. 둘 다 이름순이다 |

`agent_list` 결과 한 줄은 `{ "code", "name", "description", "connectors", "skills" }` 다. 비어 있는 칸은 싣지 않아 매 호출의 글을 줄인다.
`agent_list` 설명에 「소개와 붙은 서비스, 스킬을 보고 맡길 에이전트를 고른다」 를 더한다.

### 할 수 있는 일을 모으기

`AgentCapabilitiesReader.read(List<Agent>)` 가 목록 하나에 한 번 돈다.

1. 플러그인: `readyConnectorNames(agentIds)` 가 바인딩과 연결을 한 번 읽고, 플러그인마다 카탈로그 이름을 찾는다. 카탈로그를 읽지 못하면(캐시가 실패를 기억하는 동안 던지는 예외 포함) 그 목록의 플러그인을 모두 빼고 경고 로그를 남긴다. manifest 가 없는 플러그인만 뺀다. 목록 화면과 `agent_list` 가 카탈로그 장애로 실패하지 않게 하려는 것이다
2. 스킬: 에이전트마다 `uploadedNames(profile)`. 파일을 읽지 못하면 그 에이전트의 스킬을 비우고 경고 로그를 남긴다

카탈로그 캐시는 지금 판정 경로가 쓰는 `ConnectorCatalogCache` 를 그대로 쓴다. 보관 시간이 지난 첫 읽기는 대시보드 응답을 기다리고, 대시보드가 답하지 않으면 캐시의 실패 기억 덕에 그 뒤 읽기는 곧바로 빈 이름으로 답한다. 「화면 경로는 쓰지 않는다」 는 그 클래스 주석과 [`code-architecture.md`](../../../backend/docs/code-architecture.md) 의 표 줄을 「연결 화면은 쓰지 않는다. 에이전트 목록의 플러그인 이름만 쓴다」 로 고친다. 연결 화면이 운영자의 목록 변경을 바로 보여야 한다는 까닭은 그대로이고, 이름은 60초 늦어도 위험이 없다.

### 화면

| 자리 | 바뀌는 것 |
| --- | --- |
| 답 말풍선 `AssistantRow` | 그 대화의 에이전트 `AgentBadge` 와 이름. `MessageBubble` 과 `message-list.tsx` 의 기다림 줄이 context(`AnswerAgentProvider`)로 같은 값을 읽는다. 값은 `ConversationView` 에 더하는 `agentIcon`, `agentColor` 와 기존 `agentCode`, `agentName` 이다. 지웠지만 정리 전인 에이전트(`deleted_at` 이 있다)도 `agentCode`, `agentName` 을 null 로 내 「지운 에이전트」 로 그린다. 꺼진 에이전트도 대화 줄의 값으로 아이콘과 색을 그린다 |
| 대화 머리 | 이름 앞에 `AgentBadge`. 좁은 화면의 잠긴 대화는 이름을 숨기던 자리에 `AgentBadge` 만 보인다(DECISION15) |
| 새 대화 화면 카드, 에이전트 목록 `/agents` | `AgentBadge`, 이름, 소개 한 줄과 그 아래 할 수 있는 일 「네이버 쇼핑 · 장보기」. 소개가 없으면 소개 자리를 비우고 할 수 있는 일만 보인다. 카드와 `@` 줄의 접근성 이름은 에이전트 이름만 둔다 |
| `@` 목록 | `AgentBadge` 와 이름, 소개 한 줄. 좁은 목록이라 할 수 있는 일은 싣지 않는다 |
| 에이전트 상세 | 성격 다음에 「소개」 절. 이름, 소개(남은 글자 수), 아이콘 고르기, 색 고르기, 저장. 할 수 있는 일은 읽기만. 대화 줄이 아직 없는 새 대화는 목록의 값으로 그린다 |

답마다 에이전트를 싣지 않는다. PR1 에서 한 대화의 답은 모두 그 대화의 에이전트가 쓰기 때문이다. `@` 로 다른 에이전트가 답하는 PR4 에서 `MessageView` 에 답을 쓴 에이전트를 더한다.

## 실패와 일관성

- 소개 쓰기는 잠근 한 행 갱신이고 Hermes 를 부르지 않는다. 동시에 고치면 나중 쓰기가 남고, 다른 칸은 덮지 않는다
- 할 수 있는 일 모으기의 부분 실패는 위 「할 수 있는 일을 모으기」 대로 빼고 답한다
- 배포 순서: 마이그레이션이 컬럼을 더한 뒤 새 이미지가 뜬다. 옛 이미지는 새 컬럼을 모르므로 되돌려도 동작한다. 이름을 「비서」 로 바꾼 것은 되돌리지 않는다. 되돌린 이미지에서도 이름은 「비서」 로 보인다
- `agent_list` 에 실리는 소개는 그 에이전트 주인이 쓴 글이다. 그룹 공개 에이전트의 소개는 다른 사용자가 쓴 글이 모델 입력에 들어간다. 지금도 이름이 같은 길로 들어가고, 소개는 60자로 묶여 있어 감싸지 않는다. 바꾸지 않는 까닭을 [`connector-policy.md`](../connector-policy.md) 의 읽기 흐름 표와 대조해 PR 본문에 적는다

## 검증 전략

| 요구사항 | 경계와 환경 |
| --- | --- |
| REQ1 | backend 통합 테스트: 주인·`ADMIN` 은 저장, `ADMIN` 이 남의 비공개 에이전트 저장, 다른 사용자는 거절, 상한과 목록 밖 값 거절, 빈 값은 null, 저장이 공개 범위를 덮지 않음. 할 수 있는 일은 연결과 바인딩이 모두 READY 인 것만, 카탈로그 실패에 플러그인만 빠짐, 표식 없는 더 새 버전의 스킬 이름 포함. 마이그레이션은 H2 와 CI 의 MySQL job. DML 은 `GroupSettingMysqlMigrationTest` 처럼 MySQL 하위 검사를 둔다 |
| REQ2 | backend: `GET /api/v1/agents` 와 `agent_list` 의 새 칸, 첫 로그인 에이전트의 이름과 소개, DML 이 조건에 맞는 줄만 바꾸는지. 브라우저: 답 이름표, 좁은 화면 이름표, 상세 저장 뒤 목록과 대화에 반영, 읽기 전용 표시. 화면 흐름이 바뀌므로 PR 에 `검사:전체` 라벨 |
| 공통 | 두 목록(아이콘, 색)의 글자 대조 단위 테스트 |

배포 뒤 확인: 운영에서 기본 에이전트의 이름이 「비서」 가 됐는지와 `agent_list` 의 결과 길이. 확인 방법은 `fos-home-infra` 가 갖는다.

## PR2 협업 보이기

REQ3, REQ4 의 책임이다. 테이블은 늘리지 않고 `result_delivery` 에 칸 하나를 더한다.

### 지금 구조

- 위임 자식은 실행 줄만 남기고 대화 이력에는 아무것도 남기지 않는다([에이전트와 스킬](../agent-skill.md) 의 「다른 에이전트에게 맡기기」). 이력 API 는 답 줄마다 작업 과정 요약(`activity`)의 자식 수만 센다.
- 대화 스트림의 `subagent` 사건은 Hermes 안의 하위 에이전트 사건이다. FOS 위임 자식의 시작과 끝은 화면에 사건으로 가지 않는다. 위임은 turn 의 SSE 와 다른 스레드(`DelegationRuns`)에서 돌고, 끝나면 Spring 사건 `DelegationFinished` 만 낸다.
- 결과 알림 줄은 「쇼핑 담당 에이전트의 결과가 도착했어요」 글만 가진 `SYSTEM` 줄이다(`ChatDeliveryInput.delegationNotice`). 위임 결과가 여럿이면 「○○ 외 N개」 한 줄이다. 전달 묶음은 마지막 시도의 마지막 알림 줄(`last_attempt_notice_message_id`)만 기억한다. 첫 시도는 위임 알림 줄 다음에 승인 결과마다 알림 줄을 더 남기므로, 위임 알림 줄을 묶음에서 찾을 칸이 없다.
- 승인 카드는 `GET /api/v1/chat/conversations/{id}/connector-actions` 로 그린다. 승인 줄에 요청한 실행의 에이전트(`agent_id`)가 있다. 대화 허락 목록은 이미 대화의 에이전트와 다른 에이전트의 이름을 싣는다(`ConnectorConversationGrantView.agentName`).

### 책임과 경계

| 자리 | 맡는 것 |
| --- | --- |
| `agent.application.dto.AgentLabel` | 이름표 한 벌(`code`, `name`, `icon`, `color`). 지운 에이전트와 없는 에이전트는 네 칸이 모두 null 이다. 대화 줄(`ConversationView`)과 같은 규칙이다 |
| `chat.turn.application.DelegationLines` | 맡긴 줄과 결과 도착 줄의 읽기. 답 줄의 실행 번호로 직접 위임 자식을 한 번에, 대화의 묶음으로 결과 알림 줄의 자식을 한 번에, 실행 번호 하나로 사건용 줄 하나를 읽는다 |
| `chat.nextturn.application.dto.DelegationStarted` (새 사건) | 위임 실행 줄이 생겼다. `DelegationFinished` 와 짝이다. 내는 쪽은 받는 쪽을 모른다 |
| `orchestration.application.DelegationRuns` | 실행 줄이 생긴 직후 `DelegationStarted` 를 낸다. 알리다 난 예외는 경고 로그만 남긴다 |
| `chat.nextturn.application.DelegationLineNotifier` (새 빈) | `DelegationStarted` 와 `DelegationFinished` 를 받아 그 위임이 turn 의 직접 위임이면 `ChatEvent.delegation` 을 `ConversationEventHub` 로 낸다 |
| `chat.turn.application.dto.ChatEvent` | `delegation` 칸과 생성 함수 `delegation(conversationId, line)` |
| `chat.delivery` 의 `ResultDelivery`, `ResultDeliveryRecorder` | 새 칸 `delegation_notice_message_id`. 첫 시도가 위임 알림 줄을 저장하면 그 번호를 함께 적고, 다시 전달은 바꾸지 않는다. 알림 줄 번호마다 그 줄이 전한 위임 실행 번호를 돌려준다 |
| `chat.turn.application.ChatDeliveryTurns` | 위임 알림 줄(첫 알림 줄)의 번호를 묶음에 넘긴다 |
| `chat.turn.presentation.ChatController`, `ChatDtos.MessageView` | `delegations`, `deliveredResults` 칸 |
| `connector.approval` 의 `ConnectorActionService`, `ConnectorActionController` | 승인 줄 목록에 대화의 에이전트와 다른 요청 에이전트의 이름표(`requestedBy`)를 붙인다 |
| `web/src/components/chat/delegation-lines.tsx` | 맡긴 줄과 결과 도착 줄의 그림 |
| `web/src/lib/delegation.ts`, `use-conversation-session-state.ts` 의 `liveDelegations` | `delegation` 사건으로 받은 줄을 대화 화면이 들고 있다가 이력의 줄과 합친다. 합치기 규칙과 글은 `lib/delegation.ts` 가 갖는다 |
| `web/src/components/chat/approval-card.tsx` | 카드 위의 「○○이 요청」 줄 |

### 인터페이스와 데이터

#### 맡긴 줄로 보일 위임

실행 줄의 `delegation_key` 가 있고 `parent_execution_id` 가 `root_execution_id` 와 같은 실행이다. 곧 사용자 대화의 turn(루트 실행)이 바로 맡긴 위임이다. 그 turn 안의 Hermes 하위 에이전트가 부른 `agent_delegate` 도 부모가 루트이므로 든다. 맡은 에이전트가 다시 맡긴 손자는 부모가 루트가 아니라 빠진다(spec DECISION18). 실행 전에 거절된 위임은 실행 줄이 없어 빠진다. 줄을 만든 뒤 제출이 실패한 위임은 「실패」 로 보인다.

#### 이력 API

`GET /api/v1/chat/conversations/{id}/messages` 의 메시지에 두 칸을 더한다. 둘 다 해당하지 않는 줄은 빈 목록이다.

| 칸 | 줄 | 뜻 |
| --- | --- | --- |
| `delegations` | `ASSISTANT` | 그 답의 실행(`executionId`)이 바로 맡긴 위임. 실행 번호순 |
| `deliveredResults` | `SYSTEM` | 그 알림 줄이 전한 위임 결과. 묶음의 항목 순서다 |

한 항목은 아래 모양이다. `agent` 의 네 칸이 모두 null 이면 지운 에이전트다.

```json
{ "executionId": 41, "parentExecutionId": 40, "status": "RUNNING",
  "agent": { "code": "shopping", "name": "쇼핑 담당", "icon": "🛍️", "color": "ROSE" } }
```

`status` 는 실행 줄의 `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED` 다. 화면은 차례로 「진행 중」, 「끝남」, 「실패」, 「멈춤」 으로 그린다.

읽기는 대화 하나에 질의가 몇 번으로 고정된다. 답 줄의 실행 번호 목록으로 위임 자식을 한 번, 대화의 묶음을 한 번(지금 `deliveryStates` 가 읽는 것과 같은 줄), 결과 실행을 번호 목록으로 한 번, 에이전트를 번호 목록으로 한 번 읽는다. 결과 실행은 그 사용자와 그 대화의 줄만 쓴다. 남의 실행 번호가 묶음에 들었어도 싣지 않는다.

#### `delegation` 사건

`ChatEvent` 에 `delegation` 칸을 더하고 `type` 이 `delegation` 인 사건을 만든다. 칸의 모양은 이력 API 의 한 항목과 같다.
위임 실행 줄이 생겼을 때(`RUNNING`)와 끝났을 때(끝난 상태) 한 번씩 그 대화의 대화 단위 SSE 로 간다. turn 의 SSE 에는 싣지 않는다. 대화 화면은 보낸 창이든 다른 창이든 대화 단위 SSE 를 늘 열고 있으므로, 보낸 창, 다른 창, 자동 turn 이 같은 길로 받는다. `approval` 사건과 같이 보낸 turn 이 도는 동안에도 미루지 않는다.

받은 화면은 줄을 실행 번호로 들고 있다가 `parentExecutionId` 가 같은 답 아래에 그린다. 흐르는 답은 `started` 의 실행 번호, 다른 창에서 보는 답은 `/running` 의 실행 번호로 찾는다. 답 글이 아직 없으면 기다림 줄 아래에 그린다. 이력을 다시 읽으면 이력의 줄과 합치고, 같은 실행이면 끝난 상태를 고른다. 끝난 상태는 다시 바뀌지 않으므로 늦게 온 쪽이 끝난 상태를 덮지 않는다.

`forViewer` 는 이 사건을 바꾸지 않는다. 이름표와 상태는 `MEMBER` 역할에게도 보이는 값이다.

#### 결과 도착 줄

`result_delivery` 에 컬럼 하나를 더한다. 마이그레이션 번호는 `orch queue reserve` 로 받는다.

| 컬럼 | 타입 | 뜻 |
| --- | --- | --- |
| `delegation_notice_message_id` | `BIGINT NULL` | 첫 시도가 저장한 위임 결과 알림 줄. 묶음에 위임 결과가 없거나 이 칸 전에 만든 묶음이면 NULL |

다시 전달은 새 알림 줄(`ChatService.RETRY_NOTICE`)을 남기고 이 칸은 바꾸지 않는다. 처음 도착한 줄이 이름표를 그대로 가진다.
이 칸 전의 알림 줄은 `deliveredResults` 가 비어 지금처럼 글만 보인다. 옛 줄을 채우는 DML 은 두지 않는다. 같은 대화의 실행 줄과 알림 줄을 시각으로 맞추는 것 말고는 이을 근거가 없기 때문이다.

화면은 `deliveredResults` 가 있으면 저장된 글 대신 결과마다 한 줄로 「이름표 + {이름}이 끝냈어요」(`SUCCEEDED`) 또는 「이름표 + {이름}이 하지 못했어요」(`FAILED`)를 그린다. 조사는 받침을 보고 정한다(`withParticle`). 저장된 글은 바꾸지 않는다. 옛 화면과 다른 소비자가 그대로 읽는다. 「결과 다시 전달」 단추는 지금 자리(묶음의 마지막 알림 줄 아래)에 그대로 둔다.
그 아래 자동 turn 의 답은 대화의 에이전트가 쓴 답이라 PR1 의 이름표 그대로다(spec DECISION19).

#### 승인 카드

`GET /api/v1/chat/conversations/{id}/connector-actions` 의 한 줄에 `requestedBy` 를 더한다. 승인 줄의 `agent_id` 가 대화의 `agent_id` 와 다르면 그 에이전트의 `AgentLabel`, 같거나 비었으면 null 이다. 지운 에이전트는 네 칸이 null 인 이름표다.
승인과 거절 응답(`POST .../approve`, `.../reject`)은 대화를 모르므로 이 칸을 싣지 않는다. 화면은 응답으로 카드를 바꿀 때 앞 값을 지킨다.
화면은 카드 머리 위에 「이름표 + {이름}이 요청」 을 그린다. 묶음 카드 안의 한 건마다 같다.

### 화면

| 자리 | 그림 |
| --- | --- |
| 답 본문 아래 | 맡긴 줄. 「(맡은 에이전트 이름표) {대화 에이전트 이름} → {맡은 에이전트 이름}에게 맡김 · {상태}」. 작은 글자의 한 줄이고 여럿이면 실행 번호순으로 쌓는다 |
| 기다림 줄 | 답 글이 아직 없는 turn 이 맡겼으면 기다림 점이나 작업 과정 아래에 같은 줄 |
| 결과 알림 줄 | 결과마다 「(이름표) {이름}이 끝냈어요」. 가운데 정렬은 지금 알림 줄과 같다 |
| 승인 카드 | 위 「승인 카드」 |

**맡긴 줄은 답 본문 아래에 둔다.** 답 글 안 어디서 맡겼는지는 저장되지 않는다. 흐르는 동안 글 사이에 끼우면 새로 고친 뒤 자리가 달라진다. 흐르는 답(`AnswerReveal`)은 본문 칸 안에서만 자라므로 그 아래 줄은 밀려 내려갈 뿐 다시 그려지지 않는다.

### 실패와 일관성

- 위임 결과 알림 줄의 `system` 사건에도 `deliveredResults` 를 실어, 자동 turn 이 도는 동안에도 결과 도착 줄을 이름표로 그린다.
- 맡긴 뒤 turn 이 답 줄 없이 실패하면 맡긴 줄을 붙일 답이 없어 대화에는 남지 않는다. 결과 도착 줄과 실행 트리로 본다.
- 사건을 놓쳐도 줄은 이력이 갖는다. 대화 단위 SSE 가 다시 붙으면 화면이 이력을 다시 읽는 지금 규칙(`resumeAfterReconnect`)이 상태를 맞춘다.
- `DelegationStarted` 와 `DelegationFinished` 를 받아 사건을 내다 실패하면 경고 로그만 남긴다. 위임 실행과 결과 전달은 이 사건에 기대지 않는다.
- 서버가 다시 떠 기동 정리가 끝낸 위임도 `DelegationFinished` 를 내므로 같은 사건이 간다.
- 배포 순서: 마이그레이션이 칸을 더한 뒤 새 이미지가 뜬다. 옛 이미지는 새 칸을 모르고 NULL 로 둔다. 그 사이 만든 묶음의 알림 줄은 글만 보인다.

### 검증 전략

| 요구사항 | 경계와 환경 |
| --- | --- |
| REQ3 | backend 통합 테스트: 이력 API 의 `delegations` 가 직접 위임만 싣고(손자, 위임이 아닌 자식 제외) 상태와 이름표, 지운 에이전트를 싣는다. `DelegationStarted`·`DelegationFinished` 가 그 대화의 대화 단위 구독에 `delegation` 사건을 내고 손자는 내지 않는다. 브라우저: 가짜 Hermes 의 위임 시나리오로 보낸 창의 진행 중 줄, 자식이 끝나면 새로 고치지 않고 「끝남」, 새로 고친 뒤 같은 줄 |
| REQ4 | backend: 첫 시도가 `delegation_notice_message_id` 를 적고 다시 전달은 그대로 둔다. 이력 API 의 `deliveredResults` 가 그 줄에만 붙는다. 승인 줄 목록의 `requestedBy` 가 대화 에이전트와 다를 때만 붙는다. 브라우저: 결과 도착 줄의 이름표와 글, 그 아래 답의 비서 이름표, 승인 카드의 「○○이 요청」. 마이그레이션은 H2 와 CI 의 MySQL job |

## PR3 마법사

REQ5, REQ6 의 책임이다. 테이블과 컬럼을 늘리지 않는다. 지금 있는 만들기, 성격 쓰기, 붙이기, 떼기를 한 요청이 차례로 부른다.

### 지금 구조

- 에이전트 만들기(`AgentLifecycleService.create`)는 주인 행을 잠그고 개수 상한을 센 뒤 profile 을 만들고, 실패하면 profile 을 거둔다. 이름만 받는다.
- 성격은 `PersonaService` 가 대시보드의 `SOUL.md` 로 읽고 쓴다. 쓰기는 읽어 둔 본문의 지문이 맞을 때만 한다.
- 붙이기(`ConnectorBindingService.bind`)와 떼기(`unbind`)는 주인만 하고, 설치 외부 호출을 시작 상태 커밋 뒤에 한다. `single_binding` 플러그인은 그 연결이 다른 에이전트에 붙어 있으면 붙이기를 거절한다.
- 연결을 마친 화면(`ConnectorConnectionPanel`)은 바인딩이 없거나 에이전트 화면에서 왔으면 붙일 에이전트 고르기(`ConnectorAgentChooser`)를 연다. 연결을 끊으면 그 연결의 바인딩이 모두 떨어진다.

### 책임과 경계

| 자리 | 맡는 것 |
| --- | --- |
| `agent.application.AgentLifecycleService` | `create(user, introduction, visibility)` 를 더한다. 이름만 받던 만들기와 같은 잠금과 상한이고, 행을 저장하기 전에 소개를 함께 적어 소개 저장이 따로 실패하지 않는다 |
| `connector.connection.application.SpecialistAgentCreator` (새 빈) | 아래 「순서와 거두기」. 만들기, 성격 틀, 붙이기, 떼기를 부르고 실패하면 역순으로 거둔다. 자기 트랜잭션은 없다. 부르는 단계가 각자 잠그고 커밋한다 |
| `connector.connection.presentation.SpecialistAgentController` | `POST /api/v1/connections/{connectorId}/specialist` |
| `ConnectorSummary`, `ConnectionDtos.ConnectorView` | `singleBinding` 칸. 화면이 떼기 칸을 잠그는 데 쓴다 |
| `web/src/lib/specialist-agent.ts` | 제안을 띄울지, 미리 채울 이름·소개·아이콘, 떼기 칸의 글과 잠금, 요청 함수 |
| `web/src/components/agent/specialist-agent-form.tsx` | 미리 채운 이름·소개·아이콘·색과 떼기 칸, 만들기 단추. 연결 화면과 에이전트 목록이 같은 부품을 쓴다 |
| `web/src/components/agent/specialist-agent-offer.tsx` | 연결 화면의 제안. 받으면 위 부품, 거절하면 지금의 고르기 |
| `web/src/components/agent/connector-onboarding.tsx` | 연결 화면 맨 위 영역. 제안을 띄울 때면 제안을, 아니면 지금의 고르기(`ConnectorAgentChooser`)를 그린다. 패널은 이 부품 하나만 부른다 |
| `web/src/components/agent/specialist-agent-dialog.tsx` | 에이전트 목록의 「전문 에이전트 만들기」. 준비된 연결을 고른 뒤 같은 부품 |

### API

`POST /api/v1/connections/{connectorId}/specialist`

```json
{ "name": "네이버 쇼핑 담당", "description": "네이버 쇼핑 일을 맡아요", "icon": "🛍️", "color": null, "detach": true }
```

- 소개 네 칸은 `PATCH /api/v1/agents/{code}/introduction` 과 같은 판정(`AgentIntroduction.of`)이다
- `detach` 가 참이면 그 연결이 지금 붙은 다른 에이전트의 바인딩을 모두 뗀다. 붙은 곳이 없으면 아무것도 하지 않는다
- 공개 범위는 늘 비공개다(spec DECISION14). 도구는 새 에이전트 기본값이고 플러그인의 스킬은 붙이기가 올린다(spec DECISION23)
- 응답은 `201` 과 아래 본문이다. `connection` 은 `PUT /api/v1/agents/{code}/connections/{connectorId}` 의 응답과 같다

```json
{ "agentCode": "a1b2c3d4e5", "agentName": "네이버 쇼핑 담당",
  "connection": { "connectorId": "naver-shopping", "bound": true, "status": "PENDING", "restartRequired": false },
  "detachedFrom": [{ "agentCode": "a0000000001", "agentName": "비서" }] }
```

| 거절 | 코드 | 만든 것 |
| --- | --- | --- |
| 소개가 상한을 넘거나 목록 밖 값 | `VALIDATION_FAILED` | 없음 |
| 연결이 준비되지 않았거나 없음 | `CONNECTOR_NOT_CONNECTED` | 없음 |
| 카탈로그에 없는 플러그인 | `CONNECTOR_NOT_FOUND` | 없음 |
| `single_binding` 이 다른 에이전트에 붙었는데 `detach` 가 거짓 | `CONNECTOR_SINGLE_BINDING` | 없음 |
| 개수 상한 | `AGENT_LIMIT_REACHED` | 없음 |
| 만들기, 성격 틀, 붙이기, 떼기 중 실패 | 그 단계의 코드 | 거둔다 |

### 순서와 거두기

미리 거절할 것을 모두 본 뒤에 만든다. 위 표의 앞 넷은 아무것도 만들기 전에 거절한다.

1. 에이전트를 소개와 함께 만든다(`create(user, introduction, PRIVATE)`). 상한은 여기서 센다
2. 성격 틀을 쓴다. 새 profile 의 지금 본문을 읽고 그 지문으로 `PersonaService.write` 를 부른다
3. `single_binding` 이고 `detach` 면 다른 에이전트에서 먼저 뗀다. 붙어 있으면 새 에이전트에 붙일 수 없기 때문이다
4. 새 에이전트에 붙인다(`bind`)
5. `single_binding` 이 아니고 `detach` 면 이제 다른 에이전트에서 뗀다. 붙인 뒤에 떼므로 붙이기가 실패해도 지금 쓰던 에이전트는 그대로다

2 부터 5 사이에서 실패하면 새 에이전트를 지운다(`AgentLifecycleService.delete`). 지우기가 그 에이전트의 바인딩을 떼고 profile 을 거두므로 성격 틀과 붙은 설치도 함께 거둬진다. 3 에서 뗀 에이전트가 있으면 그다음 다시 붙인다. 거두기의 실패는 원래 오류를 가리지 않고 오류 로그만 남긴다. 응답은 원래 단계의 오류다.

5 에서 떼기가 실패하면 새 에이전트를 지우므로 그 연결은 떼려던 에이전트에 그대로 남는다. 쓰던 대로 쓰이고 사용자는 다시 시도한다.

각 단계는 자기 잠금과 트랜잭션을 그대로 쓴다. 단계 사이에 다른 요청이 같은 연결을 붙이거나 뗄 수 있다. 그때는 그 단계가 지금처럼 거절하고 위 거두기를 탄다.

### 성격 틀

새 profile 의 `SOUL.md` 를 아래 틀로 바꾼다. 이름과 플러그인 이름만 넣고 나머지는 고정 글이다. 사용자는 상세의 성격에서 고친다.

```text
너는 「{이름}」이다. 「{플러그인 이름}」 일만 맡는 전문 에이전트다.
{소개}
이 서비스의 도구와 함께 붙은 스킬로 일하고, 맡은 일이 이 서비스 밖이면 할 수 없다고 짧게 답한다.
```

소개가 비었으면 그 줄을 뺀다.

### 화면

| 자리 | 그림 |
| --- | --- |
| 연결을 마친 화면 | 그 연결에 바인딩이 하나도 없고 방금 연결을 마쳤고 `?agent=` 로 오지 않았으면 고르기 대신 「이 서비스만 맡는 전문 에이전트를 만들까요?」 와 「만들기」, 「고른 에이전트에 붙이기」 가 뜬다. 「고른 에이전트에 붙이기」 는 지금의 고르기를 연다 |
| 만들기 화면 | 이름(「{플러그인 이름} 담당」), 소개(「{플러그인 이름} 일을 맡아요」), 아이콘(🤖. 운영 코드는 특정 플러그인을 모르므로 플러그인마다 고르지 않는다)을 미리 채운다. 색은 자동이다. 그 아래 떼기 칸 |
| 떼기 칸 | 연결이 붙은 다른 에이전트가 있을 때만 보인다. 글은 「「{붙은 에이전트 이름}」에서는 떼고 맡기기만」 이고 기본으로 켠다. 기본 비서는 이름이 「비서」 라 「「비서」에서는 떼고 맡기기만」 으로 보인다. `single_binding` 이면 켠 채 잠그고 까닭을 적는다 |
| 에이전트 목록 | 「새 에이전트」 옆에 「전문 에이전트 만들기」. 내 연결 가운데 준비된 것을 고르면 같은 만들기 화면이다. 준비된 연결이 없으면 「외부 서비스 연결」 로 안내한다 |
| 만든 뒤 | 연결 화면에서는 「「{이름}」을 만들고 「{플러그인 이름}」을 붙였어요」 와 언제 쓸 수 있는지, 그 에이전트와 대화하기. 언제 쓸 수 있는지는 연결 화면이 반영을 기다리며 다시 읽는 바인딩 상태를 따른다. 에이전트 목록에서는 그 에이전트 상세로 옮긴다 |
| 실패 | 만들기 화면에 그 단계의 까닭을 보인다. 만들기 뒤 단계의 실패면 「만들던 에이전트를 거뒀어요. 에이전트 목록에 남아 있으면 지워 주세요」 를 붙인다. 거두기도 실패하면 에이전트가 남을 수 있어서다. 위 거절 표에서 아무것도 만들지 않는 거절과 연결 실패에는 붙이지 않는다 |

**제안은 바인딩이 하나도 없을 때만 띄운다(2026-11-12 사용자 확인, spec DECISION7).** 스펙의 「처음 준비된 화면」 은 연결을 마친 뒤 아직 어느 에이전트에도 붙지 않은 때다. 붙지 않았으니 그 서비스가 붙은 전문 에이전트도 없다. 기본 비서를 가리는 칸이 없어도 판정이 된다. 이미 붙은 연결로 전문 에이전트를 만드는 길은 에이전트 목록이다.

### 실패와 일관성

- 한 요청이 몇 초에서 수십 초 걸린다. profile 만들기와 설치 외부 호출이 들어서다. 화면은 도는 동안 단추를 막고 창을 닫지 못하게 한다
- 거두기 도중 서버가 멈추면 만든 에이전트가 남을 수 있다. 사용자에게는 실패로 보이고 목록에 새 에이전트가 보인다. 사용자가 지우거나 그대로 쓴다. 남은 것을 찾는 정리 작업은 두지 않는다
- 붙이기가 설치를 부르다 실패해 바인딩이 `PENDING` 으로 남는 경우(`CONNECTOR_OPERATION_FAILED`)도 실패로 보고 거둔다. 지금 붙이기 화면은 남겨 두고 다음 확인에 맡기지만, 마법사는 새로 만든 에이전트라 남길 까닭이 없다

### 검증 전략

| 요구사항 | 경계와 환경 |
| --- | --- |
| REQ5 | backend 통합 테스트: 만들면 소개가 채워진 비공개 에이전트와 그 연결의 바인딩이 생기고 성격 틀을 쓴다. 상한, 준비 안 된 연결, 목록 밖 값은 아무것도 만들지 않는다. 성격 쓰기와 붙이기가 실패하면 새 에이전트가 지워지고 profile 을 거둔다. 브라우저: 연결을 마친 화면의 제안, 미리 채운 값, 만든 뒤 안내, 에이전트 목록의 「전문 에이전트 만들기」 |
| REQ6 | backend: `detach` 면 다른 에이전트의 바인딩이 없어지고 `agent_list` 에 새 에이전트와 그 서비스가 보인다. `single_binding` 은 먼저 떼고 붙이며, `detach` 없이는 거절한다. 붙이기가 실패하면 뗀 에이전트에 다시 붙인다. 화면 단위 테스트: 떼기 칸의 글과 잠금. 실제 대화에서 맡긴 줄이 생기는지는 배포 뒤 확인한다 |

## PR4 @ 멘션

REQ7 의 책임이다. 테이블과 컬럼을 늘리지 않는다. 지금 있는 대화 turn 을 고른 에이전트로 한 번 돌리고, 맡김인지는 실행 줄의 값으로 판정한다.

### 지금 구조

- 입력창의 `@` 목록은 새 대화 화면에서만 뜨고 대화의 에이전트를 고른다(`Composer` 의 `mention`). 첫 메시지 뒤에는 잠긴다.
- 보내기 요청(`SendMessageRequest`)은 대화, 글, 에이전트 코드, 첨부다. 이어 쓰는 대화는 에이전트 코드를 무시하고 대화의 에이전트로 돈다.
- 대화의 turn 은 `ChatTurnRunner` 가 대화의 Hermes session 으로 돌리고 답을 그 실행 번호의 `ASSISTANT` 줄로 남긴다. 사람이 보낸 turn 의 실행 줄은 `question_message_id` 로 질문 줄을 잇는다.
- `agent_delegate` 의 위임은 부모 실행 아래 새 session 으로 돌고 답은 실행 줄의 `output_text` 에만 남는다. 끝난 결과는 `delegation_key` 가 있는 실행만 골라 자동 turn 이 대화의 에이전트에게 전한다(ADR-040).
- 이력 API 의 답 줄에는 답을 쓴 에이전트 칸이 없다. 한 대화의 답은 모두 그 대화의 에이전트가 썼기 때문이다(DECISION5).

### 맡김 turn

**보내기 요청에 `delegateTo`(에이전트 코드)가 있으면 Control Plane 이 그 에이전트로 turn 하나를 돌린다.** 모델은 맡길지를 정하지 않는다(spec DECISION9).
대화의 turn 잠금, 질문 저장, 실행 기록, 중지, 대기 메시지, 결과물은 보통 turn 과 같다. 다른 점은 넷이다.

| 무엇 | 보통 turn | 맡김 turn |
| --- | --- | --- |
| 실행할 에이전트 | 대화의 에이전트 | `delegateTo`. 요청자가 쓸 수 있고 켜진 에이전트만(`AgentService.requireStartable`) |
| Hermes session | 대화의 session. 끝나면 대화에 적는다 | 새 `fos-<uuid>`. 대화의 `hermes_session_id` 를 읽지도 바꾸지도 않는다 |
| 입력 | 사용자 글 | 대화의 최근 줄과 출처 머리줄, 그 아래 사용자 글. 아래 「맡은 에이전트의 입력」 |
| 지시 끝 | 없음 | `TurnIntent.MENTION_INSTRUCTION` |

실행 줄은 부모가 없는 루트 실행이고, 대화는 그 대화, 에이전트는 맡은 에이전트다. `delegation_key` 는 두지 않는다. 두면 자동 결과 전달이 끝난 맡김을 위임 결과로 읽어 대화의 에이전트를 깨운다. spec DECISION20 의 「비서는 끼지 않는다」 를 어긴다.
**맡김 실행은 「대화의 실행 가운데 부모가 없고 에이전트가 대화의 에이전트와 다른 실행」 이다.** 대화의 에이전트는 바뀌지 않으므로(`docs/features/chat.md` 「요구」) 보통 turn, 자동 turn, 예약 turn, 먼저 살펴보기는 모두 대화의 에이전트로 돈다. 판정은 `MentionLines` 가 한 질의(`findByConversationIdAndParentExecutionIdIsNullAndAgentIdNotOrderByIdAsc`)로 한다.

거절은 아무것도 저장하기 전에 한다.

| 거절 | 코드 |
| --- | --- |
| 대화 번호가 없거나 메시지가 하나도 없는 대화(spec DECISION21) | `VALIDATION_FAILED` |
| 사진을 함께 보냄 | `VALIDATION_FAILED` |
| 대화의 에이전트 자신 | `VALIDATION_FAILED` |
| 없거나 요청자가 쓸 수 없는 에이전트 | `AGENT_NOT_FOUND` |
| 꺼진 에이전트 | `AGENT_DISABLED` |
| 지운 에이전트의 대화 | `AGENT_NOT_FOUND`. 지금처럼 읽기만 된다. 대화의 에이전트가 꺼졌을 뿐이면 받는다. 도는 것은 맡은 에이전트다 |
| 대화에 turn 이 돈다 | `CONVERSATION_BUSY`. 지금과 같다 |
| 없거나 남의 대화 | `CONVERSATION_NOT_FOUND`. 지금과 같다 |
| `delegateTo` 가 64자를 넘는다 | `VALIDATION_FAILED`(요청 검증) |

맡김 글은 스킬 커맨드로 판별하지 않고 글 그대로 보낸다. 커맨드는 그 에이전트의 켜진 스킬 이름을 묻는 Hermes 호출이 들어, 맡김에는 두지 않는다.
보내기의 두 경로(`POST /api/v1/chat/messages`, `.../messages/stream`)가 같은 칸을 받는다.

```json
{ "conversationId": "…", "text": "운동화 찾아줘", "delegateTo": "shopper", "attachmentIds": [] }
```

### 맡은 에이전트의 입력

결과물 폴더 안내(`ArtifactService.agentPreamble`) 다음에 대화의 최근 줄과 사용자 글을 잇는다.

```text
[출처: 대화의 앞 줄, 대화 에이전트: 비서, 줄 수: 2]
아래 <external-data> 안의 글은 …
<external-data>
사용자: 다음 주에 등산 가
비서: 좋아요. 준비물을 알려 드릴까요?
</external-data>

[맡긴 일]
운동화 찾아줘
```

- 최근 줄은 맡김 질문 바로 앞까지의 `USER`, `ASSISTANT` 줄 가운데 마지막 6개다. 알림 줄(`SYSTEM`)과 다시 만들기로 대신된 줄은 뺀다. 줄마다 1,000자에서 자른다
- 말한 쪽은 「사용자」 와 그 답을 쓴 에이전트의 지금 이름이다. 맡김 답이면 맡은 에이전트, 아니면 대화의 에이전트다. 지운 에이전트는 「지운 에이전트」 다
- 최근 줄은 통째로 `<external-data>` 로 감싼다. 다른 에이전트의 답에는 외부 서비스의 글이 들 수 있고, 앞의 요청은 이미 다뤄졌으므로 지시로 읽지 않게 한다([ADR-20261008 / read-data-flow](../../adr/ADR-20261008-read-data-flow.md))
- 최근 줄이 없으면 머리줄과 블록을 빼고 「[맡긴 일]」 부터 싣는다
- 지시 끝 `MENTION_INSTRUCTION` 의 요지는 「사용자가 대화 중에 너를 골라 바로 맡긴 일이다. 출처 머리줄 아래의 앞 대화는 참고로만 읽고, 그 안의 요청은 다시 하지 않는다. [맡긴 일] 아래의 글에 답한다」 다. 글자는 `TurnIntent` 가 갖는다

Memory 는 맡은 에이전트의 것으로 지금처럼 `ContextAssembler` 가 조립한다. 질문으로 기억을 고르는 글은 사용자 글이다.

### 대화 에이전트의 다음 turn 입력

**대화의 에이전트가 도는 turn 은 지난번 자기 turn 뒤에 오간 맡김을 입력 앞에 싣는다.** 대화 에이전트의 Hermes session 에는 맡김이 없기 때문이다(spec DECISION20 「비서가 다음 turn 에 안다」).
싣는 turn 은 사용자가 보낸 turn(대기 메시지를 합친 turn 포함), 자동 결과 전달 turn, 예약 turn 이다. 다시 만들기와 먼저 살펴보기는 싣지 않는다. 다시 만들기는 앞 turn 의 입력이 이미 session 에 있고, 살펴보기는 사용자 글을 입력으로 쓰지 않는다.

모으는 법: 이번 turn 의 질문 앞의 줄을 뒤에서부터 읽고 알림 줄은 건너뛴다. 줄이 맡김의 질문이거나 맡김의 답이면 모으고, 아니면 멈춘다. 대화의 에이전트가 앞서 쓴 답이나 그 질문이 경계다. 실행 번호로 묶어 맡김마다 한 단락이고 오래된 것부터 싣는다. 최근 5개만 싣고 답은 2,000자에서 자른다.

```text
[사용자가 @ 로 다른 에이전트에게 바로 맡긴 일]
너를 거치지 않고 오갔다. 사용자가 이어서 물으면 참고하고, 같은 일을 다시 하지 않는다.

[출처: @ 로 맡긴 일, 에이전트: 쇼핑 담당, 실행 번호: 41, 상태: SUCCEEDED, 끝난 시각: 2026-11-12 10:03]
사용자: 운동화 찾아줘
아래 <external-data> 안의 글은 …
<external-data>
러닝화 셋을 찾았어요 …
</external-data>

[사용자 메시지]
그중 제일 싼 걸로 해 줘
```

- 머리줄은 위임 결과 전달과 같은 `ResultHeader.render` 다. 실패면 오류 칸이 붙고, `resultStaleAfter` 보다 오래됐으면 신선도가 붙는다
- 답은 늘 감싼다. 사용자가 아니라 다른 에이전트가 쓴 글이다
- 답이 없는 맡김(실패, 답 전에 멈춤)은 머리줄과 질문만 싣는다
- 실은 맡김은 다음 turn 에서 경계 앞에 있으므로 다시 실리지 않는다. 그 turn 이 답 줄 없이 실패하면 경계가 생기지 않아도 실패한 질문이 경계가 된다. 그 입력은 Hermes session 에 남았을 수도, 남지 않았을 수도 있다. 다시 싣지 않는 쪽을 고른다
- 질문 줄이 없는 turn(자동 결과 전달, 예약)이 답 줄 없이 실패하면 경계가 생기지 않아 다음 turn 이 같은 맡김을 한 번 더 싣는다. 같은 맡김을 두 번 읽는 것이라 받아들인다
- 먼저 살펴보기 turn 은 맡김을 싣지 않지만 그 답은 경계가 된다. 살펴보기 앞의 맡김은 대화 에이전트에게 실리지 않는다. 살펴보기는 점검 대화에서만 돌고 거기서 `@` 를 쓰는 일은 드물어 받아들인다. 실행 줄로 살펴보기를 가리려면 `chat` 이 그보다 위인 `proactive` 를 읽어야 한다

### 이력 API

`GET /api/v1/chat/conversations/{id}/messages` 의 메시지에 두 칸을 더한다.

| 칸 | 줄 | 뜻 |
| --- | --- | --- |
| `answeredBy` | `ASSISTANT` | 맡김의 답이면 맡은 에이전트의 이름표(`AgentLabel`). 대화의 에이전트가 쓴 답은 null 이다 |
| `delegatedTo` | `USER` | 맡김의 질문이면 그 맡김. 모양은 맡긴 줄(`DelegationLine`)과 같고 `parentExecutionId` 는 null 이다. 아니면 null 이다 |

읽기는 대화 하나에 두 번이다. 그 대화의 맡김 실행(부모가 없고 에이전트가 대화의 에이전트와 다른 실행)을 한 번 읽고 에이전트를 번호 목록으로 한 번 읽는다. 질문 줄은 실행의 `question_message_id`, 답 줄은 실행 번호로 찾는다.

### 다시 만들기

마지막 답이 맡김의 답이거나 마지막 질문이 맡김의 질문이면 `VALIDATION_FAILED` 로 거절한다. 다시 만들기는 대화의 에이전트로 대화의 session 에서 돌기 때문이다. 화면은 맡김 답에 「답 다시 만들기」 를 그리지 않는다.

### 책임과 경계

| 자리 | 맡는 것 |
| --- | --- |
| `chat.turn.presentation.ChatDtos` | `SendMessageRequest.delegateTo`, `MessageView` 의 `answeredBy`, `delegatedTo` |
| `chat.turn.application.ChatService` | `send`, `stream` 이 `delegateTo` 를 받아 맡김이면 `ChatTurnRouting.routeMention` 과 `TurnIntent.Mention` 으로 돌린다 |
| `chat.turn.application.ChatTurnRouting` | `routeMention`. 위 거절 표. 질문 저장은 사용자가 보낸 turn 과 같다 |
| `chat.turn.application.dto.TurnIntent` | `Mention` 과 `MENTION_INSTRUCTION` |
| `chat.turn.application.ChatTurnRunner` | 질문을 저장하기 전에 `MentionInputs.lead` 로 입력 앞 단락을 받는다. 맡김이면 최근 줄(`recentLines`), 대화 에이전트의 turn 이면 지난 맡김(`carryOver`)이다 |
| `chat.turn.application.ChatTurnLifecycle` | 맡김이면 새 session 으로 명령과 실행 줄을 만들고, 끝나거나 멈춰도 대화의 session 을 적지 않는다(바뀐 시각만 올린다). 추천 질문을 다시 만들지 않는다 |
| `chat.recovery.application.RecoveredRunRecorder` | 기동 정리가 맡김을 마무리할 때 대화의 session 을 적지 않는다 |
| `chat.turn.application.MentionLines` (새 빈) | 맡김 실행의 판정과 읽기. 대화의 맡김 실행을 한 번 읽어 질문 줄과 답 줄에 붙일 값을 만든다 |
| `chat.turn.application.MentionInputs` (새 빈) | 맡은 에이전트의 입력과 대화 에이전트의 다음 turn 머리 단락 |
| `usage.execution.infra.AgentExecutionRepository` | 대화의 맡김 실행을 읽는 메서드 |
| `web/src/app/api/chat/stream/route.ts`, `web/src/components/chat/use-conversation-send.ts` | `delegateTo` 를 넘긴다. 보내기가 거절되면 고른 에이전트를 되돌린다 |
| `web/src/lib/mention.ts` | `@` 목록에 넣을 에이전트, 맡김 질문과 답의 화면 값 |
| `web/src/components/chat/delegation-lines.tsx` | 질문 아래의 「나 → 쇼핑 담당에게 맡김 · 상태」 줄(`MentionLine`) |
| `web/src/components/chat/composer.tsx` 와 입력 훅 | 메시지가 있는 대화의 `@` 목록과 「{이름}에게 맡기기」 칩 |
| `web/src/components/chat/message-bubble.tsx`, `message-list.tsx` | 답 줄과 기다림 줄의 이름표를 `answeredBy` 로 바꾼다. 맡김 답의 다시 만들기를 감춘다 |

### 화면

| 자리 | 그림 |
| --- | --- |
| `@` 목록 | 메시지가 하나라도 있는 대화에서 답이 돌지 않을 때 뜬다. 목록은 에이전트 목록(`GET /api/v1/agents`, 요청자가 쓸 수 있고 켜진 것)에서 대화의 에이전트를 뺀 것이다(spec DECISION21). 뺀 뒤 비면 뜨지 않는다 |
| 고른 뒤 | `@` 와 친 글자를 지우고 입력창 위에 「(이름표) {이름}에게 맡기기」 칩과 지우기 단추를 둔다. 칩이 있는 동안 사진 단추를 감춘다. 보내면 칩이 비워진다 |
| 보낸 질문 아래 | 「(맡은 에이전트 이름표) 나 → {이름}에게 맡김 · 진행 중·끝남·실패·멈춤」. 맡긴 줄과 같은 글자 크기다 |
| 답 | 기다림 줄과 답의 이름표가 맡은 에이전트다. 그 답의 맡긴 줄(맡은 에이전트가 다시 맡긴 일)도 「{맡은 에이전트} → …」 로 보인다 |
| 맡김 답의 버튼 | 「답 다시 만들기」 가 없다 |
| 다른 창 | 맡김도 turn 잠금을 쥐므로 지금처럼 답이 오는 중으로 보이고 끝나면 이력을 다시 읽는다. 이력의 마지막 질문이 도는 맡김이면 기다림 줄이 맡은 에이전트 이름표다 |

**답이 도는 동안에는 `@` 목록을 띄우지 않는다.** 그동안 보낸 글은 대기 메시지가 되고, 대기 메시지는 글만 받는다(ADR-048).
**사진과 맡김을 함께 보내지 않는다.** 사진은 대화에 올리고 대화의 에이전트가 사진을 받는지로 판정한다. 맡은 에이전트까지 판정을 넓히지 않는다.

### 실패와 일관성

- 맡은 실행이 실패하면 질문 아래 줄이 「실패」 이고 답 줄이 없다. turn 실패 안내는 지금과 같다. 대화 에이전트의 다음 turn 에는 실패로 실린다
- 멈추면 지금의 중지와 같다. 그때까지의 답이 맡은 에이전트 이름표로 남는다
- 서버가 다시 떠 기동 정리가 도는 맡김을 마무리할 때도 대화의 session 을 적지 않는다(`RecoveredRunRecorder`). 맡김 판정과 같은 기준(에이전트가 대화의 에이전트와 다르다)이다
- 맡은 에이전트가 `agent_delegate` 로 다시 맡기면 그 위임은 부모가 맡김 실행(루트)이라 PR2 의 맡긴 줄로 맡김 답 아래에 보인다. 끝난 결과는 지금처럼 대화의 에이전트가 자동 turn 으로 전한다. 자동 결과 전달은 대화 단위다
- 배포: 마이그레이션이 없다. 옛 화면은 `delegateTo` 를 보내지 않고 새 칸을 무시한다. 새 화면이 옛 서버에 `delegateTo` 를 보내면 옛 서버가 칸을 무시하고 대화의 에이전트로 돈다. 배포는 서버가 먼저다

### 검증 전략

| 요구사항 | 경계와 환경 |
| --- | --- |
| REQ7 | backend 통합 테스트: 맡김 보내기가 고른 에이전트의 루트 실행을 새 session 으로 만들고, 답이 그 실행의 `ASSISTANT` 줄로 남고, 대화의 에이전트와 session 이 그대로다. 맡은 에이전트의 입력에 출처 머리줄과 최근 줄, 사용자 글이 실린다. 이력 API 의 `delegatedTo`, `answeredBy`. 거절 표. 대화 에이전트의 다음 turn 입력에 맡김이 실리고, 그다음 turn 에는 다시 실리지 않는다. 다시 만들기 거절. 화면 단위 테스트: `@` 목록의 후보(대화의 에이전트 제외). 브라우저: 메시지가 있는 대화에서 `@` 로 골라 보내면 「나 → 쇼핑 담당에게 맡김」 줄과 맡은 에이전트 이름표의 답이 보이고, 새로 고쳐도 같다. 화면 흐름이 바뀌므로 PR 에 `검사:전체` 라벨 |

배포 뒤 확인: 실제 Hermes 로 맡김 한 번과 그다음 비서 turn 이 맡김을 아는지 왕복한다. 확인 방법은 `fos-home-infra` 가 갖는다.

## PR5 대화방

REQ8, REQ9 의 책임이다. 테이블과 컬럼을 늘리지 않는다. 대화방은 `conversation.purpose` 의 새 값이고(spec DECISION13), 날짜 경계는 실행 줄의 시작 시각으로 판정한다.

### 지금 구조

- `conversation.purpose` 는 `CHAT` 과 `CHECK` 다. 점검 대화는 사용자와 에이전트마다 지우지 않은 것 가운데 `id` 가 가장 큰 것을 이어 쓰고, `CheckConversations` 가 메모리 잠금 안에서 찾고 만든다(ADR-080). 칸은 `VARCHAR(16)` 이고 값 제약이 없다.
- `/` 는 새 대화 화면이다(`web/src/app/page.tsx`). 「새 대화」, 앱 이름, 단축키, 대화를 지운 뒤 이동이 모두 `/` 로 가고, `ChatPanel` 은 주소가 `/` 로 바뀌면 대화별 상태를 새로 만든다. 「<에이전트>와 대화하기」 는 `/?agent=<code>` 다.
- 대화 목록 API(`GET /api/v1/chat/conversations`)는 지우지 않고 숨기지 않은 대화를 최근 것부터 쪽으로 준다. 이름 바꾸기와 지우기는 사이드바 목록 줄의 메뉴에만 있다(`conversation-nav.tsx`).
- 에이전트 지우기(`AgentLifecycleService.delete`)는 주인과 `ADMIN` 이 한다. 첫 에이전트도 지울 수 있고, 그때 사용자의 기본 profile 은 거두지 않는다(`profileManaged` 가 거짓). 끄기는 관리자 경로(`AgentAdminService`)에만 있다.
- 대화 turn 은 대화의 `hermes_session_id` 로 돌고, 실행 줄의 `hermes_session_id` 에는 대화의 루트 session 을 적는다(ADR-031). Hermes 는 길어진 session 을 스스로 압축하고 새 session 을 돌려주면 보낼 session 만 바뀐다. 루트는 바뀌지 않는다.
- `ConversationSessions.renew` 가 두 session 칸을 새 `fos-<uuid>` 로 함께 바꾼다. 먼저 살펴보기가 한 session 의 실행 수 상한에 닿았을 때 쓴다.
- 대화 밖 실행은 `ExecutionRecorder.startDetached` 로 만든다. 대화, 부모, session 이 비고 사용자 실행 한도는 백그라운드로 센다. 추천 질문이 이 길을 쓴다.

### 대화방

**대화방은 `purpose = ROOM` 인 대화이고 사용자마다 지우지 않은 것 가운데 `id` 가 가장 큰 것 하나를 쓴다.** 점검 대화와 같은 규칙이고, 에이전트가 아니라 사용자로만 찾는다.

- 만드는 때: 처음 열 때(`POST /api/v1/chat/conversations/room`). 배포 뒤 처음 열면 빈 방이다. 지난 대화를 옮겨 오지 않는다(spec DECISION24)
- 에이전트: 만들 때의 기본 비서. 대화의 에이전트는 바뀌지 않는다는 지금 규칙을 따른다
- 제목: 고정 글 「대화방」. 첫 메시지가 빈 제목을 채우는 규칙은 제목이 비어 있지 않아 타지 않는다. 화면은 제목 대신 에이전트 이름표를 그린다
- 기본 비서가 없으면(지운 첫 에이전트가 남은 옛 사용자) 만들지 않고 `AGENT_NOT_FOUND` 다. 화면은 새 대화 화면으로 간다
- 기본 비서가 꺼져 있어도 방은 연다. 보내기는 지금처럼 `AGENT_DISABLED` 다

**기본 비서는 「주인이 요청자이고 profile 이 요청자의 기본 profile(`allowed_person.hermes_profile`)인, 지우지 않은 에이전트」 다.** 칸을 두지 않는다(아래 결정 DECISION25). `agent` 가 `people` 보다 아래라 기존 port `ReservedProfileNames` 에 `profileOf(email)` 을 더하고 `people` 이 구현한다.

**지우기는 profile 이 허용 목록의 누군가의 기본 profile 인 에이전트를 거절한다.** 주인과 `ADMIN` 모두 `PRIMARY_AGENT_PROTECTED`(409)다. 그런 에이전트는 첫 로그인이 만든 것뿐이다. 만들기가 그 profile 이름을 받지 않기 때문이다(`AgentLifecycleService` 의 profile 이름 판정). 사람을 허용 목록에서 지울 때의 정리(`AgentUserPurge`)는 이 경로를 타지 않아 그대로다. 끄기는 이미 관리자 경로에만 있어 바꾸지 않는다(spec DECISION24).

**대화방은 이름을 바꾸거나 지울 수 없다.** 두 경로(`PATCH`, `DELETE /api/v1/chat/conversations/{id}`)가 `ROOM` 이면 `VALIDATION_FAILED` 다. 화면은 대화방을 목록 줄로 그리지 않아 메뉴가 없다.

### 이력 밖 API

| 경로 | 뜻 |
| --- | --- |
| `POST /api/v1/chat/conversations/room` | 요청자의 대화방을 찾고 없으면 만든다. 응답은 대화 한 줄(`ConversationView`)이다. 같은 사용자의 두 요청이 겹쳐도 하나만 만든다 |
| `GET /api/v1/chat/conversations/room` | 요청자의 대화방을 찾기만 한다. 없으면 `CONVERSATION_NOT_FOUND` 다. 사이드바가 이름표를 그릴 때 쓴다 |
| `GET /api/v1/chat/conversations` | 대화방을 뺀다. 첫 쪽과 다음 쪽 모두 같다 |

`ConversationView.purpose` 에 `ROOM` 이 더해진다. 화면 타입도 같이 바꾼다.

### 날짜 경계

**대화방에서 사용자가 보낸 turn(`TurnIntent.Fresh`, 대기 메시지를 합친 turn 포함)이 질문을 저장하기 전에 판정한다.** 다시 만들기, `@` 맡김, 자동 결과 전달, 예약 turn 은 판정하지 않는다. 다시 만들기는 앞 turn 의 session 으로 돌아야 하고, 맡김은 새 session 이며, 사람이 없는 turn 은 「날이 바뀐 첫 메시지」 가 아니다.

판정: 그 대화에서 부모가 없고 `hermes_session_id` 가 대화의 지금 루트 session 인 실행 가운데 가장 이른 `started_at` 을 Asia/Seoul 날짜로 바꿔 오늘보다 앞이면 넘는다. 실행이 없으면(새 방, 막 바꾼 session) 넘지 않는다.

- 칸을 두지 않는다. 요약이 성공하면 루트가 새 값이 되어 그 session 의 첫 실행이 오늘이고, 실패하면 루트가 그대로라 다음 메시지에서 다시 넘는다(spec DECISION25)
- 압축 교체는 보낼 session 만 바꾸고 루트를 두므로 판정이 흔들리지 않는다
- 며칠 쉬었어도 한 번만 요약한다. 요약은 옛 session 전체를 대상으로 한다
- 시각은 `Clock` 을 쓴다. 시간대는 다른 집계와 같은 `Asia/Seoul` 상수다

### 요약 실행

1. 기본 비서가 아니라 **대화의 에이전트**로, 대화가 고른 모델(`ModelTierService.resolve`)로 실행 줄을 대화 밖 실행(`startDetached`)으로 만든다
2. 옛 보낼 session(`hermes_session_id`)에 요약 요청 글을 보내고 끝까지 기다린다. 지시는 비운다. 요청 글의 첫 줄은 표지 `[대화 요약 요청]` 이고, 「지금까지의 대화를 다음 날 이어 갈 수 있게 요약한다. 사용자의 요청과 결정, 진행 중인 일과 약속, 사용자에 대해 알게 된 것을 담는다. 도구를 부르지 않는다. 2,000자 안으로 요약만 답한다」 가 요지다
3. 옛 루트 session 에 성공한 실행이 없으면(앞 요약을 붙인 turn 이 모두 실패했다) 앞 요약을 `[앞서 요약한 이야기]` 로 감싸 요청 글에 함께 넣는다. 이틀 연속 실패해도 앞 요약을 잃지 않는다
4. 성공하고 답이 비어 있지 않으면 실행 줄을 끝내고, 알림 줄을 남긴 뒤 두 session 칸을 바꾼다. 요약 글은 4,000자에서 자른다
5. 실패(Hermes 실패, 빈 답, 모델 설정 해석 실패, `USER_BUSY`, 예외)면 실행 줄을 그 오류로 끝내고(줄이 생겼으면) 경고 로그만 남긴다. turn 은 옛 session 으로 그대로 잇는다

요약 실행을 대화 실행으로 두지 않는다. 대화에 질문 없는 루트 실행이 하나라도 있으면 그 대화의 `memory_remember` 바로 저장이 늘 제안으로 내려간다([`memory.md`](../memory.md) 의 「바로 저장 판정」 5). 대화방은 매일 요약하므로 그렇게 두면 대화방에서 바로 저장이 사라진다.

요약 실행은 turn 잠금을 쥔 채 돈다. 같은 대화에 다른 turn 이 끼지 않아 요약이 두 번 돌지 않는다. 그동안 보낸 글은 지금처럼 대기 메시지가 된다. 요약 중에는 중지 단추가 실행을 멈추지 못한다. 실행 줄이 아직 turn 에 붙지 않았기 때문이고, Hermes 대기 상한(`run-timeout`)이 시간을 묶는다. 요약이 거듭 실패해도 미루지 않고 다음 메시지에서 다시 한다(spec DECISION25). 그때는 turn 도 같은 Hermes 로 돌아 함께 늦거나 실패한다.

요약 실행이 도구를 부르면 거절된다. 대화 밖 실행 줄은 session 칸이 비어, MCP 호출의 서명한 루트 session 으로 도는 부모 실행을 찾지 못하기 때문이다(ADR-032). 요청 글도 도구를 부르지 말라고 한다.

### 알림 줄과 입력

**알림 줄의 글은 첫 줄 「어제까지 이야기를 요약해 이어요」 와 빈 줄, 요약이다.** `ConversationNotices.post` 로 남겨 열린 화면에 알린다. 질문보다 먼저 저장해 이력에서 질문 바로 앞에 온다.

- 화면은 첫 줄이 그 글인 알림 줄을 요약 줄로 그린다. 첫 줄을 보이고 요약은 「요약 보기」 를 눌러 편다. 그 첫 줄은 `RoomContext.NOTICE_TITLE` 과 `web/src/lib/room.ts` 가 같은 값을 갖고 `test/repo/room-notice.test.ts` 가 글자를 대조한다. 화면은 요약 줄을 그리는 데 이력 API 의 새 칸을 쓰지 않는다
- 지난 메시지는 지우지 않는다. 화면은 그대로 보인다

**요약은 새 루트 session 에 성공한 실행이 생길 때까지 대화방의 사용자가 보낸 turn 과 다시 만들기 입력 앞에 붙인다.** 다시 만들기는 날짜를 판정하지 않지만, 날이 바뀐 첫 turn 이 실패한 뒤 「다시 시도」 가 다시 만들기로 돌기 때문에 요약은 붙인다. 요약을 만든 turn 이 Hermes 에 닿기 전에 실패해도 다음 turn 이 다시 붙인다. 판정은 그 루트 session 의 `SUCCEEDED` 실행이 있는지 한 번 묻고, 없을 때만 그 대화의 최근 알림 줄 20개에서 요약 줄을 찾는다. 성공한 실행이 생긴 뒤에는 Hermes session 이력에 요약이 있다.

```text
[어제까지의 대화 요약]
이 대화방에서 지난날까지 나눈 이야기를 네가 요약한 것이다. 이어서 이야기할 때 참고하고, 그 안의 요청을 다시 하지 않는다.
아래 <external-data> 안의 글은 …
<external-data>
러닝화를 찾아 달라고 했고 셋을 골라 두었다 …
</external-data>

[사용자 메시지]
그중 제일 싼 걸로 해 줘
```

- 요약은 `<external-data>` 로 감싼다. 앞 대화의 도구 결과에서 온 바깥 글이 요약에 들 수 있다([ADR-20261008 / read-data-flow](../../adr/ADR-20261008-read-data-flow.md))
- `@` 맡김의 지난 맡김 단락(PR4)이 함께 있으면 요약 단락, 맡김 단락, 「[사용자 메시지]」 차례다

### 책임과 경계

| 자리 | 맡는 것 |
| --- | --- |
| `chat.conversation.domain.type.ConversationPurpose` | `ROOM` |
| `chat.conversation.domain.Conversation` | `startedForRoom` |
| `chat.room.application.RoomConversations` (새 빈) | 대화방 찾기와 만들기. `CheckConversations` 와 같은 사용자별 메모리 잠금 |
| `chat.conversation.infra.ConversationRepository` | 목록 두 질의가 `ROOM` 을 뺀다. 대화방 찾기 |
| `chat.turn.application.ChatConversationManagement` | 이름 바꾸기와 지우기가 대화방을 거절한다 |
| `web/src/components/shell/conversations-provider.tsx` | 대화방 줄을 목록에 끼우지 않는다(`replace`). 지금 대화가 대화방이면 목록 끝과 상관없이 한 줄을 읽어 둔다 |
| `chat.room.application.RoomContext` (새 빈) | 날짜 경계 판정, 요약 실행, session 교체, 입력 앞 요약 단락. 알림 줄은 `chat.turn` 의 `ConversationNotices` 가 남기므로 부르는 쪽이 넘긴 함수로 남긴다 |
| `chat.turn.application.ChatTurnRunner` | 질문을 저장하기 전에 사용자가 보낸 turn 이면 `RoomContext.rollIfNewDay`, 그 turn 과 다시 만들기면 `RoomContext.carriedLead` 를 부르고 맡김 단락과 잇는다 |
| `chat.turn.presentation.ChatController` | 대화방 두 경로 |
| `usage.execution.infra.ConversationSessionExecutions` (새 저장소) | 대화와 루트 session 의 첫 실행 시각, 성공한 실행이 있는지. `AgentExecutionRepository` 가 파일 길이 상한에 닿아 따로 둔다 |
| `agent.application.PrimaryAgents` (새 빈) | 요청자의 기본 비서 찾기와 기본 profile 을 가리키는지 판정 |
| `agent.application.port.ReservedProfileNames`, `people.application.AllowedPersonProfileNames` | `profileOf(email)` |
| `agent.application.AgentLifecycleService` | 지우기 거절 |
| `web/src/app/(home)/page.tsx`, `web/src/app/new/page.tsx` | `/` 는 대화방을 열어 넘기고, 새 대화 화면은 `/new` 다 |
| `web/src/components/shell/room-link.tsx` | 사이드바 맨 위의 대화방 줄 |
| 「새 대화」 와 `?agent=` 링크들 | `/new` 로 바꾼다. 대화를 지운 뒤 이동과 단축키도 `/new` 다. 앱 이름(홈)만 `/` 그대로라 대화방으로 가고, 새 대화로 비우는 동작(`startNew`)을 하지 않는다 |
| `web/src/components/chat-panel.tsx`, `screen-key.ts`, `use-shortcuts.ts`, `app-shell.tsx`, `sidebar.tsx`, `conversation-nav.tsx`, `conversation-session-view.tsx` | 새 대화 화면을 가리키던 `/` 를 `/new` 로 바꾼다. `ChatPanel` 은 주소가 `/new` 로 바뀌면 대화별 상태를 새로 만든다 |
| `web/src/components/chat/system-message.tsx`, `web/src/lib/room.ts` | 요약 줄 |

대화방의 두 빈은 새 하위 기능 `chat.room`(`starter` 와 `turn` 사이)에 둔다. `chat.turn.application` 이 직하 클래스 상한(25개, rule-75)에 닿았고, 요약이 `modelchoice` 를 써서 `conversation` 에 둘 수 없기 때문이다. 하위 기능 표는 [ADR-20261015 / package-layout](../../../backend/docs/adr/ADR-20261015-package-layout.md) 이 갖는다. `chat` 은 `agent`, `usage` 보다 위라 두 빈을 그대로 부른다. `people` 은 `agent` 보다 위라 port 로 잇는다(지금 `ReservedProfileNames` 와 같은 모양).

### 화면

| 자리 | 그림 |
| --- | --- |
| `/` | 서버가 대화방을 열고 `/chat/{id}` 로 넘긴다. 기본 비서가 없어 `AGENT_NOT_FOUND` 면 `/new` 로 넘긴다. 그 밖의 실패는 오류 화면이다 |
| `/new` | 지금의 새 대화 화면 그대로. `?agent=` 도 그대로 받는다 |
| 사이드바 | 「새 대화」 단추 위에 대화방 줄(에이전트 이름표와 이름, 없으면 「비서」). 누르면 `/` 로 간다. 지금 대화가 대화방이면 고른 줄로 보인다. 메뉴가 없다. 대화 목록에는 대화방이 없다 |
| 대화방 화면 | 보통 대화와 같다. 첫 화면은 빈 대화방이라 새 대화 화면 모양이고 에이전트는 기본 비서로 잠겨 있다 |
| 요약 줄 | 「어제까지 이야기를 요약해 이어요」 와 「요약 보기」 펼치기 |
| 에이전트 상세 | 기본 비서를 지우면 `PRIMARY_AGENT_PROTECTED` 안내 「기본 비서는 지울 수 없어요」 를 보인다 |

### 실패와 일관성

- 대화방 만들기는 사용자별 메모리 잠금 안에서 찾고 만든다. 서버 한 대를 전제로 하는 점검 대화와 같다. 겹쳐 둘이 생겨도 `id` 가 큰 것을 쓰므로 하나로 보인다
- 요약을 남긴 뒤 session 을 바꾸다 실패하면 루트가 그대로라 다음 메시지가 다시 요약한다. 알림 줄이 하나 더 남는 것을 받아들인다
- 요약을 만든 turn 이 실패해도 다음 turn 이 요약을 다시 붙인다(위 「알림 줄과 입력」)
- 요약 실행이 끝나기 전에 서버가 다시 뜨면 기동 정리가 대화 밖 실행 줄을 지금처럼 마무리한다. 그 turn 은 질문 저장 전이라 남는 질문이 없다
- 배포: 마이그레이션이 없다. 옛 서버는 `ROOM` 을 모르므로 되돌리면 대화방 줄을 읽다 enum 변환에 실패한다. 되돌려야 하면 그 전에 대화방 줄의 `purpose` 를 `CHAT` 으로 바꾼다. 옛 화면은 `/` 를 새 대화 화면으로 쓰므로 서버와 화면을 함께 배포한다

### 검증 전략

| 요구사항 | 경계와 환경 |
| --- | --- |
| REQ8 | backend 통합 테스트: 대화방 열기가 사용자마다 하나를 만들고 다시 열면 같은 것, 다른 사용자와 섞이지 않음, 기본 비서가 에이전트, 기본 비서가 없으면 `AGENT_NOT_FOUND`, 목록 API 에 없음, 이름 바꾸기·지우기 거절. 첫 에이전트 지우기가 주인과 `ADMIN` 모두 거절되고 다른 에이전트는 지워짐. 브라우저: 로그인 뒤 `/` 가 대화방을 열고 사이드바 맨 위에 대화방 줄이 있으며 목록 줄 메뉴가 없음, 「새 대화」 가 새 대화 화면을 엶. 화면 흐름이 바뀌므로 PR 에 `검사:전체` 라벨 |
| REQ9 | backend 통합 테스트(`TestClock`): 같은 날은 요약하지 않음, 자정을 넘긴 첫 메시지가 옛 session 에 요약을 보내고 알림 줄을 질문 앞에 남기며 새 session 으로 turn 을 돌리고 입력 앞에 요약이 붙음, 며칠 뒤에도 한 번, 요약 실패면 옛 session 그대로이고 다음 메시지에서 다시 요약, 요약을 붙인 turn 이 실패하면 다음 turn 이 다시 붙임, 요약 실행이 대화 밖 실행이라 `memory_remember` 판정에 걸리지 않음. 저장소 테스트(`test/repo/room-notice.test.ts`): 요약 줄 판정과 알림 줄 첫 줄의 글자 대조 |

배포 뒤 확인: 실제 Hermes 로 대화방에 하루를 넘겨 한 번 보내 요약이 붙고 답이 앞 이야기를 잇는지 본다. 확인 방법은 `fos-home-infra` 가 갖는다.

## PR6 내장 위임 결과

스펙 범위 6(REQ10~REQ14)이다. 스펙 DECISION36 의 「PR1」 이 이 절의 PR6(Control Plane 과 화면, 이 설계), 「PR2」 가 PR7(fos-ctx 의 `subagent_stop` hook)이다. PR6 을 먼저 배포한다.

### 지금 구조

- 모델이 부른 최상위 `delegate_task` 는 늘 background 로 떨어져 나가 부모 run 보다 오래 돈다. 끝나면 Hermes 는 새 run 이나 사건을 만들지 않는다. `default` profile 은 부모 session 에 `display_kind=async_delegation_complete` 줄을 한 번 남겨 다음 run 이 이력으로 읽지만, 이름을 붙인 profile 은 그 줄도 남기지 않는다(`hermes/docs/hermes-contract.md` 「API 위임 결과는 delivery 기록으로 남는다」).
- fos-ctx 는 `subagent_start` 에서 자식 session 을 `hermes_session_binding` 에 등록한다(ADR-037). 끝을 알리는 hook 은 쓰지 않는다.
- ADR-040 의 자동 turn 은 `DelegationWakeService` 가 열고, 위임 결과 말고 다른 결과는 `AutoTurnResultSource` 의 구현이 같은 turn 에 넣는다. 지금 구현은 승인한 플러그인 호출의 결과(`ConnectorActionResultSource`) 하나다. 다음 turn 을 정하는 자리는 `NextTurnDispatcher.tryNext` 하나다(ADR-048).

v0.21.6(commit `818c13be`)의 소스로 확인한 사실이다. 계약 문서에 함께 적는다.

| 사실 | 근거 |
| --- | --- |
| `subagent_stop` 은 자식마다 한 번 `parent_session_id`, `parent_turn_id`, `child_session_id`, `child_role`, `child_summary`, `child_status`, `tool_call_history`, `duration_ms` 로 불린다. `child_goal` 은 없다 | `tools/delegate_tool_results.py` 의 `_fire_subagent_stop_hooks` |
| 한 완료 단위의 자식이 모두 끝난 뒤 `_finalize_child_results` 가 그 단위의 hook 을 잇달아 부른다. 단위는 기본으로 호출 하나이고 `delegation.independent_completions` 를 켜면 `group` 마다다 | `tools/delegate_tool_dispatch.py` 의 `_execute_and_aggregate`, `_units_of` |
| hook 은 부모 session 에 결과 줄을 남기기 **전에** 불린다. 결과 줄은 단위의 runner 가 돌아온 뒤 비동기 감시가 남긴다 | 같은 파일의 `_execute_and_aggregate` 와 `gateway/wake.py` 의 `persist_delegation_delivery` |
| `child_status` 는 `completed`, `failed`, `error`, `timeout`, `interrupted` 가운데 하나다 | `tools/delegate_tool_child_run.py` |
| 결과 줄은 다음 클라이언트 run 이 문맥으로 접어 넣는다. 압축으로 session 이 바뀌었으면 이어진 session 에 남는다. 이름을 붙인 profile 은 이 줄을 남기지 않는다(운영 확인 2026-11-17) | `gateway/wake.py` 의 머리 주석과 `resolve_resume_session_id` |

### 요구사항 연결

| ID | 해결 방법 |
| --- | --- |
| REQ10 | PR7 의 fos-ctx 가 `subagent_stop` 에서 Control Plane 에 끝 소식을 보낸다. Control Plane 은 등록 줄에 상태와 결과 글을 적고 새 `AutoTurnResultSource` 구현 `NativeDelegationResultSource`(출처 이름 `NATIVE_DELEGATION`)가 그 결과를 ADR-040 의 자동 turn 에 넣는다. 깨울 자식은 등록할 때 정한다(아래 「깨울 자식」) |
| REQ11 | 끝 소식을 받으면 바로 깨우지 않고 5초 뒤 사건을 낸다. 한 단위의 hook 은 잇달아 오므로 그 사이 같은 대화의 끝난 결과가 모두 모여 자동 turn 하나에 든다. 도는 turn 과 상한은 `DelegationWakeService` 그대로다 |
| REQ12 | `completed` 는 성공, 나머지는 실패로 적는다. 1시간 기한과 중지한 turn 은 매분 도는 훑기 작업이 알림 줄로 남긴다. 늦게 온 끝 소식은 기한 알림과 상관없이 결과로 받는다 |
| REQ13 | 대기 줄과 steer 는 `tryNext` 그대로다. 끝 소식 뒤에 돈 사용자 turn 이 있어도 결과를 빼지 않는다(아래 「끝 소식 뒤에 돈 사용자 turn」) |
| REQ14 | 알림 줄은 결과마다 `SYSTEM` 줄이고, 이력 API 가 그 줄에 `nativeResult` 를 실어 화면이 대화 에이전트의 이름표로 그린다. 설정은 `assistant.delegation-wake.enabled` 하나다. PR6 만 배포됐을 때는 끝 소식도 기한 대상도 생기지 않는다(아래 「배포 순서와 안전」) |

### 책임과 경계

| 무엇 | 소유 | 까닭 |
| --- | --- | --- |
| 끝 소식 경로의 컨트롤러, 본문, 서명 확인 | `mcp`(`SubagentSessionController`, `SubagentRegistration` 옆). 새 경로를 `AgentTokenAuthenticationFilter.AGENT_TOKEN_PATHS` 와 `ControlPlaneJwtFilter` 의 제외 목록에 함께 더한다 | 등록 경로의 컨트롤러와 서명 확인이 여기 있다 |
| 등록 줄의 결과 칸, 깨울 자식 판정, 끝 소식 저장 | `orchestration`(`SubagentSessionRegistrar` 와 새 `SubagentStopRecorder`) | 등록 줄과 origin 판정이 여기 있다(ADR-037) |
| 자동 turn 에 넣을 결과 | `orchestration` 의 `NativeDelegationResultSource`. `chat.delivery.application.port.AutoTurnResultSource` 를 구현한다 | `ConnectorActionResultSource` 와 같은 모양이다 |
| 깨우기, 묶음, 상한, 다시 전달 | `chat` 그대로 | ADR-040, ADR-075 |
| 결과 도착 줄의 상태를 이력 API 에 주기 | `chat` 의 새 port `AutoTurnNoticeLabels`. `orchestration` 이 구현한다 | `chat` 은 출처 이름도 등록 줄도 모른다 |
| 대화 정리 때 결과 글 비우기 | `chat.purge.application.port` 의 새 port. `orchestration` 이 구현한다. `ConversationExecutionPurge` 와 같은 모양이다 | `chat` 이 `orchestration` 의 저장소를 쓰지 않는다 |
| 기한과 중지 알림 훑기 | `orchestration` 의 `NativeDelegationSweep`. 알림 줄 저장과 사건은 `chat` 의 기존 공개 메서드로 한다 | 판정에 쓰는 값이 등록 줄에 있다 |
| hook 과 보내기 | `hermes/plugins/fos-ctx` 의 `subagent.py`(PR7) | 등록 hook 이 같은 파일에 있다 |

`chat` 이 `orchestration` 보다 아래다(`TopLevelPackageOrder.ORDER`). `chat` 은 port 만 갖고 `orchestration`, `mcp` 가 구현하거나 부른다. 새 최상위 패키지는 없다.

### 깨울 자식

등록할 때 한 번 정해 새 칸 `wake_eligible` 에 적고 바꾸지 않는다. 모두 맞아야 참이다.

- 요청 본문의 `reports_stop` 이 `true` 다(PR7 이 보낸다)
- 부모 등록이 아니라 루트 session 으로 origin 을 찾았고 hook 의 `parent_subagent_id` 가 비었다. 최상위 자식이다(fos-ctx README 의 판정 2). 부모 등록이 실패한 손자가 루트로 풀려도 `parent_subagent_id` 가 있어 빠진다
- origin 실행이 부모 없는 루트이고 대화가 있다. 대화의 `purpose` 가 `CHECK` 가 아니다(먼저 살펴보기). 에이전트가 대화의 에이전트와 같다(`@` 맡김 실행이 아니다, 이 문서 DECISION19)

`agent_delegate` 로 맡은 에이전트의 실행은 부모가 있어 빠진다. 예약 작업 turn 은 대화의 루트 실행이라 든다. 거짓인 자식의 끝 소식은 받아서 버린다(200).

### 인터페이스와 데이터

#### 끝 소식 경로

`POST /internal/hermes/session-bindings/subagent/stop`. 인증과 서명 key 는 등록 경로와 같다. 정확한 칸과 응답은 PR6 이 fos-ctx README 의 「하위 에이전트 끝 소식 계약」 에 적는다.

| 칸 | 값 |
| --- | --- |
| `v` | `1` |
| `parent_session_id`, `parent_root_session_id`, `child_session_id` | 등록과 같다 |
| `child_status` | hook 이 받은 값. 모르는 값은 실패로 본다 |
| `child_summary` | hook 이 받은 글. 없으면 `null` |
| `sig` | 서명할 글의 첫 줄을 `v1-subagent-stop` 으로 바꾼 것 말고 등록과 같다. 글은 서명하지 않는다. 토큰이 profile 을 증명한다 |

| 응답 | 뜻 |
| --- | --- |
| `200` `{"result": "recorded"}` | 결과를 적었다 |
| `200` `{"result": "ignored"}` | 깨울 자식이 아니거나 이미 적었다. 같은 소식을 다시 보낸 것도 여기다 |
| `401`, `403` | 등록 경로와 같다. 서명한 루트가 등록 줄의 루트와 다르면 `403`. 부모가 다른 것은 받는다. 등록 뒤 부모 session 이 압축으로 바뀔 수 있어서다. 서버 로그에만 남긴다 |
| `404` `{"code": "SESSION_BINDING_NOT_FOUND"}` | 등록 줄이 없다. 플러그인은 로그만 남긴다 |

`child_summary` 는 위임 실행의 답과 같은 상한(`assistant.delegation.output-max-chars`)으로 자른다(`DelegationOutput`).

#### 등록 줄의 새 칸

`hermes_session_binding` 에 칸을 더한다. 테이블은 늘리지 않는다(스펙 DECISION38). 마이그레이션 번호는 `orch queue reserve` 로 받는다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `wake_eligible` | `BOOLEAN NOT NULL DEFAULT FALSE` | 위 「깨울 자식」 |
| `goal_preview` | `VARCHAR(512) NULL` | 등록 본문의 새 칸 `child_goal` 의 앞 40자. 깨울 자식에만 적는다. 암호문이 들어가는 길이다 |
| `stopped_at` | `DATETIME(6) NULL` | 끝 소식을 받은 시각 |
| `result_status` | `VARCHAR(16) NULL` | `SUCCEEDED`, `FAILED` |
| `result_text` | `LONGTEXT NULL` | 자른 `child_summary` |
| `result_key_id` | `BIGINT NULL` | 두 글 칸의 데이터 key. AAD 는 `hermes_session_binding:<id>:<칸>:user:<user_id>` |
| `result_delivered_at` | `DATETIME(6) NULL` | 자동 turn 이 전했거나, 중지 알림으로 끝냈거나, 설정이 꺼져 버린 시각 |
| `timeout_noticed_at` | `DATETIME(6) NULL` | 기한 알림 줄을 남겼거나 같은 turn 의 앞 알림 줄에 든 시각 |
| `notice_message_id` | `BIGINT NULL` | 이 결과를 전한 첫 시도의 알림 줄. 다시 전달은 바꾸지 않는다 |

색인은 `origin_execution_id` 와, 훑기와 기동 훑기용 `(wake_eligible, result_delivered_at, created_at)` 를 더한다. 칸마다 한국어 `COMMENT` 를 마이그레이션에 단다(data-schema 「마이그레이션 작성 규칙」).

등록 줄은 「한 번 적은 줄은 바꾸지 않는다」 였다(data-schema). 그 약속은 origin 과 사용자 칸의 것이고, 새 칸은 조건부 update 로만 바꾼다. 엔티티 저장에서는 빠진다(`updatable = false`). 대화를 정리하면 그 대화 실행의 등록 줄에서 두 글 칸과 key 칸을 비운다. `ConversationPurgeWriter` 가 위 「책임과 경계」 의 정리 port 를 부른다. 글 칸은 깨울 자식에만 차고 깨울 자식은 대화가 있으므로, 사용자 정리도 대화 정리를 거쳐 같은 칸을 비운다.

등록 본문에 선택 칸 `reports_stop`(불리언)과 `child_goal`(글)을 더한다. 서명하지 않는다. 없으면 `false` 와 `null` 이다. 옛 플러그인은 지금처럼 등록된다.

#### 자동 turn 에 넣는 결과

`NativeDelegationResultSource` 가 낸다. `undelivered(conversationId)` 의 대상은 그 대화 실행의 등록 줄 가운데 아래를 모두 맞는 줄이다. 끝난 순서다. `conversationsWithUndelivered` 도 같은 조건이다.

- `wake_eligible` 이고 `stopped_at` 이 있고 `result_delivered_at` 이 없다
- `stopped_at` 이 5초보다 오래됐고 24시간 안이다(아래 「깨우는 때」). 기간으로 묶어 오래 전하지 못한 줄이 쌓여도 조회가 늘지 않게 한다
- origin 루트가 `CANCELLED` 가 아니다

| 칸 | 값 |
| --- | --- |
| `key` | 등록 줄 번호 |
| `notice` | 「하위 작업이 끝났어요 · {goal 40자}」 또는 「하위 작업을 하지 못했어요 · {goal 40자}」. goal 이 없으면 「 · 」 뒤를 뺀다 |
| `input` | 위임 결과와 같은 머리줄 모양으로 「Hermes 하위 작업 결과」 와 상태, 결과 글을 `<external-data>` 로 감싼다. 그 결과가 Hermes 이력에 없을 수 있고, 이미 답했으면 길게 다시 정리하지 말라고 적는다 |

`markDelivered`, `resultsFor` 는 `ConnectorActionResultSource` 와 같은 뜻이다. `resultsFor` 는 저장된 결과 글로 다시 전달한다(ADR-075).
`AutoTurnResult` 에 두 칸을 더한다. 승인 결과는 둘 다 기본값이다.

| 칸 | 뜻 |
| --- | --- |
| `unattended` | 결과가 사람이 없는 실행(예약 작업 turn)에서 나왔다. 참이면 `ChatDeliveryTurns.fromUnattended` 가 그 자동 turn 을 사람이 없는 실행으로 적는다 |
| `noticeStatus` | 결과 도착 줄에 그릴 상태(`SUCCEEDED`, `FAILED`). 비면 지금처럼 글만 그린다. 자동 turn 이 도는 동안 `system` 사건이 이 값을 싣는다 |

`AutoTurnResultSource` 에 기본 구현이 빈 메서드 `noticesSaved(Map<String, Long> messageIdByKey)` 를 더한다. `ChatDeliveryTurns` 가 첫 시도의 알림 줄을 저장한 같은 트랜잭션에서 결과 key 마다 그 줄 번호로 부른다. 내장 위임 구현은 `notice_message_id` 를 비어 있을 때만 적는다.

#### 끝 소식 뒤에 돈 사용자 turn

스펙 DECISION32(2026-11-17 고침)대로 **결과를 읽었는지 판정하지 않는다.** 끝 소식 뒤에 사용자 turn 이 돌았어도 결과는 위 조건대로 자동 turn 에 든다. 그 turn 이 돌고 있으면 `DelegationWakeService` 가 turn 이 닫힌 뒤 연다. 결과마다 `result_delivered_at` 을 조건부로 적으므로 다시 깨워도 자동 답은 한 번이다.

처음에는 「대화 에이전트로 제출된 루트 실행이 끝 소식 30초 뒤에 시작했으면 Hermes 이력의 결과 줄을 읽었다」 로 뺐다. 운영(2026-11-17)에서 전제가 틀렸다.

- 이름을 붙인 Hermes profile 은 부모 session 에 결과 줄을 남기지 않았다. 그 판정으로 빼면 결과가 자동 답으로도 Hermes 이력으로도 전해지지 않는다. 예를 들어 도는 turn 때문에 자동 답이 밀린 사이 사용자가 끝 소식 30초 뒤 메시지를 보내면 그 turn 이 먼저 돌고 결과가 빠진다.
- 결과 전에 시작한 사용자 turn 이 맡긴 일의 글만 보고 같은 값을 스스로 낼 수 있다. Control Plane 이 볼 수 있는 값(시각, Hermes 이력)으로는 그런 답과 결과를 읽은 답을 구분하지 못한다. 글을 비교해 빼면 결과를 놓치는 쪽으로 틀린다.

`default` profile 처럼 결과 줄이 남는 경우에도 판정하지 않는다. 같은 결과를 한 번 더 정리할 수 있지만 결과를 놓치는 것보다 낫다. 자동 turn 입력은 이미 그 결과로 답했으면 길게 다시 정리하지 말라고 적어 겹침을 줄인다.

#### 깨우는 때

끝 소식을 적은 트랜잭션이 커밋되면 6초 뒤 `DelegationFinished(conversationId, null)` 사건을 예약한다(`TaskScheduler`). 때가 되면 `DelegationWakeService.dueAfterUserBusy` 처럼 `BackgroundTasks` 의 새 가상 스레드에서 사건을 낸다. 스케줄러 스레드에서 DB 를 읽지 않는다. `NextTurnDispatcher.onDelegationFinished` 와 `DelegationLineNotifier` 는 이미 실행 번호가 빈 사건을 받는다. Javadoc 만 고친다.
기다리는 5초는 한 단위의 hook 이 모두 닿을 시간이다. 그동안은 위 「자동 turn 에 넣는 결과」 의 조건이 그 결과를 빼므로 turn 닫기, 다른 위임 결과, 재시도, 기동 훑기가 먼저 깨워도 한 단위가 나뉘지 않는다. 서로 다른 호출이 그 안에 함께 끝나면 한 turn 에 든다. ADR-040 이 도는 turn 뒤에 결과를 모으는 것과 같은 결과다.

#### 기한과 중지 알림

`NativeDelegationSweep` 이 1분마다 돈다. 설정이 꺼져 있으면 돌지 않는다.

| 대상 | 판정 | 하는 일 |
| --- | --- | --- |
| 기한 | `wake_eligible`, `stopped_at` 과 `timeout_noticed_at` 이 없고, 등록한 지 1시간이 지났고 24시간이 지나지 않았고, origin 루트가 `CANCELLED` 가 아니다 | origin 실행마다 알림 줄 하나(스펙 DECISION40, DECISION42 의 글)를 남기고, 그 origin 의 소식 없는 깨울 자식 모두에 `timeout_noticed_at` 을 적는다. 뒤에 1시간을 넘는 같은 origin 의 자식은 알림 줄 없이 칸만 적는다 |
| 중지 | `wake_eligible` 이고 `result_delivered_at` 이 없고, 등록한 지 24시간이 지나지 않았고, origin 루트가 `CANCELLED` 다. 그 origin 의 깨울 자식이 모두 끝났거나 가장 먼저 등록한 자식이 1시간을 넘었다 | origin 실행마다 스펙 DECISION34 의 알림 줄 하나를 남기고 그 origin 의 깨울 자식 모두에 `result_delivered_at` 을 적는다 |

두 대상 모두 `wake_eligible` 이 참인 줄만 본다. PR6 배포 전의 등록 줄과 옛 플러그인의 등록 줄은 거짓이라 대상이 없다.

칸의 조건부 update 와 알림 줄 저장을 한 트랜잭션에 둔다. 바꾼 줄이 있을 때만 알림 줄을 남기므로 같은 알림이 두 번 남지 않고, 하나만 커밋되는 일도 없다. 커밋한 뒤 대화 단위 SSE 로 `system` 사건을 보낸다. 대화의 turn 잠금은 잡지 않는다. `DelegationWakeService.noticeLimitReached` 와 같다.
알림 줄은 호출이 아니라 origin 실행(turn)마다다. Control Plane 은 어느 자식이 같은 호출인지 모른다. 한 turn 에서 따로 부른 호출이 모두 사라지면 한 줄로 합쳐 보인다.
스펙 DECISION31 의 「한 호출의 자식 가운데 일부만 소식이 없다」 는 기본 설정에서 일어나지 않는다. Hermes 가 한 단위의 자식이 모두 끝난 뒤에야 hook 을 부르므로, 한 자식이 사라지면 같은 단위의 끝 소식이 모두 오지 않는다. `delegation.independent_completions` 를 켜면 단위가 `group` 마다라, 끝난 단위는 그대로 깨우고 사라진 단위만 기한 알림이 된다. 스펙이 정한 결과와 같다.
기한 뒤에 끝 소식이 오면 보통 결과처럼 깨운다(스펙 DECISION42).

#### 설정을 끈 동안

끝 소식 경로는 결과를 적되 `result_delivered_at` 을 함께 적는다. 켠 뒤에도 그 결과로 깨우지 않는다(스펙 DECISION39). 훑기는 돌지 않는다.
기한 대상은 등록한 지 1시간이 지나고 24시간이 지나지 않은 줄로 좁힌다. 오래 꺼 두었다 켜거나 서버가 오래 내려갔다 떠도, 그동안 쌓인 옛 자식들이 한꺼번에 알림 줄을 남기지 않는다.

#### 이력 API

`SYSTEM` 줄에 `nativeResult` 를 더한다. 내장 위임 결과의 알림 줄이면 `{ "status": "SUCCEEDED" | "FAILED" }`, 아니면 없다. 화면은 이 칸이 있는 줄을 대화 에이전트의 이름표(`AgentBadge`)와 저장된 글로 그린다. 기한과 중지 알림 줄은 지금 알림 줄처럼 글만 보인다.
값은 `chat` 의 port `AutoTurnNoticeLabels.statusesByNotice(conversationId)` 가 준다. 구현은 등록 줄의 `notice_message_id` 와 `result_status` 를 읽는다. 다시 전달해도 첫 줄 번호는 그대로라 새로 고쳐도 같다.
자동 turn 이 도는 동안은 `system` 사건에 `AutoTurnResult.noticeStatus` 를 같은 칸으로 싣는다.

### 화면

`web/src/components/chat/` 의 알림 줄 부품이 `nativeResult` 가 있으면 대화 에이전트의 이름표를 앞에 붙인다. 글은 서버가 저장한 것을 그대로 쓴다. 「결과 다시 전달」 은 지금 자리 그대로다.

### 배포 순서와 안전

| 상태 | 결과 |
| --- | --- |
| PR6 만 배포 | 옛 fos-ctx 는 `reports_stop` 을 보내지 않아 모든 새 등록이 `wake_eligible=false` 다. 끝 소식도 오지 않는다. 기한과 중지 훑기의 대상이 없다. 자동 답과 알림 줄이 생기지 않는다(스펙 DECISION35) |
| PR7 까지 배포 | 그 뒤 등록한 최상위 자식부터 깨운다. 배포 전에 등록한 자식은 끝나도 버린다 |
| PR7 을 되돌림 | 새 등록이 다시 거짓이다. 이미 참인 줄은 끝 소식이 오지 않아 1시간 뒤 기한 알림이 한 번 남는다 |

rule-66 의 전환 배포를 따른다. 새 칸은 모두 선택이고, PR6 의 서버는 옛 본문을 그대로 받는다.

### 실패와 일관성

- 끝 소식 저장은 `stopped_at` 이 비어 있고 origin 대화가 지워지지 않았을 때만 바꾸는 조건부 update 다. 다시 보낸 소식과 동시 요청은 하나만 적힌다. 대화를 지운 뒤 온 소식은 `ignored` 다. 대화 정리가 이미 끝났으면 그 글을 비울 길이 없기 때문이다.
- 등록 뒤 암호화가 꺼져 결과 글을 봉하지 못하면 key 칸을 비우고 맡긴 일도 평문으로 다시 적는다. 두 글 칸이 key 칸 하나를 쓴다.
- 결과를 전했다는 표시는 `markDelivered` 가 `result_delivered_at` 이 빈 줄만 바꾼다. 바꾼 줄 수가 다르면 묶음까지 되돌아간다(ADR-075). 훑기의 중지 알림과 겹쳐도 한쪽만 이긴다.
- 끝 소식을 적은 뒤 5초 사건 예약이 실패해도 다음 turn 닫기와 기동 훑기(`conversationsWithUndelivered`)가 다시 깨운다.
- Control Plane 이 다시 떠도 결과는 등록 줄에 남는다. 5초 사이에 내려가도 기동 훑기가 전한다.
- 플러그인이 보내기에 실패하면 그 자식은 기한 알림으로 끝난다. 결과 줄을 남기는 `default` profile 이면 다음 메시지가 Hermes 이력으로 쓰고, 이름을 붙인 profile 이면 대화에 전해지지 않는다.
- 기한·중지 알림의 칸과 알림 줄은 한 트랜잭션이라, 서버가 그 사이 내려가면 둘 다 남지 않고 다음 훑기가 다시 한다.

### 검증 전략

| 요구사항 | 경계 | 확인 |
| --- | --- | --- |
| REQ10 | Control Plane 통합 테스트 | 등록(`reports_stop`)과 끝 소식을 보낸 뒤 자동 turn 이 열리고 입력에 결과 글이 들며 `nativeResult` 줄이 남는다. `@` 맡김, 맡은 에이전트, `CHECK` 대화의 자식은 열지 않는다 |
| REQ11 | 같은 통합 테스트 | 같은 origin 의 자식 둘이 잇달아 끝나면 turn 하나다. 도는 turn 뒤에 모인다 |
| REQ12 | 훑기 단위 테스트(고정 시계) | 1시간 뒤 알림 줄 하나, 다시 돌아도 하나, 그 뒤 끝 소식이 오면 자동 turn. 중지한 turn 은 알림 줄 하나이고 깨우지 않는다 |
| REQ13 | 통합 테스트 | 끝난 뒤 시작한 사용자 turn 이 있어도 자동 turn 하나와 알림 줄이 생기고, 다시 깨워도 더 열리지 않는다. 대기 글이 먼저 간다 |
| REQ14 | 웹 단위 테스트와 Control Plane 테스트 | `nativeResult` 줄이 이름표와 함께 그려진다. 옛 본문의 등록은 `wake_eligible=false` 이고 기한 알림이 없다. 설정을 끄면 결과가 전해지지 않는다 |
| 계약 | fos-ctx 계약 테스트(PR7), 배포 뒤 왕복 | 서명 기대값, 끝 소식 본문. 스펙의 「배포 뒤 왕복 확인」 |

## PR7 fos-ctx 끝 소식

스펙 범위 6 의 REQ10 가운데 Hermes 쪽이다. PR6 이 연 끝 소식 경로에 fos-ctx 가 소식을 보내고, 등록 본문에 `reports_stop`, `child_goal` 을 넣는다. Control Plane 과 화면은 바꾸지 않는다.

### 지금 구조

- `hermes/plugins/fos-ctx/subagent.py` 의 `subagent_start` 가 `FOS_CTX_SUBAGENT_URL` 로 등록 본문을 보낸다. 제한 시간 `REGISTER_TIMEOUT` 초, 연결 실패와 5xx 에만 한 번 더 보낸다. PR7 전에는 받은 `child_goal` 을 보내지 않았다.
- `plugin.yaml` 의 `hooks` 와 `register` 는 `pre_tool_call`, `subagent_start`, `transform_tool_result` 셋이다.
- 끝 소식 계약(경로, 칸, 서명, 응답)은 PR6 이 `hermes/plugins/fos-ctx/README.md` 의 「하위 에이전트 끝 소식 계약」 에 적었다. 서버는 그 모양으로 받는다(`SubagentStop.verify`).

v0.21.6(`818c13be`)과 v0.23.2(`ae2a81cc`)의 tag 소스로 확인한 사실이다.

| 사실 | 근거 |
| --- | --- |
| 두 버전 모두 `subagent_stop` 을 같은 키워드 인자로 부른다. v0.23.2 는 `child_summary` 의 비밀값을 가린 뒤 넘긴다(`redact_child_value`) | `tools/delegate_tool_results.py` 의 `_fire_subagent_stop_hooks` |
| `subagent_stop` 은 부모의 마무리 잠금(`_parent_finalization_lock`) 안에서 부르는 스레드가 자식마다 잇달아 부른다. 제한 시간 worker 로 옮기지 않는다 | 같은 파일의 `_finalize_child_results`, `hermes_cli/plugins_dispatch.py` 의 `_HOOK_CALLER_THREAD_HOOKS` |
| 두 버전 모두 `subagent_start` 에 `child_goal` 을 넘긴다 | `tools/delegate_tool.py` 의 `_build_child_agent` 끝 |

### 책임과 경계

| 무엇 | 소유 | 까닭 |
| --- | --- | --- |
| 끝 소식 본문, 서명, 보내기 | `fos-ctx` 의 `subagent.py` 의 새 `build_stop`, `subagent_stop` | 등록 본문과 보내기가 같은 파일에 있다 |
| 끝 소식 서명 | `context.py` 의 새 `sign_subagent_stop`(첫 줄 `v1-subagent-stop`) | 서명 함수가 모두 여기 있다 |
| hook 이름과 인자가 두 Hermes 버전에 있는지 | `hermes/tests/hermes_contract.py` 의 새 선언 `SUBAGENT_HOOKS` 와 `test_hermes_contract.py` 의 소스 검사 | Hermes 소스 계약의 선언이 모두 여기 있다. CI 는 기준 버전을 보고, 다른 버전은 같은 검사를 그 소스로 돌린다 |

### 보내는 것

- 주소는 `FOS_CTX_SUBAGENT_URL` 끝에 `/stop` 을 붙인 것이다. 끝의 `/` 는 떼고 붙인다. 운영 값을 하나도 늘리지 않는다. 주소가 없으면 등록도 끝 소식도 보내지 않는다.
- 등록 본문의 `reports_stop` 은 늘 `true` 다. 이 버전의 플러그인은 등록하는 자식마다 `subagent_stop` 을 받아 끝 소식을 보내기 때문이다. `child_goal` 은 hook 이 받은 글을 그대로 넣고, 글이 아니거나 비었으면 `null` 이다.
- 끝 소식의 `parent_root_session_id` 는 등록과 같은 `root_session(parent_session_id)` 다. 압축으로 부모 session 이 바뀌어도 루트는 같다. state.db 를 읽지 못하면 부모 session 을 루트로 쓴다. 등록과 같다.
- `child_status`, `child_summary` 는 글이 아니면 `null` 이다. 결과 글을 자르지 않는다. 서버가 자른다.
- 손자 자식처럼 깨울 자식이 아닌 것도 보낸다. `subagent_stop` 에는 `parent_subagent_id` 가 없어 플러그인이 가리지 못하고, 서버가 `ignored` 로 답한다.
- 제한 시간과 다시 보내기는 등록과 같다. 연결 실패와 5xx 에만 한 번 더 보내고, 4xx(404 포함)는 로그만 남긴다. 어떤 예외도 hook 밖으로 내지 않는다.
- 로그에는 시도 횟수와 상태 코드, 예외 종류만 남긴다. 토큰, 서명, 맡긴 일, 결과 글은 남기지 않는다.

hook 은 부모의 마무리 잠금 안에서 동기로 돈다. 보내기가 늦으면 그만큼 부모 session 의 결과 줄이 늦게 남는다. 자식 하나에 최악 `REGISTER_TIMEOUT` 의 두 배다. 결과 줄은 다음 turn 이 읽는 것이고(이름을 붙인 profile 은 남기지 않는다), 자동 turn 은 끝 소식 뒤 5초를 기다리므로(「깨우는 때」) 이 지연이 결과를 놓치게 하지 않는다.

### 배포와 되돌리기

PR6 이 운영에 있어야 한다. PR6 전의 서버는 `/stop` 경로가 없어 2xx 가 아닌 답을 주고 플러그인은 로그만 남긴다. 등록 본문의 새 칸은 PR6 서버가 받고 옛 서버는 모르는 키라 버린다. 그래서 어느 순서로 배포돼도 등록은 끊기지 않는다(rule-66). 되돌리기는 「배포 순서와 안전」 의 「PR7 을 되돌림」 이다.

### 검증 전략

| 무엇 | 경계 | 확인 |
| --- | --- | --- |
| 끝 소식 본문과 서명 | `test_fos_ctx.py` 의 가짜 Control Plane | README 확인 값과 같은 `sig`, `/stop` 경로, 칸, 상태와 글이 글이 아니면 `null` |
| 등록 본문의 새 칸 | 같은 테스트 | `reports_stop: true`, `child_goal` 이 hook 의 글 |
| 실패 분기 | 같은 테스트 | 5xx 한 번 더, 4xx 그대로, 연결 실패, 주소·토큰·session 없음, 예외. 모두 로그만 남기고 None. 로그에 비밀과 글이 없다 |
| hook 선언 | 같은 테스트의 manifest·`register` 검사 | 네 hook |
| Hermes 계약 | `test_hermes_contract.py` 를 v0.21.6, v0.23.2 소스로 | 두 hook 이 `VALID_HOOKS` 에 있고, 부르는 자리가 플러그인이 읽는 키워드 인자를 넘기며 `subagent_stop` 이 부르는 스레드에서 돈다 |
| 운영 왕복 | 스펙의 「배포 뒤 왕복 확인」 1~5 | 코디네이터가 배포 뒤 돌린다 |


## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | 소개·아이콘·색을 둘 자리 | `agent` 의 새 컬럼 셋 | 작성자 | 테이블을 늘리지 않는다(spec DECISION13). 페르소나와 달리 Hermes 가 읽지 않는 화면 값이라 `SOUL.md` 에 두지 않는다 |
| DECISION2 | 이름 바꾸기 | 소개와 같은 PATCH 로 주인과 `ADMIN` 이 이름도 바꾼다 | 작성자 | 지금은 이름을 바꾸는 경로가 없다. 기본 비서 「비서」 와 마법사가 만든 이름을 사용자가 고칠 수 있어야 한다 |
| DECISION3 | 소개 상한 | 60자 | 작성자 | 휴대폰 카드 한 줄에 들어가고, `agent_list` 로 매 위임 판단에 실린다. 새 스킬 설명 상한(`SkillService.MAX_NEW_DESCRIPTION_CHARS`)과 같다 |
| DECISION4 | 아이콘 고르기 | 고정 이모지 목록 | 작성자 | 스펙 REQ1 이 「목록 밖 아이콘은 거절」 을 요구한다. 아무 글자를 받으면 서버가 이모지 하나인지 판정하기 어렵다 |
| DECISION5 | 답마다 에이전트 싣기 | PR1 에서는 싣지 않고 대화의 에이전트로 그린다. PR4 에서 싣는다 | 작성자 | 지금은 답을 쓰는 에이전트가 대화마다 하나다 |
| DECISION6 | 할 수 있는 일의 모음 자리 | 새 port `AgentConnectorNames`, 기존 port `ProfileSkillFiles` 의 새 메서드, `AgentCapabilitiesReader` | 작성자 | `agent` 가 `skill`, `connector` 보다 아래다. 대시보드를 부를 수 있는 조회는 「부르지 않는다」 가 약속인 `AgentConnectorBindings` 와 섞지 않는다 |
| DECISION7 | 기본 비서를 가리는 법 | 칸을 두지 않고 「주인의 기본 profile 을 가리키는 에이전트」 로 정한다. PR1 은 마이그레이션 조건에만 쓴다 | 작성자 | 첫 에이전트의 profile 이 기본 profile 이다(`FirstAgentCreator`). `agent.hermes_profile` 과 `allowed_person.hermes_profile` 이 둘 다 유일하다 |
| DECISION8 | 플러그인 이름의 원본 | 카탈로그 캐시. 바인딩 행에 이름을 적어 두지 않는다 | 작성자 | 이름을 적으면 칸과 이관이 늘고 운영자가 이름을 바꿔도 남는다. 캐시의 실패 기억이 대기 시간을 묶는다 |
| DECISION9 | 맡긴 줄의 실시간 경로 | 새 사건 `delegation` 을 대화 단위 SSE 로만 보낸다 | 작성자 | 위임은 turn 의 SSE 와 다른 스레드에서 돌고 turn 이 끝난 뒤에도 끝난다. 대화 단위 SSE 는 모든 창이 늘 연다. 기존 `subagent` 사건은 Hermes 안의 하위 에이전트라 뜻이 다르다 |
| DECISION10 | 결과 알림 줄과 결과를 잇는 자리 | `result_delivery.delegation_notice_message_id` 컬럼 | 작성자 | 묶음은 마지막 알림 줄만 기억하고, 첫 시도는 승인 결과마다 알림 줄을 더 남긴다. `chat_message` 의 `SYSTEM` 줄은 `execution_id` 가 비어야 하고(data-schema), `items_json` 은 결과 목록이라 줄 번호를 섞지 않는다 |
| DECISION11 | 결과 도착 줄의 글 | 저장된 글은 그대로 두고 화면이 이름과 상태로 결과마다 한 줄을 만든다 | 작성자 | 「○○ 외 N개」 한 줄은 여러 이름표를 보이지 못한다. 저장된 글을 바꾸면 이미 남은 줄과 글이 달라진다 |
| DECISION12 | 옛 알림 줄 | 채우지 않고 글만 보인다 | 작성자 | 줄과 실행을 이을 근거가 시각 일치뿐이다 |
| DECISION13 | 맡긴 줄의 자리 | 답 본문 아래 | 작성자 | 맡긴 자리가 저장되지 않아 새로 고친 뒤에도 같은 자리여야 한다(spec REQ3 「새로 고치거나 다른 기기에서 열어도 같은 줄」) |
| DECISION14 | 마법사의 단계를 묶는 자리 | `connector` 의 새 빈 `SpecialistAgentCreator`. 자기 트랜잭션 없이 지금의 만들기, 성격 쓰기, 붙이기, 떼기를 부른다 | 작성자 | `agent` 가 `connector` 보다 아래라 붙이기를 부를 수 없다. 단계마다 외부 호출이 있어 한 트랜잭션으로 묶으면 그동안 잠금을 쥔다 |
| DECISION15 | 떼기의 차례 | `single_binding` 은 붙이기 전, 나머지는 붙인 뒤 | 작성자 | 붙인 뒤에 떼야 붙이기가 실패해도 쓰던 에이전트가 그대로다. `single_binding` 은 떼지 않으면 붙일 수 없다 |
| DECISION16 | 제안을 띄우는 판정 | 방금 연결을 마쳤고 바인딩이 하나도 없고 `?agent=` 로 오지 않았을 때 | 작성자 | 「처음 준비된 화면」 이 곧 아직 붙지 않은 때다. 기본 비서를 가리는 칸을 새로 두지 않아도 「전문 에이전트가 이미 있으면 띄우지 않는다」 가 지켜진다 |
| DECISION17 | 떼기 칸의 글 | 지금 붙은 에이전트의 이름으로 「「{이름}」에서는 떼고 맡기기만」. 붙은 곳이 없으면 칸이 없다 | 작성자 | 기본 비서는 이름이 「비서」 라 스펙의 「비서에서는 떼고 맡기기만」 과 뜻이 같고, 다른 에이전트면 「「○○」에서는 떼고 맡기기만」 으로 스펙 REQ6 의 「○○에서 떼기」 를 보인다. 기본 비서 판정 없이 된다. 기본 비서의 이름을 바꿨으면 바꾼 이름으로 보인다 |
| DECISION18 | `@` 맡김을 돌리는 길 | 대화 turn 을 고른 에이전트와 새 session 으로 한 번 돌린다. `agent_delegate` 의 자식 실행(`ChildExecutionRunner`)을 쓰지 않는다 | 작성자 | 사용자가 보낸 일이라 질문 저장, 흐르는 답, 중지, 대기 메시지, 결과물을 보통 turn 과 같게 받아야 한다. 자식 실행은 답을 대화에 남기지 않고 부모가 있어야 한다 |
| DECISION19 | 맡김 실행을 가리는 법 | 칸을 두지 않고 「대화의 실행 가운데 부모가 없고 에이전트가 대화의 에이전트와 다른 실행」 으로 정한다. `delegation_key` 는 두지 않는다 | 작성자 | 대화의 에이전트는 바뀌지 않아 다른 루트 실행이 생기지 않는다. `delegation_key` 가 있으면 자동 결과 전달이 비서를 깨운다(spec DECISION20) |
| DECISION20 | 맡은 에이전트가 받는 앞 대화 | 최근 `USER`·`ASSISTANT` 6줄, 줄마다 1,000자, 출처 머리줄과 `<external-data>` | 작성자 | spec DECISION20 「바로 앞 대화 몇 줄」. 다른 에이전트의 답에 외부 글이 들 수 있고 앞 요청을 다시 하지 않게 한다 |
| DECISION21 | 비서가 맡김을 아는 길 | 대화 에이전트의 다음 turn 입력 앞에 지난 자기 turn 뒤의 맡김을 머리줄과 함께 싣는다. 최근 5개, 답 2,000자 | 작성자 | 대화 에이전트의 Hermes session 에 맡김이 없다. instructions 는 매 실행 조립이라 기록이 session 에 남지 않는다. 위임 결과 전달과 같은 머리줄이라 모델이 같은 규칙으로 읽는다 |
| DECISION22 | 응답 중과 사진이 있을 때의 `@` | 응답 중에는 목록을 띄우지 않고, 사진과 함께 맡기지 않는다 | 작성자 | 대기 메시지는 글만 받는다(ADR-048). 사진 판정은 대화의 에이전트로 한다 |
| DECISION23 | 맡김 답의 다시 만들기 | 막는다 | 작성자 | 다시 만들기는 대화의 에이전트와 session 으로 돈다. 맡김을 다시 하려면 다시 `@` 로 보낸다 |
| DECISION24 | 대화방을 가리는 법 | `conversation.purpose` 의 새 값 `ROOM`. 사용자마다 지우지 않은 것 가운데 `id` 가 가장 큰 것을 쓰고 처음 열 때 만든다 | 작성자 | spec DECISION13. 점검 대화가 같은 규칙으로 이미 돈다(ADR-080) |
| DECISION25 | 기본 비서를 찾는 법 | 주인이 요청자이고 profile 이 요청자의 기본 profile 인 에이전트. 지우기 거절은 「profile 이 허용 목록의 누군가의 기본 profile」 로 넓게 본다 | 작성자 | DECISION7 을 따른다. 기본 profile 을 가리키는 에이전트는 첫 로그인이 만든 것뿐이라 넓게 봐도 다른 에이전트가 걸리지 않고, 주인을 바꾼 첫 에이전트도 지워지지 않는다 |
| DECISION26 | `/` 와 새 대화 화면의 주소 | `/` 는 대화방을 열어 `/chat/{id}` 로 넘기고 새 대화 화면은 `/new` 다 | 작성자 | 대화 화면은 주소가 대화 식별자를 갖는 것을 전제로 상태를 새로 만든다(`ChatPanel`). 대화방만 `/` 에 그리면 그 전제가 둘로 갈린다 |
| DECISION27 | 대화방을 목록에서 빼기 | 목록 API 가 빼고 사이드바가 따로 읽어 맨 위에 그린다 | 작성자 | 목록은 쪽으로 나눠 읽어 대화방이 첫 쪽에 없을 수 있다. 목록 줄의 이름 바꾸기·지우기 메뉴를 대화방에서 따로 감출 필요도 없어진다 |
| DECISION28 | 날짜 경계를 판정하는 값 | 대화의 지금 루트 session 으로 돈 첫 실행의 `started_at` | 작성자 | 칸을 늘리지 않는다. 요약이 성공하면 루트가 바뀌어 다음 판정이 오늘이 되고, 실패하면 그대로라 spec DECISION25 의 「다음 메시지에서 다시 요약」 이 저절로 된다. 압축 교체는 루트를 바꾸지 않는다 |
| DECISION29 | 요약 실행의 실행 줄 | 대화 밖 실행(`startDetached`). 대화의 에이전트와 모델로 옛 session 에 보낸다 | 작성자 | 대화의 질문 없는 루트 실행은 그 대화의 `memory_remember` 바로 저장을 늘 제안으로 내린다. 요약이 매일 그 조건을 걸지 않게 한다. 대화방은 끝나지 않아 바깥 도구나 결과 전달이 한 번 들면 그 뒤로 제안으로만 남는 것은 지금 판정 그대로이고, 판정 범위를 루트 session 으로 좁히는 것은 기억 정책 변경이라 이 PR 범위 밖이다. 비용과 실패는 실행 기록에 그대로 남는다 |
| DECISION30 | session 을 바꾸는 법 | 두 칸을 새 `fos-<uuid>` 로 함께 바꾼다(`ConversationSessions.renew`) | 작성자 | 비운 뒤 첫 turn 이 채우는 것과 결과가 같고, 한 번의 갱신이라 비운 채 남는 때가 없다. 먼저 살펴보기가 같은 길을 쓴다 |
| DECISION31 | 요약을 두는 자리 | 알림 줄 본문(첫 줄 고정 글과 요약). 새 루트 session 에 성공한 실행이 생길 때까지 입력 앞에 붙인다 | 작성자 | 칸을 늘리지 않고, 요약을 붙인 첫 turn 이 실패해도 요약을 잃지 않는다. 사용자는 무엇을 이어 받았는지 펼쳐 본다 |
| DECISION32 | 끝을 알아채는 길 | fos-ctx 의 `subagent_stop` hook 이 Control Plane 에 알린다 | 작성자 | 두 Hermes 버전 모두 API run 에 완료 사건이 없고 hook 은 있다(docs/features/hermes-upgrade/spec.md). session 메시지 조회는 대화마다 폴링이 든다 |
| DECISION33 | 결과를 두는 자리 | `hermes_session_binding` 의 새 칸 | 작성자 | 자식 하나가 등록 줄 하나다. 테이블을 늘리지 않는다(spec DECISION38) |
| DECISION34 | 깨울 자식을 정하는 때 | 등록할 때 `wake_eligible` 로 한 번 정한다 | 작성자 | 판정에 쓰는 origin 실행과 대화가 등록 때 이미 풀린다. 끝 소식 때 다시 풀면 압축이나 지운 대화로 답이 바뀐다 |
| DECISION35 | PR7 전의 안전 | 등록 본문의 `reports_stop` 이 참인 자식만 깨우고 기한을 센다 | 작성자 | 사용자가 기존 설정 하나로 켜기로 해 PR6 이 배포 즉시 동작한다(spec DECISION35). `subagent_start` 만으로 기한을 세면 PR7 전의 모든 위임이 1시간 뒤 알림을 남긴다 |
| DECISION36 | 한 호출의 자식을 모으는 법 | 끝 소식 뒤 5초 기다렸다 깨운다. 호출 번호를 저장하지 않는다 | 작성자 | Hermes 가 한 단위의 hook 을 잇달아 부른다(`_finalize_child_results`). `subagent_start` 에 호출 번호가 없어 Control Plane 이 호출을 구분할 수 없다. spec DECISION30 의 「도착하는 대로」 는 같은 5초 안에 끝난 다른 호출을 함께 전해도 지켜진다 |
| DECISION37 | 이미 읽은 결과 | 칸도 질의 조건도 두지 않는다(DECISION41, 2026-11-17 고침) | 작성자 | 읽었는지 판정하지 않으므로 둘 것이 없다 |
| DECISION38 | 자동 turn 입력의 결과 글 | Control Plane 이 저장한 결과 글을 입력에 넣는다. Hermes 이력의 결과 줄에 기대지 않는다 | 작성자 | hook 이 결과 줄보다 먼저 와 turn 을 열 때 그 줄이 없을 수 있다. 다시 전달(ADR-075)도 저장된 글이 있어야 한다. 모델이 같은 결과를 두 번 볼 수 있는 것을 감당한다 |
| DECISION39 | 기한·중지 알림 줄의 단위 | origin 실행(turn)마다 한 줄 | 작성자 | 같은 호출의 자식을 알 수 없다(DECISION36). spec DECISION40 이 답마다 한 줄로 정했다(사용자 2026-11-17) |
| DECISION40 | 결과 도착 줄과 출처를 잇는 법 | 등록 줄의 `notice_message_id` 에 첫 시도의 알림 줄을 적고, `chat` 의 port 로 상태를 읽는다 | 작성자 | 묶음의 줄 번호 칸은 위임 결과가 있을 때만 차거나 다시 전달로 덮인다(`result_delivery`). 줄 번호 순서와 시각으로 잇는 것은 DECISION12 가 기각했다. 같은 표의 칸 하나라 테이블이 늘지 않는다 |
| DECISION41 | 이미 읽은 결과의 판정 | 판정하지 않는다. 끝 소식 뒤에 돈 사용자 turn 이 있어도 자동 답을 한 번 연다(2026-11-17 고침. 처음에는 「끝 소식 30초 뒤에 시작한 제출된 루트 실행이 있으면 읽은 것」 이었다) | 사용자 2026-11-17 | 이름을 붙인 profile 은 결과 줄을 남기지 않아 처음 판정이 결과를 잃는다. 스스로 셈한 답은 Control Plane 이 구분하지 못한다(위 「끝 소식 뒤에 돈 사용자 turn」) |
| DECISION42 | 끝 소식 주소 | 등록 주소 끝에 `/stop` 을 붙인다. 새 환경 변수를 두지 않는다 | 작성자 | 두 경로가 같은 Control Plane 의 형제 경로다. 운영 값이 늘면 운영 저장소와 배포 순서가 함께 늘고, 하나만 넣으면 끝 소식만 빠진다 |
| DECISION43 | `reports_stop` 을 보내는 조건 | 이 플러그인은 등록마다 `true` | 작성자 | `subagent_stop` 을 늘 등록하므로 등록한 자식은 모두 끝 소식을 받는다. 보내기에 실패한 자식은 서버의 기한 알림이 맡는다 |
| DECISION44 | 깨울 자식이 아닌 끝 소식 | 플러그인이 가리지 않고 보낸다 | 작성자 | `subagent_stop` 에 `parent_subagent_id` 가 없다. 등록 때의 판정을 플러그인 메모리에 두면 gateway 를 다시 띄울 때 잃고, 판정의 주인은 서버다(DECISION34) |

## 결정 기록 연결

| 결정 | 종류 | ADR | 현재 규칙의 원본 |
| --- | --- | --- | --- |
| DECISION1 과 할 수 있는 일을 읽을 때 모으기 | architecture | [ADR-20261110 / agent-introduction](../../adr/ADR-20261110-agent-introduction.md). ADR-036 의 소개를 없앤 부분을 대체한다 | [에이전트 팀](../agent-team.md) 의 「소개와 할 수 있는 일」 |
| DECISION18, DECISION19, DECISION21 | architecture | [ADR-20261112 / mention-delegation](../../adr/ADR-20261112-mention-delegation.md). ADR-017 의 「무엇을 할지는 Hermes 가 정한다」 에 예외를 둔다 | [에이전트 팀](../agent-team.md) 의 「`@` 로 바로 맡기기」 |
| DECISION24 부터 DECISION31 | feature | 필요 없음. 대화 목적의 새 값과 실행 줄로 판정하는 규칙이라 기능 파일이 원본이고, 코드 한 곳(`RoomConversations`, `RoomContext`)을 바꾸면 되돌린다 | [에이전트 팀](../agent-team.md) 의 「대화방」 |
| DECISION32 부터 DECISION40 | architecture | [ADR-20261117 / native-delegation-wake](../../adr/ADR-20261117-native-delegation-wake.md). ADR-040 의 깨우기를 Hermes 내장 위임으로 넓힌다 | 이 문서의 「PR6 내장 위임 결과」. 구현 PR 이 [에이전트 팀](../agent-team.md) 에 절을 더한다 |
| DECISION42 부터 DECISION44 | feature | 필요 없음. 플러그인 한 파일(`subagent.py`)의 보내는 방식이고 그 파일을 되돌리면 된다. 끝 소식 계약은 ADR-20261117 / native-delegation-wake 가 갖는다 | fos-ctx [README](../../../hermes/plugins/fos-ctx/README.md) 의 「하위 에이전트 끝 소식 계약」 |
