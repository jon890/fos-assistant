## ADR-20261121 / sandbox-default-policy: 실행 공간 정책에 목록 밖 named profile 을 위한 기본 설정을 둔다

- **status**: `accepted`
- Date: 2026-11-21
- **decision-type**: `architecture`
- **areas**: `operations`
- **현재 규칙의 원본**: [셸 실행 공간](../../README.md#셸-실행-공간)

### 맥락

named profile 마다 운영이 정책에 줄을 넣어야 셸을 켤 수 있었다. 새 에이전트가 같은 공통 격리 설정을 쓰는 경우에도 운영 반영을 기다렸다.

### 결정

운영이 `isolate_unlisted` 를 켜면 목록 밖 named profile 에 공통 Docker 설정을 적용한다. `profiles` 에 명시한 설정은 우선 적용한다. 목록 밖 profile 에는 별도 마운트와 env 를 추가하지 않는다. `default` 는 적용 대상에서 제외한다.

기본값은 거짓으로 두어 이전 정책을 그대로 읽는다. 정책을 바꾸기 전에 이 키를 아는 plugin 을 배포한다. 운영 절차와 실제 값은 fos-home-infra 에서 관리한다.

### 대안

`profiles` 에 와일드카드 키를 넣는 안은 named profile 이름 검증과 충돌한다. 최상위 참 거짓 값은 이미 정의된 공통 설정을 그대로 쓰고, 값을 지워 이전 동작으로 돌아갈 수 있다.

근거는 [에이전트 도구 스펙](../../../docs/features/agent-tools/spec.md) 의 DECISION3 과 [설계](../../../docs/features/agent-tools/design.md) 의 PR2 에 있다.
