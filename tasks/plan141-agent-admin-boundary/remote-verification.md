# 원격 검증

| 선행 조건 | 실행 위치 | 확인할 것 | 기대값 |
| --- | --- | --- | --- |
| 구현 PR 브랜치를 push한 뒤 | GitHub Actions | backend, quality, public-safe, pr-size와 저장소의 필수 checks | 관련 검사가 빠지지 않고 모두 성공한다 |
| 해당 구조 규칙 위반을 넣은 임시 검증 브랜치를 코디네이터가 허용한 경우 | GitHub Actions | 해당 backend 또는 quality job 결과 | 위반한 FQN과 규칙 설명을 포함해 실패한다. 검증 브랜치는 머지하지 않는다 |
| Hermes 연동 구현도 함께 달라진 경우에만 배포한 뒤 | 운영 저장소의 확인 절차 | 실제 실행의 요청·응답 왕복 | 기존 계약대로 끝난다. 방법과 환경 값은 fos-home-infra가 소유한다 |
