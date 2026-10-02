# Phase 04. 알림 줄을 저장하는 동안의 짧은 turn 잠금을 적는다

**Execution profile**: fast

## 목표

`TurnCancellation.runIfIdle` 이 알림 줄을 저장하는 동안 그 대화의 turn 잠금을 잠깐 잡는다는 것을 Javadoc 과 흐름 문서에 적는다.
코드를 읽는 사람이 그 사이의 `CONVERSATION_BUSY` 와 「도는 turn 있음」 표시를 결함으로 읽지 않게 한다.

**범위 외**: 동작을 바꾸지 않는다. 재시도나 예약 구조를 새로 만들지 않는다. Javadoc 과 문서만 고친다.

## 컨텍스트

- `backend/src/main/java/com/bifos/assistant/chat/application/TurnCancellation.java`
  - `runIfIdle(Long conversationId, Runnable work)` 가 사용자 번호와 실행 번호가 없는 `TurnHandle` 을 `byConversation` 에 넣고 `work` 를 돌린 뒤 `close` 한다.
  - 그 사이 `markOf(conversationId)` 는 `new TurnMark(true, null)` 을 돌려준다. 도는 turn 이 있다고 읽히고 실행 번호는 비어 있다.
  - 그 사이 `open(userId, conversationId)` 는 `CONVERSATION_BUSY` 를 던진다.
- 부르는 곳은 `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionListener.java` 하나다.
  끝난 승인 요청(거절, 만료, 연결 변경)의 알림 줄을 한 트랜잭션으로 저장한다.
- `close` 가 닫기 리스너를 부르므로 그 사이 쌓인 대기 메시지와 결과는 잠금이 풀리는 자리에서 이어서 간다.

**근거 문서**: `docs/flow.md` 의 「승인이 필요한 호출」 과 「응답 중에 보낼 때」, `docs/adr/ADR-048-응답-중에-보낸-메시지는-control-plane-이-쌓아-두고-다음-turn-으로-합쳐-보낸다.md`

## 의도 메모

- 잠금을 잡는 시간이 트랜잭션 하나라 구조를 바꾸지 않는다. 알림 줄 저장과 다음 turn 을 정하는 자리가 같은 잠금 아래 차례로 돌게 하려고 잡는 잠금이다.
- 문서는 평서체로 쓴다. 엠대시로 절을 잇지 않는다.

## 작업 항목

### 1. `TurnCancellation.java` 의 Javadoc

- `runIfIdle` 의 Javadoc 에 아래를 더한다.
  - 작업이 도는 동안 그 대화는 도는 turn 이 있는 것으로 보인다. `markOf` 는 실행 번호가 없는 표시를 돌려주고 `open` 은 `CONVERSATION_BUSY` 다.
  - 부르는 쪽은 트랜잭션 하나처럼 짧은 작업만 넘긴다. Hermes 호출처럼 오래 걸리는 작업을 넘기지 않는다.
  - 그 사이 거절된 보내기는 화면이 대기 메시지로 다시 넣고, 잠금을 풀 때 닫기 리스너가 이어서 보낸다.
- `markOf` 의 Javadoc 에 「`runIfIdle` 이 잡은 잠금도 도는 turn 으로 읽힌다. 그때 실행 번호는 null 이다」 를 더한다.

### 2. `docs/flow.md`

- 「승인이 필요한 호출」 의 「갈리는 지점」 표 끝에 한 줄을 더한다.
  | 끝난 요청의 알림 줄을 저장하는 순간 사용자가 그 대화에 보낸다 | 알림 줄을 저장하는 트랜잭션 하나 동안 그 대화의 turn 잠금을 잡는다. 그 사이의 보내기는 `CONVERSATION_BUSY` 를 받고, 글만 보낸 것이면 화면이 대기 메시지로 다시 넣어 잠금이 풀리는 자리에서 간다. 실행은 열리지 않으므로 도는 turn 표시에 실행 번호가 없다 |

### 3. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/chat/application/TurnCancellationTest.java`

동작은 그대로지만 문서에 적은 특성을 테스트로 고정한다. 이 파일이 이미 있으면 거기에 더하고, 없으면 만든다.
같은 패키지의 기존 테스트가 `TurnCancellation` 을 만드는 방식을 따른다(`HermesRunsClient` 는 Mockito 가짜, 유예 시간은 짧은 `Duration`).

- 「알림 줄을 저장하는 동안에는 도는 turn 으로 보이고 실행 번호가 없다」: `runIfIdle` 의 작업 안에서 `markOf(conversationId)` 가 도는 turn 이고 실행 번호가 null 이며,
  `open(1L, conversationId)` 이 `CONVERSATION_BUSY` 를 던진다. 작업이 끝난 뒤에는 `markOf` 가 `TurnMark.NONE` 이고 닫기 리스너가 한 번 불렸다.
- 「도는 turn 이 있으면 작업을 돌리지 않는다」: `open` 한 뒤 `runIfIdle` 이 거짓을 돌려주고 작업이 불리지 않는다.

## 검증

```bash
# cwd: 저장소 root
ls backend/src/test/java/com/bifos/assistant/chat/application/
cd backend && ./gradlew test --tests '*TurnCancellationTest' --tests '*ConnectorActionDeliveryTest' --tests '*ArchitectureRulesTest'
cd backend && ./gradlew qualityCheck
scripts/check-public-safe.sh
```

- 모두 종료 코드 0 이다.
- `git diff --stat` 에 `TurnCancellation.java` 의 주석 줄만 바뀌었다. 실행되는 코드 줄이 바뀌지 않았다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/application/TurnCancellation.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/application/TurnCancellationTest.java` | 수정 |
| `docs/flow.md` | 수정 |
