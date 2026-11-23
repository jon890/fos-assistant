# 비서 중심 대화

사용자는 늘 기본 비서와만 대화하고, 전문 에이전트는 비서가 불러 맡기는 기능이다. 합의한 변경과 단계는 [스펙](assistant-hub/spec.md)과 [설계](assistant-hub/design.md)가 갖고, 이 파일은 지금 동작하는 것만 적는다.

covers: `backend/src/main/java/com/bifos/assistant/orchestration/application/DelegationWaiter.java`, `backend/src/main/java/com/bifos/assistant/orchestration/application/ChildRunEvents.java`, `backend/src/main/java/com/bifos/assistant/orchestration/application/ChildRunRecording.java`, `backend/src/main/java/com/bifos/assistant/chat/nextturn/application/DelegationActivityNotifier.java`, `backend/src/main/java/com/bifos/assistant/chat/nextturn/application/DelegationActivityEvents.java`, `web/src/components/chat/activity/activity-delegation.ts`, `backend/src/main/java/com/bifos/assistant/chat/conversation/application/ConversationEntry.java`

## 요구

- 대화 turn 이 다른 에이전트에게 맡기면 비서는 정한 시간까지 그 결과를 기다려 답 하나로 낸다. 넘으면 짧은 안내로 답을 끝내고 결과는 다음 답으로 온다. 아래 「맡긴 일 기다리기」.
- 맡은 에이전트가 쓴 도구는 실행 트리와 비서 답의 작업 과정에 그 에이전트의 도우미 줄 아래로 보인다. 아래 「맡긴 실행의 도구 줄」.
- 새 대화는 늘 기본 비서와 시작하고, `@` 로 바로 맡기는 길과 「대화하기」 단추는 없다. 다른 에이전트의 일은 이름을 부르거나 바꿔 달라고 해도 비서가 맡긴다. 아래 「입구를 비서로 모으기」.
- 지난 전문 에이전트 대화를 읽기 전용으로 막는 일, 예약 작업을 비서로 돌리는 일, 연결과 스킬을 비서 대화에서 쓰는 일은 아직 만들지 않았다([스펙](assistant-hub/spec.md)의 REQ5, REQ7, PR3).

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
| 지시문 | 「맡길 수 있는 에이전트」 구역이 이름을 말하며 맡기라는 부탁은 그 에이전트에게 맡기라고 한다(`DelegationTargetsInstructions`). 서비스 개념 목록은 에이전트를 바꿔 달라는 부탁에 바꿨다고 하지 않고 맡겨 처리한다고 답하고 링크를 붙이지 않게 한다(`ServiceConceptsInstructions`) |
| 평가 세트 | 서비스 개념 묶음(`backend/src/main/resources/quality/bundles/service-concepts.json`, `test/model-eval/concepts/questions.json`)의 바꾸기 문항은 `/new?agent=` 링크를 실패로 세고, 이름으로 맡기는 문항은 `agent_delegate` 를 기대한다 |

블로그 사진 평가(`test/model-eval/blog`)처럼 전문 에이전트와 새 대화를 여는 평가 스크립트는 이 변경 뒤 새 대화를 열지 못한다. 비서를 거치게 고치는 일은 남은 일이다.

## 배포 뒤 확인

Hermes 와 주고받는 것을 바꿨으므로 배포한 뒤 평가 계정으로 한 번씩 왕복한다. 항목과 통과 기준은 [스펙](assistant-hub/spec.md) 의 「배포 뒤 왕복 확인」 이 갖고, 실행 방법과 운영 값은 `fos-home-infra` 가 갖는다.
