# 에이전트 팀 기술 설계

스펙: [`spec.md`](spec.md).

스펙의 DECISION2 대로 PR 다섯으로 나눈다. 이 문서는 PR1 부터 PR3 까지를 구현할 깊이로 쓰고, 뒤 PR 은 경계와 데이터 자리만 정한다. 뒤 PR 은 시작할 때 이 문서의 해당 절을 고치고 검토를 다시 받는다.

| PR | 요구사항 | 내용 |
| --- | --- | --- |
| PR1 | REQ1, REQ2 | 에이전트 소개·아이콘·색과 할 수 있는 일, 답 이름표, 기본 비서 이름 |
| PR2 | REQ3, REQ4 | 맡긴 줄, 결과 도착 줄과 승인 카드의 이름표 |
| PR3 | REQ5, REQ6 | 전문 에이전트 만들기 마법사 |
| PR4 | REQ7 | 대화 중 `@` 로 바로 맡기기 |
| PR5 | REQ8, REQ9 | 기본 비서와 이어지는 대화방과 날짜별 요약 |

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
| REQ2 | `AgentView` 에 소개, 아이콘, 색, 할 수 있는 일을 싣는다. 화면은 부품 `AgentBadge` 하나로 답, 대화 머리, 목록, 새 대화 카드, `@` 목록을 그린다. 답 이름표는 대화 줄(`ConversationView`)의 에이전트 칸으로 그린다. `agent_list` 결과에도 소개와 할 수 있는 일을 싣는다. 첫 에이전트는 이름 「비서」 와 고정 소개로 만들고, 이미 있는 첫 에이전트는 마이그레이션이 바꾼다 |
| REQ3 | (PR2) 이력 API 의 `ASSISTANT` 줄에 그 답의 실행이 바로 맡긴 위임 자식(`delegation_key` 가 있고 부모가 그 실행인 실행 줄)의 에이전트와 상태를 싣는다. 도는 동안은 위임이 시작하고 끝날 때 새 사건 `delegation` 을 대화 단위 SSE 로 그 대화의 모든 구독에 보낸다. 아래 「PR2 협업 보이기」 |
| REQ4 | (PR2) 위임 결과 알림 줄(`SYSTEM`)에 그 줄이 전한 자식 실행의 에이전트와 상태를 싣는다. 줄과 결과는 전달 묶음(`result_delivery`)의 새 칸 `delegation_notice_message_id` 와 `items_json` 으로 잇는다. 승인 줄(`connector_action.agent_id`)이 대화의 에이전트와 다르면 승인 카드 응답에 그 에이전트를 싣는다 |
| REQ5 | (PR3) 화면이 연결 완료 뒤 제안을 띄우고, Control Plane 의 한 요청이 에이전트 만들기(소개 포함), 성격 틀 쓰기, 바인딩을 차례로 하고 실패하면 역순으로 거둔다. 순서는 `agent` 위의 패키지(`connector`)가 갖는다. 아래 「PR3 마법사」 |
| REQ6 | (PR3) 같은 요청이 「떼기」 칸이면 그 연결이 지금 붙은 다른 에이전트(기본 비서 또는 다른 에이전트)의 바인딩을 모두 뗀다. `single_binding` 은 화면이 칸을 잠그고 서버도 떼기 없이 붙이기를 거절한다. 제안은 연결에 바인딩이 하나도 없을 때만 뜨므로 「그 서비스가 붙은 전문 에이전트」 가 있으면 뜨지 않는다 |
| REQ7 | (PR4) 보내기 요청에 맡길 에이전트를 싣고 Control Plane 이 위임 실행을 바로 만든다. 위임 입력에는 `@` 메시지와 그 대화의 최근 `USER`·`ASSISTANT` 줄 몇 개를 출처 머리줄과 함께 싣는다. 맡은 에이전트의 답은 그 실행의 에이전트를 가진 `ASSISTANT` 줄로 남기고 `MessageView` 에 답을 쓴 에이전트를 더한다. 비서의 Hermes session 에는 이 주고받음이 없으므로, 비서의 다음 turn 입력 앞에 위임 결과 전달과 같은 머리줄로 「사용자가 `@` 로 맡긴 일과 답」 을 싣는다. 무엇을 할지 Control Plane 이 정하는 예외라 ADR-017 을 고치는 ADR 을 그 PR 에 둔다 |
| REQ8 | (PR5) 대화방은 `conversation.purpose` 의 새 값이다. 기본 비서는 「주인의 기본 profile 을 가리키는 에이전트」 로 정하고 새 칸을 두지 않는다. 지우기 경로가 기본 비서를 거절한다 |
| REQ9 | (PR5) 날이 바뀐 첫 메시지의 turn 을 시작하기 전에 직전 session 의 요약 실행을 돌리고, 요약을 `SYSTEM` 줄로 남긴 뒤 `hermes_session_id` 와 `hermes_root_session_id` 를 비워 새 session 으로 turn 을 연다. 새 루트 session 은 첫 turn 이 채우는 지금 규칙을 따른다. 요약 글은 그 session 의 첫 turn 입력 앞에 실어 Hermes session 이력에 남긴다. instructions 는 실행마다 조립하므로 거기에 두지 않는다. 요약이 실패하면 두 칸을 그대로 두고 다음 메시지에서 다시 한다 |

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
| 만들기 화면 | 이름(「{플러그인 이름} 담당」), 소개(「{플러그인 이름} 일을 맡아요」), 아이콘(플러그인마다 정한 이모지, 없으면 🤖)을 미리 채운다. 색은 자동이다. 그 아래 떼기 칸 |
| 떼기 칸 | 연결이 붙은 다른 에이전트가 있을 때만 보인다. 글은 「「{붙은 에이전트 이름}」에서는 떼고 맡기기만」 이고 기본으로 켠다. 기본 비서는 이름이 「비서」 라 「「비서」에서는 떼고 맡기기만」 으로 보인다. `single_binding` 이면 켠 채 잠그고 까닭을 적는다 |
| 에이전트 목록 | 「새 에이전트」 옆에 「전문 에이전트 만들기」. 내 연결 가운데 준비된 것을 고르면 같은 만들기 화면이다. 준비된 연결이 없으면 「외부 서비스 연결」 로 안내한다 |
| 만든 뒤 | 연결 화면에서는 「「{이름}」을 만들고 「{플러그인 이름}」을 붙였어요」 와 그 에이전트와 대화하기. 에이전트 목록에서는 그 에이전트 상세로 옮긴다 |
| 실패 | 만들기 화면에 그 단계의 까닭을 보이고 「만든 것은 거뒀어요」 를 붙인다. 상한이면 상한 문구만 보인다 |

**제안은 바인딩이 하나도 없을 때만 띄운다.** 스펙의 「처음 준비된 화면」 은 연결을 마친 뒤 아직 어느 에이전트에도 붙지 않은 때다. 붙지 않았으니 그 서비스가 붙은 전문 에이전트도 없다. 기본 비서를 가리는 칸이 없어도 판정이 된다. 이미 붙은 연결로 전문 에이전트를 만드는 길은 에이전트 목록이다.

### 실패와 일관성

- 한 요청이 몇 초에서 수십 초 걸린다. profile 만들기와 설치 외부 호출이 들어서다. 화면은 도는 동안 단추를 막고 창을 닫지 못하게 한다
- 거두기 도중 서버가 멈추면 만든 에이전트가 남을 수 있다. 사용자에게는 실패로 보이고 목록에 새 에이전트가 보인다. 사용자가 지우거나 그대로 쓴다. 남은 것을 찾는 정리 작업은 두지 않는다
- 붙이기가 설치를 부르다 실패해 바인딩이 `PENDING` 으로 남는 경우(`CONNECTOR_OPERATION_FAILED`)도 실패로 보고 거둔다. 지금 붙이기 화면은 남겨 두고 다음 확인에 맡기지만, 마법사는 새로 만든 에이전트라 남길 까닭이 없다

### 검증 전략

| 요구사항 | 경계와 환경 |
| --- | --- |
| REQ5 | backend 통합 테스트: 만들면 소개가 채워진 비공개 에이전트와 그 연결의 바인딩이 생기고 성격 틀을 쓴다. 상한, 준비 안 된 연결, 목록 밖 값은 아무것도 만들지 않는다. 성격 쓰기와 붙이기가 실패하면 새 에이전트가 지워지고 profile 을 거둔다. 브라우저: 연결을 마친 화면의 제안, 미리 채운 값, 만든 뒤 안내, 에이전트 목록의 「전문 에이전트 만들기」 |
| REQ6 | backend: `detach` 면 다른 에이전트의 바인딩이 없어지고 `agent_list` 에 새 에이전트와 그 서비스가 보인다. `single_binding` 은 먼저 떼고 붙이며, `detach` 없이는 거절한다. 붙이기가 실패하면 뗀 에이전트에 다시 붙인다. 화면 단위 테스트: 떼기 칸의 글과 잠금. 실제 대화에서 맡긴 줄이 생기는지는 배포 뒤 확인한다 |

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
| DECISION17 | 떼기 칸의 글 | 지금 붙은 에이전트의 이름으로 「「{이름}」에서는 떼고 맡기기만」. 붙은 곳이 없으면 칸이 없다 | 작성자 | 기본 비서는 이름이 「비서」 라 스펙의 글과 같고, 다른 에이전트면 「○○에서 떼기」 가 된다(spec REQ6). 기본 비서 판정 없이 된다 |

## 결정 기록 연결

| 결정 | 종류 | ADR | 현재 규칙의 원본 |
| --- | --- | --- | --- |
| DECISION1 과 할 수 있는 일을 읽을 때 모으기 | architecture | [ADR-20261110 / agent-introduction](../../adr/ADR-20261110-agent-introduction.md). ADR-036 의 소개를 없앤 부분을 대체한다 | [에이전트 팀](../agent-team.md) 의 「소개와 할 수 있는 일」 |

PR4 의 ADR-017 예외는 그 PR 에서 ADR 로 남긴다.
