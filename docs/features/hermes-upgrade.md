# Hermes 버전 올리기

운영 Hermes 를 새 버전으로 올릴 때 Control Plane 과 Hermes 에 설치하는 plugin 이 함께 맞춰야 하는 계약을 다룬다.

covers: `hermes/tests/hermes_contract.py`, `scripts/checks/check-hermes-contract.sh`, `backend/src/main/java/com/bifos/assistant/hermes/infra/HermesSessionRuns.java`, `backend/src/main/java/com/bifos/assistant/usage/execution/application/LostRunRelease.java`

지금 연동 기준 버전은 `hermes/tests/hermes_contract.py` 의 `HERMES_VERSION` 과 `HERMES_COMMIT` 이 갖는다.
버전마다 달라지는 Hermes 의 동작은 [`hermes/docs/hermes-contract.md`](../../hermes/docs/hermes-contract.md) 의 「Hermes 를 올릴 때」 가 갖는다.

v0.23.2 로 올리는 변경의 요구와 결정은 스펙 [`hermes-upgrade/spec.md`](hermes-upgrade/spec.md), 구조는 설계 [`hermes-upgrade/design.md`](hermes-upgrade/design.md)가 갖는다.
지금은 Control Plane 이 두 버전의 실행 조회를 함께 읽는다(PR1). fos-ctx, 계약 테스트, 본문 없는 요청 진단은 아직 v0.21.6 에만 맞고, 운영 Hermes 는 v0.21.6 이다.

## 두 버전의 실행 조회

Hermes 의 상태와 사건 이름은 `hermes/infra` 가 옮긴다. application 은 Hermes 문자열을 보지 않는다.

- v0.23.2 의 `queued` 는 도는 것으로, `unknown` 은 해제한 뒤 잃은 실행으로 읽는다. 잃은 turn 은 `REMOTE_RUN_LOST` 로 실패하고 화면 문구는 연결 실패와 같다
- 조회에 없는 `error`, `pending_steer`, `runtime` 은 끝 사건으로 채우고, 얻지 못하면 끼운 글을 「전달됐는지 확인 못 함」 으로 둔다
- 새 실행을 보내기 전에 같은 profile 의 최근 연결 실패·시간 초과 실행을 잃은 것이면 해제한다(`LostRunRelease`)

규칙과 근거는 [Hermes 계약](../../hermes/docs/hermes-contract.md) 의 「v0.23.2 의 session 대기열」 과 [ADR-061](../../backend/docs/adr/ADR-061-재기동-때-남은-실행은-hermes-에-물어-정하고-도는-실행에는-다시-붙는다.md) 이 갖는다.

## 올리는 순서

1. `scripts/checks/check-hermes-contract.sh <tag>` 로 새 버전의 소스를 계약 테스트에 넣어 실패 목록을 얻는다.
2. 실패마다 Control Plane, plugin, 계약 문서 가운데 무엇을 고칠지 정하고, 지금 버전과 새 버전에서 함께 동작하게 고쳐 배포한다([rule-66](../code-rules/rule-66.md)).
3. 이미지 빌드, 복제 데이터 리허설, 운영 적용과 되돌리기는 `fos-home-infra` 가 갖는다.
4. 적용한 뒤 실제 실행을 왕복시켜 보고, 기준 버전과 계약 문서를 새 버전으로 고친다.
