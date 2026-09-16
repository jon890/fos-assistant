## ADR-001: Hermes 를 런타임으로 두고 core 를 고치지 않는다

- Status: Accepted
- Date: 2026-09-17

### 맥락

Hermes Agent 는 이미 홈서버에서 돌고 있고 도구, skill, subagent, session 을 갖추고 있다.
가족용 비서에 필요한 실행 기능은 대부분 여기 있다.
Hermes 를 고쳐 쓰면 상류 갱신을 따라갈 때마다 우리 수정본을 다시 얹어야 한다.

### 결정

Hermes 는 Agent Runtime 으로만 쓰고 core 를 고치지 않는다.
쓰는 확장 지점은 profile, API server, plugin hook 셋이다.

우리 서비스는 Control Plane 을 맡는다.
사용자, Memory 접근 권한, credential 바인딩, 모델 라우팅, 사용량 집계가 여기 있다.

Hermes 를 고쳐야 할 것 같은 요구가 나오면 먼저 아래 순서로 검토한다.

1. profile 설정으로 되는가
2. Runs API 나 다른 엔드포인트로 되는가
3. plugin hook 으로 되는가
4. 셋 다 아니면 그때 상류에 제안한다

### 거절한 대안

- Hermes 를 fork 해 필요한 곳을 직접 고치는 방식은 상류를 따라갈 비용이 계속 들어 쓰지 않는다.
- Hermes 를 버리고 에이전트 루프를 직접 만드는 방식은 도구와 subagent 를 다시 만들어야 하므로 쓰지 않는다.
- Hermes 를 라이브러리로 품어 같은 프로세스에서 돌리는 방식은
  Python 런타임과 Java 서비스를 한 배포 단위로 묶어 버리므로 쓰지 않는다.

### 결과

MVP 는 Hermes 를 한 줄도 고치지 않고 성립한다.
plugin 도 필요 없고 profile 설정과 API server 만으로 된다.

대신 Hermes 가 주지 않는 것은 우리도 갖지 못한다.
호출 하나 단위의 모델 구분과 cached token 이 여기 해당하고, 그것이 필요해지면 plugin 을 만든다.
