# 원격 검증

| 선행 조건 | 실행 위치 | 확인 방법 | 기대값 |
| --- | --- | --- | --- |
| PR push | GitHub CI | backend, web, e2e, unit, hermes, quality, public-safe와 전체 브라우저 검사 | 필수 검사 통과 |
| 머지와 backend 배포 | 운영 환경 | 코디네이터가 오래된 기억이 허용된 에이전트로 새 실행을 시작하고 그 실행의 출처 기록을 읽는다. 실행 방법은 운영 저장소를 따른다 | 기본 180일을 넘은 기억에 STALE |
| 별도 기준 적용과 backend 배포 | 운영 환경 | collection별 기준을 0으로 설정한 뒤 새 실행의 출처 기록을 읽는다 | 해당 collection의 기억에 UNKNOWN |
