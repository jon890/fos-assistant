# Phase 03. 권한 도우미로 지우고 관리자가 공간별 용량을 본다

**Execution profile**: deep

## 목표

`DELETE /api/v1/workspace/entries?path=` 가 운영의 권한 도우미를 unix socket 으로 불러 요청자의 공간에서 경로 하나를 지운다.
`GET /api/v1/admin/workspaces` 가 실행 공간마다 용량과 항목 수를 준다.

**범위 외**: 권한 도우미 자체(운영 저장소 `fos-home-infra` 가 만든다), 화면(phase 04).

## 컨텍스트

**근거 문서**: 이 phase 의 첫 작업이 적용하는 `docs/code-architecture.md` 「실행 공간 파일」 의 「지우기」(도우미 계약 포함), 「관리자 용량」, 「로그와 기록」, `docs/flow.md` 「파일 공간을 열 때」 의 지우기 시퀀스, `docs/adr/ADR-20261009-workspace-explorer.md`.

phase 01 이 만든 것(읽어서 이름을 맞춘다):

- `workspace/infra/WorkspaceProperties` 의 `deleteSocket()` 과 `deletable()`.
- `workspace/domain/WorkspacePath` 의 `parse`, `isRoot`, `value`, `segments`.
- `workspace/infra/WorkspaceTree` 의 조각마다 링크를 따라가지 않고 여는 규칙. 지우기 전 존재 확인에 같은 규칙을 쓴다.
- `workspace/domain/WorkspaceEntryKind` 네 값.
- `workspace/application/WorkspaceService`, `workspace/presentation/WorkspaceController`, `WorkspaceDtos`, `ErrorCode` 의 `WORKSPACE_*`.

기존 식별자:

- `AgentService.byIds(Collection<Long>)` 이 `Map<Long, Agent>` 를 준다. `UserDisplayNameService.find(Long)` 이 이름이나 `null` 을 준다.
- 관리자 경로는 `CurrentUserProvider.requireAdmin()` 으로 막는다(`browser/presentation/UserBrowserAdminController.java`).
- Jackson 은 3 이다. `tools.jackson.databind` 를 쓴다(`backend/AGENTS.md`).
- `Clock` 빈이 있고 시험은 `TestClock` 이다.

## 의도 메모

- 주인 키는 요청자에게서만 만든다(`"u" + user.id()`). 요청 본문과 인자로 받지 않는다.
- 도우미를 부르기 전에 Control Plane 이 경로 규칙과 읽기 마운트의 존재를 본다. 없으면 도우미를 부르지 않고 404 다. 도우미도 같은 검사를 다시 한다.
- unix socket 은 `SocketChannel.open(StandardProtocolFamily.UNIX)` 와 `UnixDomainSocketAddress` 로 연다. 제한 시간은 non-blocking 채널과 `Selector` 로 건다. 따로 스레드를 띄우지 않는다.
- 답 한 줄은 64 KiB 까지만 읽는다. 넘거나 JSON 이 아니면 `WORKSPACE_DELETE_FAILED` 다.
- 지우기 로그는 `INFO` 한 줄: 사용자 번호, 상대 경로, 종류, 지운 항목 수, 결과. 실패도 같은 줄에 결과 코드로 남긴다.
- 관리자 용량은 `Files.walkFileTree` 를 `FOLLOW_LINKS` 없이 쓴다. 읽지 못한 디렉터리(`visitFileFailed`)는 건너뛰고 `partial` 이다.
- 기각: 지우기를 비동기 작업으로 돌리기. 한 번에 경로 하나와 항목 10,000 개가 상한이라 요청 안에서 끝난다.

## 작업 항목

### 1. 문서 적용

```bash
git apply tasks/plan114-workspace-explorer/docs-stage3.patch
```

`docs/code-architecture.md`, `docs/flow.md`, `docs/backend/packages.md`, `docs/README.md`, `docs/frontend/structure.md`, `docs/frontend/shell.md`, `docs/prd.md` 가 바뀐다. 화면 문서의 변경은 phase 04 가 구현한다.

### 2. `workspace/domain/WorkspaceDeleter.java` 신규 port 와 결과 record

```java
public interface WorkspaceDeleter {
    WorkspaceDeletion delete(String owner, WorkspacePath path, int maxEntries);
}
public record WorkspaceDeletion(WorkspaceEntryKind kind, long entries, long bytes) {}
```

실패는 `ApiException` 으로 던진다. 도우미 코드와 응답의 대응은 문서의 표다.

### 3. `workspace/infra/UnixSocketWorkspaceDeleter.java` 신규

`WorkspaceDeleter` 의 구현이다. `LiveProperties<WorkspaceProperties>` 에서 socket 경로를 읽는다. 비었으면 `WORKSPACE_DELETE_UNAVAILABLE`.

- 요청은 `{"version":1,"owner":...,"path":...,"max_entries":...}` 한 줄과 `\n` 이다.
- 연결과 쓰기와 읽기를 합쳐 30초 안에 끝내지 못하면 채널을 닫고 `WORKSPACE_DELETE_FAILED` 다. 연결하지 못해도 같다.
- `ok` 가 참이면 `kind`, `entries`, `bytes` 를 읽는다. `kind` 가 네 값이 아니면 실패다.
- `ok` 가 거짓이면 `NOT_FOUND`, `LINK_IN_PATH` 는 `WORKSPACE_ENTRY_NOT_FOUND`, `TOO_MANY_ENTRIES` 는 `WORKSPACE_DELETE_TOO_MANY`, 나머지는 `WORKSPACE_DELETE_FAILED` 다.

### 4. `ErrorCode` 에 세 코드

`WORKSPACE_DELETE_UNAVAILABLE`(503), `WORKSPACE_DELETE_TOO_MANY`(409), `WORKSPACE_DELETE_FAILED`(502).

### 5. `WorkspaceService.delete` 와 컨트롤러

```java
public WorkspaceDeletion delete(CurrentUser user, String rawPath)
```

- `deletable()` 이 거짓이면 `WORKSPACE_DELETE_UNAVAILABLE`, 루트가 없으면 `WORKSPACE_UNAVAILABLE`.
- 빈 경로는 `VALIDATION_FAILED`.
- `WorkspaceTree` 에 `static boolean exists(Path ownerDir, WorkspacePath path)` 를 더한다. 중간 조각 규칙은 목록과 같고, 마지막 조각은 링크를 따라가지 않고 무엇이든 있으면 참이다. 거짓이면 `WORKSPACE_ENTRY_NOT_FOUND`.
- `WorkspaceDeleter.delete(owner, path, 10_000)` 를 부르고 로그를 남긴다.
- `WorkspaceController` 에 `@DeleteMapping("/entries")` 를 더하고 `DeletionView(kind, entries, bytes)` 를 `WorkspaceDtos` 에 둔다.

### 6. 관리자 용량

- `workspace/application/model/WorkspaceSpaceKind`(`USER`, `AGENT`), `WorkspaceSpaceUsage(WorkspaceSpaceKind kind, long id, String name, long bytes, long entries, boolean partial)`, `WorkspaceUsageReport(boolean available, List<WorkspaceSpaceUsage> spaces)`.
- `workspace/infra/WorkspaceUsageWalker.java`: `static WorkspaceMeasured measure(Path dir, long maxEntries, Instant deadline, Clock clock)`. 결과 record 는 `workspace/domain/WorkspaceMeasured(long bytes, long entries, boolean partial)` 다. 링크는 세되 따라가지 않는다. 일반 파일만 크기에 더한다.
- `workspace/application/WorkspaceUsageService.java`: 루트 바로 아래의 `^u(\d+)$`, `^a(\d+)$` 디렉터리만 센다(링크인 디렉터리는 세지 않는다). 공간마다 200,000 개, 전체 30초 상한이다. 상한에 닿은 뒤의 공간은 세지 않고 `partial` 참, 크기 0 으로 둔다. 이름은 `UserDisplayNameService.find` 와 `AgentService.byIds` 로 채운다. `bytes` 내림차순이다.
- `workspace/presentation/WorkspaceAdminController.java`: `@RequestMapping("/api/v1/admin/workspaces")` 의 `GET`. `requireAdmin()` 뒤에 부른다. 응답 DTO 는 `WorkspaceDtos` 에 둔다.

### 7. 검사

unix socket 경로 길이 상한 때문에 시험의 socket 은 `Files.createTempDirectory(Path.of("/tmp"), "ws")` 아래에 둔다. 끝나면 지운다.

`infra/UnixSocketWorkspaceDeleterTest.java`: 시험 안에서 `ServerSocketChannel.open(StandardProtocolFamily.UNIX)` 로 가짜 도우미를 띄운다.

- 받은 요청 줄이 `version`, `owner`, `path`, `max_entries` 를 갖는다.
- `ok` 답을 `WorkspaceDeletion` 으로 바꾼다.
- `NOT_FOUND`, `LINK_IN_PATH`, `TOO_MANY_ENTRIES`, `FAILED`, 모르는 코드, 깨진 JSON, 64 KiB 를 넘는 줄, 답 없이 닫기를 각각 문서의 오류로 바꾼다.
- 답하지 않는 도우미는 제한 시간 뒤 `WORKSPACE_DELETE_FAILED` 다. 시간은 이 클래스가 받는 제한 시간 인자로 짧게 둔다(생성자 하나를 package-private 으로 더 둔다).
- socket 설정이 비면 `WORKSPACE_DELETE_UNAVAILABLE`.

`presentation/WorkspaceControllerDeleteTest.java`(`WorkspaceControllerTest` 와 같은 방식, 도우미는 기록만 하는 가짜 `WorkspaceDeleter`)

- 요청자 `u301` 의 `a.txt` 를 지우면 200 이고, 가짜가 받은 주인은 `u301` 이다.
- `?path=../u302/a.txt` 는 400 이고 가짜가 불리지 않는다. `u302` 에만 있는 경로는 404 이고 가짜가 불리지 않는다.
- 빈 경로는 400. 링크를 지나는 경로는 404 이고 가짜가 불리지 않는다.
- socket 이 비면 503 이고 상태의 `deletable` 이 거짓이다.

`application/WorkspaceUsageServiceTest.java`

- `u1`, `u2`, `a3`, `tmp` 디렉터리 가운데 앞의 셋만 나오고 `bytes` 내림차순이다.
- `u1` 안의 링크가 가리키는 큰 파일은 크기에 들지 않는다.
- 항목 상한을 넘는 공간은 `partial` 참이다(시험은 상한 인자를 작게 준다).
- 응답 어디에도 파일 이름이 없다.

`presentation/WorkspaceAdminControllerTest.java`: `MEMBER` 는 403, `ADMIN` 은 200.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.workspace.*' --tests 'com.bifos.assistant.architecture.*'
cd backend && ./gradlew test
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
scripts/check-local.sh --skip-browser
```

- 첫 줄의 두 묶음이 통과한다.
- `git grep -n "WORKSPACE_DELETE_TOO_MANY" backend/src/main` 이 한 줄 이상이다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `docs/code-architecture.md` | 수정 |
| `docs/flow.md` | 수정 |
| `docs/backend/packages.md` | 수정 |
| `docs/README.md` | 수정 |
| `docs/frontend/structure.md` | 수정 |
| `docs/frontend/shell.md` | 수정 |
| `docs/prd.md` | 수정 |
| `backend/src/main/java/com/bifos/assistant/workspace/domain/WorkspaceDeleter.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/domain/WorkspaceDeletion.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/domain/WorkspaceMeasured.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/infra/UnixSocketWorkspaceDeleter.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/infra/WorkspaceUsageWalker.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/infra/WorkspaceTree.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/workspace/application/WorkspaceService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/workspace/application/WorkspaceUsageService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/application/model/WorkspaceSpaceKind.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/application/model/WorkspaceSpaceUsage.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/application/model/WorkspaceUsageReport.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/presentation/WorkspaceController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/workspace/presentation/WorkspaceAdminController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/presentation/WorkspaceDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/workspace/infra/UnixSocketWorkspaceDeleterTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/workspace/presentation/WorkspaceControllerDeleteTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/workspace/application/WorkspaceUsageServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/workspace/presentation/WorkspaceAdminControllerTest.java` | 신규 |
