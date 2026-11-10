# 에이전트 팀

사용자가 에이전트를 이름표와 소개로 구분해 보고, 기본 비서와 전문 에이전트가 나눠 일하는 기능이다. 합의한 변경과 단계는 [스펙](agent-team/spec.md)과 [설계](agent-team/design.md)가 갖고, 이 파일은 지금 동작하는 것만 적는다.

covers: `backend/src/main/java/com/bifos/assistant/agent/domain/AgentIntroduction.java`, `backend/src/main/java/com/bifos/assistant/agent/domain/type/AgentColor.java`, `backend/src/main/java/com/bifos/assistant/agent/application/AgentCapabilitiesReader.java`, `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorNameLookup.java`, `web/src/components/agent/agent-badge.tsx`, `web/src/components/agent/agent-introduction-section.tsx`, `web/src/lib/agent-badge.ts`

## 요구

- 에이전트마다 한 줄 소개, 아이콘, 색이 있다. 답, 대화 머리, 새 대화 카드, 에이전트 목록, `@` 목록이 같은 이름표를 그린다.
- 위임 도구 `agent_list` 가 소개와 할 수 있는 일을 실어, 맡기는 에이전트가 그것을 보고 고른다([에이전트와 스킬](agent-skill.md) 의 「다른 에이전트에게 맡기기」).
- 첫 로그인에 만든 에이전트가 그 사용자의 기본 비서이고 이름은 「비서」 다([사용자](users.md) 의 「첫 에이전트의 기본 도구」).

## 소개와 할 수 있는 일

에이전트마다 한 줄 소개, 아이콘, 색을 둔다. 주인과 `ADMIN` 이 상세의 「소개」 절(성격 다음)에서 이름과 함께 고친다(`PATCH /api/v1/agents/{code}/introduction`). `ADMIN` 은 지우기처럼 남의 비공개 에이전트에도 닿는다.
고를 수 있는 아이콘과 색, 소개 상한은 `AgentIntroduction` 과 `AgentColor` 가 갖고, 화면의 `web/src/lib/agent-badge.ts` 가 같은 목록을 갖는다. 목록 밖 값과 상한을 넘는 소개는 `VALIDATION_FAILED` 다.
저장은 에이전트 행을 잠근 뒤 한다. `Agent` 는 저장할 때 모든 칸을 쓰므로 잠그지 않으면 그 사이 바뀐 공개 범위나 주인을 옛 값으로 덮는다.

**할 수 있는 일은 저장하지 않고 읽을 때 모은다.** 연결과 바인딩이 모두 `READY` 인 플러그인의 이름(카탈로그의 `title`)과 올린 스킬 이름이다. 올린 스킬에는 게시가 timeout 으로 끝나 표식 없이 남은 더 새 버전도 든다.
에이전트 목록(`GET /api/v1/agents`)과 `agent_list` 가 `AgentCapabilitiesReader` 로 같은 값을 쓴다. 카탈로그나 스킬 디렉터리를 읽지 못하면 실패하지 않고 그 칸을 비운다.

| 자리 | 무엇을 보이나 |
| --- | --- |
| 답과 대화 머리 | 그 대화의 에이전트 아이콘·색·이름. 값은 대화 줄(`ConversationView` 의 `agentIcon`, `agentColor`)에서 온다. 이름표는 늘 지금 값이다. 좁은 화면의 잠긴 대화는 이름 대신 이름표만 보인다 |
| 새 대화 카드, 에이전트 목록 | 이름표, 이름, 소개 한 줄과 그 아래 할 수 있는 일. 소개가 없으면 할 수 있는 일만 보인다 |
| `@` 목록 | 이름표와 이름, 소개 한 줄 |
| 아이콘이나 색을 고르지 않았다 | 이름 첫 글자와 `code` 로 정한 자동 색 |

