# 원격 검증

| 선행 조건 | 실행 위치 | 명령 | 기대값 |
|---|---|---|---|
| PR 브랜치를 push 한 뒤 | GitHub Actions | PR 의 CI `backend`, `web`, `browser-mobile`, `browser-desktop`, `e2e`, `unit`, `hermes`, `public-safe`, `quality` | 모두 통과 |
| 배포한 뒤 | 운영 웹 | 승인이 필요한 커넥터 도구를 부르는 대화를 하나 연 뒤 다른 화면으로 옮긴다 | 알림 단추에 수가 보이고, 알림을 누르면 승인 카드가 있는 그 대화로 간다. 확인 절차는 운영 저장소가 갖는다 |
