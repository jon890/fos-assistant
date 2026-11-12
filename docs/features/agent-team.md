# 에이전트 팀

사용자가 에이전트를 이름표와 소개로 구분해 보고, 기본 비서와 전문 에이전트가 나눠 일하는 기능이다. 합의한 변경과 단계는 [스펙](agent-team/spec.md)과 [설계](agent-team/design.md)가 갖고, 이 파일은 지금 동작하는 것만 적는다.

covers: `backend/src/main/java/com/bifos/assistant/agent/domain/AgentIntroduction.java`, `backend/src/main/java/com/bifos/assistant/agent/domain/type/AgentColor.java`, `backend/src/main/java/com/bifos/assistant/agent/application/AgentCapabilitiesReader.java`, `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorNameLookup.java`, `web/src/components/agent/agent-badge.tsx`, `web/src/components/agent/agent-introduction-section.tsx`, `web/src/lib/agent-badge.ts`, `backend/src/main/java/com/bifos/assistant/chat/turn/application/DelegationLines.java`, `backend/src/main/java/com/bifos/assistant/chat/nextturn/application/DelegationLineNotifier.java`, `web/src/components/chat/delegation-lines.tsx`, `web/src/lib/delegation.ts`, `backend/src/main/java/com/bifos/assistant/connector/connection/application/SpecialistAgentCreator.java`, `web/src/lib/specialist-agent.ts`, `web/src/components/agent/specialist-agent-form.tsx`, `web/src/components/agent/specialist-agent-offer.tsx`, `web/src/components/agent/specialist-agent-dialog.tsx`, `web/src/components/agent/connector-onboarding.tsx`, `backend/src/main/java/com/bifos/assistant/chat/turn/application/MentionLines.java`, `backend/src/main/java/com/bifos/assistant/chat/turn/application/MentionInputs.java`, `web/src/lib/mention.ts`, `web/src/components/chat/delegate-chip.tsx`, `backend/src/main/java/com/bifos/assistant/agent/application/PrimaryAgents.java`, `backend/src/main/java/com/bifos/assistant/chat/conversation/application/RoomConversations.java`, `backend/src/main/java/com/bifos/assistant/chat/turn/application/RoomContext.java`, `backend/src/main/java/com/bifos/assistant/chat/turn/presentation/RoomController.java`, `web/src/app/page.tsx`, `web/src/components/shell/room-link.tsx`, `web/src/lib/room.ts`

## 요구

- 에이전트마다 한 줄 소개, 아이콘, 색이 있다. 답, 대화 머리, 새 대화 카드, 에이전트 목록, `@` 목록이 같은 이름표를 그린다.
- 위임 도구 `agent_list` 가 소개와 할 수 있는 일을 실어, 맡기는 에이전트가 그것을 보고 고른다([에이전트와 스킬](agent-skill.md) 의 「다른 에이전트에게 맡기기」).
- 첫 로그인에 만든 에이전트가 그 사용자의 기본 비서이고 이름은 「비서」 다([사용자](users.md) 의 「첫 에이전트의 기본 도구」).
- 서비스를 처음 연결하면 그 연결 하나만 맡는 전문 에이전트를 만들자고 제안하고, 받으면 한 번에 만든다. 에이전트 목록의 「전문 에이전트 만들기」 로도 만든다. 아래 「전문 에이전트 만들기」.
- 대화의 turn 이 다른 에이전트에게 맡기면 그 답 아래에 맡긴 줄이 보이고, 결과 도착 줄과 위임 자식의 승인 카드에 맡은 에이전트의 이름표가 붙는다. 아래 「대화 안의 협업」.
- 메시지가 있는 대화에서 입력창의 `@` 로 고른 에이전트에게 바로 맡기고, 그 답이 맡은 에이전트 이름표로 대화에 남는다. 대화의 에이전트는 다음 turn 에 그 주고받음을 받는다. 아래 「`@` 로 바로 맡기기」.
- 사용자마다 기본 비서와 늘 이어지는 대화방이 하나 있고 `/` 가 그 방을 연다. 날이 바뀐 첫 메시지에서 앞 맥락을 요약해 새 session 으로 잇는다. 기본 비서는 지울 수 없다. 아래 「대화방」.

## 소개와 할 수 있는 일

에이전트마다 한 줄 소개, 아이콘, 색을 둔다. 주인과 `ADMIN` 이 상세의 「소개」 절(성격 다음)에서 이름과 함께 고친다(`PATCH /api/v1/agents/{code}/introduction`). `ADMIN` 은 지우기처럼 남의 비공개 에이전트에도 닿는다.
고를 수 있는 아이콘과 색, 소개 상한은 `AgentIntroduction` 과 `AgentColor` 가 갖고, 화면의 `web/src/lib/agent-badge.ts` 가 같은 목록을 갖는다. 목록 밖 값과 상한을 넘는 소개는 `VALIDATION_FAILED` 다.
저장은 에이전트 행을 잠근 뒤 한다. `Agent` 는 저장할 때 모든 칸을 쓰므로 잠그지 않으면 그 사이 바뀐 공개 범위나 주인을 옛 값으로 덮는다.

**할 수 있는 일은 저장하지 않고 읽을 때 모은다.** 연결과 바인딩이 모두 `READY` 인 플러그인의 이름(카탈로그의 `title`)과 올린 스킬 이름이다. 올린 스킬에는 게시가 timeout 으로 끝나 표식 없이 남은 더 새 버전도 든다.
에이전트 목록(`GET /api/v1/agents`)과 `agent_list` 가 `AgentCapabilitiesReader` 로 같은 값을 쓴다. 카탈로그나 스킬 디렉터리를 읽지 못하면 실패하지 않고 그 칸을 비운다.
`ADMIN` 이 남의 비공개 에이전트를 고친 응답에는 할 수 있는 일을 비워 낸다. 관리자도 남의 에이전트의 연결은 보지 않는다.

| 자리 | 무엇을 보이나 |
| --- | --- |
| 답과 대화 머리 | 그 대화의 에이전트 아이콘·색·이름. 값은 대화 줄(`ConversationView` 의 `agentIcon`, `agentColor`)에서 온다. 이름표는 늘 지금 값이다. 좁은 화면의 잠긴 대화는 이름 대신 이름표만 보인다 |
| 새 대화 카드, 에이전트 목록 | 이름표, 이름, 소개 한 줄과 그 아래 할 수 있는 일. 소개가 없으면 할 수 있는 일만 보인다 |
| `@` 목록 | 이름표와 이름, 소개 한 줄 |
| 아이콘이나 색을 고르지 않았다 | 이름 첫 글자와 `code` 로 정한 자동 색 |


## 대화 안의 협업

**맡긴 줄은 대화의 turn(루트 실행)이 바로 맡긴 위임만 보인다.** 실행 줄의 `delegation_key` 가 있고 부모가 그 트리의 루트인 실행이다. 맡은 에이전트가 다시 맡긴 손자와 실행 전에 거절된 위임은 줄이 없고 실행 트리에서 본다. 판정은 `DelegationLines.isDirectDelegation` 이다.

| 자리 | 무엇을 보이나 | 값이 오는 곳 |
| --- | --- | --- |
| 답 본문 아래 | 「(맡은 에이전트 이름표) {대화 에이전트} → {맡은 에이전트}에게 맡김 · 진행 중·끝남·실패·멈춤」. 답 글이 아직 없으면 기다림 줄 아래 | 이력 API 의 `delegations`, 도는 동안은 `delegation` 사건 |
| 결과 도착 줄 | 결과마다 「(이름표) {맡은 에이전트}이 끝냈어요」, 실패면 「하지 못했어요」. 그 아래 자동 turn 의 답은 대화 에이전트의 이름표다 | 이력 API 의 `deliveredResults` |
| 승인 카드 | 대화의 에이전트와 다른 에이전트가 요청한 카드에만 「(이름표) {그 에이전트}이 요청」 | 승인 줄 목록의 `requestedBy` |

맡긴 자리는 저장되지 않으므로 줄은 늘 답 본문 아래에 둔다. 새로 고치거나 다른 기기에서 열어도 같은 자리다.
`delegation` 사건은 위임 실행 줄이 생길 때와 끝날 때 대화 단위 SSE 로 그 대화의 모든 구독에 간다(`DelegationLineNotifier`). 화면은 받은 줄을 실행 번호로 들고 있다가 이력의 줄과 합치고, 같은 실행이면 끝난 상태를 고른다.
결과 도착 줄은 전달 묶음의 `delegation_notice_message_id` 로 결과를 찾는다. 자동 turn 이 도는 동안은 그 줄의 `system` 사건이 같은 결과(`deliveredResults`)를 싣는다. 이 칸 전에 만든 알림 줄은 저장된 글만 보인다. 저장된 글(「○○ 에이전트의 결과가 도착했어요」)은 바꾸지 않는다.
승인과 거절 응답은 대화를 몰라 `requestedBy` 를 싣지 않으므로 화면이 카드를 바꿀 때 앞 값을 지킨다.
이름표의 네 칸(`AgentLabel`)이 모두 비어 있으면 지운 에이전트다. 화면은 「지운 에이전트」 로 그린다.

## `@` 로 바로 맡기기

메시지가 하나라도 있는 대화에서 입력창에 `@` 를 치면 요청자가 쓸 수 있고 켜진 에이전트 가운데 대화의 에이전트를 뺀 목록이 뜬다. 고르면 친 글자가 지워지고 입력창 위에 「{이름}에게 맡기기」 칩이 놓인다. 보내면 Control Plane 이 모델 판단 없이 그 에이전트로 turn 하나를 돌린다([ADR-20261112 / mention-delegation](../adr/ADR-20261112-mention-delegation.md)).

| 자리 | 무엇을 하나 |
| --- | --- |
| 보내기 요청 | `delegateTo` 에 에이전트 코드를 싣는다. 새 대화, 메시지가 없는 대화, 사진을 함께 보낸 요청, 대화의 에이전트 자신은 `VALIDATION_FAILED` 다. 쓸 수 없는 에이전트는 `AGENT_NOT_FOUND`, 꺼진 에이전트는 `AGENT_DISABLED` 다 |
| 맡은 에이전트의 run | 새 Hermes session 이다. 대화의 session 은 읽지도 바꾸지도 않는다. 입력은 출처 머리줄 아래 `<external-data>` 로 감싼 대화의 최근 6줄과 「[맡긴 일]」 아래 사용자 글이다 |
| 질문 아래 | 「(이름표) 나 → {이름}에게 맡김 · 진행 중·끝남·실패·멈춤」. 이력의 `delegatedTo` 로 그린다 |
| 답 | 맡은 에이전트의 이름표다. 이력의 `answeredBy` 로 그리고, 다시 생성 단추가 없다 |
| 대화 에이전트의 다음 turn | 지난 자기 turn 뒤의 맡김을 「@ 로 맡긴 일」 머리줄(`ResultHeader`)과 질문, 감싼 답으로 입력 앞에 싣는다. 사용자가 보낸 turn, 자동 결과 전달 turn, 예약 turn 이 싣고 한 번 실은 것은 다시 싣지 않는다. 경계는 대화 에이전트의 앞 답이나 질문이다 |

**맡김 실행은 대화의 실행 가운데 부모가 없고 에이전트가 대화의 에이전트와 다른 실행이다.** 칸을 두지 않는다. `delegation_key` 가 없어 자동 결과 전달을 타지 않으므로 대화의 에이전트는 그사이 답하지 않는다. 판정과 읽기는 `MentionLines`, 입력 단락은 `MentionInputs` 가 갖는다.
답이 도는 동안에는 `@` 목록을 띄우지 않는다. 대기 메시지는 글만 받아 대화의 에이전트에게 간다.
맡은 에이전트가 승인을 요청하면 위 「대화 안의 협업」 의 승인 카드 줄(`requestedBy`)이 그 카드에 붙는다. 대화의 에이전트와 다른 에이전트가 요청한 카드이기 때문이다.
서버가 다시 떠 기동 정리가 맡김을 마무리할 때도 대화의 session 은 적지 않는다.

## 대화방

**대화방은 `conversation.purpose = ROOM` 인 대화이고 사용자마다 지우지 않은 것 가운데 `id` 가 가장 큰 것 하나를 쓴다.** `RoomConversations` 가 사용자별 잠금 안에서 찾고 만든다.

| 자리 | 무엇을 하나 |
| --- | --- |
| `/` | 서버가 `POST /api/v1/chat/conversations/room` 으로 대화방을 열고 `/chat/{id}` 로 넘긴다. 처음 열면 기본 비서의 빈 방을 만들고, 지난 대화를 옮기지 않는다. 기본 비서가 없으면(`AGENT_NOT_FOUND`) 새 대화 화면 `/new` 로 간다 |
| 새 대화 화면 | `/new` 다. 「새 대화」 단추, 단축키, 「<에이전트>와 대화하기」(`/new?agent=<code>`)가 이리 온다. 앱 이름(홈)은 `/` 로 간다 |
| 사이드바 | 「새 대화」 위에 대화방 줄(`room-link`)이 고정이다. 이름표와 에이전트 이름을 `GET /api/v1/chat/conversations/room` 으로 읽고, 아직 열지 않았으면 「비서」 로 보인다. 대화 목록 API 는 대화방을 빼므로 목록 줄의 이름 바꾸기·지우기 메뉴가 없다 |
| 이름 바꾸기, 지우기 | 서버도 대화방이면 `VALIDATION_FAILED` 로 거절한다 |
| 제목 | 고정 글 「대화방」. 첫 메시지가 제목을 채우지 않는다 |

**기본 비서는 주인이 요청자이고 profile 이 요청자의 기본 profile(`allowed_person.hermes_profile`)인 에이전트다.** 칸을 두지 않는다(`PrimaryAgents`).
에이전트 지우기는 profile 이 허용 목록의 누군가의 기본 profile 인 에이전트를 주인도 `ADMIN` 도 지우지 못하게 `PRIMARY_AGENT_PROTECTED` 로 거절한다. 그런 에이전트는 첫 로그인이 만든 것뿐이다. 끄기는 관리자 경로에만 있다. 사람을 허용 목록에서 지울 때의 정리는 이 판정을 타지 않는다.

### 날이 바뀐 첫 메시지

대화방에서 사용자가 보낸 turn 이 질문을 저장하기 전에 `RoomContext` 가 판정한다. 다시 만들기, `@` 맡김, 자동 결과 전달, 예약 turn 은 판정하지 않는다.

1. 대화의 지금 루트 session 으로 돈 루트 실행 가운데 가장 이른 `started_at` 의 Asia/Seoul 날짜가 오늘보다 앞이면 날을 넘었다. 실행이 없으면 넘지 않았다
2. 넘었으면 대화의 에이전트와 대화가 고른 모델로 옛 보낼 session 에 요약 요청(첫 줄 `[대화 요약 요청]`)을 보낸다. 대화 밖 실행으로 남겨 그 대화의 `memory_remember` 바로 저장 판정([기억](memory.md) 의 「바로 저장 판정」 5)에 걸리지 않는다. 옛 루트에 성공한 실행이 없으면 앞 요약도 요청에 함께 넣는다
3. 성공하면 알림 줄 「어제까지 이야기를 요약해 이어요」(빈 줄 뒤에 요약, 4,000자까지)을 질문 앞에 남기고 두 session 칸을 새 `fos-<uuid>` 로 바꾼다(`ConversationSessions.renew`). 화면은 첫 줄과 「요약 보기」 펼치기로 그린다
4. 실패하면 아무것도 바꾸지 않고 옛 session 으로 turn 을 잇는다. 루트가 그대로라 다음 메시지가 다시 요약한다

요약은 새 루트 session 에 성공한 실행이 생길 때까지 사용자가 보낸 turn 과 다시 만들기의 입력 앞에 「[어제까지의 대화 요약]」 머리와 `<external-data>` 로 감싸 붙인다. 지난 메시지는 지우지 않는다.
요약 실행은 turn 잠금을 쥔 채 돌고 중지 단추로 멈추지 못한다. 도구를 부르면 부모 실행을 찾지 못해 거절된다.

## 전문 에이전트 만들기

연결 하나와 그 플러그인의 스킬만 담은 비공개 에이전트를 한 요청으로 만든다(`POST /api/v1/connections/{connectorId}/specialist`, `SpecialistAgentCreator`). 도구는 새 에이전트 기본값이다.
요청은 이름, 소개, 아이콘, 색과 `detach` 이고, 응답은 새 에이전트의 번호와 이름, 새 에이전트에서 본 그 연결(`connection`), 연결을 뗀 에이전트들(`detachedFrom`)이다.

| 자리 | 무엇을 하나 |
| --- | --- |
| 연결을 마친 화면 | 그 연결이 아직 어느 에이전트에도 붙지 않았으면 제안이 먼저 뜬다([플러그인 연결](connector.md) 의 「연결 뒤 전문 에이전트 제안」). 붙은 곳이 없으니 그 서비스가 붙은 전문 에이전트도 없다 |
| 에이전트 목록 | 「전문 에이전트 만들기」 가 준비된 내 연결을 고르게 하고 같은 만들기 화면을 연다. 만들면 그 에이전트 상세로 간다 |
| 만들기 화면 | 이름 「{플러그인} 담당」, 소개 「{플러그인} 일을 맡아요」, 아이콘 🤖 를 미리 채운다. 그 연결이 붙은 다른 에이전트가 있으면 「「{이름}」에서는 떼고 맡기기만」 칸을 켜 둔다. 기본 비서는 이름이 「비서」 라 「「비서」에서는…」 으로 보인다. `single_binding` 플러그인은 칸을 켠 채 잠근다 |

**순서는 만들기(소개 포함), 성격 틀 쓰기, 붙이기, 떼기다.** `single_binding` 이면 붙이기 전에 떼고, 아니면 붙인 뒤에 뗀다. 붙이기가 실패해도 쓰던 에이전트가 그대로이게 하려는 것이다.
목록 밖 플러그인, 준비되지 않은 연결, 떼기 없는 `single_binding`, 개수 상한은 아무것도 만들기 전에 거절한다.
그 뒤 단계에서 실패하면 새 에이전트를 지우고(바인딩, profile, 성격 틀이 함께 거둬진다) 먼저 뗀 에이전트에 다시 붙인 뒤 원래 오류를 돌려준다. 화면은 그 까닭에 「만들던 에이전트를 거뒀어요」 와, 거두기도 실패했을 때를 위해 목록에 남아 있으면 지우라는 안내를 붙인다. 아무것도 만들기 전의 거절에는 붙이지 않는다.

성격 틀은 「너는 「{이름}」이다. 「{플러그인}」 일만 맡는 전문 에이전트다」 와 소개, 서비스 밖의 일은 할 수 없다고 짧게 답하라는 한 줄이다. 사용자는 상세의 성격에서 고친다.
비서는 떼어 낸 서비스를 `agent_list` 의 소개와 할 수 있는 일을 보고 맡긴다. 늘 맡긴다고 보장하지 않는다(spec DECISION22).
