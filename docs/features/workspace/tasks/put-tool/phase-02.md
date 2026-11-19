# Phase 02. `file_space_put` 도구와 답에 묶기, 실행 입력의 파일 공간 단락

**Execution profile**: standard

## 목표

에이전트가 MCP 도구 `file_space_put` 으로 실행 공간이나 대화 폴더의 파일을 요청자의 「내 파일」 에 넣는다. 넣은 파일은 turn 이 끝날 때 답에 묶이고 메시지 응답에 `FILE` 항목으로 나간다. `artifact_write` 가 제목과 설명을 받는다. 에이전트가 받는 실행 입력에 「파일 공간」 단락이 들어간다.

**범위 외**: 화면(phase 03). 에이전트의 찾기 도구(PR3). Hermes 플러그인 변경(없다).

## 컨텍스트

MCP 도구는 `McpToolCatalog` 가 정의를, `McpController` 가 인자 검사와 분기를, `McpToolService` 가 실행을 맡는다. 요청자는 `McpCaller`(origin 실행의 사용자, origin 실행)로 온다.
`artifact_write` 의 처리(`McpController.writeArtifact`, `McpToolService.writeArtifact`)와 그 테스트 `McpArtifactWriteToolTest` 가 본보기다.
실행 공간은 `WorkspaceService` 와 `WorkspaceTree` 가 읽는다. 지금은 요청자의 디렉터리 `u<사용자 번호>` 만 읽는다. 넣기 도구는 origin 실행의 에이전트가 쓰는 실행 공간(`Agent.sandboxOwner()`)을 읽어야 한다.
최상위 패키지의 층 순서에서 `workspace` 가 `mcp` 위에 있어 `mcp` 가 `workspace` 를 직접 부르지 못한다. `mcp/application/port/` 의 포트를 `workspace` 가 구현한다(`ApprovalResumes` 가 본보기다).
phase 01 이 `ArtifactVaultService.keep` 과 묶음 보관의 origin·제목 경로, `vault_meta`, `vault_origin`, `vault_message_id` 를 만들어 두었다.

**요구사항**: REQ1, REQ2, REQ3, REQ6, REQ7, REQ8

**근거 문서**: `docs/features/workspace/spec.md`, `docs/features/workspace/design.md` 의 「`file_space_put` 도구」, 「`artifact_write` 의 제목」, 「답에 묶기와 메시지 응답」, 「실행 입력의 단락」, 「실패와 일관성」, `docs/adr/ADR-20261119-file-space-put.md`, `docs/features/mcp.md`

## 의도 메모

- 도구 인자에 대화 식별자를 두지 않는다. 대화와 사용자는 origin 실행에서만 읽는다
- 결과와 로그에 호스트 경로와 내부 번호를 담지 않는다
- 실행 공간의 파일은 한 번 읽은 바이트를 그대로 넘긴다. 다시 읽지 않는다
- fos-ctx 의 `REQUIRED_TOOLS` 는 고치지 않는다
- `McpController.java` 는 499줄이고 파일 길이 상한은 500줄이다. 이 phase 의 첫 커밋으로 `artifact_write` 의 handler(`writeArtifact`, `onlyArtifactFields`, `exactlyOneText`, `artifactValidationReason`)를 `mcp/presentation/McpArtifactCalls.java` 로 동작 변경 없이 옮긴다(이동만 하는 커밋). 그 뒤 커밋에서 동작을 바꾼다. 새 도구의 handler 는 `mcp/presentation/McpFileSpaceCalls.java` 에 둔다
- 조건 없는 지시는 싼 모델이 요청과 상관없이 따른다. 단락은 「`file_space_put` 도구가 있을 때만」 을 먼저 적는다(`ArtifactService.IMAGE_RULES` 의 꼴)

## 작업 항목

### 1. 실행 공간 읽기 포트

- `mcp/application/port/SandboxFiles.java`(신규): `SandboxFile read(String sandboxOwner, String relativePath, long maxBytes)`. 결과 record `SandboxFile(Status status, byte[] bytes)` 의 `Status` 는 `FOUND`, `MISSING`, `TOO_LARGE` 다. 같은 파일 안의 중첩 타입으로 둔다
- `workspace/application/WorkspaceSandboxFiles.java`(신규): 루트가 설정됐고 주인 디렉터리가 링크가 아닌 디렉터리일 때 `WorkspacePath` 와 `WorkspaceTree.stat`, `WorkspaceTree.open` 으로 일반 파일 하나를 읽는다. 크기는 열기 전과 읽은 뒤에 모두 본다. 폴더, 링크, 읽을 수 없는 파일, 없는 파일, 경로 형식 오류는 `MISSING`, 상한을 넘으면 `TOO_LARGE` 다. 로그에 경로를 남기지 않는다

### 2. 대화 폴더의 형식을 보지 않는 읽기

`ArtifactPathPolicy` 와 `ArtifactStore` 에 형식을 보지 않고 대화 폴더 안의 일반 파일을 푸는 메서드를 더한다. 폴더 안인지 보는 규칙은 기존 `resolveInside` 와 같다. `ArtifactController` 가 쓰는 기존 메서드는 고치지 않는다.

### 3. `mcp/application/McpFileSpaceTools.java`(신규)와 `McpToolCatalog`, `McpController`

- 도구 정의: 이름 `file_space_put`, 인자 `path`(필수), `title`, `description`. 설명에 「사용자의 파일 공간(내 파일)에 파일을 넣는다」, 경로의 두 뜻, 받는 형식, 25MB, 이미 들어가는 것(답에 묶인 HTML, `image_generate` 의 그림)은 넣지 않는다는 것을 적는다
- 경로 판정은 설계의 순서다. 이 대화의 `ArtifactStore.agentFolder(conversationId) + "/"` 접두, `/workspace/` 접두, 그 밖의 절대 경로는 `NOT_FOUND`, 상대 경로는 대화 폴더다
- 대화 폴더의 HTML, CSS, 사진(`ArtifactStore.contentTypeOf` 가 값을 주는 경로)은 `keepBundle` 로, 그 밖의 형식은 2번의 읽기로 바이트를 얻어 `keep(PUT)` 으로 넣는다. 실행 공간 파일은 `SandboxFiles` 로 읽어 `keep(PUT_WORKSPACE)` 로 넣는다. 실행 공간의 주인 키는 origin 실행의 `agentId` 로 에이전트를 읽어 `sandboxOwner()` 로 만든다. `agentId` 가 없으면 `NOT_FOUND` 다
- 보관 자리는 10초까지 기다린다
- 결과는 설계의 판정 표다. 성공은 `saved`, `already`, `title`, `message`. 실패는 `isError` 와 까닭 코드, 「파일 공간에 넣지 못했다」 로 시작하고 「사용자에게 넣었다고 말하지 않는다」 로 끝나는 글이다. 보관 서비스의 예외는 오류 코드와 고정 메시지로 까닭을 나눈다(`artifactValidationReason` 의 방식). `keep` 의 표는 phase 01 의 작업 항목 4 다. `ARTIFACT_VAULT_UNSUPPORTED` 가운데 메시지에 `larger` 나 `too many` 가 든 것은 `TOO_LARGE`, `missing` 이 든 것과 `ARTIFACT_NOT_FOUND`, `ARTIFACT_GONE` 은 `NOT_FOUND`, 나머지는 `UNSUPPORTED_TYPE` 이다. `ARTIFACT_VAULT_FULL` 은 `QUOTA_FULL`, 그 밖은 `UNAVAILABLE` 이다. 결과 모양은 `McpToolResultShape` 의 규칙을 따른다
- `McpController`: handler 등록, `CHECK_TREE_WRITE_TOOLS` 에 더한다. `writeAllowedInCheck` 가 `McpCaller` 를 받게 하고, 이 도구일 때는 인자 대신 `check.conversationId()` 가 `caller.originExecution().conversationId()` 와 같은지 본다. 모르는 인자 거절과 글 인자 검사는 `McpFileSpaceCalls` 가 한다
- `McpMemoryRemember.INTERNAL_TOOLS` 에 `mcp__fos_assistant__file_space_put` 을 더한다

### 4. `artifact_write` 의 제목과 설명

- `McpToolCatalog` 의 `artifact_write` 정의, `McpArtifactCalls.onlyArtifactFields`, `McpDtos.ArtifactWriteArguments`, `ArtifactWriteRequest` 에 `title`, `description` 을 더한다
- `ArtifactWriteService.write` 가 HTML 경로이고 제목이나 설명이 있으면, 쓰기가 성공한 뒤 그 대화·경로의 「답에 묶이지 않은 결과물 행」(`message_id` 가 비고 `vault_user_id` 와 `generation_call_key` 가 빈 행)에 `vault_meta` 를 둔다. 없으면 만들고 있으면 바꾼다. 암호화를 쓸 수 없으면 제목을 버리고 파일만 쓴다. 제목 저장이 실패해도 쓰기 결과는 성공이다
- `ArtifactService.recordTurn` 은 바뀐 HTML 을 묶을 때 그 경로의 묶이지 않은 행이 있으면 그 행에 답 번호와 크기를 적고, 없으면 지금처럼 새 행을 만든다

### 5. 답에 묶기와 메시지 응답

- `ChatArtifactRepository`, `ChatArtifactWriter` 에 넣기 도구의 보관 행을 묶는 update 를 더한다. 조건은 그 대화, `vault_origin` 이 `PUT` 또는 `PUT_WORKSPACE`, `vault_message_id` 가 빔, `vault_bundle_id` 가 빔, `deleted_at` 이 빔이다. 묶기 전에, 그 답에 같은 경로의 결과물 행이 있는 `PUT` 행은 `vault_origin` 을 비운다
- `ArtifactService.recordTurn` 의 HTML 묶기를 private 메서드로 빼어 이른 반환이 그 안에서만 일어나게 하고, 그 뒤 한 곳에서 넣은 파일을 묶는다. HTML 훑기가 실패했거나 바뀐 HTML 이 없는 turn 에서도 묶인다. 실패해도 던지지 않고 경고 로그만 남긴다
- 메시지 응답: 답 번호들로 넣기 도구의 보관 뿌리 행(지운 이력 포함)을 projection 으로 읽어 `ArtifactView` 의 `FILE` 항목으로 더한다. `ChatDtos.ArtifactView` 에 `title` 을 더한다. `FILE` 의 `byteSize` 는 묶음 전체, `deleted` 는 지웠는지, `vaultItemId` 는 뿌리 행 번호, `path` 는 지웠으면 빈 글이다. 조립은 `ChatController` 의 `ArtifactView` 를 만드는 한 곳이다. 제목은 `ArtifactVaultMeta` 로 풀고, 조회와 풀기는 `chat.artifact.application` 의 새 메서드(`ArtifactVaultService.putFilesByMessage`)가 맡는다

### 6. 실행 입력의 단락

`ArtifactService.agentPreamble` 에 설계의 「실행 입력의 단락」 다섯 줄을 `IMAGE_RULES` 뒤에 더한다. 맡긴 실행에 관한 줄(파일을 만든 에이전트가 직접 넣는다)도 더한다.

### 7. 테스트

- `McpFileSpacePutToolTest`(신규, `@BackendIntegrationTest`, `McpArtifactWriteToolTest` 의 준비 코드와 `McpCallSigner` 를 쓴다. 실행 공간 루트는 임시 디렉터리를 설정으로 준다):
  실행 공간의 xlsx 를 넣어 요청자의 보관함에 있고 바이트가 같다. 그룹 공개 에이전트를 다른 사용자가 써도 요청자의 것이고 주인의 목록에는 없다. 대화 폴더의 상대 경로와 절대 경로가 모두 된다. 다른 대화의 폴더 경로, `/etc/` 같은 절대 경로, `..` 가 든 경로, 링크는 `NOT_FOUND` 다. zip 은 `UNSUPPORTED_TYPE`, 상한 초과는 `TOO_LARGE`, 용량 초과는 `QUOTA_FULL` 이고 보관함에 생기지 않는다. 실패 결과의 글이 정한 문장을 담고 호스트 경로를 담지 않는다. 같은 파일을 두 번 넣으면 한 건이고 둘째가 `already` 다. 모르는 인자는 거절된다. 살펴보기 트리에서 쓰기를 허용하지 않은 점검은 거절되고 허용한 점검은 된다
- `FileSpaceTurnTest`(신규, `GeneratedImageTurnTest` 가 본보기다): 넣은 뒤 `recordTurn` 을 지나면 메시지 응답에 `FILE` 항목이 있고 제목과 `vaultItemId` 가 맞다. 바뀐 HTML 이 없는 turn 에서도 묶인다. 보관함에서 지우면 `deleted` 가 참이다. 같은 답에 같은 경로의 HTML 결과물이 있으면 `FILE` 항목이 없다. 넣기 도구 없이 대화 폴더에 쓴 xlsx 는 응답에 없다. 다시 넣어 `already` 가 되면 다음 답으로 옮겨 묶인다. `artifact_write` 에 준 제목이 자동 보관본의 목록 제목이 된다
- 실행 입력에 「파일 공간」 단락과 실패 규칙이 있는지 보는 사례를 `FileSpaceTurnTest` 에 둔다(`artifactService.agentPreamble(conversation)` 의 글을 본다)
- `FileSpaceTurnTest` 에 더할 사례: HTML 훑기가 실패하는 turn(대화 폴더를 읽을 수 없게 만든다)에서도 넣은 파일이 묶인다
- `McpFileSpacePutToolTest` 에 더할 사례: 다른 사용자가 그 보관본의 본문 주소(`/api/v1/artifact-vault/{id}/content`)를 열면 없는 파일과 같은 응답이다
- `WorkspaceSandboxFilesTest`(신규): 일반 파일, 폴더, 링크, 크기 초과, 없는 주인 디렉터리

## 검증

```bash
cd backend && ./gradlew test --tests '*McpFileSpacePutToolTest' --tests '*FileSpaceTurnTest' --tests '*WorkspaceSandboxFilesTest' --tests '*McpArtifactWriteToolTest' --tests '*ArtifactTest' --tests '*ArtifactWriteServiceTest' --tests '*ArtifactMcpValidationTest' --tests '*ChatServiceTest' --tests '*ChatRegenerateTest' --tests '*ChatAttachmentTurnTest' --tests '*McpToolResultShapeTest' --tests '*GeneratedImageTurnTest' --tests '*ArtifactAutoVaultTest' --tests '*ArchitectureRules*' --tests '*McpMemoryRememberToolTest'
```

같은 맥에서 여러 worker 가 도므로 Gradle, 브라우저 테스트, `check-local.sh` 줄은 저장소 공통 지시서의 무거운 검사 대기열(`orch slot heavy -- <명령>`)로 감싸 실행한다. 기대값은 모두 통과다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/mcp/application/port/SandboxFiles.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/workspace/application/WorkspaceSandboxFiles.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpFileSpaceTools.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolCatalog.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpMemoryRemember.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpArtifactCalls.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpFileSpaceCalls.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/infra/ArtifactPathPolicy.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/infra/ArtifactStore.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/infra/ChatArtifactRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/application/ChatArtifactWriter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/application/ArtifactService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/application/ArtifactWriteService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/application/ArtifactVaultService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/application/dto/ArtifactWriteRequest.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/domain/ChatArtifact.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/presentation/ChatController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/presentation/ChatDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpFileSpacePutToolTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/FileSpaceTurnTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/workspace/application/WorkspaceSandboxFilesTest.java` | 신규 |
