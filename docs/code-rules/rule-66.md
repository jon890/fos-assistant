# rule-66

plugin은 전환 배포 동안 이전 Control Plane 호출을 받는다.

검사: [이전 호출 호환](../../hermes/tests/test_dashboard_profile_api_connector_install_legacy.py), [리뷰](../../.github/workflows/code-review-prompt.txt)

## 까닭

Control Plane과 plugin을 서로 다른 시점에 배포해도 호출이 끊기지 않게 한다.

## 예외와 검토 범위

새 경로를 먼저 더하고 이전 경로 제거는 다음 배포로 미룬다.
배포 순서는 Hermes 아키텍처 문서가 갖는다.

## 이 저장소의 사례

[code-architecture.md](../../hermes/docs/code-architecture.md)에서 확인한다.
Hermes 아키텍처 문서는 plugin과 Control Plane의 전환 배포 순서를 정한다.

책임과 실행 절차는 [code-architecture.md](../../hermes/docs/code-architecture.md)에서 이어 읽는다.
