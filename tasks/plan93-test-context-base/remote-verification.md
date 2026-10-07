# 원격 검증

| 선행 조건 | 실행 위치 | 명령 | 기대값 |
|---|---|---|---|
| PR 브랜치를 push 한 뒤 | GitHub Actions | PR 의 CI `backend` job(MySQL 마이그레이션과 `mysql` 태그 검사 포함) | 통과. H2 검사를 상속한 MySQL 검사 넷이 공통 기반을 물려받고도 통과한다 |
| PR 브랜치를 push 한 뒤 | GitHub Actions | PR 의 CI 필수 검사 전체(backend, web, e2e, unit, hermes, quality, public-safe) | 모두 통과 |
