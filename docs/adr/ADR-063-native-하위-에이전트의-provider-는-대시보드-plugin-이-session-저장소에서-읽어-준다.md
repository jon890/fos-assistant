## ADR-063: native 하위 에이전트의 provider 는 대시보드 plugin 이 session 저장소에서 읽어 준다

- **status**: `accepted`
- **결정**: 대시보드 plugin `dashboard-profile-api` 가 `GET /api/profiles/<이름>/sessions/<session id>/provider` 를 연다.
  이 경로는 그 profile 의 session 저장소를 읽기 전용으로 열어 자식 session 한 줄의 provider 와 모델만 돌려준다.
  Control Plane 은 session 응답에 provider 가 없는 종료 자식마다 이 경로를 한 번 불러 원장 줄에 provider 를 적고 가격표로 환산한다.
  읽지 못한 자식은 지금처럼 `PROVIDER_UNKNOWN` 으로 남긴다.
- **맥락**: [ADR-062](ADR-062-native-하위-에이전트-사용량은-재조회-작업-줄을-원장으로-넓혀-합계에-더한다.md) 가 native 자식의 토큰을 원장에 적지만,
  v0.21.5 의 `GET /api/sessions/{id}` 는 provider 를 내보내지 않아 금액이 모두 비었다.
  Hermes 는 그 값을 session 저장소의 `sessions.billing_provider` 에 적는다([`hermes/delegation.md`](../hermes/delegation.md) 의 「자식 session 의 provider 는 저장소에만 있다」).
  2026-10-02 에 운영 Hermes v0.21.5 에서 자식 session 115줄을 읽기 전용으로 집계했다. 115줄 모두 `billing_provider` 가 채워져 있었고, 줄마다 모델과 provider 의 짝이 하나였다.
  [ADR-001](ADR-001-hermes-를-런타임으로-두고-core-를-고치지-않는다.md) 의 검토 순서로 보면 profile 설정에는 이 값이 없고, API server 의 어느 경로도 이 값을 주지 않는다.
  남는 것은 plugin 이다.
- **대안 기각**:
  - profile plugin 의 `post_llm_call` hook 으로 호출마다 provider 를 Control Plane 에 보낸다. LLM 호출마다 gateway 스레드가 HTTP 를 한 번 더 기다리고, Control Plane 에 쓰는 경로와 그 중복 제거가 새로 필요하다.
    자식 한 명에 필요한 것은 종료 뒤의 값 하나다. 이 hook 이 자식 agent 의 호출에도 자식 session 번호로 불리는지는 확인하지 않았다.
  - `subagent_stop` hook 에서 받는다. v0.21.5 의 이 hook 은 provider 를 넘기지 않는다.
  - API server 의 응답을 감싸 provider 를 더한다. gateway 프로세스의 응답 직렬화를 바꾸는 것이라 core 를 고치는 것과 같다.
  - Hermes 의 `SessionDB` 를 가져다 쓴다. 대시보드의 읽기 전용 열기는 스키마가 낡았으면 쓰기 연결로 고치는 단계를 거친다. 값 하나를 읽는 경로가 저장소를 고칠 수 있게 된다.
  - 상류가 session 응답에 provider 를 싣는 판을 기다린다. 기한이 없고 그동안 native 자식의 금액이 모두 빈다.
  - 부모의 provider 로 채운다. ADR-062 가 이미 기각했다.
- **결과**:
  - 얻는 것:
    - native 자식의 금액이 자식이 실제로 돈 provider 와 모델로 환산된다.
    - 읽는 값이 provider 와 모델 둘뿐이고 대상이 `source` 가 `subagent` 인 줄뿐이다. 대화 본문, system prompt, 토큰 수는 이 경로로 나가지 않는다.
    - 저장소를 SQLite 의 읽기 전용 방식으로 열어 이 경로가 저장소의 내용을 바꿀 수 없다.
    - 상류가 session 응답에 provider 를 싣기 시작하면 Control Plane 은 그 값을 먼저 쓰고 이 경로를 부르지 않는다.
  - 감당할 것:
    - Hermes 의 공개 계약이 아닌 저장소 스키마에 기댄다. 기대는 것은 `sessions` 표의 `id`, `source`, `model`, `billing_provider` 와 `session_model_usage` 표의 `session_id`, `model`, `billing_provider`, `task` 다.
      Hermes 판을 올릴 때마다 이 이름을 다시 확인한다. 이름이 바뀌면 경로는 503 으로 답하고 자식은 가격 미확인으로 남는다. 금액이 틀리게 적히지는 않는다.
    - 대시보드 프로세스가 profile 의 session 저장소 파일을 읽을 수 있어야 한다.
    - 한 자식이 모델이나 provider 를 바꿔 가며 돌았으면 provider 를 주지 않는다. 한 줄에 금액 하나를 적는 원장으로는 그 자식을 환산하지 못한다.
    - 종료 자식마다 대시보드를 한 번 더 부른다. 대시보드가 답하지 못하면 자식이 끝난 뒤 10분 동안만 다시 부르고, 그 뒤에는 `PROVIDER_UNKNOWN` 으로 적는다.
      10분은 Hermes 가 적은 종료 시각과 Control Plane 의 시계로 센다. 자식이 부모보다 10분 넘게 먼저 끝났으면 다시 부르지 않는다.
    - 배포는 plugin 묶음을 먼저 올린다. 옛 plugin 은 이 경로를 401 로 답하고, Control Plane 은 그 답을 「읽지 못함」 으로 적는다. 그렇게 적힌 줄은 다시 조회하지 않는다.
- **적용 범위**: `hermes/plugins/dashboard-profile-api/` 의 읽기 경로와 `usage` 패키지의 재조회. 경로의 계약은 [`hermes/README.md`](../../hermes/README.md) 가, 원장 줄에 적는 규칙은 [`model-tiers.md`](../model-tiers.md) 의 「원장 줄에 적는 것」 이 갖는다.
