# Phase 01. Control Plane 이 실행 공간 파일을 읽어 주인에게만 보인다

**Execution profile**: deep

## 목표

`backend` 에 최상위 패키지 `workspace` 를 만들고, 요청자의 실행 공간 디렉터리 `u<사용자 번호>` 의 상태, 목록, 본문을 주는 세 경로를 연다.
링크를 따라가지 않고, 남의 공간에 닿는 길이 없게 한다.

**범위 외**: 지우기 경로(phase 03), 관리자 용량(phase 05), 화면(phase 02, 04, 06).

## 컨텍스트

**근거 문서**: `docs/code-architecture.md` 의 「실행 공간 파일」(설정, 경로 규칙, API, 본문 머리글, 로그와 기록), `docs/flow.md` 의 「파일 공간을 열 때」, `docs/adr/ADR-20261009-workspace-explorer.md`, `docs/backend/packages.md` 의 `workspace` 줄과 「최상위 패키지의 층 순서」.
이 phase 의 동작은 위 문서와 같아야 한다. 문서와 다르게 만들어야 하면 멈추고 묻는다.

따를 기존 패턴:

- 설정: `backend/src/main/java/com/bifos/assistant/browser/infra/BrowserProperties.java` 와 `LivePropertiesConfig.browserPropertiesLive`. `@ConfigurationPropertiesScan` 이 record 를 찾는다.
- 컨트롤러: `browser/presentation/UserBrowserController.java`. 주인은 `CurrentUserProvider.require().id()` 로만 정한다.
- 본문 스트림과 머리글: `chat/presentation/ArtifactController.java` 의 `read`. `{*path}` 경로 변수는 앞의 `/` 와 함께 디코딩된 값을 준다.
- 링크를 따라가지 않는 디렉터리 핸들: `chat/infra/ArtifactFileWriter.java` 가 `SecureDirectoryStream` 이 있으면 쓰고 없으면 경고 뒤 대체 경로로 간다.
- 컨트롤러 검사: `backend/src/test/java/com/bifos/assistant/browser/presentation/UserBrowserControllerTest.java`. `@BackendIntegrationTest` 에 standalone `MockMvc` 와 보안 문맥으로 요청자를 넣는다. 검사 클래스에 컨텍스트 키를 바꾸는 선언을 두지 않는다(`docs/backend/testing.md`).

기존 식별자(읽어서 확인한 것):

- `Agent.sandboxOwner()` 가 `"u" + ownerUserId` 를 낸다. 이 phase 는 같은 규칙으로 `"u" + 요청자 번호` 를 만든다. `Agent` 의 접근자는 fluent 다: `code()`, `name()`, `visibility()`, `ownerUserId()`.
- `AgentService.readableBy(CurrentUser)` 는 켜지고 지우지 않은 에이전트 가운데 요청자가 읽을 수 있는 것이다. `AgentVisibility` 는 `com.bifos.assistant.agent.domain.type.AgentVisibility` 의 `PRIVATE`, `GROUP` 이다.
- `UserExecutionLimiter.used(Long userId)` 가 그 사용자가 지금 쥔 자리 수다(`com.bifos.assistant.usage.application`).
- `ErrorCode` 는 `shared/error/ErrorCode.java` 의 enum 이고 `ApiException(ErrorCode, String)` 으로 던진다.
- `ArtifactController.CONTENT_SECURITY_POLICY` 는 package-private 상수다.
- `TopLevelPackageOrder.ORDER`(`backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrder.java`) 의 마지막이 `attention` 이다. `TopLevelPackageOrderTest` 의 `allowsTopPackageToUseEveryOtherPackage` 가 나머지 20개와 맨 위 `attention` 을, `orderHasTwentyOneDistinctPackages` 가 21개를 박아 두었다.
- `ArchitectureRules.LIVE_SETTINGS` 는 실행 중에 쓰는 설정 record 목록이고 마지막 항목이 `BrowserProperties.class` 다. 그 record 를 `LivePropertiesConfig` 밖에서 직접 주입받으면 `LIVE_SETTINGS_ONLY_THROUGH_LIVE_PROPERTIES` 가 막는다.
- 결과물은 `css` 를 `text/css; charset=utf-8` 로 준다(`chat/infra/ArtifactPathPolicy.java`).
- Spring 의 `ContentDisposition.inline()/attachment().filename(name, UTF_8).build().toString()` 은 `filename="<ASCII 로 옮긴 이름>"; filename*=UTF-8''<이름>` 을 함께 낸다. 문서 표가 이 모양이다.

## 의도 메모

- 주인 디렉터리를 요청 값으로 정하는 경로를 만들지 않는다. 경로 변수와 인자는 그 디렉터리 안의 상대 경로뿐이다.
- 경로 조각마다 `LinkOption.NOFOLLOW_LINKS` 로 연다. `toRealPath()` 로 정규화해 루트 안인지 보는 방식(결과물의 방식)을 쓰지 않는다. 링크가 공간 안을 가리켜도 따라가지 않는다.
- 하드 링크 수는 `Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)` 로 본다. 그 속성을 못 읽는 파일 시스템이면 1 로 본다.
- 본문을 열기 직전에 종류를 다시 본다. FIFO 를 열면 스레드가 막히므로 일반 파일이 아니면 열지 않는다. 확인과 열기 사이에 바뀌는 경우는 ADR 의 「감당할 것」 이다.
- 읽기 권한이 없는 파일은 `Files.isReadable` 로 판정한다(목록의 `readable`). 열다가 `AccessDeniedException` 이면 403 이다.
- 미리보기 형식은 확장자로만 정한다. 파일 내용으로 짐작하지 않는다.
- 오류 로그에 경로를 남기지 않는다. 사용자 번호와 예외 종류만 남긴다.
- 기각: 주인 디렉터리가 없을 때 만들기. Control Plane 은 읽기 전용이다. 빈 목록을 준다.
- 서비스는 `Files` 를 직접 부르지 않는다. 파일 시스템 조회는 모두 `WorkspaceTree` 의 정적 메서드로 한다.
- 확장자 규칙은 문서 「본문 머리글」 의 문단 그대로다. 화면(phase 02)의 `previewKind` 도 같은 규칙이고 두 쪽 시험에 같은 예시(`.env`, `.gitignore`, `Makefile`, `a.TXT`)를 둔다.

## 작업 항목

### 1. `shared/util/SandboxedContentPolicy.java` 신규와 `ArtifactController` 의 상수 이동

`public final class SandboxedContentPolicy` 에 두 상수를 둔다.

- `HTML` 은 지금 `ArtifactController.CONTENT_SECURITY_POLICY` 의 값 그대로다.
- `NONE` 은 `"sandbox; default-src 'none'"` 이다.

`ArtifactController.CONTENT_SECURITY_POLICY` 는 `SandboxedContentPolicy.HTML` 을 가리키게 바꾼다. 이름은 그대로 두어 기존 검사를 고치지 않는다.

### 2. `workspace/infra/WorkspaceProperties.java` 신규와 설정

```java
@Validated
@ConfigurationProperties(prefix = "assistant.sandbox-workspace")
public record WorkspaceProperties(String root, String deleteSocket) {
    public boolean available() { ... }   // root 가 비지 않았다
    public boolean deletable() { ... }   // deleteSocket 이 비지 않았다
    public Path rootPath() { ... }       // available() 일 때만 부른다
}
```

- `LivePropertiesConfig` 에 `workspacePropertiesLive(WorkspaceProperties value)` 빈을 `browserPropertiesLive` 와 같은 모양으로 더한다.
- `ArchitectureRules.LIVE_SETTINGS` 의 끝에 `WorkspaceProperties.class` 를 더한다. 이 record 는 `LiveProperties<WorkspaceProperties>` 로만 받는다.
- `backend/src/main/resources/application.yml` 의 `assistant:` 아래에 아래를 더한다. 주석은 각 값이 무엇이고 비면 어떻게 되는지 한 줄씩 적는다.

```yaml
  sandbox-workspace:
    root: ${ASSISTANT_SANDBOX_WORKSPACE_ROOT:}
    delete-socket: ${ASSISTANT_SANDBOX_WORKSPACE_DELETE_SOCKET:}
```

- `backend/src/test/resources/application-test.yml` 에도 같은 키를 빈 값으로 둔다. 시험 기본값은 꺼짐이다.

### 3. `workspace/domain/WorkspacePath.java` 신규

상대 경로 값 객체다. `docs/code-architecture.md` 「경로 규칙」 의 거절 표를 그대로 구현한다.

- `static WorkspacePath parse(String raw)`: `null` 과 빈 문자열은 빈 경로다. 맨 앞 `/`, 빈 조각, `.`, `..`, NUL, 제어 문자(U+0000–U+001F, U+007F)는 `ApiException(VALIDATION_FAILED)`. 전체 UTF-8 4,096 바이트, 조각 255 바이트, 조각 64개를 넘어도 같다.
- `static WorkspacePath ofSegments(List<String> segments)`: 본문 경로(`{*path}` 를 `/` 로 나눈 것)용. 같은 검사를 한다.
- `List<String> segments()`, `boolean isRoot()`, `String value()`(조각을 `/` 로 이은 것), `String name()`(마지막 조각).
- `static boolean addressable(String name)`: `%`, `;`, `\` 가 없고 제어 문자가 없으면 참이다. 목록의 `openable` 이 쓴다.

### 4. `workspace/domain/` 과 `workspace/application/model/` 의 record 와 enum

`infra` 는 `application` 을 쓰지 못한다(`ArchitectureRules.LAYER_DIRECTION`). 그래서 `WorkspaceTree` 가 만드는 값은 `domain` 에 두고, 서비스만 쓰는 값은 `application/model` 에 둔다. 타입 하나에 파일 하나다.

`workspace/domain/`

- `WorkspaceEntryKind`: `DIRECTORY`, `FILE`, `LINK`, `OTHER`.
- `WorkspaceEntry(String name, WorkspaceEntryKind kind, Long size, Instant modifiedAt, boolean readable, boolean openable)`. `size` 는 `FILE` 만 채운다.
- `WorkspaceListing(String path, List<WorkspaceEntry> entries, boolean truncated)`.
- `WorkspaceOpenedFile(long size, Instant modifiedAt, InputStream body)`.

`workspace/application/model/`

- `WorkspaceStatus(boolean available, boolean deletable, boolean exists, int runningExecutions, List<WorkspaceAgent> agents)` 와 `WorkspaceAgent(String code, String name, boolean shared)`.
- `WorkspaceFile(String name, String contentType, long size, boolean html, boolean download, InputStream body)`.

### 5. `workspace/infra/WorkspaceTree.java` 신규

파일 시스템을 다루는 유일한 곳이다. 생성자는 없고 정적 메서드만 둔다. 주인 디렉터리 `Path ownerDir` 를 받는다.

- `static Optional<WorkspaceListing> list(Path ownerDir, WorkspacePath path, int limit)`
  - `ownerDir` 가 없으면 빈 경로에만 빈 목록을 주고, 빈 경로가 아니면 `Optional.empty()` 다.
  - `ownerDir` 자체가 링크이거나 디렉터리가 아니면 `Optional.empty()` 다.
  - 조각마다 링크가 아닌 디렉터리인지 보고 연다. 아니면 `Optional.empty()`.
  - 디렉터리 항목을 `limit + 1` 개까지만 읽는다. 읽은 것 가운데 `limit` 개를 디렉터리 먼저, 이름(`String.compareTo`) 순서로 정렬해 주고, `limit + 1` 번째가 있었으면 `truncated` 다.
  - 항목의 종류는 링크를 따라가지 않은 속성으로 정한다. `readable` 은 `FILE` 이면 `Files.isReadable` 이고 하드 링크가 1 일 때만 참, `DIRECTORY` 면 `Files.isReadable`, `LINK` 와 `OTHER` 는 거짓이다.
  - 이름이 UTF-8 로 온전하지 않은 항목도 이름 그대로 넣는다. `openable` 은 `WorkspacePath.addressable(name)` 이다.
- `static boolean isDirectoryNoFollow(Path dir)`: 링크가 아닌 디렉터리면 참이다. 루트와 주인 디렉터리 판정에 쓴다.
- `static Optional<WorkspaceEntry> stat(Path ownerDir, WorkspacePath path)`: 중간 조각 규칙은 `list` 와 같고, 마지막 조각을 링크를 따라가지 않고 본 목록 한 줄을 준다. 없거나 링크를 지나면 비어 있다. 서비스가 미리보기 크기 상한을 열기 전에 판정할 때 쓴다.
- `static WorkspaceOpenedFile open(Path ownerDir, WorkspacePath path)`
  - 중간 조각 규칙은 위와 같다. 없거나 링크를 지나면 `ApiException(WORKSPACE_ENTRY_NOT_FOUND)`.
  - 마지막 조각이 일반 파일이 아니면 `WORKSPACE_ENTRY_NOT_FOUND`. 하드 링크가 둘 이상이면 `WORKSPACE_ENTRY_UNREADABLE`.
  - `SecureDirectoryStream` 이 있으면 마지막 디렉터리 핸들에서 `newByteChannel(name, Set.of(READ), NOFOLLOW_LINKS)` 로 연다. 없으면 `Files.newByteChannel(path, READ, NOFOLLOW_LINKS)` 로 열고, 대체 경로를 쓴다는 경고를 프로세스에 한 번만 남긴다.
  - 연 뒤에는 `Channels.newInputStream` 으로 감싼다. `AccessDeniedException` 은 `WORKSPACE_ENTRY_UNREADABLE` 이다.
- 디렉터리 핸들은 모두 닫는다. 연 스트림은 응답이 닫는다.

### 6. `workspace/application/WorkspacePreviewPolicy.java` 와 `workspace/application/model/WorkspacePreview.java` 신규

`docs/code-architecture.md` 「본문 머리글」 의 첫 표와 확장자 문단을 구현한다.

- `static Optional<WorkspacePreview> of(String name)` 와 record `WorkspacePreview(String contentType, long maxBytes, boolean html)`. 타입 하나에 파일 하나라 record 는 따로 둔다.
- 확장자 규칙: 마지막 `.` 뒤를 소문자로 읽는다. `.` 으로 시작하고 다른 `.` 이 없는 이름은 그 뒤 전체가 확장자다(`.env` 는 `env` 라 글, `.gitignore` 는 `gitignore` 라 없음). `.` 이 없는 이름은 확장자가 없는 이름이라 글이다.
- `css` 는 `text/css; charset=utf-8` 이다. 글 확장자 목록과 상한(1 MiB, 5 MiB, 20 MiB)은 문서의 값이다.

### 7. `workspace/application/WorkspaceService.java` 신규

생성자 인자는 `LiveProperties<WorkspaceProperties>`, `AgentService`, `UserExecutionLimiter` 셋이다(`@RequiredArgsConstructor`).

```java
public WorkspaceStatus status(CurrentUser user)
public WorkspaceListing list(CurrentUser user, String rawPath)
public WorkspaceFile open(CurrentUser user, String rawPathFromPathVariable, boolean download)
```

- 주인 디렉터리는 `rootPath().resolve("u" + user.id())` 하나다.
- `available()` 이 거짓이거나 `WorkspaceTree.isDirectoryNoFollow(root)` 가 거짓이면 `status` 는 `available=false` 의 값을, 나머지 둘은 `ApiException(WORKSPACE_UNAVAILABLE)` 을 낸다.
- `status.agents` 는 `AgentService.readableBy(user)` 가운데 `ownerUserId` 가 요청자인 것이고, `shared` 는 `visibility() == GROUP` 이다. `runningExecutions` 는 `UserExecutionLimiter.used(user.id())` 다.
- `list` 의 상한은 1,000 이다. `WorkspaceTree.list` 가 비면 `WORKSPACE_ENTRY_NOT_FOUND`.
- `open` 은 앞의 `/` 를 떼고 `/` 로 나눠 `WorkspacePath.ofSegments` 로 만든다.
  `download` 면 형식 검사 없이 `application/octet-stream` 이다. 아니면 `WorkspacePreviewPolicy.of` 가 비면 `WORKSPACE_PREVIEW_UNSUPPORTED`, `WorkspaceTree.stat` 의 크기가 상한을 넘으면 열기 전에 `WORKSPACE_PREVIEW_TOO_LARGE` 다. 응답에는 연 뒤의 크기를 쓴다.
- `exists` 는 `WorkspaceTree.isDirectoryNoFollow(ownerDir)` 다.

### 8. `shared/error/ErrorCode.java` 에 다섯 코드

`WORKSPACE_UNAVAILABLE`(503), `WORKSPACE_ENTRY_NOT_FOUND`(404), `WORKSPACE_ENTRY_UNREADABLE`(403), `WORKSPACE_PREVIEW_TOO_LARGE`(413), `WORKSPACE_PREVIEW_UNSUPPORTED`(415). 다른 코드처럼 한국어 Javadoc 한 줄을 단다.

### 9. `workspace/presentation/WorkspaceController.java` 와 `WorkspaceDtos.java` 신규

`@RequestMapping("/api/v1/workspace")`.

- `GET ""` → `StatusView`
- `GET "/entries"` 인자 `path`(선택) → `ListingView`. `modifiedAt` 은 ISO-8601 문자열이다.
- `GET "/files/{*path}"` 인자 `download`(선택, `"1"` 이면 내려받기) → `ResponseEntity<InputStreamResource>`.
  머리글은 문서의 「본문 머리글」 둘째 표다. `Content-Disposition` 은 `ContentDisposition.inline()` 또는 `attachment()` 에 `filename(name, StandardCharsets.UTF_8)` 를 주어 만든다.
  HTML 미리보기의 CSP 는 `SandboxedContentPolicy.HTML`, 그 밖은 `SandboxedContentPolicy.NONE` 이다. `Cache-Control: private, no-store`, `X-Content-Type-Options: nosniff`, `Content-Length` 를 붙인다. `ETag` 와 `Last-Modified` 는 붙이지 않는다(Spring 이 304 로 바꿔 연 스트림이 남는 것을 막는다).
- DTO record 는 `WorkspaceDtos` 한 파일에 둔다(`ArchitectureRules.CONTROLLERS_HAVE_NO_NESTED_RECORDS`).

### 10. `TopLevelPackageOrder.ORDER` 에 `"workspace"` 와 그 시험

`"attention"` 뒤, 맨 끝에 더한다. `TopLevelPackageOrderTest` 는 아래로 고친다.

- `allowsTopPackageToUseEveryOtherPackage`: 나머지는 21개, 맨 위는 `"workspace"`.
- `orderHasTwentyOneDistinctPackages` 를 `orderHasTwentyTwoDistinctPackages` 로 바꾸고 22 를 단언한다. `@DisplayName` 은 「층 순서는 겹치는 이름 없이 스물둘이다」.

### 11. 검사

`backend/src/test/java/com/bifos/assistant/workspace/` 아래에 둔다. 임시 디렉터리는 `@TempDir` 로 만든다.

`domain/WorkspacePathTest.java`

- 정상: `"a/b.txt"` 의 조각 둘, 빈 값은 루트.
- 경계: 조각 64개는 받고 65개는 거절한다.
- 거절: `"../u2"`, `"/etc/passwd"`, `"a//b"`, `"a/./b"`, `"a\u0000b"`, 조각 65개, 조각 256바이트. 모두 `VALIDATION_FAILED`.
- `addressable`: `"보고서.csv"` 참, `"50%.txt"`, `"a;b"`, `"a\\b"` 거짓.

`infra/WorkspaceTreeTest.java`

- 목록: 디렉터리 먼저 이름 순서, `FILE` 의 크기, 1,001개 중 1,000개와 `truncated`.
- 링크: 공간 밖 파일을 가리키는 심볼릭 링크 파일은 `LINK` 이고 `readable` 거짓, `open` 은 `WORKSPACE_ENTRY_NOT_FOUND`.
- 링크 디렉터리: 형제 주인 디렉터리(`u2`)를 가리키는 링크 디렉터리 아래를 `list` 와 `open` 하면 비거나 `WORKSPACE_ENTRY_NOT_FOUND`. 형제의 파일 이름이 결과 어디에도 없다.
- 주인 디렉터리 자체가 링크면 빈 결과다.
- 하드 링크: 같은 파일의 하드 링크는 `readable` 거짓, `open` 은 `WORKSPACE_ENTRY_UNREADABLE`.
- 특수 파일: `mkfifo` 로 만든 FIFO 는 `OTHER`, `open` 은 막히지 않고 `WORKSPACE_ENTRY_NOT_FOUND`. 이 시험에 `@Timeout(5)` 를 단다. `mkfifo` 가 없으면 `Assumptions.assumeTrue` 로 건너뛴다.
- `stat` 과 `isDirectoryNoFollow`: 링크 디렉터리는 거짓, 링크를 지나는 경로의 `stat` 은 비어 있다.
- 권한: 소유자 권한을 뺀 파일은 `readable` 거짓, `open` 은 `WORKSPACE_ENTRY_UNREADABLE`. root 로 도는 환경이면 `Assumptions.assumeFalse` 로 건너뛴다.

`application/WorkspacePreviewPolicyTest.java`

- `a.html` 은 html, `a.css` 는 `text/css; charset=utf-8`, `a.TXT` 와 `.env` 와 `Makefile` 은 `text/plain; charset=utf-8`, `.gitignore` 와 `a.bin` 은 비어 있다, `a.png` 는 `image/png` 와 20 MiB.

`presentation/WorkspaceControllerTest.java` (`@BackendIntegrationTest`, standalone `MockMvc`, `UserBrowserControllerTest` 와 같은 방식. 서비스는 `LiveProperties.fixed(WorkspaceProperties.class, new WorkspaceProperties(tempDir.toString(), ""))` 와 기반이 주는 `AgentService`, `UserExecutionLimiter` 로 만든다)

- 상태: 루트가 빈 설정이면 `available=false`, 디렉터리가 없으면 `exists=false`, 있으면 참. `deletable` 은 거짓.
- 상태의 `agents`: 요청자가 주인인 비공개 에이전트와 그룹 공개 에이전트가 나오고 그룹 공개 쪽만 `shared` 가 참이다. 다른 사용자가 주인인 그룹 공개 에이전트는 나오지 않는다. 에이전트는 `JdbcTemplate` 이나 기존 시험의 준비 방법으로 만들고 끝나면 지운다.
- 남의 공간: 요청자 `u301` 의 공간에는 없고 `u302` 에만 있는 경로를 목록과 본문으로 물으면 404 이고, `?path=../u302` 는 400 이다.
- `a.css` 미리보기는 `text/css; charset=utf-8` 과 `SandboxedContentPolicy.NONE` 이다.
- 머리글: `a.html` 미리보기는 `text/html; charset=utf-8`, `SandboxedContentPolicy.HTML`, `nosniff`, `private, no-store`, `inline`. `a.txt` 는 `text/plain; charset=utf-8` 과 `SandboxedContentPolicy.NONE`. `a.svg` 는 `text/plain`. `?download=1` 은 `application/octet-stream` 과 `attachment` 와 `filename*=UTF-8''`.
- 한글 이름 `보고서.csv` 를 조각 인코딩 주소로 열면 200 이다.
- 413: 1 MiB 를 넘는 `big.txt` 미리보기. 같은 파일 내려받기는 200.
- 415: `a.bin` 미리보기.
- 503: 루트가 빈 설정이면 목록이 `WORKSPACE_UNAVAILABLE`.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.workspace.*' --tests 'com.bifos.assistant.architecture.*' --tests 'com.bifos.assistant.chat.presentation.*'
cd backend && ./gradlew test
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
scripts/check-local.sh --skip-browser
```

- 첫 줄의 세 묶음이 모두 통과한다. `WorkspaceTreeTest` 의 링크, 하드 링크, 특수 파일 검사가 Linux CI 에서 건너뛰지 않는다.
- `git grep -n "sandbox-workspace:" backend/src/main/resources/application.yml` 이 한 줄 이상이다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/shared/util/SandboxedContentPolicy.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ArtifactController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/workspace/infra/WorkspaceProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/LivePropertiesConfig.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/src/main/java/com/bifos/assistant/workspace/domain/WorkspacePath.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/domain/WorkspaceEntryKind.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/domain/WorkspaceEntry.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/domain/WorkspaceListing.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/domain/WorkspaceOpenedFile.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/application/model/WorkspaceStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/application/model/WorkspaceAgent.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/application/model/WorkspaceFile.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/infra/WorkspaceTree.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/application/WorkspacePreviewPolicy.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/application/model/WorkspacePreview.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/application/WorkspaceService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/workspace/presentation/WorkspaceController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/presentation/WorkspaceDtos.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrder.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrderTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/workspace/application/WorkspacePreviewPolicyTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/workspace/domain/WorkspacePathTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/workspace/infra/WorkspaceTreeTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/workspace/presentation/WorkspaceControllerTest.java` | 신규 |
