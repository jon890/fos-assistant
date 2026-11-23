# 비서 중심 대화

사용자는 늘 기본 비서와만 대화하고, 전문 에이전트는 비서가 불러 맡기는 기능이다. 합의한 변경과 단계는 [스펙](assistant-hub/spec.md)과 [설계](assistant-hub/design.md)가 갖고, 이 파일은 지금 동작하는 것만 적는다.

covers: `backend/src/main/java/com/bifos/assistant/orchestration/application/DelegationWaiter.java`, `backend/src/main/java/com/bifos/assistant/orchestration/application/ChildRunEvents.java`, `backend/src/main/java/com/bifos/assistant/orchestration/application/ChildRunRecording.java`, `backend/src/main/java/com/bifos/assistant/chat/nextturn/application/DelegationActivityNotifier.java`, `backend/src/main/java/com/bifos/assistant/chat/nextturn/application/DelegationActivityEvents.java`, `web/src/components/chat/activity/activity-delegation.ts`, `backend/src/main/java/com/bifos/assistant/chat/conversation/application/ConversationEntry.java`, `web/src/components/chat/read-only-notice.tsx`, `backend/src/main/java/com/bifos/assistant/orchestration/application/SkillDelegations.java`, `backend/src/main/java/com/bifos/assistant/chat/nextturn/application/SkillDelegationQuestions.java`, `web/src/components/chat/use-skill-delegation.ts`

## 요구

- 대화 turn 이 다른 에이전트에게 맡기면 비서는 정한 시간까지 그 결과를 기다려 답 하나로 낸다. 넘으면 짧은 안내로 답을 끝내고 결과는 다음 답으로 온다. 아래 「맡긴 일 기다리기」.
- 맡은 에이전트가 쓴 도구는 실행 트리와 비서 답의 작업 과정에 그 에이전트의 도우미 줄 아래로 보인다. 아래 「맡긴 실행의 도구 줄」.
- 새 대화는 늘 기본 비서와 시작하고, `@` 로 바로 맡기는 길과 「대화하기」 단추는 없다. 다른 에이전트의 일은 이름을 부르거나 바꿔 달라고 해도 비서가 맡긴다. 아래 「입구를 비서로 모으기」.
- 에이전트가 기본 비서가 아닌 지난 대화는 읽기만 하고, 예약 작업은 만들거나 고칠 때 기본 비서로 돈다. 아래 「입구를 비서로 모으기」.
- 연결은 전문 에이전트에만 붙인다. 비서 대화의 붙이기 승인 카드는 그 서비스만 맡는 새 전문 에이전트를 만들어 붙인다. 아래 「연결은 전문 에이전트에만」.
- 비서 대화의 `/` 목록에 맡길 수 있는 전문 에이전트의 스킬이 「에이전트 · 스킬」 로 함께 보이고, 골라 보내면 대화 turn 없이 그 에이전트에게 맡긴다. 예약 작업 지시도 같은 꼴로 맡긴다. 아래 「스킬 맡기기」.

## 맡긴 일 기다리기

**대화 turn 의 루트 실행이 `agent_delegate` 를 부르면 Control Plane 이 그 도구 호출 안에서 결과를 기다린다**([ADR-20261121 / delegate-wait](../../backend/docs/adr/ADR-20261121-delegate-wait.md)).

| 경우 | 도구 결과 |
| --- | --- |
| 상한 안에 끝남(실패와 취소 포함) | `agent_status` 와 같은 모양의 결과. 전했다고 적어 결과 도착 줄과 자동 답이 생기지 않는다 |
| 상한을 넘김 | `RUNNING` 과 「누구에게 무엇을 맡겼는지 한 줄로 끝내고 결과를 짐작해 쓰지 않는다」 는 `note`. 결과는 [에이전트와 스킬](agent-skill.md) 의 「위임 결과가 도착했을 때」 대로 다음 답이 전한다 |
| 손자 위임, 먼저 살펴보기, 상한 0 | 기다리지 않고 지금처럼 번호와 `RUNNING` |
| 기다리는 동안 사용자가 답을 중지함 | 곧 풀리고 전했다고 적지 않는다 |

상한은 `assistant.delegation.delegate-wait-max`(기본 2분)이고 한 답에서 처음 기다리기 시작한 때부터 흐른 시간으로 센다. Hermes 는 Control Plane 도구를 하나씩 부르므로 같은 답의 맡김은 차례로 남은 시간만 기다린다. 판정은 `DelegationWaiter` 가 갖는다.

## 맡긴 실행의 도구 줄

맡긴 실행도 Hermes 사건 스트림을 열어 그 실행 줄에 도구 사건을 남긴다(`ChildRunEvents`, [실행 기록](execution.md) 의 「사건 옮겨 적기」).
대화의 turn 이 바로 맡긴 실행의 도구 사건은 대화 단위 SSE 의 `delegation_activity` 로 가고([대화와 대기열](chat.md) 의 「화면으로 보내는 사건」), 화면은 도는 답의 작업 과정에 맡은 에이전트의 도우미 줄을 두고 그 아래 도구 줄을 들여 보인다.
답이 끝날 때 아직 도는 맡긴 실행은 「맡겨 둠」 이고 답을 실패로 세지 않는다. 실행 트리의 노드는 `delegated` 칸으로 맡긴 실행을 알려 새로 고쳐도 같게 그린다.

## 입구를 비서로 모으기

**새 대화는 늘 요청자의 기본 비서와 시작한다.** 기본 비서는 [에이전트 팀](agent-team.md) 의 「대화방」 이 정한 대로 허용 목록이 준 profile 을 가리키는 에이전트다(`PrimaryAgents`).

| 자리 | 동작 |
| --- | --- |
| 새 대화와 빈 대화 만들기 | `ConversationEntry` 가 기본 비서로 정한다. 요청의 `agentCode` 가 비었거나 기본 비서면 받고, 다른 코드면 `VALIDATION_FAILED` 다. 기본 비서가 없으면 `AGENT_NOT_FOUND`, 꺼졌으면 `AGENT_DISABLED` 다. 이어 쓰는 대화는 그 대화의 에이전트로 돈다 |
| 새 대화 화면 | 기본 비서의 이름표만 보이고 고르는 목록이 없다. `?agent=` 는 읽지 않는다. 기본 비서가 목록에 없으면 「비서를 쓸 수 없어요. 관리자에게 알려 주세요」 를 보이고 입력창을 잠근다([대화와 대기열](chat.md) 의 「새 대화 화면」) |
| `@` | 입력창이 목록을 띄우지 않고, 보내기의 `delegateTo` 는 `VALIDATION_FAILED` 다. 지난 맡김은 [에이전트 팀](agent-team.md) 의 「지난 `@` 맡김」 대로 보인다 |
| 「대화하기」 | 에이전트 상세, 전문 에이전트 만들기 제안, 연결 뒤 고르기에 없다 |
| 지난 대화 | 에이전트가 기본 비서가 아닌 대화는 읽기 전용이다. 사람이 보내는 요청은 `VALIDATION_FAILED` 이고 자동 turn 은 막지 않는다. 화면은 입력창 대신 안내와 대화방 링크를 보인다([대화와 대기열](chat.md) 의 「이어 쓸 수 없는 대화」) |
| 예약 작업 | 화면과 `task_` 도구로 만들거나 고치면 에이전트가 기본 비서가 된다. 화면에 에이전트 칸이 없고 요청의 `agentCode` 는 읽지 않는다. 멈추기와 다시 켜기는 에이전트를 바꾸지 않는다([예약 작업](schedule.md) 의 「작업」) |
| 지시문 | 「맡길 수 있는 에이전트」 구역이 이름을 말하며 맡기라는 부탁은 그 에이전트에게 맡기라고 한다(`DelegationTargetsInstructions`). 서비스 개념 목록은 에이전트를 바꿔 달라는 부탁에 바꿨다고 하지 않고 맡겨 처리한다고 답하고 링크를 붙이지 않게 한다(`ServiceConceptsInstructions`) |
| 평가 세트 | 서비스 개념 묶음(`backend/src/main/resources/quality/bundles/service-concepts.json`, `test/model-eval/concepts/questions.json`)의 바꾸기 문항은 `/new?agent=` 링크를 실패로 세고, 이름으로 맡기는 문항은 `agent_delegate` 를 기대한다 |

블로그 사진 평가(`test/model-eval/blog`)처럼 전문 에이전트와 새 대화를 여는 평가 스크립트는 이 변경 뒤 새 대화를 열지 못한다. 비서를 거치게 고치는 일은 남은 일이다.

## 연결은 전문 에이전트에만

**기본 비서에는 연결을 새로 붙이지 않는다.** 배포 전부터 비서에 붙은 연결은 그대로 쓰고 뗄 수만 있다. 비서에서 떼고 전문 에이전트에 맡기려면 연결 뒤 「전문 에이전트 만들기」 의 떼기 칸을 쓴다.

| 자리 | 동작 |
| --- | --- |
| 화면의 붙이기(`PUT /agents/{code}/connections/{id}`) | 기본 비서면 `PRIMARY_AGENT_NO_CONNECTIONS`(409)로 거절한다(`AgentConnectionController`). 떼기는 그대로다 |
| 에이전트 상세의 연결 절 | 비서면 목록 응답의 까닭이 `PRIMARY_AGENT` 이고, 화면은 붙이기와 「외부 서비스 연결하기」 를 숨기고 맡긴다는 안내와 떼기만 보인다 |
| 연결 뒤 에이전트 고르기 | 후보에 비서가 없다([외부 서비스 연결](connector.md) 의 「연결 뒤 에이전트 고르기」) |
| 비서 대화의 `connector_attach` | 그 연결이 붙은 전문 에이전트(쓸 수 있고 켜짐)가 있으면 카드 없이 `specialist_attached` 와 그 에이전트를 돌려주고 모델이 `agent_delegate` 로 맡긴다. 그 붙임이 반영을 기다리는 중이면(`agent_status` 가 `pending`) 맡기지 않고 조금 뒤에 쓸 수 있다고 알린다. 비서에 붙어 있으면 `already_attached` 다. 쓸 수 없는 전문 에이전트에만 붙어 있으면 카드 없이 `specialist_unavailable` 과 연결 화면 링크다. 그 밖에는 「새 전문 에이전트를 만들어 붙이기」 카드를 띄운다. 만들 이름은 `<서비스 이름> 담당` 이다 |
| 카드 승인 | `SpecialistAgentCreator` 로 소개(「<서비스> 일을 맡아요」, 🤖)가 채워진 비공개 전문 에이전트를 만들어 붙이고, 실패하면 만든 것을 거둔다. 알림 줄은 「「Gmail 담당」 를 만들어 「Gmail」 를 붙였어요」 와 언제 쓸 수 있는지다. 배포 전에 뜬 옛 카드는 붙이지 않고 다시 부탁해 달라고 알린다 |

## 스킬 맡기기

**비서 대화의 `/` 목록에서 전문 에이전트의 스킬을 고르면 Control Plane 이 모델 판단 없이 그 에이전트에게 그 스킬로 맡긴다.** 대화 turn 은 열지 않아 보낸 뒤 바로 다음 질문을 받는다.

| 자리 | 동작 |
| --- | --- |
| 목록(`GET /chat/skill-delegations/targets`) | 요청자가 쓸 수 있고 켜진 에이전트(`agent_list` 와 같은 기준)에서 기본 비서를 뺀 에이전트마다, 비서의 `/` 목록과 같은 기준의 켜진 스킬을 그 설명과 함께 준다(`skills` 의 줄마다 `name`, `description`). 이름이 겹치거나 가운뎃점, 줄바꿈이 든 이름의 에이전트는 어느 쪽인지 글로 정하지 못해 뺀다. 웹은 대화의 에이전트가 기본 비서일 때만 읽어 비서 스킬 뒤에 「에이전트 이름 · 스킬」 로 붙이고, 줄마다 이름 아래 설명 한 줄을 보인다(DECISION29) |
| 고르기 | 입력창에 `/<에이전트 이름> · <스킬> ` 이 들어간다. 이 글 꼴은 `DelegatedSkillCommand` 가 정하고, 비서의 `/이름` 보다 먼저 본다 |
| 보내기(`POST /chat/skill-delegations`) | 글이 목록의 「에이전트 · 스킬」 로 시작하면 웹이 이 API 로 보낸다. 판정은 대화(주인, 읽기 전용, 없으면 기본 비서로 새 대화), 에이전트(쓸 수 있고 켜짐, 비서가 아님), 스킬, 사용자 자리와 서버 전체 위임 한도 차례다. 다 지나면 질문을 남기고 부모 없는 위임 실행을 띄워 실행 줄이 생기면 202 다. 맡긴 실행은 사용자 동시 실행 한도에서 자식 실행처럼 센다. 실패하면 남긴 질문을 지운다. 답이 도는 동안에도 대기 줄 대신 바로 보낸다. 응답 전에 다시 누르면 한 번만 보내고, 사진이나 답장을 집었으면 맡기지 않고 안내한다 |
| 맡긴 실행 | 부모가 없고 위임 키와 질문 번호가 있다. 지난 `@` 맡김과 같은 모양이라 질문 아래 「나 → 커리어에게 맡김」 줄과 상태가 보이고 다시 생성은 막힌다. 끝나면 지금 깨우기가 결과 도착 줄과 비서 답을 연다(아직 전하지 않은 결과 조회가 부모 없는 위임 실행도 읽는다). 기동 정리는 위임 실행으로 다룬다 |
| 예약 작업 | 지시가 `/<에이전트 이름> · <스킬>` 로 시작하면 발화가 사용자 자리 없이 대화 잠금만 잡고(맡긴 실행이 한도를 센다) 시작 알림 줄과 지시를 남기고 이름으로 찾은 에이전트를 사람이 없는 실행으로 그 스킬로 돌려 그 실행으로 발화를 닫는다. 결과는 발화가 끝난 뒤 깨우기가 비서 답으로 전한다. 에이전트가 없거나(같은 이름이 둘이어도) 스킬이 켜져 있지 않으면 발화가 `FAILED` 다([예약 작업](schedule.md) 의 「작업」) |

## 배포 뒤 확인

Hermes 와 주고받는 것을 바꿨으므로 배포한 뒤 평가 계정으로 한 번씩 왕복한다. 항목과 통과 기준은 [스펙](assistant-hub/spec.md) 의 「배포 뒤 왕복 확인」 이 갖고, 실행 방법과 운영 값은 `fos-home-infra` 가 갖는다.
