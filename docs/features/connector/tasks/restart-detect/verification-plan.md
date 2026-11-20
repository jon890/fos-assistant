# 재시작 감지 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ3 | `plugin_updated` 답으로 재시작 대기가 된 바인딩 둘. 하나는 가짜 기동 시각보다 앞서, 하나는 뒤에 대기가 됐다 | `backend/src/test/java/com/bifos/assistant/connector/ConnectorGatewayRestartTest.java` 의 재시작 감지 사례를 돌린다 | 앞선 바인딩만 반영 맞추기를 거쳐 `READY`, 뒤의 바인딩은 재시작 대기로 남는다 | H2 통합 테스트, 대시보드 대역 | worker | 없음 | PR 전 |
| REQ3 | 대시보드 plugin 과 가짜 pid, 임시 `/proc` | `hermes/tests/test_dashboard_profile_api_gateway.py` 를 돌린다 | `GET /api/gateway` 가 기동 시각 정수를 답하고 정리 없는 부름(`cleanup_stale=False`)만 쓴다 | 가짜 Hermes 모듈 | worker | 없음 | PR 전 |
| REQ3 | `hermes_contract.py` 의 `gateway.status.get_running_pid` 선언 | CI 의 hermes 계약 job 이 Hermes v0.23.2 소스와 대조한다 | 통과 | Draft CI | worker | push | PR 뒤 |
| REQ4 | 재시작 대기 바인딩, 기동 시각 대역이 빈 값 | `ConnectorGatewayRestartTest` 의 기동 시각 모름 사례를 돌린다 | 바인딩이 재시작 대기로 남고 경고 로그가 한 줄, 같은 상태가 이어지면 더 남지 않는다 | H2 통합 테스트 | worker | 없음 | PR 전 |
| REQ4 | 대시보드가 404 나 `null` 을 답한다 | `backend/src/test/java/com/bifos/assistant/hermes/HttpHermesGatewayClientTest.java` 를 돌린다 | 기동 시각이 비어 있다 | MockRestServiceServer | worker | 없음 | PR 전 |
| REQ7 | 재시작 대기 바인딩, 기동 시각이 대기보다 앞섬 / 대기 뒤 / 모름 | `ConnectorGatewayRestartTest` 의 다시 확인 사례를 돌린다 | 앞서면 `CONNECTOR_RESTART_NOT_DONE` 이고 상태와 설치 호출이 그대로, 대기 뒤와 모름은 지금처럼 `READY` | H2 통합 테스트 | worker | 없음 | PR 전 |
| REQ3 | 운영에 배포된 Control Plane 과 대시보드 plugin | 배포 뒤 공유 gateway 재시작 한 번 뒤, 재시작 대기 바인딩이 관리자 조작 없이 `READY` 가 되는지 `fos-home-infra` 의 절차로 본다 | 다음 30초 주기 안에 `READY` | 운영 환경 | 코디네이터 | 배포 | PR 뒤 |
