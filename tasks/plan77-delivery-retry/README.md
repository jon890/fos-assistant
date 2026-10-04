# plan77 실패한 결과 전달의 보존과 다시 전달

자동 turn 이 부모 대화에 넘긴 결과를 전달 묶음과 시도로 남기고, 실패하거나 중지한 전달을 사용자가 화면에서 다시 전달한다.
결정은 `docs/adr/ADR-070-결과-전달은-묶음과-시도로-남기고-사용자가-저장된-결과만-다시-전달한다.md`,
흐름과 갈리는 지점은 `docs/backend/agent-delegation.md` 의 「결과 전달이 끝나지 않았을 때」,
표의 칸은 `docs/backend/schema/chat.md` 의 `result_delivery`, `result_delivery_item`, `result_delivery_attempt`,
화면은 `docs/frontend/chat.md` 의 「결과 다시 전달」 이 갖는다.

## 구현 순서

phase 는 번호 순서로 한다. 뒤 phase 가 앞 phase 의 클래스와 표를 쓴다.

| phase | 무엇 | 쓰는 앞 phase |
| --- | --- | --- |
| 01 | 표 셋과 엔티티, `ResultDeliveryRecorder`, 자동 turn 이 묶음과 첫 시도를 남기고 닫는다 | 없다 |
| 02 | 기동할 때 남은 시도를 닫는다. 기동 정리가 정한 부모 실행 줄의 시도도 닫는다 | 01 |
| 03 | 다시 전달 API 와 이력 API 의 `delivery` | 01, 02 |
| 04 | e2e 시나리오 | 03 |
| 05 | 화면 | 03 |

## 성공 기준

이슈의 완료 기준을 아래 테스트가 관측한다.

| 완료 기준 | 관측하는 테스트 |
| --- | --- |
| 알림 트랜잭션 뒤, 실행 줄이 생기기 전의 실패와 재기동 뒤에도 복구할 수 있다 | phase 01 `ResultDeliveryRecordTest`, phase 02 `ResultDeliveryRecoveryTest`, phase 03 `ResultDeliveryRetryTest` |
| 부모 provider 실패 뒤 저장된 결과로 결과만 다시 전달한다 | phase 03 `ResultDeliveryRetryTest`, phase 04 `delivery-retry.ts` |
| 완료 사건 중복, 재접속, 버튼 연속 입력에도 활성 시도는 하나다 | phase 01 `ResultDeliveryRecordTest`, phase 03 `ResultDeliveryRetryTest`, phase 05 `chat-delivery-retry.spec.ts` |
| 복구 중 커넥터 execute 와 자식 submit 수가 늘지 않는다 | phase 03 `ConnectorDeliveryRetryTest`, `ResultDeliveryRetryTest`, phase 04 `delivery-retry.ts` |
| 성공, 실패, 사용자 중단을 구분해 기록한다 | phase 01 `ResultDeliveryRecordTest` |
| 권한이 사라진 뒤의 재시도는 지금 권한을 따른다 | phase 03 `ResultDeliveryRetryTest` |
| 기동 정리(#118)와 사용자 실행 한도(#157)의 회귀 테스트를 지킨다 | 모든 phase 의 `./gradlew test`. 기존 테스트를 지우거나 약하게 바꾸지 않는다 |
| 테스트와 공개 기록에는 합성 결과만 쓴다 | 모든 phase. 결과 글은 「조사 결과」 같은 지어낸 글이다 |

## 모든 phase 에 걸리는 것

- 이미 적용된 마이그레이션 파일은 고치지 않는다. 새 표는 `V66__result_delivery.sql` 하나에 둔다. DDL 만 담는다
- 기존 테스트(위임, 결과 전달, 대화 잠금, 취소, 기동 정리, 사용자 실행 한도)를 지우거나 단언을 약하게 바꾸지 않는다. 바뀐 시그니처를 따라 고치는 것만 한다
- 공개 저장소다. 운영 값(주소, 포트, 컨테이너, 경로, DB 이름)을 코드와 테스트와 커밋에 적지 않는다
- 하위 에이전트는 orca 명령을 쓰지 않는다
