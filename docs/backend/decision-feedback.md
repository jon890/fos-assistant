# 판단 피드백

사용자에게 보인 제안에 사용자가 어떻게 반응했고 실행이 어떻게 끝났는지를 남기고, 상황부터 결과까지 다시 읽는 읽기 모델을 낸다.
결정은 [ADR-20261007 / decision-feedback](../adr/ADR-20261007-decision-feedback.md)에 있다.
칸은 [`schema/feedback.md`](schema/feedback.md) 가 갖는다.
이 기록은 개인화 모델이 아니다. Memory, 할 일의 억제 규칙, 지금 화면의 판정을 바꾸지 않는다.
예외는 살펴보기 발견의 「관심 없음」 하나다. 같은 점검 대화의 digest 기간 안에서만 같은 주제를 내린다([ADR-20261008 / check-finding-reaction](../adr/ADR-20261008-check-finding-reaction.md)). 아래 「살펴보기 발견의 지금 반응」 을 본다.

## 이어지는 기록

| 단계 | 어디에 남는가 | 판단 피드백이 가리키는 방법 |
| --- | --- | --- |
| 상황 | `proactive_check` | `source_check_id`, 또는 `origin_execution_id` 가 그 살펴보기의 `root_execution_id` 와 같다 |
| 후보 | `proactive_check_problem` | 같은 살펴보기의 줄 |
| 판단 | `proactive_value_evaluation` | 같은 살펴보기의 평가. provider, 모델, 입력 버전은 `evidence_json` 이 갖는다 |
| 정책 | `proactive_autonomy_decision` | 같은 살펴보기의 판정. 규칙 버전은 `policy_version` 이다 |
| 사용자 반응과 실행 결과 | `decision_feedback_event` | 이 문서의 사건 |

## 사건

| 사건 | 뜻 | 주체 |
| --- | --- | --- |
| `SURFACED` | 사용자가 볼 수 있는 자리에 제안을 올렸다 | `AGENT`, `SYSTEM` |
| `ACCEPTED` | 제안을 받아들였다 | `USER` |
| `DISMISSED` | 제안을 숨겼거나, 받아들인 할 일을 그만뒀다 | `USER` |
| `POSTPONED` | 제안을 미뤘다 | `USER` |
| `EDITED` | 제안에서 온 할 일을 고쳤다 | `USER` |
| `APPROVED` | 승인 줄을 승인했다 | `USER` |
| `REJECTED` | 제안이나 승인 줄을 거절했다 | `USER` |
| `EXECUTION_SUCCEEDED` | 실행이 성공했다. 제안에서 온 할 일을 끝낸 것도 여기다 | `USER`, `SYSTEM` |
| `EXECUTION_FAILED` | 실행이 실패했거나 시작하지 못했다 | `SYSTEM` |

무응답은 사건이 아니다. `SURFACED` 뒤에 사용자 사건이 없다는 사실로만 읽는다.
승인 줄의 만료, 실행 결과를 모르는 `UNKNOWN`, 보고 열기도 사건을 남기지 않는다. 보고 열기는 `attention_event` 의 `OPENED` 가 센다.

### 기록 지점

| 제안 | 사건 | 자리 |
| --- | --- | --- |
| 할 일(`follow_up:<공개 식별자>`) | `SURFACED` | `FollowUpService.propose` 가 새 제안을 저장했을 때 |
| | `ACCEPTED` | `accept`, 그리고 같은 제목을 사람이 직접 더해 제안을 받아들였을 때(`DIRECT_CREATE`) |
| | `REJECTED` | `reject` |
| | `EDITED` | `update` 가 제목, 기한, 기다리는 중 가운데 하나를 바꿨을 때 |
| | `EXECUTION_SUCCEEDED` | `done` |
| | `DISMISSED` | `drop` |
| Memory 제안(`memory:<번호>`) | `SURFACED` | `MemoryService.proposeUser` 나 `memory_remember` 의 `MemoryCaptureService.remember` 가 새 제안을 저장했을 때. 바로 저장한 기억은 사용자 본인의 말이라 제안이 아니고 남기지 않는다 |
| | `ACCEPTED`, `REJECTED` | `PROPOSED` 줄의 `accept`, `reject`. 사용자가 대화에서 같은 사실을 직접 말해 `memory_remember` 가 제안을 받아들인 것도 `ACCEPTED` 다 |
| | `DISMISSED` | 사용자가 그 `memory_remember` 기록을 되돌려 제안 상태로 돌아갔을 때. `reason_code` 는 `MEMORY_UNDO` 다. 받아들임을 무른 마지막 결정이다 |
| 승인 줄(`connector_action:<공개 식별자>`) | `SURFACED` | 정책 판정이 `PENDING` 줄을 저장했을 때. 살펴보기 트리면 `source_check_id` 를 채운다 |
| | `APPROVED` | `approve`. 누른 때 이미 만료됐거나 그 뒤 실행하지 못하고 끝나도 사용자의 반응이라 남긴다 |
| | `REJECTED` | `reject` |
| | `EXECUTION_SUCCEEDED`, `EXECUTION_FAILED` | 실행 결과가 `SUCCEEDED`, `FAILED` 일 때. 실패는 커넥터가 선언한 오류 코드를 `reason_code` 에 둔다 |
| 지금 화면의 제안 항목 | `DISMISSED`, `POSTPONED` | `NEEDS_ME` 카드의 `FOLLOW_UP_PROPOSED`, `MEMORY_PROPOSED`, `APPROVAL_PENDING` 항목을 숨기거나 미뤘을 때. 열쇠는 그 `itemKey` 다. 제안의 대화(할 일, 승인 줄)나 제안한 실행(Memory)을 채우고, 원천을 찾지 못하면 남기지 않는다 |
| 살펴보기 발견(`check_finding:<번호>`) | `SURFACED` | 그 살펴보기의 `SURFACED` 를 남길 때 「새로 알릴 것」(`kind = NEW`) 발견마다. 주체는 `SYSTEM` 이다 |
| | `ACCEPTED`, `POSTPONED`, `DISMISSED` | 점검 대화의 발견 단추 「받아들임」, 「나중에」, 「관심 없음」. 지금 반응과 같은 단추를 다시 누르면 남기지 않는다 |
| 살펴보기(`proactive_check:<번호>`) | `SURFACED` | 사용자가 열었거나 매일 깨우기로 돈 살펴보기가 검사한 보고를 남기고 끝났을 때 |
| | `EXECUTION_SUCCEEDED`, `EXECUTION_FAILED` | `AUTONOMY` 살펴보기가 끝났을 때. 결과 블록을 읽지 못했으면 `INVALID_RESULT`, 멈추거나 실패했으면 그 오류 코드다. 기동 정리가 닫은 줄은 `INTERRUPTED` 다 |
| 행동 정책 판정(`autonomy_decision:<번호>`) | `EXECUTION_FAILED` | 자동 실행을 시작하지 못했을 때. 오류 코드를 `reason_code` 에 둔다 |

사용자가 직접 더한 할 일은 제안이 아니라 사건을 남기지 않는다. 사람이 고르는 지금 화면의 `FOLLOW_UP_OPEN` 항목도 같은 까닭으로 남기지 않는다.
알릴 것이 없어 보고를 남기지 않은 살펴보기는 침묵이다. 사건이 아니라 상황의 `reportSurfaced = false` 로 읽는다.
문제 후보와 행동 정책의 `SURFACE`, `ASK_APPROVAL` 은 사용자에게 제안으로 보이는 화면이 없어 `SURFACED` 를 남기지 않는다.
관리자 영역의 가치 평가 절은 관리자가 자기 평가 결과를 읽는 화면이고 제안이 아니므로 `SURFACED` 를 남기지 않는다.

### 남기는 방법

`DecisionFeedbackRecorder.record` 가 부르는 쪽의 트랜잭션이 커밋한 뒤 새 트랜잭션으로 한 줄을 넣는다.
살펴보기의 끝은 저장된 줄을 다시 읽어 판정한다. 끝난 상태를 저장하지 못했으면 남기지 않고 기동 정리가 닫을 때 남긴다.
되돌려진 동작의 사건은 남지 않는다. 기록이 실패해도 부르는 쪽으로 던지지 않고 사용자 번호와 종류, 예외 이름만 로그에 낸다.
실행 번호를 받으면 그 실행의 트리 루트와 대화를 채운다. 실행 줄이 없으면 그 칸을 비운다.
묶인 대화를 이미 지웠으면 넣지 않는다. 대화를 지운 뒤 그 대화의 제안에 반응하거나 실행이 끝나도 사건이 남지 않는다.
대화가 남았는지는 기록 트랜잭션 안에서 대화 줄을 공유 잠금으로 읽어 본다. 대화 삭제는 그 줄을 고친 뒤 같은 트랜잭션에서 사건을 지우므로, 삭제가 먼저 커밋되면 지운 대화로 보고 버리고, 기록이 먼저 잠그면 삭제가 기다렸다가 그 사건까지 지운다.

제목, 본문, 인자, 결과 글, 모델이 쓴 글은 받지 않는다.
판(`subject_version`)은 할 일이면 `title_key`, Memory 면 판 번호, 승인 줄이면 인자 해시다. 고친 칸은 `TITLE`, `DUE_AT`, `WAITING` 같은 이름만 남긴다.

## 반응 읽기

반응은 저장하지 않는다. replay 읽기 모델이 제안 하나의 사건을 그때마다 읽는다. 규칙 버전은 `FeedbackLabeler.VERSION` 이고 지금은 2다.

1. 사용자 사건 가운데 처음 나온 `ACCEPTED`, `APPROVED` 는 `ACCEPTED`, 처음 나온 `REJECTED` 와 할 일의 그만둠(`DISMISSED`)은 `DECLINED` 다
2. 그런 사건이 없고 `POSTPONED` 나 지금 화면의 숨기기(`DISMISSED`, `ATTENTION_HIDE`)가 있으면 `DEFERRED` 다
3. 그것도 없고 `SURFACED` 가 있으면 `NO_RESPONSE` 다
4. 보인 기록이 없으면 `NOT_SURFACED` 다

| 반응 | `wantsNow` | 뜻 |
| --- | --- | --- |
| `ACCEPTED` | 참 | 그때 그 제안을 원했다 |
| `DECLINED` | 거짓 | 그 제안 하나를 그때 원하지 않았다 |
| `DEFERRED` | 거짓 | 지금은 아니다. 싫다는 뜻이 아니다 |
| `NO_RESPONSE` | 없음 | 표본에서 뺀다 |
| `NOT_SURFACED` | 없음 | 반응을 받을 자리가 없었다. 표본에서 뺀다 |

`wantsNow` 는 나중에 `P(user wants this now | situation, goal, history, candidate)` 를 견줄 때의 최소 표본이다. 이번에는 견주지 않는다.

**잘못된 학습을 막는 규칙**

- 무응답을 싫어함으로 읽지 않는다
- 거절은 그 제안 하나에 대한 일회성 반응이다. 같은 주제가 여러 번 거절돼도 읽기 모델은 오래 가는 선호를 만들지 않는다
- 지금 화면의 숨기기는 상태가 바뀔 때까지만 가리므로 거절이 아니라 미루기로 읽는다. 숨긴 뒤 받아들이면 `ACCEPTED` 다
- 첫 반응을 나중 사건이 덮지 않는다. 받아들인 뒤 그만둔 할 일은 `ACCEPTED` 이고 그만둠은 사건으로만 남는다
- `persistentPreference` 는 사용자의 마지막 결정(받아들임, 거절, 되돌림)이 받아들임인 Memory 제안만 참이다. 첫 반응은 그대로 `ACCEPTED` 로 읽되, 받아들인 뒤 되돌리거나 거절했으면 오래 가는 선호가 아니다. 오래 가는 선호는 Memory 에만 남는다([ADR-012](../adr/ADR-012-memory-는-사람이-승인한-것만-남는다.md), [ADR-20261007 / memory-remember](../adr/ADR-20261007-memory-remember.md))
- 규칙 버전 2 에서 `persistentPreference` 를 마지막 결정 기준으로 바꿨다. 버전 1 은 받아들인 사건이 하나라도 있으면 참이었다
- 에이전트와 시스템의 사건은 사용자 반응이 아니다

### 살펴보기 발견의 지금 반응

발견의 지금 반응은 그 발견의 사용자 사건 가운데 마지막 `ACCEPTED`, `POSTPONED`, `DISMISSED` 다. 사용자가 단추를 바꾸면 지금 반응도 바뀐다.
위 첫 반응 규칙과 다르다. 지금 반응은 replay 에 쓰지 않고 다음 살펴보기의 입력과 되풀이 판정에만 쓴다([먼저 살펴보기](proactive-check.md)의 「살펴보기가 읽는 맥락」 과 「검사」).
「관심 없음」 이 그 발견의 주제를 내리는 것은 그 발견을 알린 시각부터 같은 점검 대화의 `digest-window` 안뿐이다. 기간이 지나면 같은 주제를 다시 알릴 수 있다.

## replay 읽기 모델

`GET /api/v1/decision-feedback/export?days=30` 이 요청자의 기록을 `DecisionFeedbackExport` 로 낸다. 화면은 없다.
이 모양이 그대로 API 계약이다. offline replay 도구와 모양을 함께 맞추려고 별도 응답 DTO 를 두지 않고 `version` 으로 바뀜을 알린다.
`days` 는 1부터 365까지이고 기본 30이다. 벗어나면 400 `VALIDATION_FAILED` 다. 관리자도 남의 기록을 읽지 못한다.

| 칸 | 뜻 |
| --- | --- |
| `version`, `labelVersion` | 읽기 모델의 모양 버전과 반응 읽기 규칙 버전 |
| `from`, `to` | 읽은 기간 |
| `records[].decisionKey` | 결정 하나의 열쇠. 살펴보기에서 나왔으면 `check:<번호>`, 아니면 그 제안의 열쇠다 |
| `records[].situation` | 살펴보기의 에이전트, 계기, 상태, 결과, 보고를 보였는지, 시각. 살펴보기 밖의 제안이면 없다 |
| `records[].candidates` | 문제 후보의 번호, 상태, 버린 까닭, 문제 키, 행동 종류, 부작용 힌트, 확신, 근거의 주제 키와 확인 시각 |
| `records[].judgments` | 평가의 번호, replay 원본, 결과, 입력 버전과 기준 시각, adapter 와 모델, 추천 순서, 후보별 축 선택과 확신 |
| `records[].policies` | 판정의 번호, 후보, 수준, 까닭 코드, 규칙 버전, 실행 상태, 시작한 살펴보기 |
| `records[].subjects` | 제안별 사건, 반응, `wantsNow`, 고쳤는지, 마지막 실행 결과, `persistentPreference` |

결정에 묶는 순서는 다음과 같다.

1. 사건의 `source_check_id`
2. 사건의 `origin_execution_id` 를 루트 실행으로 가진 살펴보기
3. 자동 실행한 살펴보기에 묶인 제안은 결과든 그 트리가 낸 할 일과 승인 줄이든 그 실행을 허락한 판정의 원천 살펴보기로 옮긴다

자동 실행한 살펴보기 자신은 따로 상황 하나로 남는다. 그 살펴보기의 문제 후보와 그 후보의 판단과 정책은 그 상황에 속하기 때문이다. 사용자 반응은 모두 원천 결정에 모인다.

상황은 그 기간에 연 살펴보기와 사건이 가리키는 살펴보기다. 기간 안에 사건이 있는 제안은 기간 앞의 사건도 함께 읽어 첫 반응을 잃지 않는다.
지운 대화에 묶인 사건이 하나라도 있는 제안과, 점검 대화를 지운 살펴보기의 결정은 싣지 않는다.

후보의 문제와 행동 글, 축의 설명, 비교 설명, 할 일 제목, Memory 본문, 커넥터 인자와 결과는 싣지 않는다.
후보의 문제 키, 행동 종류, 부작용 힌트, 확신은 모델이 쓴 값을 정규화해 저장한 짧은 열쇠라 싣는다. 요청자 자신에게만 나간다.

### offline replay 로 할 수 있는 것

- 같은 상황에서 판단과 정책이 무엇을 냈는지, 그 결정에서 사용자가 무엇을 받아들이고 거절했는지 견준다
- 평가의 저장된 입력으로 다른 provider 나 모델을 replay 하고([가치 평가](value-evaluation.md)의 「저장과 replay」), 같은 결정의 사용자 반응과 나란히 둔다
- 정책 규칙을 바꿨을 때 저장된 `inputs_json` 으로 판정을 다시 내고 지난 실행 결과와 견준다

후보 하나와 사용자 반응 하나를 직접 잇는 표본은 아직 없다. 후보와 정책 판정을 사용자에게 제안으로 보이는 화면이 생기면 그 화면이 `SURFACED` 를 남기고 같은 열쇠 규칙을 따른다.

## Career 매일 깨우기와의 연결

| 일 | 남는 것 |
| --- | --- |
| 제안 | 매일 깨우기 살펴보기의 보고 `SURFACED`, 그 트리가 낸 할 일과 Memory 의 `SURFACED` |
| 침묵 | 보고 없는 상황. 사건은 없다 |
| 숨기기와 미루기 | 지금 화면의 제안 항목 `DISMISSED`, `POSTPONED`. 보고 항목에는 숨기기 단추가 없다 |
| 발견 반응 | 점검 대화의 발견 단추가 남기는 `check_finding` 의 `ACCEPTED`, `POSTPONED`, `DISMISSED`. 그 살펴보기의 결정에 묶인다 |
| 관심 범위 줄이기 | 대화에서 나온 Memory 제안의 `ACCEPTED` 나 `REJECTED`. 받아들인 것만 오래 가는 선호다 |
| 할 일 | 할 일의 `ACCEPTED`, `EDITED`, `EXECUTION_SUCCEEDED`, `DISMISSED`. 모두 그 살펴보기의 결정에 묶인다 |

대화로 「이런 공고는 관심 없다」 고 답한 글 자체는 기록하지 않는다. 그 글은 대화에만 있다. 발견 하나에 대한 거절은 발견 단추로 남긴다.

## 보관과 삭제

| 일 | 처리 |
| --- | --- |
| 사용자 줄을 지움 | FK `ON DELETE CASCADE` 로 함께 지운다 |
| 대화를 지움 | `ChatService.delete` 가 같은 트랜잭션에서 그 대화의 사건과, 그 사건이 가리키는 제안의 다른 사건을 지운다 |
| 보관 기간이 지남 | 마지막 사건이 `assistant.decision-feedback.retention`(기본 365일)보다 오래된 제안의 사건을 `cleanup-cron`(기본 매일 04:45)에 모두 지운다. 사건 단위로 지우면 나중 사건만 남아 첫 반응을 잘못 읽는다. 검사에서는 `-` 로 끈다 |
| 살펴보기, 판정, 실행 줄을 지움 | 그 번호 칸만 비운다(`ON DELETE SET NULL`) |

대화 삭제의 정리는 같은 트랜잭션이라 실패하면 대화 삭제도 실패한다. 「기록이 사용자의 동작을 막지 않는다」 의 유일한 예외다. 지운 대화에 사건이 남지 않게 하려는 선택이다.
행동 정책 판정의 사건은 원천 살펴보기의 점검 대화를 `conversation_id` 로 채워 점검 대화를 지울 때 함께 지운다.

## 검증

`FeedbackLabelerTest` 가 사용자 반응 fixture 열두 가지로 읽기 규칙을 본다.
`DecisionFeedbackFlowTest` 가 매일 깨우기의 보고, 침묵, 트리의 할 일과 Memory 제안, 반응, 대화 삭제, 자동 실행 결과의 묶기, 되돌림, 보관 정리를 실제 DB 로 잇는다.
할 일, 승인 줄, 지금 화면, 자동 실행 시험이 각 기록 지점의 사건을 확인한다.
