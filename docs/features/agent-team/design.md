# 에이전트 팀 기술 설계

스펙: [`spec.md`](spec.md).

스펙의 DECISION2 대로 PR 다섯으로 나눈다. 이 문서는 PR1 을 구현할 깊이로 쓰고, 뒤 PR 은 경계와 데이터 자리만 정한다. 뒤 PR 은 시작할 때 이 문서의 해당 절을 고치고 검토를 다시 받는다.

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
| REQ3 | (PR2) 이력 API 의 `ASSISTANT` 줄에 그 실행이 바로 맡긴 위임 자식(`delegation_key` 가 있는 자식 실행)의 에이전트와 상태를 싣는다. 도는 동안은 기존 `subagent` 사건과 대화 단위 SSE 로 상태를 바꾼다 |
| REQ4 | (PR2) `SYSTEM` 알림 줄에 전달 묶음(`result_delivery`)의 자식 실행 에이전트를 싣는다. 승인 줄(`connector_action.agent_id`)이 대화의 에이전트와 다르면 카드에 그 에이전트를 싣는다 |
| REQ5 | (PR3) 화면이 연결 완료 뒤 제안을 띄우고, Control Plane 의 한 요청이 에이전트 만들기, 소개 저장, 성격 틀 쓰기, 바인딩을 차례로 하고 실패하면 역순으로 거둔다. 순서는 `agent` 위의 패키지(`connector`)가 갖는다 |
| REQ6 | (PR3) 같은 요청이 「떼기」 칸이면 그 연결이 지금 붙은 에이전트(기본 비서 또는 다른 에이전트)의 바인딩을 뗀다. `single_binding` 은 화면이 칸을 잠그고 서버도 떼기 없이 붙이기를 거절한다. REQ5 의 「그 서비스가 붙은 전문 에이전트」 는 그 연결이 붙은, 기본 비서가 아닌 에이전트다 |
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

아이콘 목록과 상한은 `AgentIntroduction`, 색 이름은 enum `AgentColor` 가 원본이고 화면의 `agent-badge.ts` 가 같은 값을 갖는다. 둘이 어긋나면 서버가 거절하므로, 단위 테스트 `test/unit/agent-badge.test.ts` 가 두 Java 파일과 화면 목록의 글자를 대조한다.

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
2. 스킬: 에이전트마다 `uploadedSkillNames(profile)`. 파일을 읽지 못하면 그 에이전트의 스킬을 비우고 경고 로그를 남긴다

카탈로그 캐시는 지금 판정 경로가 쓰는 `ConnectorCatalogCache` 를 그대로 쓴다. 보관 시간이 지난 첫 읽기는 대시보드 응답을 기다리고, 대시보드가 답하지 않으면 캐시의 실패 기억 덕에 그 뒤 읽기는 곧바로 빈 이름으로 답한다. 「화면 경로는 쓰지 않는다」 는 그 클래스 주석과 [`code-architecture.md`](../../../backend/docs/code-architecture.md) 의 표 줄을 「연결 화면은 쓰지 않는다. 에이전트 목록의 플러그인 이름만 쓴다」 로 고친다. 연결 화면이 운영자의 목록 변경을 바로 보여야 한다는 까닭은 그대로이고, 이름은 60초 늦어도 위험이 없다.

### 화면

| 자리 | 바뀌는 것 |
| --- | --- |
| 답 말풍선 `AssistantRow` | 그 대화의 에이전트 `AgentBadge` 와 이름. `MessageBubble` 과 `message-list.tsx` 의 기다림 줄, 모든 호출처에 넘긴다. 값은 `ConversationView` 에 더하는 `agentIcon`, `agentColor` 와 기존 `agentCode`, `agentName` 이다. 지웠지만 정리 전인 에이전트(`deleted_at` 이 있다)도 `agentCode`, `agentName` 을 null 로 내 「지운 에이전트」 로 그린다. 꺼진 에이전트도 대화 줄의 값으로 아이콘과 색을 그린다 |
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

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | 소개·아이콘·색을 둘 자리 | `agent` 의 새 컬럼 셋 | 작성자 | 테이블을 늘리지 않는다(spec DECISION13). 페르소나와 달리 Hermes 가 읽지 않는 화면 값이라 `SOUL.md` 에 두지 않는다 |
| DECISION2 | 이름 바꾸기 | 소개와 같은 PATCH 로 주인과 `ADMIN` 이 이름도 바꾼다 | 작성자 | 지금은 이름을 바꾸는 경로가 없다. 기본 비서 「비서」 와 마법사가 만든 이름을 사용자가 고칠 수 있어야 한다 |
| DECISION3 | 소개 상한 | 60자 | 작성자 | 휴대폰 카드 한 줄에 들어가고, `agent_list` 로 매 위임 판단에 실린다. 새 스킬 설명 상한(`SkillService.MAX_NEW_DESCRIPTION_CHARS`)과 같다 |
| DECISION4 | 아이콘 고르기 | 고정 이모지 목록 | 작성자 | 스펙 REQ1 이 「목록 밖 아이콘은 거절」 을 요구한다. 아무 글자를 받으면 서버가 이모지 하나인지 판정하기 어렵다 |
| DECISION5 | 답마다 에이전트 싣기 | PR1 에서는 싣지 않고 대화의 에이전트로 그린다. PR4 에서 싣는다 | 작성자 | 지금은 답을 쓰는 에이전트가 대화마다 하나다 |
| DECISION6 | 할 수 있는 일의 모음 자리 | 새 port `AgentConnectorNames`, 기존 port `ProfileSkillFiles` 의 새 메서드, `AgentCapabilitiesReader` | 작성자 | `agent` 가 `skill`, `connector` 보다 아래다. 대시보드를 부를 수 있는 조회는 「부르지 않는다」 가 약속인 `AgentConnectorBindings` 와 섞지 않는다 |
| DECISION8 | 플러그인 이름의 원본 | 카탈로그 캐시. 바인딩 행에 이름을 적어 두지 않는다 | 작성자 | 이름을 적으면 칸과 이관이 늘고 운영자가 이름을 바꿔도 남는다. 캐시의 실패 기억이 대기 시간을 묶는다 |
| DECISION7 | 기본 비서를 가리는 법 | 칸을 두지 않고 「주인의 기본 profile 을 가리키는 에이전트」 로 정한다. PR1 은 마이그레이션 조건에만 쓴다 | 작성자 | 첫 에이전트의 profile 이 기본 profile 이다(`FirstAgentCreator`). `agent.hermes_profile` 과 `allowed_person.hermes_profile` 이 둘 다 유일하다 |

## 결정 기록 연결

없음: PR1 은 기존 저장·권한 규칙 안에서 칸만 더한다. PR4 의 ADR-017 예외는 그 PR 에서 ADR 로 남긴다.
