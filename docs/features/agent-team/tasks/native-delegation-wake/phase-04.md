# Phase 04. 기한과 중지 알림 훑기

**Execution profile**: standard

## 목표

1분마다 등록 줄을 훑어, 1시간 동안 끝 소식이 없는 자식과 사용자가 중지한 turn 의 자식에 답마다 알림 줄 하나를 남긴다. 자동 답은 열지 않는다.

**범위 외**: 이력 API 와 정리(phase 5), 화면(phase 6). 늦게 온 끝 소식으로 답을 여는 길은 phase 2, 3 이 이미 만들었다.

## 컨텍스트

**요구사항**: REQ12(소식 없는 자식과 중지한 turn 의 자식), REQ14(설정을 끄거나 PR6 만 배포된 동안 알림 줄이 없다)

**근거 문서**: `docs/features/agent-team/design.md` 의 「PR6 내장 위임 결과」 → 「기한과 중지 알림」, 「설정을 끈 동안」, 「배포 순서와 안전」, `docs/features/agent-team/spec.md`(DECISION31, DECISION34, DECISION40, DECISION41, DECISION42)

- 알림 줄의 글(스펙이 정했다. 바꾸지 않는다):
  - 기한, 자식 하나: 「하위 작업의 결과를 1시간 동안 받지 못했어요. 늦게 오면 이어서 알려 드릴게요」
  - 기한, 자식 N개: 「하위 작업 N개의 결과를 1시간 동안 받지 못했어요. 늦게 오면 이어서 알려 드릴게요」
  - 중지: 「중지한 답이 맡긴 하위 작업이 끝났어요. 이어서 하려면 메시지를 보내 주세요」
- 알림 줄 저장의 본보기: `chat/turn/application/ConversationNotices.java` 의 `post(conversationId, text)` 는 저장과 SSE 를 한 번에 한다. 이 phase 는 저장을 등록 줄 update 와 한 트랜잭션에 두고 SSE 는 커밋 뒤에 보내야 한다.
- 주기 작업 본보기: `memory/application/MemoryEmbeddingRefresher.java`(`@Scheduled(fixedDelayString=..., initialDelayString=...)`, `AtomicBoolean` 으로 겹침 막기, `BackgroundTasks.start`).
- 설정: `LiveProperties<DelegationWakeProperties>` 의 `current().enabled()`. `orchestration` 이 `chat` 보다 위라 읽어도 된다(`architecture/TopLevelPackageOrder.java`).
- phase 1 의 칸과 색인 `(wake_eligible, result_delivered_at, created_at)` 를 쓴다.
- 테스트 명령은 worker 지침대로 무거운 검사 대기열로 감싸 돌린다.

## 의도 메모

- 두 대상 모두 `wake_eligible` 이 참이고 등록한 지 24시간이 안 된 줄만 본다. PR6 배포 전의 줄, 옛 플러그인의 줄, 오래 꺼 두었던 동안의 줄이 한꺼번에 알림 줄을 남기지 않게 한다.
- 조건부 update 와 알림 줄 저장을 origin 마다 한 트랜잭션에 둔다. 바꾼 줄이 있을 때만 알림 줄을 남겨 같은 알림이 두 번 남지 않는다.
- 대화의 turn 잠금은 잡지 않는다. `DelegationWakeService.noticeLimitReached` 와 같다.
- 중지한 turn 의 자식에는 기한 알림을 따로 남기지 않는다(스펙 DECISION41). 그래서 기한 대상은 origin 이 `CANCELLED` 가 아닌 줄만이다.

## 작업 항목

### 1. `ConversationNotices` 에 저장과 알리기를 나눈 두 메서드

- `Optional<ChatMessage> save(Long conversationId, String text)`: 부르는 쪽 트랜잭션 안에서 대화가 있고 지워지지 않았으면 `SYSTEM` 줄을 저장해 돌려준다.
- `void publish(Long conversationId, ChatMessage saved)`: 대화의 공개 식별자로 `ChatEvent.system` 을 보낸다. 대화가 없거나 지워졌으면 아무것도 하지 않는다.
- 지금 `post` 는 두 메서드로 같은 동작을 하게 고친다. 호출부(`ChatTurnRunner`, `ConnectorHandoffs`, `ConnectorActionSignals`)는 바꾸지 않는다.

### 2. 새 `orchestration/application/NativeDelegationSweep.java`

- `@Scheduled(fixedDelayString = "${assistant.native-delegation.sweep-delay:60s}", initialDelayString = "${assistant.native-delegation.sweep-delay:60s}")` 의 `sweep()` 이 설정이 꺼져 있거나 앞 주기가 돌면 돌아간다. 아니면 새 가상 스레드에서 `sweepOnce(clock.instant())` 를 돌린다. 예외는 경고 로그만 남긴다.
- `sweepOnce(Instant now)`(패키지 공개, 테스트가 부른다):
  - **기한**: `wake_eligible`, `stopped_at` 없음, `timeout_noticed_at` 없음, `created_at` 이 `now-24h` 보다 뒤이고 `now-1h` 이하, origin 실행이 `CANCELLED` 가 아닌 줄을 origin 마다 묶는다. origin 마다 한 트랜잭션에서:
    1. 같은 origin 에 이미 `timeout_noticed_at` 이 찬 줄이 있는지 본다(앞서 알림 줄을 남겼다).
    2. 그 origin 의 소식 없는 깨울 자식 가운데 위 조건의 줄에 `timeout_noticed_at = now` 를 조건부로 적는다. 바뀐 수가 N 이다.
    3. N > 0 이고 1 이 거짓이면 origin 실행의 대화에 기한 알림 줄 하나를 저장한다(N 이 1 이면 자식 하나 글, 아니면 N개 글).
  - **중지**: `wake_eligible`, `result_delivered_at` 없음, `created_at` 이 `now-24h` 보다 뒤, origin 실행이 `CANCELLED` 인 줄을 origin 마다 묶는다. 그 origin 의 깨울 자식이 모두 `stopped_at` 이 있거나 가장 먼저 등록한 자식이 1시간을 넘었으면, 한 트랜잭션에서 그 origin 의 `result_delivered_at` 이 빈 깨울 자식 모두에 `now` 를 적고 바뀐 줄이 있으면 중지 알림 줄 하나를 저장한다.
  - 커밋한 뒤 저장한 줄마다 `ConversationNotices.publish` 를 부른다.
- 저장소 질의는 `HermesSessionBindingRepository` 에 더한다. MySQL 쿼리 규칙(`docs/code-rules/rule-51.md`)을 따른다.

### 3. 설정

- `backend/src/main/resources/application.yml` 에 `assistant.native-delegation.sweep-delay: 60s` 를 설명 주석과 함께 더한다.
- `backend/src/test/resources/application-test.yml` 에 같은 키를 `1h` 로 둔다. 검사 도중 훑기가 스스로 돌아 다른 검사의 대화에 알림 줄을 남기지 않게 한다(`assistant.memory.embedding.refresh-delay` 와 같은 까닭). 검사는 `sweepOnce` 를 직접 부른다.

### 4. 테스트

새 `backend/src/test/java/com/bifos/assistant/orchestration/NativeDelegationSweepTest.java`(`@BackendIntegrationTest`, `@DelegationWakeEnabled`, 줄은 JDBC 로 준비하고 `sweepOnce` 에 시각을 넘긴다):

- 등록 61분 뒤 소식 없는 자식 하나 → 기한 알림 줄 하나, 글은 자식 하나 글. 다시 돌려도 줄이 늘지 않는다.
- 같은 origin 의 소식 없는 자식 둘 → 줄 하나, 「하위 작업 2개의」 글.
- 앞서 알림을 남긴 origin 에서 뒤에 1시간을 넘은 자식 → 칸만 차고 줄은 늘지 않는다.
- 등록 25시간 뒤의 줄, 깨울 자식이 아닌 줄 → 아무것도 남지 않는다(REQ14).
- 기한 알림 뒤 끝 소식이 오면(칸을 직접 채움) `NativeDelegationResultSource.undelivered` 에 그 결과가 든다(스펙 DECISION42).
- origin 이 `CANCELLED` 이고 자식이 모두 끝났다 → 중지 알림 줄 하나, `result_delivered_at` 이 찼고, 그 대화에 자동 turn 이 열리지 않는다(`undelivered` 가 비었다). 기한 알림은 없다.
- origin 이 `CANCELLED` 이고 자식 하나가 아직 돌며 30분이다 → 아무것도 없다. 61분이면 중지 알림 줄 하나.

새 `backend/src/test/java/com/bifos/assistant/orchestration/NativeDelegationSweepDisabledTest.java`(`@BackendIntegrationTest`, 깨우기 설정은 기본값인 꺼짐): `sweep()` 이 아무것도 남기지 않는다(REQ14, 스펙 DECISION39).

## 검증

```bash
cd backend && ./gradlew test --tests '*NativeDelegationSweepTest' --tests '*NativeDelegationSweepDisabledTest' --tests '*NativeDelegationResultSourceTest' --tests '*ConnectorActionDeliveryTest'
cd backend && ./gradlew archTest checkstyleMain checkstyleTest spotlessCheck
node scripts/checks/check-file-length.mjs
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/ConversationNotices.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/NativeDelegationSweep.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/infra/HermesSessionBindingRepository.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/NativeDelegationSweepTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/orchestration/NativeDelegationSweepDisabledTest.java` | 신규 |
