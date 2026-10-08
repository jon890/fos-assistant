# 행동 정책

가치 평가를 받은 문제 후보마다 무엇까지 해도 되는지 정한다.
결정은 [ADR-20261007 / autonomy-policy](../adr/ADR-20261007-autonomy-policy.md)에 있다.
입력 계약은 [가치 평가](value-evaluation.md)의 「API와 다음 행동 정책의 입력」 이 갖는다.
판정은 모델을 부르지 않는다. 같은 입력이면 같은 수준과 같은 까닭 코드가 나온다.

## 행동 수준

| 수준 | 뜻 | 이번에 하는 일 |
| --- | --- | --- |
| `IGNORE` | 다루지 않는다 | 판정만 남긴다 |
| `SURFACE` | 사용자에게 보일 가치가 있지만 실행 근거나 허락이 없다 | 판정을 남긴다. [매일 루프](proactive-loop.md)가 낸 판정이면 지금 화면 「내 차례」 에 보인다. 관리자 영역의 가치 평가 절은 판정을 읽기만 한다 |
| `ASK_APPROVAL` | 쓰기나 위험이 있어 사람의 승인 없이는 하지 않는다 | 판정을 남긴다. 새 승인 줄을 만들지 않는다. 매일 루프가 낸 판정이면 「직접 처리할 일」 로 지금 화면에 보인다 |
| `EXECUTE` | 읽기 전용 살펴보기를 한 번 시작한다 | 실행 키를 저장한 뒤 시작한다 |

외부 쓰기는 기존 커넥터 승인 경로만 거친다. 도구 호출마다 `ToolPolicyDecision.decide` 가 판정하고 승인 줄은 `connector_action` 이 갖는다.
이 정책이 그 판정을 넓히지 않는다.

## 입력

| 입력 | 어디서 | 정책이 보는 것 |
| --- | --- | --- |
| 평가 | `ValueEvaluationStore.read` | 결과 상태, replay 여부, 시작 시각, `state.asOf`, 후보별 축 판단과 확신, 추천 순서 |
| 지금의 후보 | `proactive_check_problem` | 평가의 후보와 같은 살펴보기의 `ACCEPTED` 줄이고, 문제 키와 행동 종류, 행동, 부작용 힌트가 스냅샷과 같은가 |
| 원천 살펴보기 | `proactive_check` | 요청자의 것인가, 시작 계기가 `AUTONOMY` 인가 |
| 에이전트 | `agent` | 요청자가 읽을 수 있고 켜져 있는가, 「먼저 살펴보기에 쓰기 도구 허용」 이 꺼져 있는가 |
| 실행 키 | `proactive_autonomy_decision.execution_key` | 그 원천에서 이미 자동 실행을 잡았는가 |
| 설치 설정 | `assistant.autonomy.execution-enabled` | 자동 실행을 연 설치인가. 기본 `false` |
| 사용자 동의 | `user_autonomy_preference.read_only_execution` | 사용자가 읽기 전용 자동 실행을 켰는가. 줄이 없으면 꺼짐 |

판정에 쓴 값은 `inputs_json` 에 남긴다. 축 글과 후보 글은 복제하지 않는다.

## 까닭 코드와 수준

모든 까닭을 모은 뒤 아래 순서로 수준을 정한다. 앞 묶음에 하나라도 있으면 뒤를 보지 않는다.

1. 다루지 않음 묶음이 있으면 `IGNORE`
2. 근거 부족 묶음이 있으면 `SURFACE`
3. 승인 묶음이 있으면 `ASK_APPROVAL`
4. 실행 막힘 묶음이 있으면 `SURFACE`
5. 아무것도 없으면 `EXECUTE` 와 `READ_ONLY_SAFE`

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

**모델이 권한을 넓히지 못한다.**
모델의 값은 까닭을 더할 수만 있다. 확신 `HIGH` 와 부작용 힌트 `NONE` 은 까닭을 더하지 않을 뿐 수준을 올리지 않는다.
허락에 해당하는 입력(설치 설정, 사용자 동의, 쓰기 허용, 원천과 실행 키)은 모두 Control Plane 의 기록에서 읽는다.

`EXECUTE` 는 한 판정에서 하나다. 평가가 `EVALUATED` 이면 추천 순서, 아니면 후보 식별자 순서로 보고 첫 후보만 남긴다.

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

사용자에게 바로 알리지 않는다.

| 무엇 | 처리 |
| --- | --- |
| 시작 알림 줄 | 남기지 않는다. 지금처럼 `MANUAL` 만 남긴다 |
| 답과 다섯 칸 보고 | 남기지 않는다 |
| 발견 | 저장하지 않는다. 사용자가 보지 못한 발견이 다음 살펴보기에서 이미 알린 것으로 내려가지 않게 한다 |
| 문제 후보 | 지금처럼 검사해 저장한다. 다시 가치 평가와 이 정책을 거쳐야 사용자에게 갈 수 있다 |
| 멈춤과 실패 알림 줄 | 남기지 않는다. 기동 정리가 다시 붙어 끝낸 경우도 같다. 살펴보기 줄의 상태와 오류 코드는 남긴다 |
| 마지막 살펴보기 | 살펴보기 상태 조회와 매일 깨우기 조회는 `AUTONOMY` 줄을 마지막 살펴보기로 보이지 않는다 |

자동 실행은 점검 대화의 Hermes session 을 그대로 쓴다. 그래서 그 문답이 다음 살펴보기의 모델 맥락에 들어간다.
자동 실행이 받아들인 문제 키도 다음 살펴보기의 중복 판정에 들어간다. 같은 문제가 바뀐 점 없이 다시 나오면 버려지지만, 그 후보는 이미 행동 정책을 거칠 수 있다.

## 저장

판정 줄은 `proactive_autonomy_decision`, 사용자 동의는 `user_autonomy_preference` 에 둔다. 칸은 [`schema/proactive.md`](schema/proactive.md) 가 갖는다.
판정 하나가 후보 하나다. 같은 평가를 다시 판정하면 새 줄을 남긴다.

`inputs_json` 은 `AutonomyInputs` 다.
평가 결과와 replay 여부, 평가 시각과 `asOf`, 판정 시각, 지금의 후보가 맞는지, 행동 종류와 부작용 힌트, 후보 확신,
축별 선택과 확신, 종합 확신, 근거 확인 시각, 설치 설정과 사용자 동의, 쓰기 허용, 원천의 계기, 에이전트 시작 가능, 실행 키 여부를 둔다.
축 설명과 후보 글은 복제하지 않는다.

## API

[매일 루프](proactive-loop.md)는 사용자가 켠 에이전트의 매일 깨우기 뒤 평가에 이어 `decide` 를 한 번 부른다. 판정 규칙과 자동 실행은 이 문서 그대로다.
관리자 영역 에이전트 상세의 「가치 평가」 절이 판정 결과를 읽기만 한다. 그 절의 단추는 평가와 판정을 함께 부른다([가치 평가](value-evaluation.md)의 「관리자 화면이 읽는 묶음」).
사용자 동의를 바꾸는 화면은 없다. 설치 설정 `execution-enabled` 가 꺼져 있는 동안 그 단추는 `EXECUTE` 를 만들지 못한다.

| 메서드와 경로 | 하는 일 |
| --- | --- |
| `POST /api/v1/value-evaluations/{id}/autonomy-decisions` | 요청자의 평가를 판정하고 후보별 수준, 까닭, 실행 상태를 201 로 준다 |
| `GET /api/v1/autonomy/preference` | 요청자의 동의를 읽는다 |
| `PUT /api/v1/autonomy/preference` | `{ "readOnlyExecution": true 또는 false }` 를 저장한다 |

다른 사용자의 평가는 관리자에게도 `VALUE_EVALUATION_NOT_FOUND` 다. `RUNNING` 평가는 `VALUE_EVALUATION_STATE_CONFLICT` 다.
응답에는 시작한 살펴보기 식별자와 입력 원문을 싣지 않는다.

## 설정

`assistant.autonomy` 설정이다.

| 칸 | 기본값 | 뜻 |
| --- | --- | --- |
| `execution-enabled` | `false` | 설치가 자동 실행을 연다. 꺼져 있으면 `USER_AUTONOMY_DISABLED` |
| `max-evaluation-age` | `1h` | 이보다 오래된 평가는 실행 근거가 아니다 |
| `max-evidence-age` | `72h` | 이보다 오래 전에 확인한 근거는 실행 근거가 아니다 |

## 기록할 사건

[판단 피드백](decision-feedback.md)이 읽는 자리다. 판정과 실행 상태는 이 표의 줄에 남고, 판단 피드백은 아래 둘만 사건으로 더한다.
자동 실행을 시작하지 못하면 `autonomy_decision:<번호>` 의 `EXECUTION_FAILED`, 시작한 살펴보기가 끝나면 `proactive_check:<번호>` 의 `EXECUTION_SUCCEEDED` 나 `EXECUTION_FAILED` 다.

| 때 | 어디에 남는가 |
| --- | --- |
| 판정 | `proactive_autonomy_decision` 의 새 줄 |
| 자동 실행을 잡음 | 같은 줄의 `execution_key`, `PENDING` |
| 자동 실행 시작과 실패 | 같은 줄의 `execution_status`, `execution_check_id`, `execution_error` |
| 자동 실행의 결과 | `execution_check_id` 의 살펴보기 줄과 그 문제 후보 |
| 승인 | 기존 `connector_action` 줄 |

## 검증

정책 단위 시험은 높은 가치이면서 부작용 힌트가 `EXTERNAL` 인 후보가 확신과 동의에 상관없이 `ASK_APPROVAL` 인지 본다.
`UNKNOWN` 축, `FALLBACK`, replay, 오래된 평가와 근거가 `EXECUTE` 로 가지 않는지 본다.
저장 시험은 실행 키로 같은 원천에서 두 번 시작하지 않는지, 판정이 `EXECUTE` 가 아니면 시작 경로를 부르지 않는지 본다.
살펴보기 시험은 `AUTONOMY` 가 쓰기 허용 에이전트를 Hermes 호출 전에 거절하고 답과 보고를 남기지 않는지 본다.
