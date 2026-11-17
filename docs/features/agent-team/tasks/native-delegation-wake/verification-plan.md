# 내장 위임 결과(PR6) 인수 검증 계획

스펙 `docs/features/agent-team/spec.md` 의 REQ10~REQ14 가운데 Control Plane 과 화면이 맡는 동작이다. fos-ctx 가 끝 소식을 보내는 왕복은 다음 PR 의 검증이다.

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ10 | 깨울 자식으로 등록한 줄(새 등록 칸 포함)과 대화 | 등록 경로와 끝 소식 경로를 부른 뒤 사건을 내고 이력과 가짜 Hermes 입력을 본다. `NativeDelegationDeliveryTest`, `SubagentSessionEndpointTest` | 자동 turn 하나가 열리고 입력에 결과 글이 `<external-data>` 안에 있으며, 「하위 작업이 끝났어요 · <맡긴 일>」 알림 줄과 그 뒤 답이 남는다 | 격리된 Control Plane 통합 테스트(H2, 가짜 Hermes) | worker | 없음 | PR 전 |
| REQ10 | `@` 맡김 turn, `agent_delegate` 자식 실행, 점검(`CHECK`) 대화에서 시작한 자식, 손자 자식 | 각 경우로 등록하고 끝 소식을 보낸다. `SubagentSessionRegistrarTest`(판정), `SubagentSessionEndpointTest`(`ignored` 와 칸이 비어 있음), `NativeDelegationResultSourceTest`(깨울 자식이 아니면 결과를 내지 않음) | `wake_eligible` 이 거짓이고 끝 소식이 `ignored` 이며 자동 turn 과 알림 줄이 없다 | 격리된 Control Plane 통합 테스트 | worker | 없음 | PR 전 |
| REQ11 | 같은 origin 의 깨울 자식 둘, 도는 turn 이 있는 대화, 자동 turn 수가 상한에 닿은 대화 | 두 끝 소식을 잇달아 적고 사건을 낸다. 도는 turn 이 닫힌 뒤를 본다. `NativeDelegationDeliveryTest` | 자동 turn 하나에 결과 둘, 알림 줄 둘. 도는 turn 뒤에 한 번에 전해진다. 끝난 지 5초가 안 된 결과는 내지 않는다(`NativeDelegationResultSourceTest`). 상한에 닿았으면 자동 turn 없이 한도 알림 줄만 남는다 | 격리된 Control Plane 통합 테스트 | worker | 없음 | PR 전 |
| REQ12 | 실패 상태의 끝 소식, 1시간 소식 없는 자식, 중지한 turn 의 자식 | 실패 소식으로 깨우고(`NativeDelegationDeliveryTest`), `sweepOnce` 에 시각을 넘긴다(`NativeDelegationSweepTest`) | 실패면 「하위 작업을 하지 못했어요 · …」 와 자동 답. 기한이면 알림 줄 하나(자식 수에 따른 스펙 글)이고 다시 돌아도 하나, 뒤에 끝나면 자동 답 대상이 된다. 중지면 중지 알림 줄 하나이고 자동 답이 없다 | 격리된 Control Plane 통합 테스트(고정 시계) | worker | 없음 | PR 전 |
| REQ13 | 대기 글이 있는 대화, 끝 소식 31초 뒤 시작한 사용자 turn | 사건을 내고 다음 turn 을 본다. `NativeDelegationDeliveryTest` | 대기 글 turn 이 먼저 돈다. 이미 읽은 결과는 자동 turn 과 알림 줄이 없다. steer 는 입력창 글만 받는 기존 `SteerService` 를 바꾸지 않으므로 이 PR 에서 따로 보지 않는다 | 격리된 Control Plane 통합 테스트 | worker | 없음 | PR 전 |
| REQ14 | 옛 본문(새 칸 없음)의 등록, 설정 꺼짐 | 옛 본문으로 등록하고 훑기를 돌린다. 설정을 끈 채 끝 소식을 보낸다. `SubagentSessionEndpointTest`, `NativeDelegationSweepDisabledTest`, `SubagentStopRecorderTest` | 옛 등록은 `201` 이고 `wake_eligible` 이 거짓이며 기한 알림이 없다. 설정을 끄면 결과를 적되 전달 시각도 적어 켠 뒤에도 깨우지 않는다 | 격리된 Control Plane 통합 테스트 | worker | 없음 | PR 전 |
| REQ14 | `nativeResult` 가 있는 이력과 SSE 사건 | 대화를 열고 새로 고친다. `chat-delegation-wake.spec.ts`, `NativeResultHistoryTest` | 대화 에이전트의 이름표와 저장된 글로 결과 줄이 보이고 새로 고쳐도 같다. 다시 전달 뒤에도 같다. 기한·중지 알림 줄은 글만 보인다 | 가짜 API 의 브라우저 테스트, Control Plane 통합 테스트 | worker | 없음 | PR 전 |
| REQ14 | PR6 만 배포된 운영 | 운영 배포 확인 절차(`fos-home-infra`)로 `delegate_task` 를 쓰는 대화 한 번을 돌리고 등록 줄과 대화를 읽기 전용으로 본다 | 위임을 써도 자동 답과 알림 줄이 생기지 않고, 하위 에이전트 등록이 지금처럼 되며 새 등록의 `wake_eligible` 이 거짓이다(스펙 DECISION35). 1시간 뒤에도 알림 줄이 없다 | 운영 | 코디네이터 | 배포 | PR 뒤 |
