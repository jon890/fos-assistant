# Phase 05. 관리자가 실행 공간마다 용량을 본다

**Execution profile**: deep

## 목표

`GET /api/v1/admin/workspaces` 가 실행 공간마다 주인 이름, 용량, 항목 수, 일부만 셌는지를 준다. 파일 이름과 경로는 주지 않는다.

**범위 외**: 관리자 화면(phase 06), 지우기(phase 03).

## 컨텍스트

**근거 문서**: 이 phase 의 첫 작업이 적용하는 `docs/code-architecture.md` 「실행 공간 파일」 의 「관리자 용량」 과 API 표의 관리자 줄, `docs/backend/packages.md` 의 `workspace` 줄, `docs/adr/ADR-20261009-workspace-explorer.md`.

phase 01 이 만든 것(읽어서 이름을 맞춘다): `workspace/infra/WorkspaceProperties`(`available()`, `rootPath()`), `workspace/infra/WorkspaceTree.isDirectoryNoFollow`, `workspace/presentation/WorkspaceDtos`, `ErrorCode.WORKSPACE_UNAVAILABLE`.

기존 식별자:

- `AgentService.byIds(Collection<Long>)` 이 `Map<Long, Agent>` 를 준다. `Agent.name()` 은 fluent 접근자다.
- `UserDisplayNameService.find(Long)` 이 이름이나 `null` 을 준다(`com.bifos.assistant.user.application`).
- 관리자 경로는 `CurrentUserProvider.requireAdmin()` 으로 막는다(`browser/presentation/UserBrowserAdminController.java`). 그 시험은 `backend/src/test/java/com/bifos/assistant/browser/presentation/UserBrowserControllerTest.java` 가 보안 문맥에 `ADMIN` 과 `MEMBER` 를 넣는 방식이다.
- `Clock` 빈이 있다.

## 의도 메모

- `Files.walkFileTree` 를 `FOLLOW_LINKS` 없이 쓴다. 링크는 항목으로 세되 크기에 넣지 않는다. 일반 파일만 크기에 더한다.
- 읽지 못한 디렉터리(`visitFileFailed`)는 건너뛰고 `partial` 이다.
- 루트 바로 아래의 이름이 `^u(\d+)$` 나 `^a(\d+)$` 이고 링크가 아닌 디렉터리만 센다.
- 상한(공간마다 200,000 개, 전체 30초)은 서비스 생성자 인자로 받는다. Spring 이 쓰는 생성자는 기본값을 넣는다.
- 기각: 용량을 미리 세어 표에 두기. 관리자가 가끔 여는 화면이라 요청할 때 센다. 큰 공간은 상한에서 멈춘다.

## 작업 항목

### 1. 문서 적용

phase 03 의 문서 변경이 들어간 브랜치에서 적용한다.

```bash
git apply tasks/plan114-workspace-explorer/docs-admin.patch.md
```

`docs/code-architecture.md`, `docs/backend/packages.md`, `docs/frontend/structure.md`, `docs/frontend/shell.md`, `docs/prd.md` 가 바뀐다. 화면 문서의 변경은 phase 06 이 구현한다.

### 2. `workspace/domain/WorkspaceMeasured.java` 와 `workspace/infra/WorkspaceUsageWalker.java` 신규

- `WorkspaceMeasured(long bytes, long entries, boolean partial)`.
- `static WorkspaceMeasured measure(Path dir, long maxEntries, Instant deadline, Clock clock)`. 항목이 `maxEntries` 에 닿거나 `clock.instant()` 가 `deadline` 을 지나면 `TERMINATE` 하고 `partial` 이 참이다.

### 3. `workspace/application/model/` 신규

`WorkspaceSpaceKind`(`USER`, `AGENT`), `WorkspaceSpaceUsage(WorkspaceSpaceKind kind, long id, String name, long bytes, long entries, boolean partial)`, `WorkspaceUsageReport(boolean available, List<WorkspaceSpaceUsage> spaces)`. 타입 하나에 파일 하나다.

### 4. `workspace/application/WorkspaceUsageService.java` 신규

- `@Autowired` 를 단 public 생성자: `LiveProperties<WorkspaceProperties>`, `UserDisplayNameService`, `AgentService`, `Clock`. 상한은 200,000 과 30초다.
- package-private 생성자: 위 넷에 `long maxEntriesPerSpace`, `Duration budget` 을 더 받는다. 시험이 쓴다.
- `public WorkspaceUsageReport report()`: `available()` 이 거짓이거나 루트가 디렉터리가 아니면 `available=false` 와 빈 목록이다. 아니면 위 이름 규칙으로 공간을 찾아 차례로 센다. 전체 시간이 지난 뒤의 공간은 세지 않고 `bytes` 0, `entries` 0, `partial` 참이다. 이름은 사용자는 `find`, 에이전트는 `byIds` 로 채우고 못 찾으면 `null` 이다. `bytes` 내림차순이다.

### 5. `workspace/presentation/WorkspaceAdminController.java` 신규와 DTO

`@RequestMapping("/api/v1/admin/workspaces")` 의 `GET`. `requireAdmin()` 뒤에 `report()` 를 부른다. 응답 DTO `AdminUsageView`, `AdminSpaceView` 는 `WorkspaceDtos` 에 둔다.

### 6. 검사

`application/WorkspaceUsageServiceTest.java`: 단위 시험이다. `UserDisplayNameService` 와 `AgentService` 는 Mockito mock, 시계는 `Clock.fixed` 이거나 시험이 움직이는 시계다. 루트는 `@TempDir` 이다.

- `u1`, `u2`, `a3`, `tmp` 디렉터리 가운데 앞의 셋만 나오고 `bytes` 내림차순이다. 이름은 mock 이 준 값이다.
- `u1` 안의 링크가 가리키는 공간 밖의 큰 파일은 크기에 들지 않는다. 루트 바로 아래의 링크 디렉터리 `u9` 는 나오지 않는다.
- 항목 상한을 작게 주면 넘는 공간은 `partial` 참이다.
- 응답 record 어디에도 파일 이름이 없다(`toString()` 에 시험 파일 이름이 없다).
- 루트 설정이 비면 `available` 거짓이다.

`presentation/WorkspaceAdminControllerTest.java`(`@BackendIntegrationTest`, standalone `MockMvc`): `MEMBER` 는 403, `ADMIN` 은 200 과 `available` 칸.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.workspace.*' --tests 'com.bifos.assistant.architecture.*'
cd backend && ./gradlew test
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
scripts/check-local.sh --skip-browser
```

- 첫 줄의 두 묶음이 통과한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `docs/code-architecture.md` | 수정 |
| `docs/backend/packages.md` | 수정 |
| `docs/frontend/structure.md` | 수정 |
| `docs/frontend/shell.md` | 수정 |
| `docs/prd.md` | 수정 |
| `backend/src/main/java/com/bifos/assistant/workspace/domain/WorkspaceMeasured.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/infra/WorkspaceUsageWalker.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/application/model/WorkspaceSpaceKind.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/application/model/WorkspaceSpaceUsage.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/application/model/WorkspaceUsageReport.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/application/WorkspaceUsageService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/presentation/WorkspaceAdminController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/presentation/WorkspaceDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/workspace/application/WorkspaceUsageServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/workspace/presentation/WorkspaceAdminControllerTest.java` | 신규 |
