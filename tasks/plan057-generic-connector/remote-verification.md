# 원격 검증

이 plan 의 PR 이 머지된 뒤에 한다. 그 전에 두 가지가 운영에 있어야 한다: 가계부 plugin 저장소의 `connector.json`, 운영 커넥터 목록의 새 object 모양(가계부 공통 주소를 `env` 로 준다).
배포 순서는 ADR-041 대로 `hermes/` 묶음을 먼저 설치하고 Control Plane 을 올린다.

| 선행 조건 | 실행 위치 | 명령 | 기대값 |
|---|---|---|---|
| PR 을 push 한 뒤 | GitHub Actions | PR 의 CI | 모든 job 통과 |
| 묶음 설치 뒤, Control Plane 배포 전 | 운영 Hermes 대시보드 | 대시보드 plugin live 검사와 `GET /api/connectors/catalog` | 가계부가 카탈로그에 있고, 옛 Control Plane 의 가계부 연결 확인이 그대로 READY |
| Control Plane 배포 뒤 | 운영 DB | Flyway 판과 `connector_connection` 행 | v38, 옛 가계부 행이 모두 `connector_id = 'fos-accountbook'` 으로 옮겨졌고 상태와 에이전트가 같음 |
| Control Plane 배포 뒤 | 웹 화면 | 「연결」 메뉴에서 가계부 카드와 내 상태 | 이미 연결한 사용자는 「연결됨」 |
| Control Plane 배포 뒤 | 웹 대화 | 가계부 에이전트로 이번 달 합계 묻기 | 가계부 도구로 답한다 |
