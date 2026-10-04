# Phase 03. 실행마다 실은 Memory 항목의 참조

**Execution profile**: deep

## 목표

실행 하나에 실은 문맥 항목의 참조를 `execution_context_source` 에 남기고 관리자 실행 상세에 보인다.
어느 답에 어느 기록이 들어갔는지 본문을 다시 저장하지 않고 찾을 수 있게 한다. 이 phase 는 Memory 항목만 저장하고 #162 를 기다리지 않는다.

**범위 외**: 결과 항목(`DELEGATION_RESULT`, `CONNECTOR_RESULT`)의 저장은 phase 04 가 더한다. 일반 실행 상세(`/executions/{id}`)의 표시도 하지 않는다. 출처 목록은 관리자 영역에만 보인다.

## 컨텍스트

- 표의 칸은 `docs/backend/schema/execution.md` 의 「execution_context_source」 가 갖는다. `execution_id` BIGINT, `position` INT, `source` VARCHAR(32), `source_ref` VARCHAR(80), `body_mode` VARCHAR(16), `freshness` VARCHAR(16), `created_at` DATETIME(6), 기본 키 `(execution_id, position)`
- 마이그레이션은 `backend/src/main/resources/db/migration/` 에 있고 main 의 마지막은 `V68__task.sql` 이다. `V69` 는 다른 작업이 예약했으므로 이 표는 `V70__execution_context_source.sql` 을 쓴다. 머지 직전 main 이 `V70` 을 먼저 쓰면 그 다음 번호로 옮기고 아래 변경 파일 표도 함께 고친다
- 새 표는 `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci` 를 적는다. `test/unit/migration-collation.test.ts` 가 본다(`docs/backend/schema/README.md` 「마이그레이션 작성 규칙」)
- 실행 줄은 `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` 의 private `record(...)` 가 `limiter.admit(...)` 안에서 만든다. 문맥 값은 `ExecutionContextSnapshot`(`usage/application/ExecutionContextSnapshot.java`, `record(Long contextChars, String runtimeFingerprint, String instructionsHash, Integer contextOmittedItems)`)으로 받는다
- 스냅숏을 만드는 곳은 `chat/application/ChatService.java` 의 `runTurn`(`new ExecutionContextSnapshot(...)`)과 `orchestration/application/AgentRunner.java` 둘이다
- phase 02 가 `AssembledContext.bundle()` 에 Memory 항목을 둔다. 결과 항목은 아직 묶음에 없다
- `usage` 는 `context` 와 `chat` 을 import 하지 못한다(ADR-068). 그래서 `usage` 에는 문자열 칸의 참조 타입을 두고, `chat` 과 `orchestration` 이 항목을 그 타입으로 옮겨 넘긴다
- 실행 트리 응답은 `usage/application/ExecutionTreeService.java` 의 `node(...)` 가 `ExecutionNode` 로 만든다. 내부 값은 `InternalValuePolicy.visibleTo(viewer)` 가 참일 때만 싣는다
- 관리자 실행 상세 화면은 `web/src/components/execution/execution-detail.tsx` 의 `ExecutionDetail` 이고 `useAdminView()` 가 참일 때 네 시각(`data-testid="execution-timing"`)을 그린다. 노드 타입은 `web/src/components/execution/execution-tree.tsx` 의 `ExecutionTreeNode` 다
- 사건 저장처럼 이 기록도 관측용이다. 저장이 실패해도 실행은 그대로 간다(`docs/flow.md` 「대화 한 번」 의 「사건 저장이 실패해도 대화는 성공으로 끝난다」)

**근거 문서**: `docs/backend/context-bundle.md` 의 「로그와 저장」, `docs/backend/schema/execution.md` 의 「execution_context_source」, `docs/adr/ADR-071-여러-출처의-문맥은-항목마다-출처와-권한과-신선도를-지닌-묶음으로-조립한다.md`

## 의도 메모

- 제목과 본문을 남기는 칸을 두지 않는다. 참조만으로 원래 기록을 다시 찾는다
- 실행 줄을 만드는 사용자 잠금 안에 이 저장을 넣지 않는다. 잠금 안의 일이 늘면 다른 turn 이 기다린다. 실행 줄을 만든 직후 따로 저장한다
- 지우는 경로를 만들지 않는다. 실행 줄을 지우지 않으므로 이 줄도 남는다

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V70__execution_context_source.sql` (신규)

위 칸과 기본 키로 표를 만든다. DDL 만 담는다.

### 2. 엔티티와 저장소

- `backend/src/main/java/com/bifos/assistant/usage/domain/ExecutionContextSource.java`: 위 표의 엔티티. 기본 키는 `backend/src/main/java/com/bifos/assistant/usage/domain/ExecutionContextSourceId.java`(`executionId`, `position`)다. `memory.domain.ServiceTokenCollectionId` 의 복합 키 모양(`@Embeddable record` 와 `@EmbeddedId`)을 따른다. 그래서 `executionId` 와 `position` 은 엔티티의 `id` 안에 있다. `source`, `bodyMode`, `freshness` 는 문자열 칸이다
- `backend/src/main/java/com/bifos/assistant/usage/infra/ExecutionContextSourceRepository.java`: `List<ExecutionContextSource> findByIdExecutionIdOrderByIdPositionAsc(Long executionId)` 와 `List<ExecutionContextSource> findByIdExecutionIdInOrderByIdExecutionIdAscIdPositionAsc(Collection<Long> executionIds)`. 본보기 저장소 `ServiceTokenCollectionRepository` 도 `findByIdTokenIdIn` 으로 쓴다. 인자 타입이 `Long` 과 `Collection<Long>` 이라 `RepositoryQuerySweep` 에 더할 것이 없는지 실행해 본다

### 3. `usage` 의 참조 타입과 기록

- `backend/src/main/java/com/bifos/assistant/usage/application/ContextSourceRef.java`(신규): `record ContextSourceRef(String source, String ref, String bodyMode, String freshness)`
- `ExecutionContextSnapshot` 에 다섯 번째 칸 `List<ContextSourceRef> sources` 를 더한다. 기존 생성자와 `ofChars` 는 빈 목록이다
- `ExecutionRecorder.record` 는 `limiter.admit(...)` 가 돌려준 실행 줄로 `sources` 를 `position` 0 부터 저장한다. 저장이 실패하면 `log.warn("execution context sources not recorded executionId={} count={}", ...)` 만 남기고 실행 줄을 그대로 돌려준다

### 4. 스냅숏에 항목 넣기

- `ChatService.runTurn`: `context.bundle().items()` 를 `ContextSourceRef(source.name(), ref, bodyMode.name(), freshness.name())` 로 옮겨 스냅숏에 넣는다. 커넥터 에이전트(`AssembledContext.empty()`)는 남길 항목이 없다
- `AgentRunner`: `context.bundle().items()` 를 같은 방식으로 넣는다
- 옮기는 함수는 `chat/application/ContextSourceRefs.java`(신규) 정적 함수 하나로 두고 `AgentRunner` 도 쓴다. `orchestration` 은 `chat` 위라 쓸 수 있다

### 5. 실행 트리 응답

`ExecutionNode` 에 `List<ContextSourceRef> contextSources` 를 더한다. `ExecutionTreeService` 는 트리의 실행 번호로 한 번에 읽고(`findByIdExecutionIdInOrderByIdExecutionIdAscIdPositionAsc`), `internal` 이 참일 때만 싣는다. 아니면 `null` 이다.

### 6. 관리자 실행 상세

`execution-tree.tsx` 의 `ExecutionTreeNode` 에 `contextSources?: { source: string; ref: string; bodyMode: string; freshness: string }[] | null` 를 더한다.
`execution-detail.tsx` 는 `isAdmin` 일 때 시각 구간 아래에 「실은 문맥」 절(`data-testid="execution-context-sources"`)을 그린다. 줄마다 출처 이름, 참조, 싣는 방식이다.

| 값 | 화면 문구 |
| --- | --- |
| `MEMORY_ALWAYS` | 기억(항상) |
| `MEMORY_INDEX` | 기억(제목만) |
| `DELEGATION_RESULT` | 맡긴 일 결과 |
| `CONNECTOR_RESULT` | 승인한 동작 결과 |
| `INLINE`, `TITLE_ONLY`, `OMITTED` | 본문, 제목만, 자리가 없어 뺐어요 |
| `STALE`, `UNKNOWN` | 오래됨, 시각 모름. `FRESH` 는 그리지 않는다 |

목록이 비면 「실은 문맥이 없어요」 를 그린다. 모르는 값은 원문 그대로 그린다.

### 7. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/usage/ExecutionContextSourceTest.java`(신규, `@SpringBootTest`, `@ActiveProfiles("test")`):

| 입력 | 기대 |
| --- | --- |
| 참조 셋을 담은 스냅숏으로 `ExecutionRecorder.start` | 셋이 `position` 0, 1, 2 로 저장된다 |
| 저장소가 예외를 던지게 바꿔 끼운 기록기 | 실행 줄은 `RUNNING` 으로 만들어지고 예외가 올라오지 않는다 |
| 관리자가 실행 트리를 읽는다 | 루트 노드의 `contextSources` 가 저장한 셋이다 |
| `MEMBER` 가 같은 트리를 읽는다 | `contextSources` 가 `null` 이다 |

`backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` 에 더한다. 기존 turn 준비를 따른다.

| 입력 | 기대 |
| --- | --- |
| 개인 항상 층 하나와 색인 하나가 있는 사용자의 turn | 그 실행의 줄이 `MEMORY_ALWAYS memory:<번호> INLINE`, `MEMORY_INDEX memory:<번호> TITLE_ONLY` 다. 제목과 본문을 담은 칸이 없다 |

`test/browser/execution-tree.spec.ts` 의 「실행 상세와 작업 과정에 실제 모델, 단계, 기본 강도와 기록된 시각만 보인다」 옆에 검사 하나를 더한다. 관리자 영역의 실행 상세에 `execution-context-sources` 가 있고, `MEMBER` 의 일반 실행 상세에는 없다.

### 8. 저장 모델 문서의 구현 전 표시와 역할별로 빼는 값

`docs/backend/schema/execution.md` 「execution_context_source」 의 「**아직 구현 전이다.** 표를 만든 PR 이 이 줄을 지운다.」 줄과 `docs/backend/schema/README.md` 의 `execution_context_source(아직 구현 전이다)` 의 괄호를 지운다.
`docs/backend/conversation.md` 「역할에 따라 응답에서 빼는 값」 표의 `GET /api/v1/usage/executions/{id}/tree` 의 실행 노드 줄 끝에 `contextSources` 를 더한다.

ADR-071, `docs/adr/INDEX.md`, `docs/backend/context-bundle.md`, `docs/code-architecture.md` 의 구현 전 표시는 phase 04 가 지운다.

## 검증

```bash
# cwd: backend/
./gradlew test --tests 'com.bifos.assistant.usage.ExecutionContextSourceTest' --tests 'com.bifos.assistant.chat.ChatServiceTest' --tests 'com.bifos.assistant.architecture.*'
./gradlew test
./gradlew checkstyleMain checkstyleTest
```

```bash
# cwd: 저장소 root
scripts/check-mysql-migration.sh
node --test 'test/unit/**/*.test.ts'
node test/e2e/run.ts
```

```bash
# cwd: web/
pnpm typecheck
pnpm test:browser execution-tree
```

기대값: 모두 종료 코드 0. `MysqlMigrationTest` 가 새 표의 정렬 규칙과 `ddl-auto: validate` 를 통과하고, `test/unit/migration-collation.test.ts` 가 새 파일의 정렬 규칙 구절을 받는다.
`grep -n 'execution_context_source' docs/backend/schema/README.md` 의 줄에 「아직 구현 전」 이 없다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V70__execution_context_source.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/ExecutionContextSource.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/ExecutionContextSourceId.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/ExecutionContextSourceRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ContextSourceRef.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionContextSnapshot.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionNode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionTreeService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ContextSourceRefs.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/ExecutionContextSourceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 |
| `web/src/components/execution/execution-tree.tsx` | 수정 |
| `web/src/components/execution/execution-detail.tsx` | 수정 |
| `test/browser/execution-tree.spec.ts` | 수정 |
| `docs/backend/schema/execution.md` | 수정 |
| `docs/backend/schema/README.md` | 수정 |
| `docs/backend/conversation.md` | 수정 |
