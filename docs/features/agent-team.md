# 에이전트 팀

사용자가 에이전트를 이름표와 소개로 구분해 보고, 기본 비서와 전문 에이전트가 나눠 일하는 기능이다. 합의한 변경과 단계는 [스펙](agent-team/spec.md)과 [설계](agent-team/design.md)가 갖고, 이 파일은 지금 동작하는 것만 적는다.

covers: `backend/src/main/java/com/bifos/assistant/agent/domain/AgentIntroduction.java`, `backend/src/main/java/com/bifos/assistant/agent/domain/type/AgentColor.java`, `backend/src/main/java/com/bifos/assistant/agent/application/AgentCapabilitiesReader.java`, `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorNameLookup.java`, `web/src/components/agent/agent-badge.tsx`, `web/src/components/agent/agent-introduction-section.tsx`, `web/src/lib/agent-badge.ts`, `backend/src/main/java/com/bifos/assistant/chat/turn/application/DelegationLines.java`, `backend/src/main/java/com/bifos/assistant/chat/nextturn/application/DelegationLineNotifier.java`, `web/src/components/chat/delegation-lines.tsx`, `web/src/lib/delegation.ts`, `backend/src/main/java/com/bifos/assistant/connector/connection/application/SpecialistAgentCreator.java`, `web/src/lib/specialist-agent.ts`, `web/src/components/agent/specialist-agent-form.tsx`, `web/src/components/agent/specialist-agent-offer.tsx`, `web/src/components/agent/specialist-agent-dialog.tsx`, `web/src/components/agent/connector-onboarding.tsx`

## 요구

- 에이전트마다 한 줄 소개, 아이콘, 색이 있다. 답, 대화 머리, 새 대화 카드, 에이전트 목록, `@` 목록이 같은 이름표를 그린다.
- 위임 도구 `agent_list` 가 소개와 할 수 있는 일을 실어, 맡기는 에이전트가 그것을 보고 고른다([에이전트와 스킬](agent-skill.md) 의 「다른 에이전트에게 맡기기」).
- 첫 로그인에 만든 에이전트가 그 사용자의 기본 비서이고 이름은 「비서」 다([사용자](users.md) 의 「첫 에이전트의 기본 도구」).
- 서비스를 처음 연결하면 그 연결 하나만 맡는 전문 에이전트를 만들자고 제안하고, 받으면 한 번에 만든다. 에이전트 목록의 「전문 에이전트 만들기」 로도 만든다. 아래 「전문 에이전트 만들기」.
- 대화의 turn 이 다른 에이전트에게 맡기면 그 답 아래에 맡긴 줄이 보이고, 결과 도착 줄과 위임 자식의 승인 카드에 맡은 에이전트의 이름표가 붙는다. 아래 「대화 안의 협업」.

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
그 뒤 단계에서 실패하면 새 에이전트를 지우고(바인딩, profile, 성격 틀이 함께 거둬진다) 먼저 뗀 에이전트에 다시 붙인 뒤 원래 오류를 돌려준다. 화면은 그 까닭에 「만든 것은 거뒀어요」 를 붙인다.

성격 틀은 「너는 「{이름}」이다. 「{플러그인}」 일만 맡는 전문 에이전트다」 와 소개, 서비스 밖의 일은 할 수 없다고 짧게 답하라는 한 줄이다. 사용자는 상세의 성격에서 고친다.
비서는 떼어 낸 서비스를 `agent_list` 의 소개와 할 수 있는 일을 보고 맡긴다. 늘 맡긴다고 보장하지 않는다(spec DECISION22).
