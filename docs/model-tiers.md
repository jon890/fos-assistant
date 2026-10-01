# 모델 단계와 실행 기록

입력창 옆에서 빠르게, 균형, 깊게를 고른다.
평소에는 세 단계와 설정 단추만 보인다.
설정 안에 에이전트 기본값, 내 기본값, 관리자용 그룹 단계 설정과 고급 모델 선택을 둔다.
provider와 모델 이름은 평소 입력창 옆에 표시하지 않는다.
현재 디자인 토큰을 쓰고 새 색이나 화면 구조를 도입하지 않는다.

## 모델 선택

대화 선택은 `DEFAULT`, `TIER`, `CUSTOM` 세 모드다.
새 대화는 선택이 없는 상태이며 사용자 기본 단계, 그룹 기본 단계, profile 기본값 순서로 정한다.
사용자가 기본값으로 돌아가기를 고르면 `DEFAULT`로 고정해 사용자와 그룹 설정을 건너뛴다.
`TIER`는 대화가 고른 단계를 쓰고 `CUSTOM`은 기존 provider, 모델, effort를 쓴다.
기존 선택이 있는 대화는 `CUSTOM`, 없는 대화는 선택 없는 상태로 이관한다.

그룹 관리자만 그룹 단계 정의와 그룹 기본값을 저장한다.
사용자는 자기 기본 단계만 바꿀 수 있다.
기본 단계가 없다는 값은 `null`이다.
초기 단계의 mapping은 운영 설정에서 받으며 제품 코드와 마이그레이션에 모델 이름을 넣지 않는다.
설정이 없으면 세 단계의 mapping은 모두 비어 있고, 해당 단계를 골라도 profile 기본값으로 실행한다.
이때 고른 단계는 실행 기록에 남기지만 provider, 모델, 강도를 요청에 덧붙이지 않는다.
관리자는 설정에서 「단계 설정이 필요해요」 안내를 보고 그룹 단계를 정할 수 있다.
초기 설정의 provider가 비면 요청자의 에이전트가 알려 준 기본 provider로 해석한다.
관리자가 정의를 저장하면 명시한 provider를 쓴다.
정의 저장 전에 provider와 모델의 앞뒤 공백을 없애고 빈 provider는 에이전트 기본 provider로 해석한다.
목록 조회가 실패하면 새 단계 선택을 막고 기존 선택과 profile 기본값을 유지한다.
그룹 정의 저장은 형식만 검사한다. 선택과 실행 시작 때 요청 에이전트의 catalog로 provider와 모델을 검사한다.
지원하지 않는 정의는 Hermes 제출 전에 거절하며 다른 모델로 임의로 바꾸지 않는다.
화면의 명시적 `DEFAULT` 선택은 「에이전트 기본값」으로 표시한다.

| 단계 | 코드 | 초기 설정 접두사 |
| --- | --- | --- |
| 빠르게 | `FAST` | `assistant.model-tiers.fast` |
| 균형 | `BALANCED` | `assistant.model-tiers.balanced` |
| 깊게 | `DEEP` | `assistant.model-tiers.deep` |

각 접두사 아래에 `provider`, `model`, `reasoning-effort`를 설정한다.
환경 변수는 `ASSISTANT_MODEL_TIERS_FAST_PROVIDER`, `ASSISTANT_MODEL_TIERS_FAST_MODEL`,
`ASSISTANT_MODEL_TIERS_FAST_REASONING_EFFORT` 형식이며 `BALANCED`, `DEEP`에도 같은 세 키를 쓴다.
운영 값은 비공개 infra 저장소가 배포할 때 제공한다.
설정된 mapping은 그룹이 저장한 정의가 없을 때만 쓴다.
관리자가 저장한 정의는 비어 있는 mapping도 그대로 우선해 profile 기본값으로 실행한다.
미설정 mapping의 세 값은 모두 `null`이다. mapping을 넣을 때는 모델과 유효한 강도가 필요하며 provider는 비울 수 있다.
provider나 강도만 있는 부분 설정은 거절한다.

단계 정의를 바꾸면 다음 실행부터 적용한다.
이미 시작한 실행은 단계와 provider, 모델, effort를 복사해 두므로 바뀌지 않는다.
Hermes가 실제로 쓴 provider와 모델은 실행 완료 시 결과로 갱신한다.
단계 이름은 사용자가 고른 값이며 실제 모델이 넘어가도 그대로 남는다.
직접 대화, Flow의 뿌리와 자식, Control Plane 위임 실행은 같은 단계 해석을 쓴다.

## API 계약

모든 경로는 로그인한 요청자의 그룹과 사용자에서 범위를 정한다.
요청 본문에서 group이나 profile을 받지 않는다.
없는 대화와 남의 대화는 기존 대화 접근 검사로 같은 오류를 돌려준다.

| 경로 | 요청 또는 응답 |
| --- | --- |
| `GET /api/v1/chat/model-tiers?agentCode=...` | `tiers` 배열(`tier`, `label`, `provider`, `model`, `reasoningEffort`), `userDefaultTier`, `groupDefaultTier`, `admin` |
| `PUT /api/v1/chat/model-tiers/default` | 본문 `tier`: 단계 코드 또는 `null`. 내 기본값을 저장한다 |
| `PUT /api/v1/chat/model-tiers/group` | 관리자 전용. `tiers` 세 정의와 `defaultTier`를 한 트랜잭션으로 저장한다 |
| `PUT /api/v1/chat/conversations/{id}/model-tier` | `mode`와 `tier`. `DEFAULT`면 tier는 비고 `TIER`면 유효한 코드가 필요하다 |
| 기존 대화 모델 선택 경로 | provider, 모델, effort를 저장하면서 `CUSTOM`으로 전환한다 |

## profile 기본 강도

대시보드 plugin의 Control Plane 토큰 전용 `GET /api/profiles/{name}/model-defaults`는
`provider`, `model`, `reasoningEffort` 세 값만 반환한다.
설정 전체와 비밀값, 파일 경로는 돌려주지 않는다.
profile은 서버가 확인한 바인딩에서 고른다.
조회 결과는 profile별로 짧게 캐시하며 일반 대화 응답을 기다리게 하지 않는다.
기본 profile도 이 읽기 전용 경로에서 조회할 수 있다. 쓰기 경로의 기존 제한은 유지한다.
실행 기록의 요청 강도가 비면 완료 후 기본값을 보완한다.
출처는 `REQUESTED`, `PROFILE_DEFAULT`, `UNKNOWN`으로 구분한다.
정상 조회에서 기본 강도가 없어도 확인 시각을 남겨 그 실행을 다시 조회하지 않는다.
조회 실패만 재시도하며 이후 새 설정을 이미 확인한 과거 실행에 붙이지 않는다.
이 값은 설정값이며 provider가 실제로 강도를 조정했는지 확인하는 값은 아니다.

## 비동기 자식 사용량

`SUBAGENT_STARTED`가 남았지만 같은 child session의 완료 기록이 없으면 부모 종료 뒤 별도로 조회한다.
재조회 작업은 DB에 저장하고 서버 재기동 뒤에도 이어 간다.
처음 2분은 5초 간격, 이후 지수로 늘려 최대 5분 간격으로 조회한다.
24시간 뒤 멈추며 서버 전체 동시 조회 수를 제한한다.
동시 조회는 4개, 발견 batch는 20개다.
매 주기에는 기본 강도 조회 한 건을 먼저 제출해 자식 작업이 밀려 있어도 조회할 기회를 준다.
처음 2분 이후의 간격은 `min(300, 5 * 2^backoffAttempt)`초다.
작업 상태는 `WAITING`, `DONE`, `EXPIRED`다.
에이전트가 없어졌거나 profile이 바뀐 사건은 조회하지 않고 `EXPIRED`로 남긴다.
`unconfirmed_reason`에 `AGENT_MISSING`, `PROFILE_CHANGED`, 기한 만료는 `DEADLINE`을 적는다.
처리하지 못하는 사건도 작업으로 소비해 다음 자식 발견을 막지 않는다.
서버 한 대가 작업 ID별로 실행 중인 조회를 추적해 같은 작업을 겹쳐 부르지 않는다.
완료 기록을 넣는 트랜잭션은 실행 줄을 잠그고 다음 sequence를 정한다.
자식 완료 자연키는 `(execution_id, child_session_id)`이며 기존 완료 중복은 최신 한 줄만 키를 가진다.
자식이 아직 끝나지 않았거나 읽지 못하면 토큰을 0으로 채우지 않는다.
기간을 넘긴 자식은 사용량 미확인으로 표시한다.

`GET /api/sessions/{id}`의 중첩 session에서 `ended_at`이 있는 경우에만 최종 사용량을 기록한다.
`source=subagent`와 부모 session 관계를 확인하고 같은 자식을 중복 기록하지 않는다.
`ended_at`과 `agent_close`는 성공의 증명이 아니므로 실패 여부는 확인되지 않은 값으로 둔다.
입력은 일반 입력, cache read, cache write를 합산해 부모 실행의 입력 정의와 맞춘다.
시간은 `(ended_at - started_at) * 1000`의 밀리초다.
SSE 완료 기록이 먼저 생기면 재조회 작업은 완료 처리하고 새 사건을 만들지 않는다.
이 토큰은 자식 표시용이며 기존 월별 사용량 합계에는 추가하지 않는다.

2026-10-01 종료 자식 5개를 각각 두 번 GET해 토큰과 종료 시각이 모두 같음을 확인했다.
본문과 답은 읽거나 기록하지 않았다.
이 검사는 이미 종료된 session의 반복 조회를 확인한 것이며 실행 중 값의 변화까지 확인한 것은 아니다.

## 표시와 검증

실행 줄에 요청 수신(`request_received_at`), Hermes 제출(`submitted_at`),
첫 assistant delta 수신(`first_delta_at`), 완료(`finished_at`) 시각을 남긴다.
첫 delta 시각은 한 번만 적고 본문은 추가로 저장하지 않는다.
SSE를 읽지 않는 Flow와 위임 실행은 첫 delta 시각을 비운다.
Flow 뿌리는 원래 대화 요청 수신 시각을 쓰고, 자식은 실행기 진입 시각을 쓴다.
실행 상세는 네 시각으로 구간을 보이되 없는 시각을 추정하지 않는다.
요청 수신은 ChatService의 보내기, 스트림, 다시 생성 진입점에서 측정한다.
자동 깨우기는 요청 대신 내부 trigger 시각을 쓴다.
제출은 Hermes submit 직전이며 첫 delta는 relay에서 최초 message.delta를 받을 때다.
제출 전 실패나 취소이면 제출 시각은 null이고 SSE가 없으면 첫 delta도 null이다.
실행 나무 응답은 provider, cachedInputTokens, totalTokens, modelTier, reasoningEffort,
reasoningEffortSource와 위 네 시각을 실행 줄에서 반환한다.

실행 상세와 대화 화면의 실제 제공사, 모델, 토큰, 금액은 ADMIN에게만 보인다.
MEMBER에게는 고른 단계와 걸린 시간만 보인다.
네 시각과 구간표도 ADMIN에게만 보인다.
대화 안 작업 과정에는 역할과 관계없이 모델 이름과 토큰을 넣지 않는다.
API 응답은 그대로 두며 화면에서 역할에 따라 표시한다.
하위 에이전트 이름이 비면 `subagent_id`, `goal` 순서로 채우되 preview에서 이름을 추정하지 않는다.

가짜 Hermes는 자식 완료가 부모 완료 뒤에 오거나 오지 않는 순서를 검사한다.
세션 조회 지연, 끝내 미완료, 중복 SSE, 재기동, 다른 부모 session, 권한과 기본값 우선순위도 검사한다.
배포는 대시보드 plugin을 먼저 올리고 Control Plane과 웹을 뒤에 올린다.
기존 운영 행은 이 변경에서 보정하지 않는다.

실행 나무는 시작 사건에 `subagentUsageStatus`를 합쳐 반환한다.
완료 사건이 있으면 `RECORDED`, 만료 작업이면 `UNCONFIRMED`, 기다리는 작업이면 `WAITING`이다.
작업 설명은 상태 안내로 바꾸지 않는다.
profile에도 강도 설정이 없으면 실제 강도를 모르므로 출처를 `UNKNOWN`으로 둔다.
