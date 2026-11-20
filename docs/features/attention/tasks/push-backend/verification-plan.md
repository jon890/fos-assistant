# 웹 푸시 PR1a(backend) 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ1 | 키를 설정한 테스트 컨텍스트, 사용자 줄 하나 | `PushDeviceServiceTest` 와 `PushControllerTest` 로 같은 주소를 두 번 등록하고 목록을 읽는다 | 목록에 한 줄, 응답에 `endpointHash` 가 있고 주소와 키 원문은 없다 | H2 통합 테스트 | worker | 없음 | PR 전 |
| REQ1 | 키가 없는 테스트 컨텍스트 | `GET /api/v1/push/config` 와 등록을 부른다 | `enabled: false`, 등록은 409 `PUSH_NOT_CONFIGURED` | H2 통합 테스트 | worker | 없음 | PR 전 |
| REQ9 | 사용자 줄 둘 | A 가 등록한 주소를 B 가 등록하고, 같은 주소를 두 스레드가 동시에 등록하고, `DELETE /devices/current` 와 접근 해제 사건을 낸다 | 주소는 마지막 사용자 한 명에게만 있고, 해제와 접근 해제 뒤 목록이 비어 있다 | H2 통합 테스트(동시성), CI 의 MySQL 검사(전용 쿼리 문법만) | worker | 없음 | PR 전 |
| REQ5 | 사용자 둘이 각자 기기를 등록, 가짜 `WebPushClient` | `PushDispatcherTest` 로 A 에게 알림을 만든다 | A 의 기기 주소로만 요청이 오고, 본문을 테스트의 사용자 에이전트 비밀 키로 풀면 제목과 본문, `/n/<공개 식별자>` 가 나온다 | H2 통합 테스트(가짜 보내기 클라이언트) | worker | 없음 | PR 전 |
| REQ5 | RFC 8291 부록 A 의 예시 값 | `WebPushEncryptorTest` | RFC 와 같은 암호문 | 단위 테스트 | worker | 없음 | PR 전 |
| REQ7 | 응답을 늦추거나 오류를 내는 가짜 `WebPushClient` | 알림을 만드는 트랜잭션을 돌리고 걸린 시간과 저장을 본다 | 트랜잭션이 바로 커밋되고 알림이 남는다. 되돌린 트랜잭션은 보내지 않는다 | H2 통합 테스트 | worker | 없음 | PR 전 |
| REQ8 | 410, 500 을 답하는 가짜 `WebPushClient` | 알림을 만들고 기기 목록과 요청 수를 본다 | 410 은 기기가 빠지고, 500 은 1 + 다시 시도 횟수만 요청이 온다. 로그에 주소와 키가 없다 | H2 통합 테스트 | worker | 없음 | PR 전 |
| REQ5, REQ7 | 운영에 VAPID 키가 들어간 배포 | 화면이 없으므로 이 PR 단독으로는 실제 기기 확인을 하지 않는다. 배포 뒤 Control Plane 이 키를 읽고 기동했는지와 `/api/v1/push/config` 가 `enabled: true` 인지 본다 | 기동 성공, `enabled: true` | 운영 배포 | 코디네이터 | 배포: fos-home-infra 의 키 넣기와 이 PR 머지 뒤 배포 | PR 뒤 |
