# 원격 검증

| 선행 조건 | 실행 위치 | 명령 | 기대값 |
|---|---|---|---|
| PR 브랜치를 push 한 뒤 | GitHub Actions | PR 의 CI `backend`, `web`, `browser-mobile`, `browser-desktop`, `e2e`, `unit`, `hermes`, `public-safe`, `quality` | 모두 통과 |
| 배포한 뒤 | 운영 웹 | 쓰기 도구가 있는 커넥터 에이전트로 몇 분 뒤 한 번 도는 예약 작업을 만든다 | 그 시각에 대화가 하나 생기고, 승인 요청 알림을 눌러 승인하면 실행 결과가 그 대화에 이어진다. 발화 기록은 한 줄이다. Hermes 와 실제로 한 번 왕복한다. 확인 절차는 운영 저장소가 갖는다 |
| 배포한 뒤 | 운영 | 예약 작업이 기다리는 동안 Control Plane 을 다시 띄운다 | 다시 뜬 뒤 그 회차가 한 번만 돈다 |
