# 문제 후보의 가치 평가

받아들인 문제 후보 여러 개 가운데 지금 무엇을 먼저 다룰지 판단한다.
결정은 [ADR-20261007 / value-evaluation](../adr/ADR-20261007-value-evaluation.md)에 있다.
권한, 승인, 실행 여부는 [행동 정책](autonomy-policy.md)이 정한다.
단추로 연 살펴보기는 이 평가를 자동으로 부르지 않는다. 매일 깨우기는 사용자가 켠 에이전트에서만 [매일 루프](proactive-loop.md)가 한 번 부른다. 결과를 대화에 올리지 않는다.
사람이 부르는 자리는 관리자 영역 에이전트 상세의 「가치 평가」 절 하나다. 화면은 [`web/docs/prd.md`](../../web/docs/prd.md) 의 「가치 평가 절」 이 갖는다.

## 입력과 판단 축

한 살펴보기의 `proactive_check_problem.status = ACCEPTED` 후보를 3개까지 견준다.
`DROPPED` 후보는 입력에 넣지 않는다. 후보가 없으면 모델을 부르지 않고 `EMPTY`로 끝낸다.
입력 `DecisionState`는 버전 1, 기준 시각 `asOf`, 후보 목록이다.
후보에는 식별자, 문제, 관련 목표, 제안 행동, 기대 효과, 확신, 영향과 위험, 근거 참조와 확인 시각을 둔다.

| 축 | 판단하는 것 | 모르는 것의 처리 |
| --- | --- | --- |
| `GOAL_ALIGNMENT` | 후보가 말한 목표와 문제의 관계 | 목표가 실제로 있다는 검증과 구분한다 |
| `URGENCY` | 지금 미루면 잃는 기회 | 원문 확인 시각만으로 마감을 만들지 않는다 |
| `EXPECTED_BENEFIT` | 해결하면 얻을 효과 | 후보의 기대 효과는 가설이다 |
| `COST` | 사용자 주의와 실행의 시간·부담 | 확인하지 않은 소요 시간과 금액을 만들지 않는다 |
| `RISK` | 부작용과 되돌리기 어려움 | `sideEffect`는 힌트이며 권한 판정이 아니다 |
| `EVIDENCE_QUALITY` | 근거 참조와 확인 시각의 충분함 | 원문 내용의 재검증과 구분한다 |

축별 결과 `AxisJudgement`는 `choice`, `confidence`, `explanation`, `evidenceKeys`다.
`choice`는 `LOW`, `MEDIUM`, `HIGH`, `UNKNOWN`이다. 비용과 위험의 `HIGH`는 부담이 크다는 뜻이다.
`confidence`는 `LOW`, `MEDIUM`, `HIGH`다. `UNKNOWN`이면 확신은 `LOW`여야 한다.
알려진 판단에는 해당 후보의 검증된 `topicKey`를 하나 이상 붙인다.

사용자 주의 비용과 실행 비용은 첫 pilot에서 따로 검증할 자료가 부족해 `COST`로 합쳤다.
`USER_PREFERENCE_FIT`는 따로 만들지 않았다. 관련 목표에 이미 선호가 반영될 수 있지만, 실제 Memory와 일치하는지는 확인하지 않는다.
전체 Memory와 대화 이력을 추가로 읽거나 복제하지 않는다. 개인화 학습도 하지 않는다.

## provider 계약

`DecisionProvider.evaluate(state, questions, request)`가 `DecisionResponse`를 낸다.
`DecisionRequest`는 요청 사용자이며, 상태와 질문은 모델과 무관한 값이다.
`DecisionResponse`는 provider 식별 정보와 `DecisionResult`다.
새 adapter는 이 port만 구현하며 `ValueEvaluator`의 검사와 행동 정책을 바꾸지 않는다.
같은 fixture 를 여러 provider 로 replay 해 루프 전체를 견주는 평가는 [먼저 살펴보기 루프 평가](proactive-eval.md)가 갖는다.

첫 adapter `hermes`는 도구 없는 시스템 판단 profile에 Hermes Runs로 한 번 묻는다.
profile은 설치 설정에서만 정한다. 사용자에게 보이는 에이전트를 만들지 않으며 요청 본문으로 profile을 고르지 못한다.
매번 새 session을 쓰고 후보 스냅샷과 질문만 넣는다. 모델 자격 증명은 Hermes가 갖는다.
실행 기록은 요청 사용자에게 묶고 `agent_id`와 `conversation_id`를 비운다. 기존 백그라운드 실행 한도로 센다.
실패 응답에 사용량과 실제 모델이 있으면 그것도 보존한다. 오류 본문은 남기지 않는다.
그룹이 숨긴 모델은 호출하지 않는다.

모델은 전체 후보의 순서와 비교 설명을 낸다. Control Plane은 후보·축의 누락, 중복, 근거 키와 순서의 식별자를 검사한다.
단일 점수와 고정 가중치를 만들지 않는다. `permission`, `approval`, `execute` 칸도 없다.

### 판단 profile의 확인

호출 전 대시보드 plugin의 `GET /api/profiles/{profile}/decision-readiness`를 읽는다.
Control Plane 관리 토큰만 받고 `{ "version": 1, "ready": true 또는 false }`만 응답한다.
기본 API toolset 목록에는 기본 MCP가 빠지므로 그 목록이 비었다는 이유만으로 호출하지 않는다.

다음 조건을 모두 확인한다.

- 상류 `_get_platform_tools(config, "api_server")`가 빈 집합이다.
- `platform_toolsets.api_server`는 `[no_mcp]`다.
- `memory.provider`는 `none`, `memory.memory_enabled`와 `memory.user_profile_enabled`는 `false`다.
- `fallback_providers`는 빈 배열이다.

설정 검사는 호출 직전에 한다. 검사를 마친 뒤 관리자가 profile을 바꾸는 경우까지 원자적으로 막지는 않는다.
이 profile의 설정을 바꾸거나 일반 사용자에게 바인딩하지 않는 것은 운영 계약이다.
틀은 `hermes/decision-profile/config.yaml.template`에 있다. 번들에는 `decision-config.yaml.template`로 담는다.
profile 생성과 배포는 운영 저장소가 맡는다.

## 저장과 replay

`proactive_value_evaluation`에 시도마다 새 줄을 남긴다.
모델 호출 전에 `RUNNING`과 입력·질문을 저장하고, 호출 뒤 결과를 붙인다. 모델을 기다리는 동안 저장 트랜잭션을 열지 않는다.
판단 중 대화를 지워도 요청자의 평가 기록은 완료 상태로 닫는다. 완료 응답과 이후 조회·replay에는 대화의 조회 권한을 다시 확인한다.
기동할 때 남은 `RUNNING`은 `FALLBACK / INTERRUPTED`로 닫고 자동으로 다시 부르지 않는다.
한 줄의 저장·JSON 읽기가 실패해도 다른 줄의 복구와 서버 기동을 이어 간다.
복구하지 못한 줄은 원래 상태로 두고 식별자와 오류 종류만 기록한다.

`evidence_json`에는 `DecisionEvidence`를 둔다.

| 칸 | 남기는 것 |
| --- | --- |
| `state` | 입력 버전, 기준 시각, 후보 식별자와 최소 스냅샷 |
| `questions` | 여섯 축의 판단 기준 |
| `provider` | adapter 이름·버전, 요청 provider·모델, 실제 provider·모델, 실행 식별자 |
| `result` | 결과 상태, 후보별 축·확신·설명·근거 키, 순서와 비교 설명, 실패 코드 |

실제로 돈 모델을 모르면 비운다. 요청한 모델 이름을 실제 모델로 대신 적지 않는다.
원시 응답, 예외 글, 전체 개인 문맥, 원문 본문, 커넥터 응답은 남기지 않는다.
응답 전체를 감싼 `json` 또는 언어 없는 코드 펜스 한 겹은 벗긴 뒤 같은 JSON 계약으로 검증한다. 앞뒤 설명이나 두 번째 JSON 객체는 거절한다.
후보 글은 이미 저장된 후보의 필요한 부분만 복제한다.

replay는 저장된 `state`와 `questions`를 그대로 써 새 시도를 만든다. 후보와 기준 시각은 다시 읽지 않는다.
`replay_of_id`는 바로 재평가한 시도를 가리킨다. provider가 여러 개 설치되면 이름을 바꿔 같은 입력으로 견줄 수 있다.
첫 설치에는 `hermes` 하나만 있다. 모델을 바꿔 비교하려면 설치 설정을 바꾸고 replay한다.
replay는 같은 답의 재현을 보장하지 않는다. 같은 입력에서 판단 차이와 이유를 비교하는 기능이다.

## 실패와 근거 부족

| 결과 | 뜻 | 추천 순서 |
| --- | --- | --- |
| `EVALUATED` | 모든 후보와 축, 근거 키와 순서가 검사에 통과했다 | 모델이 낸 전체 순서 |
| `INSUFFICIENT_EVIDENCE` | 모델이 판단 불가라고 했거나 종합 확신·목표·근거 판단이 부족하다 | 비운다. 축별 판단은 보존한다 |
| `FALLBACK` | 호출이나 출력 계약을 읽지 못했다 | 비운다 |
| `EMPTY` | 받아들인 후보가 없다 | 비운다. 호출하지 않는다 |

`FALLBACK` 코드는 `PROVIDER_UNAVAILABLE`, `TIMEOUT`, `PROVIDER_FAILED`, `INVALID_RESULT`, `INTERRUPTED`다.
다른 모델로 자동 재시도하거나 후보 번호 순서로 추천을 대신하지 않는다.
timeout 뒤 원격 실행의 끝을 확인할 때까지 요청자의 자리를 계속 쥐고 기존 원격 종료 확인 경로로 중지한다.
대기 timeout은 제출 뒤 시작한다. 준비 상태 조회와 제출에는 기존 Hermes HTTP timeout이 적용된다.

## API와 다음 행동 정책의 입력

| 메서드와 경로 | 하는 일 |
| --- | --- |
| `POST /api/v1/proactive-checks/{checkId}/value-evaluations` | 요청자의 끝난 살펴보기 후보를 평가한다 |
| `POST /api/v1/value-evaluations/{id}/replays` | 같은 입력으로 새 평가를 남긴다 |
| `GET /api/v1/value-evaluations/{id}` | 요청자의 평가를 읽는다 |
| `GET /api/v1/admin/agents/{code}/value-evaluation` | `ADMIN` 만. 요청자가 그 에이전트로 연, 받아들인 문제 후보가 있는 마지막 살펴보기와 그 마지막 평가, 그 평가의 마지막 판정 묶음을 읽는다 |
| `POST /api/v1/admin/proactive-checks/{checkId}/value-evaluation-runs` | `ADMIN` 만. 요청자의 끝난 살펴보기를 평가하고 곧바로 [행동 정책](autonomy-policy.md)으로 판정한 뒤 위 GET 과 같은 모양을 201 로 준다 |

POST 본문은 `{ "provider": "hermes" }`다. 설치된 adapter 이름만 받는다.
다른 사용자의 자료는 관리자에게도 `VALUE_EVALUATION_NOT_FOUND`다. 지운 대화의 평가도 읽거나 replay하지 못한다.
응답은 평가 식별자, replay 원본, 결과 상태, 실패 코드, 후보 스냅샷, 후보별 판단, 순서, 설명이다. 실행 식별자와 provider·모델 원문은 내부 기록에만 둔다.
후보 스냅샷은 `state` 의 후보마다 식별자, 문제 키, 문제 글, 행동 종류, 부작용 힌트만 싣는다. 근거 주소와 기대 효과는 싣지 않는다.

### 관리자 화면이 읽는 묶음

관리자 영역의 두 경로는 관리자 본인의 자료만 다룬다. 다른 사용자의 살펴보기는 위 경로들과 같이 `VALUE_EVALUATION_NOT_FOUND` 다.
`ADMIN` 이 아니면 `FORBIDDEN` 이다. 판단 profile 을 부르는 비용이 드는 동작을 관리자 화면 밖으로 넓히지 않기 위해서다.
응답은 `{ check, evaluation, decisions }` 다.

| 칸 | 담는 것 | 비는 때 |
| --- | --- | --- |
| `check` | 살펴보기 식별자, 시작 계기, 끝난 시각, 받아들인 후보 수 | 고를 살펴보기가 없다 |
| `evaluation` | 위 평가 응답. replay 를 포함해 그 살펴보기의 가장 최근 시도다 | 아직 평가하지 않았다 |
| `decisions` | 그 평가의 판정 가운데 마지막으로 함께 남긴 묶음. 판정 응답과 같은 모양이다 | 판정하지 않았거나 후보가 없었다 |

고르는 살펴보기는 `status = SUCCEEDED` 이고, 시작 계기가 `AUTONOMY` 가 아니고, `ACCEPTED` 문제 후보가 하나 이상 있고, 점검 대화를 지우지 않은 가장 최근 줄이다.
자동 실행이 연 살펴보기는 사람에게 바로 보이지 않으므로 고르지 않는다.
후보가 없는 줄은 평가해도 `EMPTY` 뿐이다. 모델 없이 건너뛴 예약 실행과 결과를 읽지 못한 줄도 `SUCCEEDED` 로 남으므로, 후보 조건이 없으면 그런 줄이 평가할 줄을 가린다.
에이전트는 살펴보기 상태 조회와 같이 요청자가 대화를 시작할 수 있어야 한다. 아니면 그 조회와 같은 오류다.
`POST .../value-evaluation-runs` 는 평가와 판정을 따로 부를 때와 같은 검사를 거친다. 평가가 끝난 뒤 판정이 실패하면 평가 줄은 남고 오류를 준다. 다시 누르면 새 평가를 만든다.

다음 행동 정책은 `ValueEvaluationService.read`의 `DecisionEvidence`를 읽는다.
`EVALUATED`도 추천일 뿐 실행 허락이 아니다. 정책은 현재 후보·근거의 유효성과 사용자 권한을 다시 확인해야 한다.
축의 `UNKNOWN`, 낮은 확신, 부작용 힌트는 판단에서 빠뜨리지 않는다. replay 결과를 현재 상황의 실행 근거로 바로 쓰지 않는다.

## 설정과 검증

`assistant.value-evaluation` 설정이다.

| 칸 | 기본값 | 뜻 |
| --- | --- | --- |
| `enabled` | `false` | 설치가 끝난 뒤에만 모델을 부른다 |
| `profile` | 없음 | 시스템 판단 profile. 켜면 유효한 이름이 필요하다 |
| `provider`, `model` | 없음 | 함께 정한다. 없으면 profile의 모델 기본값을 읽는다 |
| `reasoning-effort` | 없음 | 모델에 보낼 reasoning effort |
| `timeout` | `30s` | 제출 뒤 대기 한도. 양수이며 최대 2분 |
| `cost-mode` | `SUBSCRIPTION` | 기존 사용량의 비용 환산 방식 |

단위 시험은 순서의 설명, provenance와 누락 검사, 낮은 확신, timeout과 provider 실패를 본다.
저장 시험은 후보 집합과 기준 시각을 두 번 replay하고 JSON 왕복과 사용자 경계를 확인한다.
Hermes 시험은 readiness 인증과 위험한 설정 거절, 상류 도구 계약을 확인한다.
공개 시험과 기록에는 합성 데이터만 쓴다.
