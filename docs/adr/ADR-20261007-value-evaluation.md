## ADR-20261007 / value-evaluation: 가치 판단은 축별 근거와 재평가 입력을 남기고 행동 정책과 분리한다

- **status**: `accepted`
- Date: 2026-10-07

### 맥락

살펴보기가 근거 있는 문제 후보를 남기지만 무엇을 먼저 다룰지는 정하지 않는다.
공고 마감 문제와 다음 분기 공부 문제가 함께 생기면 목표, 효과, 시간과 부담을 견줘야 한다.
점수 하나만 받으면 모델을 바꿨을 때 순서가 달라진 이유를 확인하기 어렵다.

### 결정

`DecisionProvider`가 후보 집합과 질문을 받아 판단만 한다. `ValueEvaluator`가 출력의 후보·축·근거 키·순서를 검사한다.
목표 적합성, 긴급성, 기대 효과, 비용, 위험, 근거 품질의 여섯 축을 둔다.
비용은 사용자 주의와 실행 부담을 합친다. 별도 선호 축과 학습은 이번에 만들지 않는다.
판단에는 선택, 확신, 설명과 provenance를 남긴다. 고정 가중치와 단일 우선순위 숫자는 만들지 않는다.

첫 adapter는 도구가 없는 시스템 profile에 Hermes Runs로 한 번 묻는다.
Control Plane이 사용자에게 보이는 판단 에이전트를 만들지 않는다.
설치 설정이 profile을 정하고 요청자 후보만 새 session에 넣는다. 실행은 요청자의 기존 백그라운드 한도와 사용량으로 남긴다.
모델 자격 증명과 provider 선택은 Hermes가 계속 맡는다.

호출 전에 plugin이 실제 effective toolset, `no_mcp`, 두 기억 저장 비활성화와 fallback 부재를 확인한다.
상류 `GET /v1/toolsets`는 기본 MCP를 빼므로 빈 응답만으로 준비 상태를 인정하지 않는다.
새로운 모델 API key를 Control Plane이 보관하는 직접 HTTP adapter는 이 경계를 만들 수 있어 채택하지 않았다.

입력 스냅샷과 질문을 호출 전에 저장한다. replay는 같은 후보와 기준 시각을 다른 provider에 넘겨 새 결과를 남긴다.
전체 Memory와 원문은 복제하지 않는다. 모델과 호출 실패는 빈 순서로 닫으며 다른 모델로 자동 재시도하지 않는다.
권한, 승인, 실행은 행동 정책의 별도 책임이다.

### 감당할 것

- 관련 목표와 기대 효과는 모델이 쓴 가설이다. 실제 사용자 목표와 일치하는지를 확인하지 않는다.
- 근거는 참조뿐이라 원문 내용, 마감과 비용을 다시 검증하지 못한다. 모르면 `UNKNOWN`으로 둔다.
- 같은 입력이어도 모델의 순서가 같다는 보장은 없다. replay는 입력을 고정해 차이를 설명하려는 기능이다.
- readiness와 실행 제출 사이의 관리자 설정 변경을 원자적으로 막지 않는다. 판단 profile을 일반 profile로 쓰지 않는 것은 운영 계약이다.
- 평가를 살펴보기에서 자동 호출하거나 사용자에게 추천으로 올리지 않는다. 다음 행동 정책이 연결한다.

### 근거와 결과

Hermes v2026.9.24의 [도구 계산](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/hermes_cli/tools_config.py)은
`no_mcp`로 기본 MCP 합집합을 막는다.
[기억 저장](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/memory_tool.py)은
`memory_enabled`, `user_profile_enabled`를 각각 읽는다.
도구만 끄거나 `memory.enabled`만 정하는 것으로 기억 주입까지 차단했다고 판단하지 않는다.
세부 계약은 [가치 평가](../../backend/docs/flow.md)가 갖는다.
