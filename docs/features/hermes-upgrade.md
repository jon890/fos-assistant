# Hermes 버전 올리기

운영 Hermes 를 새 버전으로 올릴 때 Control Plane 과 Hermes 에 설치하는 plugin 이 함께 맞춰야 하는 계약을 다룬다.

covers: `hermes/tests/hermes_contract.py`, `scripts/checks/check-hermes-contract.sh`

지금 연동 기준 버전은 `hermes/tests/hermes_contract.py` 의 `HERMES_VERSION` 과 `HERMES_COMMIT` 이 갖는다.
버전마다 달라지는 Hermes 의 동작은 [`hermes/docs/hermes-contract.md`](../../hermes/docs/hermes-contract.md) 의 「Hermes 를 올릴 때」 가 갖는다.

v0.23.2 로 올리는 변경의 요구와 결정은 스펙 [`hermes-upgrade/spec.md`](hermes-upgrade/spec.md)가 갖는다. 아직 구현하지 않았다.

## 올리는 순서

1. `scripts/checks/check-hermes-contract.sh <tag>` 로 새 버전의 소스를 계약 테스트에 넣어 실패 목록을 얻는다.
2. 실패마다 Control Plane, plugin, 계약 문서 가운데 무엇을 고칠지 정하고, 지금 버전과 새 버전에서 함께 동작하게 고쳐 배포한다([rule-66](../code-rules/rule-66.md)).
3. 이미지 빌드, 복제 데이터 리허설, 운영 적용과 되돌리기는 `fos-home-infra` 가 갖는다.
4. 적용한 뒤 실제 실행을 왕복시켜 보고, 기준 버전과 계약 문서를 새 버전으로 고친다.
