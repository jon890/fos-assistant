# 비서 중심 대화

사용자는 늘 기본 비서와만 대화하고, 전문 에이전트는 비서가 불러 맡기는 기능이다. 합의한 변경과 단계는 [스펙](assistant-hub/spec.md)과 [설계](assistant-hub/design.md)가 갖고, 이 파일은 지금 동작하는 것만 적는다.

covers: `backend/src/main/java/com/bifos/assistant/orchestration/application/DelegationWaiter.java`, `backend/src/main/java/com/bifos/assistant/orchestration/application/ChildRunEvents.java`, `backend/src/main/java/com/bifos/assistant/orchestration/application/ChildRunRecording.java`, `backend/src/main/java/com/bifos/assistant/chat/nextturn/application/DelegationActivityNotifier.java`, `backend/src/main/java/com/bifos/assistant/chat/nextturn/application/DelegationActivityEvents.java`, `web/src/components/chat/activity/activity-delegation.ts`

## 요구

- 대화 turn 이 다른 에이전트에게 맡기면 비서는 정한 시간까지 그 결과를 기다려 답 하나로 낸다. 넘으면 짧은 안내로 답을 끝내고 결과는 다음 답으로 온다. 아래 「맡긴 일 기다리기」.
- 맡은 에이전트가 쓴 도구는 실행 트리와 비서 답의 작업 과정에 그 에이전트의 도우미 줄 아래로 보인다. 아래 「맡긴 실행의 도구 줄」.
- 새 대화의 에이전트 고르기를 없애고 입구를 비서로 모으는 일, 연결과 스킬을 비서 대화에서 쓰는 일은 아직 만들지 않았다([스펙](assistant-hub/spec.md)의 PR2, PR3).

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

## 배포 뒤 확인

Hermes 와 주고받는 것을 바꿨으므로 배포한 뒤 평가 계정으로 한 번씩 왕복한다. 항목과 통과 기준은 [스펙](assistant-hub/spec.md) 의 「배포 뒤 왕복 확인」 이 갖고, 실행 방법과 운영 값은 `fos-home-infra` 가 갖는다.
