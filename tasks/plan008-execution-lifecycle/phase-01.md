# Phase 01. 작업 영역을 제거한다

**Execution profile**: standard

## 목표

작업 영역(workspace)을 표와 칸까지 전부 제거한다.
뒤따르는 phase 가 `agent_execution` 의 스키마를 바꾸므로, 지울 칸을 먼저 지워
마이그레이션이 한 번에 끝나게 한다.

**범위 외**: 실행 lifecycle 변경은 phase-02 가 맡는다. 이 phase 는 지우기만 한다.

## 컨텍스트

작업 영역은 만들어진 뒤 한 번도 쓰이지 않았다.
운영 데이터베이스에서 확인한 것이다.

| 무엇 | 수 |
| --- | --- |
| `workspace` 표의 행 | 0 |
| `workspace_id` 가 채워진 `conversation` | 0 (전체 11건) |
| `workspace_id` 가 채워진 `agent_execution` | 0 (전체 15건) |

모든 행이 `NULL` 이므로 칸을 내려도 잃는 값이 없다.

**근거 문서**: `docs/adr/ADR-010-작업-영역을-제거하고-에이전트가-그-자리를-갖는다.md`,
`docs/data-schema.md` 의 「conversation」 절, `docs/code-architecture.md` 의 「backend 패키지」 절

## 의도 메모

- 칸만 남기고 코드만 지우는 안을 버렸다. 쓰이지 않는 칸이 스키마에 남으면
  다음에 읽는 사람이 그것이 무엇인지 다시 확인해야 한다.
- `fos-agents` 의 `AGENTS.md` 를 싣는 기능을 에이전트로 옮기는 안도 버렸다.
  에이전트가 가리키는 Hermes profile 이 스킬과 `SOUL.md` 로 이미 같은 일을 한다.
- 공개 범위에 기본값을 두지 않는다는 판단은 버리지 않는다.
  `agent` 의 `visibility` 가 그 규칙을 쓰고 있고, plan009 의 Memory 도 같게 만든다.

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V5__drop_workspace.sql` 신규

아래 순서로 적는다. 외래 키가 있으면 먼저 내린다.

```sql
ALTER TABLE agent_execution DROP COLUMN workspace_id;
ALTER TABLE conversation DROP COLUMN workspace_id;
DROP TABLE workspace;
```

`V2__workspace.sql` 과 `V4__agent.sql` 은 고치지 않는다.
이미 적용된 마이그레이션을 고치면 Flyway 의 검사가 실패한다.

### 2. `backend/src/main/java/com/bifos/assistant/workspace/` 아래 일곱 파일을 지운다

이 목록이 그 패키지의 전부다. 다른 파일이 있으면 지우지 말고 보고한다.

```
application/WorkspaceProperties.java
application/WorkspaceService.java
domain/Workspace.java
domain/WorkspaceVisibility.java
infra/WorkspaceRepository.java
presentation/WorkspaceAdminController.java
presentation/WorkspaceController.java
```

### 3. `ChatService` 에서 작업 영역을 제거한다

`backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` 다.

- 필드 `workspaces` 와 그 import 를 지운다.
- `send` 와 `stream` 의 `workspaceCode` 매개변수를 지운다.
- `prepare` 에서 `Workspace` 를 찾아 `briefing` 을 만드는 대목을 지운다.
  `HermesRunCommand` 의 `instructions` 자리에는 `null` 을 넣는다.
  이 자리는 plan009 의 `ContextAssembler` 가 채운다.
- `resolveConversation` 과 `resolveNewWorkspaceId` 에서 작업 영역을 지운다.
  `resolveNewWorkspaceId` 는 통째로 없어진다.

### 4. `Conversation` 과 `AgentExecution` 에서 칸을 지운다

- `chat/domain/Conversation.java` 의 `workspaceId` 필드와 접근자, 생성 인자
- `usage/domain/AgentExecution.java` 의 `workspaceId` 필드와 접근자, `Builder.workspaceId`
- `usage/application/ExecutionRecorder.java` 의 `base()` 에서 `.workspaceId(...)` 호출

### 5. 요청과 응답 형태에서 지운다

- `chat/presentation/ChatDtos.java` 의 `workspaceCode`
- `chat/presentation/ChatController.java` 가 그 값을 넘기는 자리와 대화 응답의 작업 영역 조회 의존성
- `shared/error/ErrorCode.java` 의 `WORKSPACE_NOT_FOUND`

### 6. 설정에서 지운다

- `backend/src/main/resources/application.yml` 의 `assistant.workspace` 절
- `backend/src/test/resources/application-test.yml` 의 같은 절

`ASSISTANT_WORKSPACE_ROOT` 를 읽는 자리가 없어진다.
홈서버 compose 의 읽기 전용 마운트도 필요 없어지지만, 그 저장소는 이 phase 의 범위가 아니다.
**제거했다는 사실을 완료 보고에 적는다.** 배포 설정은 `fos-home-infra` 가 따로 고친다.

### 7. 웹에서 지운다

- `web/src/app/api/workspaces/route.ts` 파일 삭제
- `web/src/app/api/chat/route.ts` 와 `web/src/app/api/chat/stream/route.ts` 가
  `workspaceCode` 를 실어 보내는 자리
- `web/src/components/chat-panel.tsx` 의 영역 고르기
- `web/src/components/chat/conversation-list.tsx` 의 영역 표시

### 8. 테스트에서 지운다

- `backend/src/test/java/com/bifos/assistant/workspace/WorkspaceServiceTest.java` 삭제
- `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` 에서 작업 영역을 쓰는 대목
- `test/e2e/scenarios/workspace.ts` 삭제하고 `test/e2e/run.ts` 의 목록에서 뺀다
- `test/e2e/run.ts` 와 `test/e2e/harness.ts` 에서 `workspaceRoot` 를 준비하고 넘기는 대목
- `test/browser/fixtures.ts` 에서 같은 대목
- `backend/src/test/java/com/bifos/assistant/usage/UsageCostRecordingTest.java` 의
  `Conversation.startedBy` 호출을 작업 영역 없는 인자로 바꾼다

### 9. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` 를 고친다.
작업 영역을 쓰던 대목을 지우고, 대신 아래 둘을 확인한다.

- **정상 경로**: 영역을 주지 않은 대화 한 번이 Hermes 를 지나 실행 기록을 남긴다.
  `HermesRunCommand` 의 `instructions` 가 `null` 로 나간다.
- **이 phase 가 다루는 실패**: 기존 웹 클라이언트처럼 요청 본문에 `workspaceCode` 를 실어 보내도
  HTTP 요청이 성공하고 그 값이 무시된다. 서비스 직접 호출은 JSON 역직렬화를 거치지 않으므로
  저장소가 이미 쓰는 MockMvc 테스트가 있으면 그 방식을 따르고, 없으면 e2e HTTP 시나리오에서 확인한다.
  먼저 현재 Spring Boot 4 와 Jackson 3 설정의 실제 응답을 확인한다.
  알 수 없는 필드가 400 으로 거절되면 테스트 기대값을 바꾸지 말고 배포 순서를 정하도록 중단한다.

`test/e2e/scenarios/chat.ts` 가 영역 없이 도는 것을 이미 확인하고 있으면 그대로 둔다.
`workspace.ts` 를 지운 뒤 `run.ts` 가 나머지 시나리오를 끝까지 도는 것이 판정 기준이다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
node test/e2e/run.ts
cd web && pnpm test:browser
```

남은 참조가 없는지 확인한다. 아래가 아무것도 내지 않아야 한다.

```bash
# cwd: 저장소 root
grep -rn -i "workspace" --include='*.java' --include='*.ts' --include='*.tsx' --include='*.yml' backend/src web/src test
```

`node test/e2e/run.ts` 가 실제 애플리케이션을 띄울 때 Flyway 마이그레이션을 적용한다.
전체 시나리오가 끝까지 통과하고 기동 로그에 Flyway 오류가 없는 것으로 적용 여부를 확인한다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V5__drop_workspace.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/` | 삭제 (7개) |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/Conversation.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/AgentExecution.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/workspace/WorkspaceServiceTest.java` | 삭제 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/UsageCostRecordingTest.java` | 수정 |
| `web/src/app/api/workspaces/route.ts` | 삭제 |
| `web/src/app/api/chat/route.ts` | 수정 |
| `web/src/app/api/chat/stream/route.ts` | 수정 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `web/src/components/chat/conversation-list.tsx` | 수정 |
| `test/e2e/scenarios/workspace.ts` | 삭제 |
| `test/e2e/run.ts` | 수정 |
| `test/e2e/harness.ts` | 수정 |
| `test/browser/fixtures.ts` | 수정 |
