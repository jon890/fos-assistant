# Phase 03. 결과 전달 실패 후보

**Execution profile**: standard

## 목표

결과 전달 묶음(ADR-075)이 `FAILED` 로 남은 대화를 `DELIVERY_FAILED` 후보로 만든다.
같은 대화의 실패한 turn 과 한 항목으로 합쳐 실패 카드에 보인다.
이 phase 가 이 plan 의 마지막이다. 먼저 알리기의 「아직 구현 전」 표시를 지우고, 할 일 쪽 표시만 남긴다.

**범위 외**: 결과 전달 상태를 저장하거나 고치는 일, 「결과 다시 전달」 경로(`chat` 패키지와 ADR-075 가 갖는다). 화면의 단추(plan81).

## 컨텍스트

**근거 문서**: `docs/backend/attention.md` 의 「후보와 trigger」 표의 `DELIVERY_FAILED` 줄, 「「왜 보였는가」」 의 `signals` 표의 `DELIVERY_NOT_DONE`, 「카드의 단추와 승인 경계」. `docs/adr/ADR-072-먼저-알리기의-기본값은-알리지-않음이고-control-plane-기록에서-정한-신호만-화면-안에-올린다.md` 의 「적용 범위」. `docs/adr/ADR-075-결과-전달은-묶음과-시도로-남기고-사용자가-저장된-결과만-다시-전달한다.md` 와 `docs/frontend/chat.md` 의 「결과 다시 전달」.

결과 전달 기록은 PR #167 로 main 에 있다. 클래스 경로는 모두 `backend/src/main/java/com/bifos/assistant/` 아래다.

| 무엇 | 어디 |
| --- | --- |
| 표 | `backend/src/main/resources/db/migration/V67__result_delivery.sql` 의 `result_delivery`(`id`, `conversation_id`, `status`, `attempt_count`, `created_at`, `updated_at`), `result_delivery_attempt`(`id`, `delivery_id`, `attempt_no`, `status`, `execution_id`, …), `result_delivery_item`. 사용자 칸이 없다. 사용자는 `conversation.user_id` 로 잇는다 |
| 묶음 엔티티 | `chat.domain.ResultDelivery`. `status` 는 `chat.domain.type.DeliveryStatus` 의 `DELIVERING`(시도가 도는 중), `DELIVERED`(부모 turn 이 답을 남겼다), `FAILED`(부모 turn 이 실패했다), `STOPPED`(사용자가 부모 turn 을 중지했다) |
| 시도 엔티티 | `chat.domain.ResultDeliveryAttempt`(`attemptNo` 등). 시도를 하나 더할 때마다 묶음의 `attempt_count` 가 하나 는다(`ResultDeliveryRepository.claimRetry`). 그래서 묶음의 `attempt_count` 가 마지막 시도의 `attempt_no` 다 |
| 묶음 저장소 | `chat.infra.ResultDeliveryRepository`(`findByConversationId`, `changeStatus`, `claimRetry`). 사용자별로 읽는 메서드는 없다 |
| 기록을 쓰는 곳 | `chat.application.ResultDeliveryRecorder`. 전달 기록을 쓰는 곳은 이 클래스 하나다. 이 phase 는 부르지 않는다 |
| 대화 쪽 읽기 | phase 01 의 `chat.application.OwnConversations` |
| 실패 카드의 후보 | `attention.application.FailedTurnCandidates`. 대화마다 `conversation:<UUID>` 열쇠 하나 |
| trigger, 신호 | `attention.domain.type.AttentionTrigger.DELIVERY_FAILED`(phase 01 이 이름만 두었다), `attention.application.model.AttentionSignal.DELIVERY_NOT_DONE` |
| 출처 이름 | phase 01 이 `docs/backend/attention.md` 의 「출처 이름」 표에 둔 `RESULT_DELIVERY`(`result_delivery:<묶음 번호>`, `asOf` 는 묶음의 `updated_at`) |
| 중복 판정 | `AttentionJudge` 는 같은 카드 안에서 같은 `itemKey` 둘을 따로 낸다. 그래서 합치기는 후보 수집에서 한다 |
| e2e 본보기 | `test/e2e/scenarios/delivery-retry.ts` 가 대역 Hermes 의 `busy()` 동안 승인해 묶음을 `FAILED` 로 남기고, `clearBusy()` 뒤 다시 전달해 `DELIVERED` 로 만든다 |

## 의도 메모

- **상태를 읽기만 한다.** 결과 전달 표에 쓰지 않고 `ResultDeliveryRecorder` 를 부르지 않는다
- **`FAILED` 만 후보다.** `DELIVERING` 은 시도가 도는 중이라 후보가 아니다. `DELIVERED` 와 `STOPPED` 는 해결이다. `STOPPED` 는 사용자가 멈춘 것이라 먼저 알리지 않는다. 대화 화면의 「결과 다시 전달」 은 `STOPPED` 에도 그대로 보인다
- **한 대화는 한 항목이다.** 같은 대화에 실패한 turn 과 결과 전달 실패가 함께 있으면 열쇠는 그대로 `conversation:<UUID>` 이고, `signals` 에 `NOT_RETRIED` 와 `DELIVERY_NOT_DONE` 이 함께 든다. `trigger` 는 더 최근에 생긴 쪽이다(실행의 `finishedAt` 과 묶음의 `updated_at` 을 견준다)
- `stateKey` 재료에 두 쪽의 상태를 함께 넣는다. 둘 중 하나만 바뀌어도 숨긴 항목이 다시 보인다
- **응답에 묶음 번호 칸을 더하지 않는다.** 지금 화면은 이 항목을 「대화 열기」 로 `/chat/{conversationId}` 에 보내고, 대화 화면의 알림 줄 아래에 이미 있는 「결과 다시 전달」 단추를 쓴다. 다시 전달은 대화 화면이 읽는 SSE 를 돌려주므로 지금 화면에서 부를 자리가 아니다
- 결과 전달 실패는 자동 turn 의 실패라 `FailedTurnCandidates` 의 사용자 turn 판정(`startedByUser`)에 걸리지 않는다. 두 쪽이 같은 실패를 두 번 세지 않는다

## 작업 항목

### 1. 결과 전달 실패의 읽기

- `chat.infra.ResultDeliveryRepository` 에 JPQL `findFailedOfUser(@Param("userId") Long userId, @Param("since") Instant since)` → `List<ResultDelivery>` 를 더한다. `d.status = com.bifos.assistant.chat.domain.type.DeliveryStatus.FAILED and d.updatedAt >= :since and exists (select c.id from Conversation c where c.id = d.conversationId and c.userId = :userId and c.deletedAt is null)` 를 `d.id desc` 로 읽는다
- 새 `chat.application.model.FailedDelivery(Long deliveryId, Long conversationId, int attemptCount, Instant updatedAt)`
- `chat.application.OwnConversations` 에 `List<FailedDelivery> failedDeliveriesOf(CurrentUser user, Instant since)` 를 더한다. 대화마다 가장 최근 묶음(번호가 가장 큰 것) 하나만 남긴다
- 새 저장소 메서드는 `RepositoryQueryMysqlTest` 가 실제 MySQL 에서 실행한다

### 2. `FailedTurnCandidates` 에 합친다

- `OwnConversations.failedDeliveriesOf(user, now - failureWindow)` 를 읽어 대화 번호로 실패한 turn 후보와 합친다. 대화는 phase 01 과 같이 `OwnConversations.activeOf` 로 읽는다
- 결과 전달 실패만 있는 대화도 후보가 된다. 이때 `trigger` 는 `DELIVERY_FAILED`, `signals` 는 `[DELIVERY_NOT_DONE]`, `at` 은 묶음의 `updated_at`, `sources` 는 `[{RESULT_DELIVERY, result_delivery:<묶음 번호>, updated_at}]` 이다
- 둘 다 있으면 `signals` 는 `[NOT_RETRIED, DELIVERY_NOT_DONE]`, `at` 은 두 시각 가운데 늦은 것, `sources` 는 실행 쪽과 묶음 쪽 둘이다
- `nowSignal` 은 참, `confidence` 는 `CONTROL_PLANE` 이다
- `stateKey` 재료 글:

| 경우 | 재료 글 |
| --- | --- |
| 실패한 turn 만 | `"EXECUTION_FAILED|" + 실행 번호`(phase 01 그대로. 이미 숨긴 항목이 다시 보이지 않게 한다) |
| 결과 전달 실패만 | `"DELIVERY_FAILED|" + 묶음 번호 + "|" + attempt_count` |
| 둘 다 | `"EXECUTION_FAILED|" + 실행 번호 + "|DELIVERY_FAILED|" + 묶음 번호 + "|" + attempt_count` |

- 그 구현의 `cards()` 는 그대로 `FAILURES` 다. 결과 전달 기록을 읽다 예외가 나면 실패 카드가 `UNAVAILABLE` 이 된다. phase 01 의 규칙 그대로다

### 3. 문서

- `docs/backend/attention.md`:
  - 「후보와 trigger」 표의 `DELIVERY_FAILED` 줄을 고친다. 「원래 기록」 칸은 「결과 전달 묶음 `result_delivery`([ADR-075](../adr/ADR-075-결과-전달은-묶음과-시도로-남기고-사용자가-저장된-결과만-다시-전달한다.md)). 사용자는 그 대화의 `user_id` 다」, 「후보 조건」 칸은 「`FAILED` 이고 `updated_at` 이 `failure-window` 안. `DELIVERING` 은 시도가 도는 중이라 후보가 아니다」, 「해결된 상태」 칸은 「`DELIVERED`, `STOPPED`. 대화를 지웠다」, 「`stateKey` 의 재료」 칸은 「묶음 번호와 `attempt_count`. 실패한 turn 과 합치면 그 실행 번호도 함께」 로 둔다
  - 표 아래의 「`DELIVERY_FAILED` 는 #162 가 main 에 들어온 뒤에 더한다. 그 상태를 이 패키지가 저장하거나 고치지 않는다.」 를 「`DELIVERY_FAILED` 는 결과 전달 묶음의 상태를 읽기만 한다. 그 상태를 저장하고 다시 전달하는 일은 `chat` 의 `ResultDeliveryRecorder` 가 갖는다.」 로 바꾼다
  - 「카드의 단추와 승인 경계」 표의 결과 전달 실패 줄을 「| 결과 전달 실패 | 대화 열기 | `/chat/{대화 공개 식별자}`. 다시 전달은 그 대화의 알림 줄 아래 「결과 다시 전달」 이 한다. 응답에 묶음 번호를 싣지 않는다 |」 로 바꾼다
  - 머리의 「**아직 구현 전이다.** 구현한 PR 이 이 단락을 지운다.」 를 「**할 일 후보(`FOLLOW_UP_PROPOSED`, `FOLLOW_UP_OPEN`)와 `due-soon` 은 아직 구현 전이다.** 구현한 PR 이 이 단락을 지운다.」 로 바꾼다
- `docs/adr/ADR-072-먼저-알리기의-기본값은-알리지-않음이고-control-plane-기록에서-정한-신호만-화면-안에-올린다.md` 의 `status` 줄을 `` - **status**: `accepted` `` 로 둔다
- `docs/adr/INDEX.md` 의 ADR-072 줄 상태 칸을 `Accepted` 로 둔다
- `docs/README.md` 의 `backend/attention.md` 줄 끝 「아직 구현 전이다」 를 「할 일 후보는 아직 구현 전이다」 로 바꾼다
- `docs/code-architecture.md` 의 「아직 만들지 않은 것」 에서 `attention` 줄(「먼저 알리기와 지금 화면의 판정 …」)을 지운다. 할 일 줄은 남긴다
- `docs/prd.md` 「답하는 비서에서 먼저 챙기는 비서로」 표의 ADR-072 줄(「지금 봐야 할 것만 화면 안에서 먼저 알린다」)을 「범위와 확인 방법」 표 끝으로 옮긴다. 옮기면서 확인 칸의 「사이드바 「지금」 의 건수」 를 「사이드바 「지금 볼 것」 의 건수」 로 고치고, 확인 칸의 「, 기한이 다가온 할 일」 은 뺀다. 할 일 후보는 다음 PR 에서 구현되며, 그 PR 이 이 확인 칸에 다시 더한다. 그 절의 머리 문단은 남긴다. 나머지 줄은 그 ADR 의 「아직 구현 전」 을 지우는 PR 이 옮긴다

### 4. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/attention/AttentionServiceTest.java` 에 더한다. 묶음은 `ResultDelivery.opened(conversationId, now)` 로 저장하고 `ResultDeliveryRepository.changeStatus` 로 상태를 바꾼다(`TransactionTemplate` 안에서).

| 입력 | 기대 |
| --- | --- |
| 한 대화에 `FAILED` 묶음 하나 | `failures` 에 `conversation:<UUID>` 항목, `trigger` `DELIVERY_FAILED`, `signals` `["DELIVERY_NOT_DONE"]`, `sources` 에 `RESULT_DELIVERY`, `result_delivery:<묶음 번호>`, `NOW` |
| 같은 대화에 실패한 사용자 turn 도 있다 | 항목이 하나이고 `signals` 에 둘이 다 있다 |
| `DELIVERING` 묶음 | 결과 전달 실패로 보이지 않는다 |
| `STOPPED` 묶음 | 결과 전달 실패로 보이지 않는다 |
| 묶음이 다시 전달로 `DELIVERED` 가 되고 그 자동 turn 의 루트 실행이 `SUCCEEDED` 다. 그 뒤 같은 대화에 사용자 turn 이 하나 더 `FAILED` 다 | 항목이 하나이고 `trigger` 가 `EXECUTION_FAILED`, `signals` 는 `["NOT_RETRIED"]` 다. 순서를 거꾸로 두면 다시 전달 성공이 앞선 사용자 turn 의 실패까지 해소한다 |
| 다른 사용자 대화의 `FAILED` 묶음 | 응답에 없다 |
| 지운 대화의 `FAILED` 묶음 | 응답에 없다 |

`test/e2e/scenarios/delivery-retry.ts` 에 두 단계를 더한다.

- 「Hermes 가 거절하는 동안 승인하면 결과 전달 묶음이 FAILED 로 남는다」 단계 끝에서 `GET /attention`(dad)의 `failures` 카드에 `conversationId` 가 그 대화인 항목이 있고 `why.trigger` 가 `DELIVERY_FAILED`, `why.signals` 에 `DELIVERY_NOT_DONE` 이 있다
- 「거절을 풀고 다시 전달하면 …」 단계 끝에서 `GET /attention` 의 `failures` 카드에 그 대화의 항목이 없다

## 검증

```bash
# cwd: backend/
./gradlew test --tests 'com.bifos.assistant.attention.AttentionServiceTest' --tests 'com.bifos.assistant.attention.AttentionJudgeTest'
./gradlew test
./gradlew checkstyleMain checkstyleTest spotlessCheck
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
node --test test/unit/doc-references.test.ts
scripts/check-mysql-migration.sh
scripts/check-public-safe.sh
! grep -n "아직 구현 전" docs/adr/ADR-072-먼저-알리기의-기본값은-알리지-않음이고-control-plane-기록에서-정한-신호만-화면-안에-올린다.md
! grep -n "ADR-072.*아직 구현 전" docs/adr/INDEX.md
test "$(grep -c '아직 구현 전' docs/backend/attention.md)" = 1
```

기대값: 명령이 모두 종료 코드 0 이다. `docs/backend/attention.md` 에는 할 일 후보의 표시 한 줄만 남는다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ResultDeliveryRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/model/FailedDelivery.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/OwnConversations.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/attention/application/FailedTurnCandidates.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/attention/AttentionServiceTest.java` | 수정 |
| `test/e2e/scenarios/delivery-retry.ts` | 수정 |
| `docs/adr/ADR-072-먼저-알리기의-기본값은-알리지-않음이고-control-plane-기록에서-정한-신호만-화면-안에-올린다.md` | 수정 |
| `docs/adr/INDEX.md` | 수정 |
| `docs/backend/attention.md` | 수정 |
| `docs/README.md` | 수정 |
| `docs/code-architecture.md` | 수정 |
| `docs/prd.md` | 수정 |
