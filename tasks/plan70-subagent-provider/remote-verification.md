# 원격 검증

실행 방법은 비공개 저장소 `fos-home-infra` 가 갖는다. 여기에는 확인할 것만 적는다.

| 선행 조건 | 실행 위치 | 명령 | 기대값 |
|---|---|---|---|
| plugin 묶음을 올리고 대시보드를 다시 띄운 뒤 | 홈서버 | `fos-home-infra` 의 대시보드 plugin 확인 절차로 끝난 자식 session 하나의 provider 경로를 부른다 | 200 이고 `provider` 와 `model` 이 채워져 있다. 토큰 없이 부르면 401 이다 |
| 같은 배포 뒤 | 홈서버 | 그 profile 의 `state.db` 의 수정 시각을 호출 전후로 본다 | 이 경로를 불러도 바뀌지 않는다 |
| Control Plane 을 배포한 뒤 | 브라우저와 홈서버 | 하위 에이전트를 쓰는 대화를 한 번 왕복시키고, `fos-home-infra` 의 데이터베이스 조회 절차로 그 자식의 원장 줄을 본다 | 줄이 `DONE` 이고 `provider` 와 `estimated_cost_micros` 가 채워져 있으며 `unconfirmed_reason` 이 비었다 |
| 위가 통과한 뒤 | 브라우저 | 사용량 화면의 월 합계를 본다 | 그 자식이 가격 미확인 수에 세어지지 않는다 |
