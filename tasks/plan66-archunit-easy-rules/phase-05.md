# Phase 05. 서비스의 Instant.now() 를 주입받은 Clock 으로 바꾼다

**Execution profile**: standard

## 목표

`application` 과 `infra` 의 클래스가 `Instant.now()` 를 직접 부르는 자리를 주입받은 `java.time.Clock` 으로 바꾼다.
시각을 주입받아야 테스트가 시각을 고정할 수 있다. `NO_DIRECT_INSTANT_NOW` 의 기준에서 서비스 쪽 줄이 빠진다.

**범위 외**: `*.domain` 의 엔티티가 부르는 `Instant.now()`. 다음 phase 가 맡는다. 이 phase 가 끝나도 기준 파일은 0 줄이 아니다.

## 컨텍스트

- 먼저 `tasks/plan66-archunit-easy-rules/README.md` 를 읽는다.
- `Clock` 빈은 `backend/src/main/java/com/bifos/assistant/shared/config/ClockConfig.java` 가 `Clock.systemUTC()` 로 준다.
- 본보기: `backend/src/main/java/com/bifos/assistant/chat/application/RecoveredRunRecorder.java` 가 `private final Clock clock;` 을 받고 `clock.instant()` 를 쓴다.
- 규칙은 `Instant.now()` 만 막고 `Instant.now(Clock)` 과 `clock.instant()` 는 허용한다.

**근거 문서**: `docs/adr/ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`

## 의도 메모

- `Clock.systemUTC().instant()` 는 `Instant.now()` 와 같은 값을 준다. 운영 동작은 그대로다.
- 정적 시계 보관 클래스를 만들지 않는다. 규칙만 통과하고 테스트가 시각을 고정하지 못한다.
- 생성자 주입은 Lombok `@RequiredArgsConstructor` 를 그대로 쓴다. 손으로 쓴 생성자가 있는 클래스는 그 생성자에 인자를 더한다.

## 작업 항목

### 1. 아래 클래스의 `Instant.now()` 를 `clock.instant()` 로 바꾼다

`backend/src/main/java/com/bifos/assistant/` 아래다. `Clock` 필드가 없으면 더한다. `ChatService` 와 `ExecutionRecorder` 는 이미 갖고 있다.

| 파일 | 자리 |
| --- | --- |
| `chat/application/ChatService.java` | 여섯 곳. turn 시작 시각 둘, `touchSession` 둘, `renameIfActive`, `deleteIfActive` |
| `chat/application/AttachmentService.java` | `upload` 와 `deleteByUser` |
| `chat/application/ArtifactCleaner.java` | `runScheduled` |
| `chat/application/AttachmentCleaner.java` | `runScheduled` |
| `usage/application/ExecutionRecorder.java` | 다섯 곳 |
| `usage/application/ExecutionEventRecorder.java` | 두 곳 |
| `skill/application/SkillUseRecorder.java` | `record` |
| `skill/infra/SkillStore.java` | `newVersionName`. 생성자 인자로 `Clock` 을 받고 `newVersionName` 을 인스턴스 메서드로 바꿔 `clock.millis()` 를 쓴다. 공개 메서드의 시그니처는 바꾸지 않는다 |
| `agent/application/AgentLifecycleService.java` | `delete` |
| `mcp/application/McpToolService.java` | `markDeliveredIfFinished` |
| `orchestration/application/ResearchAndBuildFlow.java` | 두 곳 |
| `orchestration/application/AgentDelegationService.java` | `delegate` |

### 2. `LAYER_DIRECTION` 을 다시 얼린다

`SkillStore` 의 생성자 시그니처가 바뀌어 `backend/config/archunit/store/2790ecd4-faaa-4952-b703-a028b68814d5` 의 `SkillStore.<init>` 줄이 글자만 달라진다.
기준을 줄이는 명령이 그 줄을 새 위반으로 잡아 실패하면 아래로 그 규칙만 다시 얼린다.

```bash
# cwd: backend/
./gradlew archTest --rerun --tests '*ArchitectureRulesTest.layerDirection' -Parchunit.freeze.refreeze=true -Parchunit.freeze.store.default.allowStoreUpdate=true
```

다시 얼리면 줄 끝의 `in (파일:줄번호)` 도 지금 값으로 다시 적혀 `SkillStore` 의 줄이 여럿 달라진다. 그래서 줄 끝을 떼고 견준다.

```bash
# cwd: backend/
F=config/archunit/store/2790ecd4-faaa-4952-b703-a028b68814d5
diff <(git show "HEAD:backend/$F" | sed 's/ in (.*)$//' | sort) <(sed 's/ in (.*)$//' "$F" | sort)
```

다시 얼리기 전과 뒤의 `wc -l` 이 같아야 하고, 위 `diff` 의 차이가 `SkillStore.<init>` 줄의 인자 목록뿐이어야 한다. 다른 줄이 더해졌으면 기준 파일을 `git checkout` 하지 말고 team-lead 에게 보고한다.

### 2-1. 이 클래스들을 손으로 만드는 테스트를 고친다

`backend/src/test/java` 에서 위 클래스를 `new` 로 만드는 테스트에 `Clock.systemUTC()` 나 그 테스트가 이미 가진 시계를 넘긴다.
`git grep -n "new SkillStore(\|new AttachmentService(\|new ArtifactCleaner(\|new AttachmentCleaner(\|new ExecutionEventRecorder(\|new SkillUseRecorder(\|new AgentLifecycleService(\|new McpToolService(\|new ResearchAndBuildFlow(\|new AgentDelegationService(" -- backend/src/test` 로 찾는다.

### 3. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/chat/AttachmentCleanerClockTest.java` 를 새로 만든다.
고정한 `Clock` 을 준 `AttachmentCleaner.runScheduled()` 가 그 시각보다 앞서 만료된 첨부만 지우고, 그 시각 뒤에 만료되는 첨부는 남긴다는 것을 단언한다.
준비 방식은 `backend/src/test/java/com/bifos/assistant/chat/AttachmentCleanerTest.java` 를 따른다.

## 검증

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
./gradlew test
! grep -n "application\.\|infra\." config/archunit/store/d3d721a0-86e5-4069-8051-dd3e7adf2c55
! grep -rn "Instant\.now()" src/main/java --include='*.java' | grep -v "/domain/"
```

모두 종료 코드 0 이어야 한다. 기준 파일에는 `domain` 의 줄만 남는다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ArtifactCleaner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentCleaner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionEventRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillUseRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/skill/infra/SkillStore.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentLifecycleService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ResearchAndBuildFlow.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentDelegationService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/AttachmentCleanerClockTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/**/*.java` | 수정 |
| `backend/config/archunit/store/d3d721a0-86e5-4069-8051-dd3e7adf2c55` | 수정 |
| `backend/config/archunit/store/2790ecd4-faaa-4952-b703-a028b68814d5` | 수정 |
