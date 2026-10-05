# Phase 01. 작업 줄 잠금으로 멈추기와 시작을 직렬화한다

**Execution profile**: standard

## 목표

예약 작업을 멈추거나 지운 응답이 나간 뒤에는 그 작업의 `QUEUED` 발화가 Hermes 에 새로 제출되지 않게 한다.
같은 잠금으로, 작업 고치기가 시작 단계가 적은 `SINGLE` 대화 번호(`task.conversation_id`)를 읽은 때의 값으로 되돌리지 않게 한다.
GitHub 이슈 #189 와 #177 의 「작업 고치기가 시작 단계가 적은 `SINGLE` 대화 번호를 되돌릴 수 있다」 항목이다.

**범위 외**: 이미 `RUNNING` 인 발화를 멈추기로 취소하는 것(새 의미라 하지 않는다). 건너뛴 발화가 남긴 빈 대화(다른 계획). cron 길이 검사(다른 계획).

## 컨텍스트

- 시작 단계는 `backend/src/main/java/com/bifos/assistant/task/application/TaskRunStarter.java` 다
  - `start(runId, now)` 가 준비 트랜잭션 `prepare` → turn 자리 `turns.open(userId, conversationId)` → 트랜잭션 `markRunning` → 가상 스레드 `runTurn` 순으로 돈다
  - `prepare` 는 `runs.findByIdForUpdate` 로 줄을 잠그고 `tasks.findById` 로 작업을 잠그지 않고 읽어 `TaskState.ACTIVE` 가 아니면 `skip(task, run, TaskRunReason.PAUSED, now)` 한다
  - `markRunning` 은 줄만 다시 잠가 `QUEUED` 인지 보고 `run.start(now)` 한다. **작업 상태를 다시 보지 않는다.** 그래서 `prepare` 뒤 `markRunning` 앞에 커밋된 멈추기가 있어도 turn 이 돈다
  - `markRunning` 이 false 를 내면 `start` 가 `turns.close(handle)` 로 turn 자리를 푼다. 이 경로는 그대로 쓴다
- 사용자 쪽은 `backend/src/main/java/com/bifos/assistant/task/application/TaskService.java` 다. `update`, `pause`, `resume`, `archive` 가 모두 `requireTask(user, taskId)`(`tasks.findByPublicIdAndOwnerUserId` 로 읽고 보관한 작업을 거른다)로 잠그지 않고 읽는다
  - `update` 는 `task.edit(...)` 로 엔티티를 고치고, JPA 가 커밋할 때 모든 칸을 읽은 때의 값으로 쓴다. 그 사이 시작 단계가 `TaskRepository.useConversation` 으로 `conversation_id` 를 적고 커밋했으면 그 값이 사라진다
- 잠금 쿼리의 기존 모양은 `backend/src/main/java/com/bifos/assistant/task/infra/TaskRunRepository.java` 의 `findByIdForUpdate` 다. `@Lock(LockModeType.PESSIMISTIC_WRITE)` 와 `@Query` 를 함께 단다
- 다른 경로의 잠금 순서: `TaskFiring` 은 `task_trigger` 를 잠그고 작업은 잠그지 않고 읽는다. `TaskRunRecovery` 와 `TaskRunStarter.finishSafely` 는 `task_run` 을 잠그고 작업은 잠그지 않고 읽는다. 작업 줄을 잠근 뒤 `task_run` 을 잠그는 경로는 지금 없다
- 검사 DB 는 H2(MySQL 모드, `LOCK_TIMEOUT=10000`)다. 비관적 잠금은 `TaskRunRepository.findByIdForUpdate` 로 이미 쓴다. 새 저장소 쿼리가 실제 MySQL 에서도 도는지는 `backend/AGENTS.md` 의 「저장소 쿼리는 실제 MySQL 에서도 실행한다」 를 따른다

**근거 문서**: `docs/backend/task.md` 의 「작업」 상태 표와 「시작」 의 표 아래 「멈추기와 지우기는 시작과 작업 줄 잠금으로 순서를 정한다」 문단(이 plan 이 이미 고쳤다). `docs/adr/ADR-076-예약-작업은-control-plane-이-갖고-발화한-실행은-대화-turn-경로로-돈다.md`

## 의도 메모

- `markRunning` 에서 작업을 잠그지 않고 다시 읽기만 하는 안은 기각한다. 멈추기 트랜잭션이 쓰기를 마치고 커밋하기 전이면 다시 읽어도 `ACTIVE` 가 보여 경합 폭만 줄어든다(#189 의 「작은 범위」)
- 사용자 쪽도 잠금으로 읽는 까닭: 멈추기가 `ACTIVE` 를 읽은 뒤 시작 단계가 `RUNNING` 을 커밋하면 멈추기 응답이 나간 뒤에도 그 발화가 돈다. 이것은 「이미 도는 발화」 라 허용하지만, 순서가 잠금으로 정해져야 문서의 계약을 검사로 고정할 수 있다
- 잠금 순서는 늘 `task_run` 다음 `task` 다. 이 phase 가 작업 줄을 잠그고 `task_run` 을 잠그는 코드를 만들지 않는다
- `@DynamicUpdate` 로 바뀐 칸만 쓰게 하는 안은 기각한다. 멈추기 경합을 풀지 못하고, 같은 칸을 두 쪽이 고치면 여전히 마지막 쓰기가 이긴다

## 작업 항목

### 1. `TaskRepository` 에 잠금 조회 둘을 더한다

`backend/src/main/java/com/bifos/assistant/task/infra/TaskRepository.java`

- `Optional<Task> findByIdForUpdate(@Param("id") Long id)`: `@Lock(LockModeType.PESSIMISTIC_WRITE)`, `@Query("select t from Task t where t.id = :id")`. 시작 단계가 쓴다
- `Optional<Task> findByPublicIdAndOwnerUserIdForUpdate(@Param("publicId") UUID publicId, @Param("ownerUserId") Long ownerUserId)`: 같은 잠금, `@Query("select t from Task t where t.publicId = :publicId and t.ownerUserId = :ownerUserId")`. `Task` 의 필드 이름(`publicId`, `ownerUserId`)은 `backend/src/main/java/com/bifos/assistant/task/domain/Task.java` 를 읽어 맞춘다
- Javadoc 에 「상태를 바꾸기 전에 작업 줄을 잠그고 다시 읽는다. 잠그는 순서는 `task_run` 다음 `task` 다」 를 한국어로 적는다

### 2. `TaskService` 의 바꾸는 메서드가 잠금으로 읽는다

`backend/src/main/java/com/bifos/assistant/task/application/TaskService.java`

- `requireTaskForUpdate(CurrentUser user, UUID taskId)` 를 더한다. `requireTask` 와 같은 거르기(보관한 작업은 `TASK_NOT_FOUND`)를 쓰고 조회만 `findByPublicIdAndOwnerUserIdForUpdate` 다
- `update`, `pause`, `resume`, `archive` 가 이것을 쓴다. `get`, `runs` 는 그대로 `requireTask` 다

### 3. `TaskRunStarter.markRunning` 이 작업을 잠그고 상태를 다시 본다

`backend/src/main/java/com/bifos/assistant/task/application/TaskRunStarter.java`

- 줄을 `runs.findByIdForUpdate` 로 잠그고 `QUEUED` 인지 본 뒤, `tasks.findByIdForUpdate(run.taskId())` 로 작업을 잠근다
- 작업이 `TaskState.ACTIVE` 가 아니면 `skip(task, run, TaskRunReason.PAUSED, now)` 하고 false 를 낸다. `start` 가 turn 자리를 푼다(기존 경로)
- 메서드 Javadoc 을 「줄과 작업을 차례로 잠그고 다시 읽는다. 아직 `QUEUED` 이고 작업이 `ACTIVE` 일 때만 `RUNNING` 으로 바꾼다」 로 고친다
- 클래스 Javadoc 의 「잠금은 ...」 문단에 잠그는 순서(`task_run` 다음 `task`)를 한 문장 더한다

### 4. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/task/TaskRunStarterTest.java` 에 더한다. 기존 `fixture`, `queued`, `awaitFinished`, `StubHermesRunsClient.received()` 를 쓴다. `TurnCancellation` 을 `@MockitoSpyBean` 으로 두어 `open` 이 불린 때(준비 트랜잭션은 커밋됐고 `markRunning` 은 아직)를 끼울 자리로 쓴다. 끼우지 않은 검사에서는 실제 메서드가 돈다.
데이터는 모두 합성이다. 실제 외부 작업을 다시 돌리지 않는다.

| 이름(`@DisplayName`) | 끼우는 것 | 기대 |
| --- | --- | --- |
| 준비 뒤 시작 전에 멈춘 작업은 새 실행을 열지 않는다 | `turns.open` 이 불리면 별도 트랜잭션(`PROPAGATION_REQUIRES_NEW`)에서 `TaskService.pause` 를 끝내고 실제 메서드를 부른다 | 발화가 `SKIPPED`, `reason` 이 `PAUSED`. Hermes 제출(`received()`) 0건. 그 대화에 `USER` 메시지 없음. `limiter.hasTurnRoom(owner)` 가 true(자리 해제) |
| 준비 뒤 시작 전에 지운 작업도 새 실행을 열지 않는다 | 위와 같되 `TaskService.archive` | 위와 같다 |
| 멈추기가 커밋되기 전이면 시작 단계가 기다렸다가 멈춘 상태를 본다 | `turns.open` 안에서 다른 스레드를 띄워 그 스레드의 트랜잭션이 `tasks.findByIdForUpdate` 로 작업을 잠그고 `pause` 한 뒤 `flush` 하고 래치를 기다리게 한다. 잠근 것을 확인한 뒤 실제 메서드를 부르고, 그 스레드는 300ms 뒤 커밋한다 | 발화가 `SKIPPED`(`PAUSED`), Hermes 제출 0건. 작업 잠금 없이 다시 읽기만 하는 구현이면 이 검사가 실패해야 한다 |
| 멈추지 않은 작업은 그대로 돈다 | 없음 | 기존 정상 경로 검사가 계속 `SUCCEEDED` 다(새로 쓰지 않아도 된다) |

기존 검사 `keepsUserEditCommittedWhileSingleRunRecordsConversation` 을 고친다. 지금은 사용자가 멈춘 상태와 이름이 남는지만 본다. 거기에 「Hermes 제출 0건, 발화가 `SKIPPED`(`PAUSED`)」 를 더한다. 이 검사의 사용자 트랜잭션은 준비 트랜잭션 안에서 커밋되므로 `markRunning` 이 멈춘 상태를 본다. 지금의 `awaitFinished(run)` 결과와 `finished.conversationId()` 단언은 새 기대에 맞게 고친다. 대화는 준비 단계가 이미 만들었으므로 `task.conversationId()` 는 그대로 그 대화다.

`backend/src/test/java/com/bifos/assistant/task/TaskServiceTest.java` 에 더한다.

| 이름 | 입력 | 기대 |
| --- | --- | --- |
| 고치기가 시작 단계가 적은 대화 번호를 되돌리지 않는다 | `SINGLE` 작업. 스레드 A 가 트랜잭션에서 `tasks.findByIdForUpdate` 로 작업을 잠근다. 스레드 B 가 `TaskService.update` 로 이름만 바꾸기 시작한다. 200ms 뒤 A 가 `tasks.useConversation(id, 대화번호, 시각)` 를 하고 커밋한다 | B 가 끝난 뒤 작업의 `conversationId` 가 A 가 적은 값이고 이름은 B 가 바꾼 값이다. `update` 가 잠그지 않고 읽으면 `conversationId` 가 null 로 되돌아가 실패해야 한다 |

스레드를 쓰는 검사는 `Future.get(10, SECONDS)` 처럼 시간 상한을 둔다. 대화 번호는 `conversations` 저장소로 합성 대화를 하나 만들어 쓴다(같은 파일의 기존 대화 만들기 방법을 따른다. 없으면 `TaskRunStarterTest` 의 대화 만들기를 따른다).

### 5. 저장소 쿼리 MySQL 검사

`RepositoryQueryMysqlTest` 가 저장소 인터페이스의 메서드를 스스로 찾아 실제 MySQL 에서 실행한다(`backend/AGENTS.md` 의 「저장소 쿼리는 실제 MySQL 에서도 실행한다」). 따로 더할 것은 없고 `scripts/check-mysql-migration.sh` 로 확인한다. 인자 타입을 만들지 못해 실패하면 `RepositoryQuerySweep` 에 그 타입의 값을 더한다.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.task.*'
cd backend && ./gradlew qualityCheck
scripts/check-mysql-migration.sh
scripts/check-local.sh tasks
```

- 첫 줄: 위 새 검사와 고친 검사가 모두 통과한다
- `scripts/check-local.sh` 는 백엔드 전체 검사와 web 검사까지 돈다. 인자는 브라우저 검사 범위다. 화면을 바꾸지 않으므로 `test/browser/tasks.spec.ts` 하나만 돌리도록 `tasks` 를 준다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/task/infra/TaskRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/application/TaskService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/application/TaskRunStarter.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/task/TaskRunStarterTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/task/TaskServiceTest.java` | 수정 |
| `docs/backend/task.md` | 수정 |
