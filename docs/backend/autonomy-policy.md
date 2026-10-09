# 행동 정책

가치 평가를 받은 문제 후보마다 무엇까지 해도 되는지 정한다.
결정은 [ADR-20261007 / autonomy-policy](../../backend/docs/adr/ADR-20261007-autonomy-policy.md)에 있다.
입력 계약은 [가치 평가](value-evaluation.md)의 「API와 다음 행동 정책의 입력」 이 갖는다.
판정에 쓰는 입력은 `AutonomyInputs` 가, 저장 모델은 [`schema/proactive.md`](schema/proactive.md) 가 갖는다.

## 행동 수준

| 수준 | 뜻 | 이번에 하는 일 |
| --- | --- | --- |
| `IGNORE` | 다루지 않는다 | 판정만 남긴다 |
| `SURFACE` | 사용자에게 보일 가치가 있지만 실행 근거나 허락이 없다 | 판정을 남긴다. [매일 루프](proactive-loop.md)가 낸 판정이면 지금 화면 「내 차례」 에 보인다. 관리자 영역의 가치 평가 절은 판정을 읽기만 한다 |
| `ASK_APPROVAL` | 쓰기나 위험이 있어 사람의 승인 없이는 하지 않는다 | 판정을 남긴다. 새 승인 줄을 만들지 않는다. 매일 루프가 낸 판정이면 「직접 처리할 일」 로 지금 화면에 보인다 |
| `EXECUTE` | 읽기 전용 살펴보기를 한 번 시작한다 | 실행 키를 저장한 뒤 시작한다 |

## 까닭 코드와 수준

묶음마다의 수준과 보는 순서는 `AutonomyPolicy` 가 갖는다. 모든 까닭을 모은 뒤 앞 묶음에 하나라도 있으면 뒤를 보지 않는다.
근거가 약한 후보에는 승인을 묻지 않는다. 승인 묶음은 실행 막힘보다 앞서므로 사용자 동의가 꺼져 있어도 쓰기 후보는 `ASK_APPROVAL` 이다.

| 묶음 | 까닭 코드 | 조건 |
| --- | --- | --- |
| 다루지 않음 | `CANDIDATE_NOT_CURRENT` | 지금의 후보 줄이 없거나 `ACCEPTED` 가 아니거나 스냅샷과 다르다 |
| 다루지 않음 | `EVALUATION_NOT_USABLE` | 평가가 `EVALUATED` 나 `INSUFFICIENT_EVIDENCE` 가 아니거나 그 후보의 판단이 없다 |
| 다루지 않음 | `LOW_VALUE` | `EXPECTED_BENEFIT` 가 `LOW` 이고 `URGENCY` 나 `GOAL_ALIGNMENT` 가 `LOW` 다 |
| 근거 부족 | `INSUFFICIENT_EVIDENCE` | 평가가 `INSUFFICIENT_EVIDENCE` 이거나 `EVIDENCE_QUALITY` 가 `LOW` 다 |
| 근거 부족 | `LOW_CONFIDENCE` | 종합 확신이나 축 확신에 `LOW` 가 있거나 후보 자신의 확신이 `MEDIUM`, `HIGH` 가 아니다 |
| 근거 부족 | `UNKNOWN_JUDGEMENT` | 여섯 축 가운데 빠지거나 `UNKNOWN` 인 축이 있다 |
| 근거 부족 | `STALE_EVALUATION` | 평가 시작 시각이나 `state.asOf` 가 `max-evaluation-age` 보다 오래됐다 |
| 근거 부족 | `STALE_EVIDENCE` | 근거 확인 시각이 없거나 `max-evidence-age` 보다 오래됐다 |
| 근거 부족 | `REPLAY_INPUT` | replay 평가다. 과거에 고정한 입력이라 지금의 실행 근거가 아니다 |
| 근거 부족 | `QUESTION_FOR_USER` | 행동 종류가 `QUESTION` 이다. 사용자가 답해야 한다 |
| 근거 부족 | `ACTION_UNDECLARED` | 행동 종류가 `ACTION`, `QUESTION` 이 아니다 |
| 승인 | `EXTERNAL_WRITE_REQUIRES_APPROVAL` | 부작용 힌트가 `EXTERNAL` 이다 |
| 승인 | `INTERNAL_WRITE_REQUIRES_APPROVAL` | 부작용 힌트가 `INTERNAL` 이다 |
| 승인 | `SIDE_EFFECT_UNDECLARED` | 부작용 힌트가 `NONE`, `INTERNAL`, `EXTERNAL` 이 아니다 |
| 승인 | `RISK_NOT_LOW` | `RISK` 가 `MEDIUM` 이나 `HIGH` 다 |
| 실행 막힘 | `VALUE_NOT_HIGH` | `EXPECTED_BENEFIT` 가 `HIGH` 가 아니거나 `GOAL_ALIGNMENT` 가 `LOW` 다 |
| 실행 막힘 | `COST_NOT_LOW` | `COST` 가 `LOW` 가 아니다 |
| 실행 막힘 | `USER_AUTONOMY_DISABLED` | 설치 설정과 사용자 동의 가운데 하나라도 꺼져 있다 |
| 실행 막힘 | `WRITE_BOUNDARY_OPEN` | 그 에이전트에 「먼저 살펴보기에 쓰기 도구 허용」 이 켜져 있다 |
| 실행 막힘 | `SOURCE_IS_AUTONOMOUS` | 원천 살펴보기가 자동 실행으로 시작했다 |
| 실행 막힘 | `AGENT_NOT_STARTABLE` | 에이전트가 없거나 지워졌거나 꺼졌거나 요청자가 읽을 수 없다 |
| 실행 막힘 | `EXECUTION_TAKEN` | 같은 판정에서 추천 순서가 앞선 후보가 `EXECUTE` 를 받았다 |
| 실행 막힘 | `ALREADY_EXECUTED` | 그 원천 살펴보기의 실행 키가 이미 있다. 다른 후보의 `SURFACE`, `ASK_APPROVAL` 은 그대로다 |
| 실행 | `READ_ONLY_SAFE` | 위의 까닭이 하나도 없다 |

## 자동 실행

`EXECUTE` 를 받은 판정은 같은 트랜잭션에서 `execution_key = check:<원천 살펴보기 식별자>` 와 `PENDING` 을 저장한다.
유일 제약에 걸리면 판정을 처음부터 한 번 다시 한다. 다시 하면 `ALREADY_EXECUTED` 가 붙는다.
커밋한 뒤 `ProactiveCheckService.startAutonomous` 로 그 에이전트의 살펴보기를 시작한다.

| 결과 | `execution_status` | 남기는 것 |
| --- | --- | --- |
| 시작했다 | `STARTED` | 새 살펴보기 식별자 `execution_check_id` |
| 시작 경로가 거절했다 | `FAILED` | 오류 코드 `execution_error`. 살펴보기 줄을 저장한 뒤 실패했으면 그 식별자도 남긴다. 다시 시작하지 않는다 |
| 저장 뒤 시작 전에 서버가 멈췄다 | `PENDING` 그대로 | 다시 시작하지 않는다 |

시작은 기존 경로의 검사를 모두 다시 거친다. 요청자가 그 에이전트를 시작할 수 있어야 하고, 시작 전 점검과 사용자 자리, 대화 잠금을 지금처럼 본다.
`AUTONOMY` 는 사용자가 누른 실행이 아니라 매일 깨우기처럼 백그라운드 자리를 쓴다.
그 에이전트에 쓰기 허용이 켜져 있으면 `PROACTIVE_CHECK_UNAVAILABLE` 로 거절한다. 판정 뒤 관리자가 켠 경우도 여기서 막힌다.
요청자의 점검 대화가 없으면 새로 만들지 않고 같은 코드로 거절한다. 사용자가 지운 대화를 빈 채로 되살리지 않기 위해서다.

### 자동 실행한 살펴보기의 결과

사용자에게 바로 알리지 않는다. 답과 보고, 발견을 남기지 않고 문제 후보만 저장하는 것은 ADR 이 갖는다. 그 밖의 처리는 아래다.

| 무엇 | 처리 |
| --- | --- |
| 시작 알림 줄 | 남기지 않는다. 지금처럼 `MANUAL` 만 남긴다 |
| 멈춤과 실패 알림 줄 | 남기지 않는다. 기동 정리가 다시 붙어 끝낸 경우도 같다. 살펴보기 줄의 상태와 오류 코드는 남긴다 |
| 마지막 살펴보기 | 살펴보기 상태 조회와 매일 깨우기 조회는 `AUTONOMY` 줄을 마지막 살펴보기로 보이지 않는다 |

자동 실행은 점검 대화의 Hermes session 을 그대로 쓴다. 그래서 그 문답이 다음 살펴보기의 모델 맥락에 들어간다.
자동 실행이 받아들인 문제 키도 다음 살펴보기의 중복 판정에 들어간다. 같은 문제가 바뀐 점 없이 다시 나오면 버려지지만, 그 후보는 이미 행동 정책을 거칠 수 있다.

## API

[매일 루프](proactive-loop.md)는 사용자가 켠 에이전트의 매일 깨우기 뒤 평가에 이어 `decide` 를 한 번 부른다. 판정 규칙과 자동 실행은 이 문서 그대로다.
관리자 영역 에이전트 상세의 「가치 평가」 절이 판정 결과를 읽기만 한다. 그 절의 단추는 평가와 판정을 함께 부른다([가치 평가](value-evaluation.md)의 「관리자 화면이 읽는 묶음」).
사용자 동의를 바꾸는 화면은 없다. 설치 설정 `execution-enabled` 가 꺼져 있는 동안 그 단추는 `EXECUTE` 를 만들지 못한다.
경로는 `AutonomyController` 가 갖는다.
다른 사용자의 평가는 관리자에게도 `VALUE_EVALUATION_NOT_FOUND` 다. `RUNNING` 평가는 `VALUE_EVALUATION_STATE_CONFLICT` 다.

## 설정

키와 기본값은 `AutonomyProperties` 가 갖는다.
