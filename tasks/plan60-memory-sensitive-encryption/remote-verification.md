# 원격 검증

실행 방법은 비공개 저장소 `fos-home-infra` 가 갖는다. 여기에는 확인할 것만 적는다.

| 선행 조건 | 실행 위치 | 명령 | 기대값 |
|---|---|---|---|
| 운영 설정에 `ASSISTANT_MEMORY_ENCRYPTION_ACTIVE_KEY_ID` 와 `ASSISTANT_MEMORY_ENCRYPTION_KEYS` 를 넣고 배포한 뒤 | 홈서버 | `fos-home-infra` 의 배포 확인 절차 | 컨테이너가 새로 만들어졌고 기동 로그에 `Started Assistant` 가 있으며 `Schema validation` 이 실패하지 않는다 |
| 같은 배포 뒤 | 홈서버 | `fos-home-infra` 의 로그 확인 절차 | 「평문으로 남은 민감 줄」 경고 로그가 없다 |
| 같은 배포 뒤 | 홈서버 | `fos-home-infra` 의 백업 절차 | 암호화 key 가 데이터베이스 백업과 다른 자리에 백업돼 있다 |
