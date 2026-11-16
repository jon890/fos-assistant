# Phase 03. 끼우기: 판정, 호출, 받음·거절·모름

**Execution profile**: standard

## 목표

설정이 켜져 있고 조건이 맞으면 `POST .../pending` 이 저장한 글을 루트 run 에 steer 하고, 응답에 따라 말풍선 행을 만들거나 대기 줄로 남긴다.

**범위 외**: turn 이 끝날 때의 정리, 다음 turn, 기동 정리, 포트 `TurnSteerLedger`(phase 4). 화면(PR2).

## 컨텍스트

**요구사항**: REQ1, REQ2, REQ5, REQ6, REQ8

**근거 문서**: `docs/features/chat/design.md` 의 「책임과 경계」, 「끼울 수 있는 turn」, 「끼우기 흐름」, `docs/adr/ADR-20261116-run-steer.md`

- 들어오는 자리: `chat/nextturn/application/PendingMessageService.enqueue`, 컨트롤러 `chat/nextturn/presentation/PendingMessageController.java`, 요청 DTO `chat/turn/presentation/ChatDtos.PendingMessageRequest`·`PendingMessageView`.
- turn 상태: `chat/turn/application/TurnCancellation`·`TurnHandle`. 루트 run 은 `ChatRunEvents.submit` 이 `trackRun` 하는 run 이다(`handle.runs` 에는 위임 run 도 섞이고 `rekey` 가 비운다).
- 하위 에이전트 사건은 `ChatRunEvents.relay` 가 받는다(`subagent.start`, `subagent.complete`).
- 맡긴 실행: `usage/execution/infra/AgentExecutionRepository.java` 의 다른 질의처럼 `e.delegationKey is not null` 조건을 쓴다. `existsByConversationIdAndStatus` 는 지금 도는 루트 실행도 잡으므로 쓰지 않는다.
- 스킬 커맨드 해석은 `skill/library/application/SkillCommandCatalog` 를 `ChatTurnRouting` 이 쓰는 방식 그대로 재사용한다.
- 다음 turn 자리: `NextTurnDispatcher.tryNext`·`tryPending`.
- 설정 본보기: `chat/nextturn/config/DelegationWakeProperties.java`(`@Validated`, rule-24).
- 패키지 순서는 `SubFeatureOrder`(`turn` < `nextturn` < `recovery`)다. `ArchitectureRules` 시험이 지킨다.

## 의도 메모

- **빈 순환을 만들지 않는다.** 상태 변경은 `SteerLedger`(nextturn, 저장소·대기 줄·hub 만 주입)가, Hermes 호출과 판정은 `SteerService`(nextturn, `SteerLedger`·`HermesRunsClient`·`TurnCancellation` 주입)가 맡는다. 둘 다 `NextTurnDispatcher` 를 주입하지 않는다. `tryNext` 는 지금처럼 `PendingMessageService` 가 부른다. phase 4 에서 `ChatTurnRunner` 가 `SteerLedger` 를 포트로 주입해도 `NextTurnDispatcher → ChatService → ChatTurnRunner → SteerLedger` 에 되돌아오는 고리가 없다.
- Hermes 호출은 트랜잭션과 대화 줄 잠금 밖, handle 의 steer 잠금 안이다.
- 「끼우는 중」 표시는 글을 더하는 같은 `change` 안에서 건다. 따로 걸면 그 사이 다른 turn 이 글을 꺼내 간다.

## 작업 항목

### 1. `TurnHandle`·`TurnCancellation`·`ChatRunEvents`·`ChatTurnRunner`

- `TurnHandle` 에 `volatile TurnRunRef rootRun`, `volatile TurnIntent intent`, `AtomicInteger openSubagents`, `AtomicBoolean steerSettled`, `final Object steerLock` 을 더한다. `rekey` 는 이 칸들을 비우지 않는다.
- `TurnCancellation`: `trackRootRun(executionId, apiBaseUrl, profile, runId)`(안에서 `trackRun` 도 부름), `markIntent(handle, intent)`, `subagentStarted(executionId)`, `subagentCompleted(executionId)`(0 아래로 내려가지 않음), `Optional<SteerTarget> steerTarget(conversationId)`. `SteerTarget` 은 `chat/turn/application/dto` 의 record(handle, 루트 실행 번호, 루트 run, intent, 하위 에이전트 수)다.
- `ChatRunEvents.submit` 이 `trackRun` 대신 `trackRootRun` 을 부르고, `relay` 의 사건 처리에서 `subagent.start`·`subagent.complete` 로 수를 바꾼다.
- `ChatTurnRunner.runTurn` 이 `turns.rekey` 바로 뒤에 `turns.markIntent(handle, intent)` 를 부른다.

### 2. 설정 `chat/nextturn/config/SteerProperties.java`

`@Validated @ConfigurationProperties("assistant.chat.steer") record SteerProperties(@DefaultValue("false") boolean enabled)`. 제한 시간은 phase 1 의 `HttpHermesRunsClient` 가 같은 접두사의 `timeout` 키로 한 곳에서만 읽는다. `application.yml` 의 `assistant.chat` 아래에 `steer.enabled: ${ASSISTANT_CHAT_STEER_ENABLED:false}`, `steer.timeout: 5s` 와 한국어 주석.

### 3. `ChatEvent.steer`

`ChatEvent.steer(UUID conversationId, Long messageId, String text, SteerState state, SteerChild child, Long executionId)` 와 직렬화 칸. 기존 `user` 사건 생성자를 본보기로 한다.

### 4. `AgentExecutionRepository.existsRunningDelegation(Long conversationId)`

`select count(e) > 0 from AgentExecution e where e.conversationId = :conversationId and e.status = RUNNING and e.delegationKey is not null`.

### 5. `chat/nextturn/application/SteerLedger.java`(신규)

트랜잭션 메서드만 둔다. `accept(conversation, pendingId, text, userId, executionId, SteerChild)`: 대기 글 빼기 + `ChatMessage.fromSteer(..., DELIVERED, child)` 저장, `steer`·`pending` 사건. `reject(conversationId, pendingId)`: 그 글의 `steering=false`, `pending` 사건. `unknown(conversation, pendingId, text, userId, executionId)`: 대기 글 빼기 + `UNCONFIRMED` 행 + 그 번호로 `prependCarried` + `held=true`, `steer`·`pending` 사건. 대기 글이 이미 없으면(생기지 않아야 한다) 행만 만들고 경고를 남긴다.

### 6. `chat/nextturn/application/SteerService.java`(신규)

- `boolean eligible(CurrentUser user, Conversation conversation, String text, boolean requested)`: 설정, `requested`, 스킬 커맨드 아님, `steerTarget` 있음, intent 가 `TurnIntent.Fresh`·`Regenerate`·`DelegationResults`, 루트 run 있음, `steerSettled` 거짓. 잠금 밖의 빠른 판정이다.
- `void steer(Conversation conversation, Long pendingId, String text, Long userId)`: `steerTarget` 을 다시 얻고 `steerLock` 안에서 `steerSettled` 를 본다. 정리됐으면 `ledger.reject`. 아니면 `hermes.steer`. `ACCEPTED` → child 를 정해(하위 에이전트 수 > 0 이면 `SUBAGENT`, 아니면 `existsRunningDelegation` 이면 `DELEGATION`, 아니면 null) `ledger.accept`. `REJECTED` → `ledger.reject`. `UNKNOWN` → `ledger.unknown`.

### 7. `PendingMessageService`·컨트롤러·`NextTurnDispatcher`

- `PendingMessageRequest` 에 `Boolean steer`. 컨트롤러가 `enqueue(user, id, text, Boolean.TRUE.equals(steer))` 로 넘긴다.
- `enqueue`: `requireQueueable` 뒤 `boolean wants = steer.eligible(...)`. `change` 안에서 상한 검사(말풍선 번호 없는 글만, `countable()`·새 `mergedLength`) → `append` → `wants` 이고 더하기 전 줄이 비어 있었고 멈추지 않았으면 새 글에 `steering=true`. 돌려받은 줄에서 그 글이 `steering` 이면 `steer.steer(...)`. 그 뒤 지금처럼 `pending` 사건과 `dispatcher.tryNext`.
- `cancel`: 그 글이 `steering` 이면 `CONVERSATION_BUSY`.
- `NextTurnDispatcher.tryPending`: `queue.blockedBySteering()` 이면 아무것도 열지 않고 참을 돌려준다.
- `PendingMessageView` 에 `messageId`, `steering`.

### 8. 시험

- `backend/src/test/java/com/bifos/assistant/chat/SteerServiceTest.java`(신규, `StubHermesRunsClient`): 받음 → 대기 글 빠짐·`DELIVERED` 행·`steer` 사건, 409 → 대기 글 남음·steering 해제 → turn 을 끝내면 그 글로 다음 turn, 모름 → `UNCONFIRMED` 행·말풍선 번호 글·held, 설정 꺼짐 → steer 호출 없음, 줄에 다른 글 있음 → 호출 없음, 스킬 커맨드 → 호출 없음, `Mention` intent → 호출 없음, 하위 에이전트 돌 때 `SUBAGENT`, 도는 맡긴 실행만 있을 때 `DELEGATION`, `steerSettled` 뒤 → 호출 없음, 두 글을 잇달아 보냄 → 둘째는 대기 칸.
- `PendingMessageControllerTest`: `steer` 없으면 호출 없음, 남의 대화 404.
- `PendingMessageServiceTest`: steering 글 취소 → `CONVERSATION_BUSY`. steering 줄에서 `tryNext` 가 위임 깨우기를 부르지 않는다.

## 검증

```bash
cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test --tests '*SteerServiceTest' --tests '*PendingMessageServiceTest' --tests '*PendingMessageControllerTest' --tests '*PendingBeforeDelegationTest' --tests '*ArchitectureRules*' --tests '*PackageLayoutRulesTest'
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/TurnHandle.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/TurnCancellation.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/ChatRunEvents.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/ChatTurnRunner.java` | 수정(intent 표시) |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/dto/SteerTarget.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/dto/ChatEvent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/execution/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/nextturn/config/SteerProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/nextturn/application/SteerLedger.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/nextturn/application/SteerService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/nextturn/application/PendingMessageService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/nextturn/application/NextTurnDispatcher.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/nextturn/presentation/PendingMessageController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/SteerServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/PendingMessageServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/presentation/PendingMessageControllerTest.java` | 수정 |
