## ADR-059: native 하위 에이전트 사용량은 재조회 작업 줄을 원장으로 넓혀 합계에 더한다

- **status**: `accepted`
- **결정**: Hermes `delegate_task` 가 만든 native 자식의 사용량과 금액을 `subagent_usage_job` 한 줄에 적는다.
  새 `agent_execution` 줄을 만들지 않고, `execution_event` 에 금액을 두지도 않는다.
  월 합계와 축별 합계는 `agent_execution` 의 합에 이 줄의 합을 더하고, 금액을 확인하지 못한 자식 수를 함께 낸다.
- **맥락**: 부모 Runs API 의 `usage` 에는 native 자식의 토큰이 없다([`hermes/delegation.md`](../hermes/delegation.md) 의 「자식 session 으로 결과와 토큰을 보완한다」).
  지금까지 자식 토큰은 `execution_event` 의 `SUBAGENT_COMPLETED` 에 표시용으로만 남았다.
  합계는 `agent_execution` 만 더해 자식 비용이 빠졌고, 빠졌다는 사실도 보이지 않았다.
  자식 사용량이 들어오는 길은 둘이다. 부모 스트림의 `subagent.complete` 사건과, 부모가 끝난 뒤의 session 조회다.
  사건에는 cache 구분과 provider 가 없고 입력 토큰이 cache 를 포함한 값 하나로 온다.
  session 조회는 일반 입력, cache read, cache write 를 따로 준다.
  v0.21.5 의 session 응답에는 provider 칸이 없다([`hermes/runs-api.md`](../hermes/runs-api.md)).
- **대안 기각**:
  - native 자식마다 `agent_execution` 줄을 만든다. 실행 목록, 실행 나무, `activity` 요약, 위임 한도 질의가 모두 그 줄을 걸러야 한다.
    `agent_delegate` 로 만든 자식은 이미 자기 실행 줄이 있어, 같은 표에 두 종류의 자식이 섞인다.
  - `execution_event` 의 완료 사건에 금액 칸을 둔다. 두 길의 중복을 사건 쪽에서 다시 막아야 하고,
    아직 끝나지 않았거나 만료된 자식은 사건이 없어 완전성을 세려면 결국 재조회 작업 표를 함께 읽는다.
  - 사건의 토큰만으로 환산한다. cache 구분이 없어 cache read 에 입력 단가가 매겨지고 provider 를 알 수 없다.
  - 자식의 provider 를 부모의 것으로 채운다. 자식은 `delegation.provider` 와 `delegation.model` 로 따로 돌 수 있어 금액이 틀린다.
  - 합계를 DB 에서 축마다 따로 낸다. 자식 줄은 실행 줄보다 훨씬 적어 한 달치를 한 번 읽어 네 축에 나눠 더하는 편이 질의와 규칙이 하나로 남는다.
- **결과**:
  - 얻는 것:
    - `(execution_id, child_session_id)` 유일 키 한 줄에 두 길이 모여, 사건 수신과 늦은 조회가 같은 사용량을 두 번 더하지 않는다.
      새 줄을 만들 때는 같은 profile 의 같은 자식 session 줄이 이미 있는지도 본다.
    - 줄의 상태가 곧 완전성이다. `WAITING` 은 확인 중, `EXPIRED` 는 확인 실패, 금액 없는 `DONE` 은 가격 미확인이다.
    - 부모, native 자식, `agent_delegate` 자식이 서로 다른 줄에 있어 합계가 겹치지 않는다. native 자식 줄은 session 의 `source` 가 `subagent` 일 때만 채운다.
  - 감당할 것:
    - 부모가 끝난 자식은 완료 사건이 이미 있어도 session 을 한 번 조회한다. 조회 간격과 동시 조회 한도는 그대로다.
    - provider 를 읽지 못하면 금액을 비우고 `PROVIDER_UNKNOWN` 으로 남긴다. session 응답이 provider 를 주지 않는 동안 native 자식은 모두 가격 미확인으로 보인다. provider 를 읽는 경로는 이슈 #110 이 다룬다.
    - 실제 청구액은 부모 실행의 `cost_mode` 를 따른다. 자식이 부모와 다른 과금 경로로 돌았는지는 확인하지 않는다.
    - cache write 토큰에는 입력 단가를 쓴다. 가격표에 cache write 단가가 없다.
    - 표 이름은 `subagent_usage_job` 으로 둔다. 이름이 조회 작업을 가리키지만 줄의 뜻은 자식 한 명의 사용량 기록이다.
    - 한 달의 자식 줄이 수천을 넘으면 합계를 DB 에서 내도록 바꾼다.
- **적용 범위**: `usage` 패키지의 재조회, 월 합계, 축별 합계. 화면의 완전성 표시는 [`frontend/structure.md`](../frontend/structure.md) 가, 줄의 칸은 [`backend/schema/execution.md`](../backend/schema/execution.md) 가 갖는다.
