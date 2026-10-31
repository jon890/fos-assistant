# Phase 01. 숨김, 미루기, 복원을 반응 기록의 필수 쓰기 경로로 옮긴다

**Execution profile**: deep

## 목표

지금 화면의 숨김, 미루기, 복원을 `decision_feedback_event` 에 반드시 남기고 현재 제어를 마지막 제어 사건에서 구한다. `attention_control` 을 지울 수 있는 조건을 구현한다.

**범위 외**: 옛 현재값을 사건으로 옮기기(phase 02), 노출 사건의 화면 쪽 변경.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261101-proactive-suggestion.md` 의 「숨김과 미루기를 반응 기록으로 옮기는 조건」, `docs/features/attention.md`, `docs/adr/ADR-072-먼저-알리기의-기본값은-알리지-않음이고-control-plane-기록에서-정한-신호만-화면-안에-올린다.md`

- 지금 제어는 `backend/src/main/java/com/bifos/assistant/attention/application/AttentionControlService.java` 가 카드별 현재값을 upsert 한다
- 지금 기록 경로 `backend/src/main/java/com/bifos/assistant/feedback/application/DecisionFeedbackRecorder.java` 는 커밋 뒤 새 트랜잭션으로 넣고 실패를 삼킨다. 제어 원장으로 그대로 쓰지 않는다

## 의도 메모

- 필수 쓰기는 `FeedbackEventWriter` 에 새 메서드로 두고 부르는 쪽 트랜잭션 안에서 넣는다. 실패하면 조작 API 가 실패한다
- 같은 요청의 재전송은 `event_key` 로 하나가 된다. 숨김→복원→숨김처럼 실제 새 조작은 다른 요청 키라 모두 남는다
- 마지막 제어 사건은 보관 기간 정리(`DecisionFeedbackCleaner`)에서 빼고, 대상이 지워지면 함께 지운다

## 작업 항목

### 1. 필수 쓰기와 현재 제어 조회

`AttentionControlService` 가 현재값 테이블 대신 반응 기록을 쓰고 읽는다. 조회는 `(user_id, card_key, subject_key, id)` 인덱스를 쓰는 리포지토리 쿼리 하나다. 인덱스는 `reserve-migration` 으로 받은 경로의 DDL 로 더한다.

### 2. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/attention/AttentionReactionControlTest.java` 에서 확인한다.

- 같은 항목의 두 카드 제어가 서로 독립이다
- 숨긴 항목은 상태가 바뀌면 다시 보인다
- 미루기는 만료 뒤 다시 보이고 복원은 바로 보인다
- 같은 요청 키의 재전송은 사건 하나다
- 보관 기간이 지나도 마지막 숨김은 남는다
- 기록이 실패하면 조작 API 가 실패한다

## 검증

```bash
cd backend && ./gradlew test --tests '*AttentionReactionControlTest' --tests '*AttentionServiceTest'
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/attention/application/AttentionControlService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/feedback/application/FeedbackEventWriter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/feedback/application/DecisionFeedbackCleaner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/feedback/infra/FeedbackEventRepository.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/attention/AttentionReactionControlTest.java` | 신규 |
| `docs/features/attention.md` | 수정 |
