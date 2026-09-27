# Phase 01. Control Plane 이 결과물 파일을 답에 묶고 스크립트 없이 준다

**Execution profile**: deep

## 목표

대화마다 결과물 폴더를 두고 실행 입력으로 그 자리를 알린다. turn 이 끝나면 그 폴더에서 이번 turn 에 바뀐 HTML 을 찾아 답 메시지에 묶는다.
대화 주인에게 그 폴더의 파일을 스크립트가 돌지 않는 머리글과 함께 준다. 30일이 지난 파일은 지운다.

**범위 외**: web 화면(phase-02). 초안을 만드는 Hermes 스킬과 홈서버 마운트(`fos-agents`, `fos-home-infra` 가 소유한다).

## 컨텍스트

**근거 문서**:
- `docs/adr/ADR-027-에이전트가-만든-html-은-대화별-폴더에-두고-스크립트-없이-보인다.md`
- `docs/code-architecture.md` 「결과물 파일」 절 전체
- `docs/data-schema.md` 「chat_artifact」
- `docs/flow.md` 「결과물 파일을 볼 때」

사진 첨부가 같은 모양을 먼저 만들었다. 거의 모든 자리에서 그 코드를 본보기로 쓴다.

계획을 쓸 때의 코드다. 시작할 때 다시 연다.

| 위치 | 지금 모양 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentProperties.java` | `@ConfigurationProperties(prefix = "assistant.attachment")` record. `root`, `agentRoot` 가 비면 생성자에서 `IllegalStateException` |
| `backend/src/main/resources/application.yml` | `assistant.attachment` 아래 `root: ${ASSISTANT_ATTACHMENT_ROOT}`, `agent-root: ${ASSISTANT_ATTACHMENT_AGENT_ROOT}`, `retention-days: 30`, `cleanup-cron: "0 0 4 * * *"` |
| `backend/src/test/resources/application-test.yml` | `assistant.attachment` 아래 `root: build/test-attachments`, `agent-root: /agent-side/attachments`, `cleanup-cron: "-"` |
| `backend/src/main/java/com/bifos/assistant/chat/infra/AttachmentStore.java` | 대화 번호로 디렉터리를 만들고 파일을 두고 지운다 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentCleaner.java` | `@Scheduled(cron = "${assistant.attachment.cleanup-cron}")`. 한 건이 실패해도 계속한다 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentService.java` | `agentInput(Long conversationId, List<ChatAttachment> attached, String text)` 가 사진이 있을 때만 `[이번 메시지에 올린 사진]` 단락을 붙인다 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | `attachments.agentInput(conversation.id(), ...)` 두 곳(한 에이전트 turn, 흐름 turn). `ChatEvent.done/stopped(...)` 를 보내는 곳 세 곳. `turns.open(...)` 이 turn 의 시작이다 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/AttachmentController.java` | `GET /{attachmentId}` 가 본문과 `Content-Type`, `Cache-Control` 을 준다 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | `MessageView(..., List<AttachmentView> attachments, ActivitySummary activity, String status)` |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` | `messages` 가 `chat.attachmentsByMessage(user, conversationId)` 로 답마다 첨부를 한 번에 읽는다 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | `ATTACHMENT_GONE(HttpStatus.GONE)` 같은 모양 |
| `backend/src/main/resources/db/migration/` | 대화 공개 식별자 계획이 `V23` 을 쓴다. 이 phase 는 `V24` |
| `test/e2e/fake-hermes.ts` | 실행 요청의 `input` 을 `run.input === "..."` 처럼 통째로 견준다. `lastSubmittedInput()` 이 마지막 `input` 을 준다 |
| `test/e2e/run.ts`, `test/browser/fixtures.ts` | Control Plane 을 띄울 때 `ASSISTANT_ATTACHMENT_ROOT` 에 실행마다 만든 임시 디렉터리, `ASSISTANT_ATTACHMENT_AGENT_ROOT` 에 `/agent-side/attachments` 를 준다 |

**마이그레이션 검사(`*MigrationTest`)가 모든 마이그레이션을 H2 의 MySQL 모드에서 돌린다.** `backend/AGENTS.md` 「엔티티와 마이그레이션은 따로 논다」 를 읽는다.

## 의도 메모

- **결과물 뿌리는 사진 첨부와 따로 둔다.** 보관과 지우는 대상이 다르다. 사진은 행 단위로, 결과물은 폴더 안 파일 단위로 지운다
- **폴더 단락을 매 turn 붙인다.** 이번 turn 에 파일을 만들지 미리 알 수 없다. 사진 단락보다 앞에 둔다
- **모델이 답에 적은 경로를 읽지 않는다.** turn 이 끝나면 폴더를 훑어 turn 시작 뒤에 바뀐 `.html` 만 묶는다. 사진과 CSS 는 행을 만들지 않는다. HTML 이 부르는 것일 뿐이다
- 훑기와 행 만들기가 실패해도 turn 은 성공으로 끝난다. 경고 로그만 남긴다. 답은 이미 저장됐다
- **보관 기간은 파일의 마지막 수정 시각으로 센다.** 폴더를 걸으며 30일 지난 파일을 지우고, 지운 HTML 의 `chat_artifact` 행에 `deleted_at` 을 적는다. 빈 폴더는 남겨도 된다
- 파일을 줄 때 행을 찾지 않는다. 대화 폴더 안에 있고 확장자가 허용되면 준다. HTML 이 부르는 사진은 행이 없다. 행은 없는 파일이 410 인지 404 인지 가릴 때만 본다
- **폴더 밖 판정은 `toRealPath()` 뒤에 한다.** 요청 경로에 `..` 이 있거나 심볼릭 링크가 폴더 밖을 가리키면 404 다. 판정 전에 파일을 열지 않는다
- 파일 크기에 상한을 두지 않는다. 사진 첨부처럼 스트림으로 준다

## Blocked 조건

- `tasks/plan028-conversation-public-id/` 가 main 에 남아 있다 → `PHASE_BLOCKED: 대화 공개 식별자 계획이 끝나지 않았다`. 파일 경로의 `{id}` 가 공개 식별자다
- `tasks/plan026-running-other-tab/` 가 main 에 남아 있다 → `PHASE_BLOCKED: plan026 이 끝나지 않았다`. 두 계획이 `chat-panel.tsx` 와 `ChatService` 의 같은 자리를 고친다

## 작업 항목

### 1. 설정

- `chat/application/ArtifactProperties.java`: `@ConfigurationProperties(prefix = "assistant.artifact")` record `(String root, String agentRoot, Integer retentionDays)`. `AttachmentProperties` 처럼 둘이 비면 기동을 멈추고 보관 기간 기본값은 30
- `application.yml`: `assistant.artifact.root: ${ASSISTANT_ARTIFACT_ROOT}`, `agent-root: ${ASSISTANT_ARTIFACT_AGENT_ROOT}`, `retention-days: 30`. 한국어 주석은 첨부 설정의 주석을 따른다
- `application-test.yml`: `root: build/test-artifacts`, `agent-root: /agent-side/artifacts`
- 설정 클래스를 등록하는 자리는 `AttachmentProperties` 가 등록된 자리를 찾아 같게 한다

### 2. 마이그레이션 `V24__chat_artifact.sql`과 엔티티

`docs/data-schema.md` 「chat_artifact」 의 칸 그대로다. `UNIQUE KEY uk_chat_artifact_message_path (message_id, path)`, `KEY ix_chat_artifact_conversation (conversation_id)`.
H2 의 MySQL 모드에서도 도는 DDL 만 쓴다. `V22__agent_starter.sql` 이 본보기다.

`chat/domain/ChatArtifact.java` 와 `chat/infra/ChatArtifactRepository.java` 를 `ChatAttachment` 와 그 저장소처럼 둔다.
저장소에 `findByMessageIdIn(Collection<Long>)`, `findByConversationIdAndPathAndDeletedAtIsNotNull`, `findByConversationIdAndPathAndDeletedAtIsNull` 정도를 둔다. 이름은 구현자가 정한다.

### 3. `chat/infra/ArtifactStore.java`

경로를 만드는 규칙이 이 파일 하나에 있다.
- `Path ensureFolder(Long conversationId)`: `{root}/{대화 번호}` 를 만든다
- `String agentFolder(Long conversationId)`: `{agentRoot}/{대화 번호}`. 끝의 `/` 는 뗀다
- `List<FoundFile> changedHtmlSince(Long conversationId, Instant since)`: 하위 폴더까지 걸어 마지막 수정 시각이 `since` 이후인 `.html` 의 상대 경로와 크기. 상대 경로는 `/` 로 나눈다
- `Optional<Path> resolveInside(Long conversationId, String relativePath)`: 확장자 허용 목록을 먼저 보고, 폴더와 파일을 `toRealPath()` 로 푼 뒤 파일이 폴더 아래인지 본다. 없거나 밖이면 빈 값
- `List<Removed> deleteOlderThan(Instant cutoff)`: 뿌리 아래를 걸어 오래된 파일을 지우고 지운 것의 대화 번호와 상대 경로를 돌려준다. 한 파일이 실패해도 계속한다

허용 확장자와 Content-Type 표는 `docs/code-architecture.md` 「결과물 파일」 의 「경로」 표를 따른다. 대소문자는 가리지 않는다.

### 4. `chat/application/ArtifactService.java`

- `String agentPreamble(Long conversationId)`: `docs/code-architecture.md` 「에이전트에게 알리는 법」 의 세 줄 단락을 돌려준다. 끝에 빈 줄 하나
- `void recordTurn(Long conversationId, Long messageId, Instant turnStartedAt)`: `messageId` 가 null 이면 아무것도 하지 않는다. 찾은 파일마다 행을 만들고, 같은 `(message_id, path)` 가 이미 있으면 건너뛴다. 예외를 밖으로 던지지 않고 경고 로그를 남긴다
- `ArtifactContent open(CurrentUser user, Long conversationId, String relativePath)`: 주인 확인은 컨트롤러가 이미 했다. `resolveInside` 가 빈 값이면, 지워졌다고 적힌 행이 있으면 `ARTIFACT_GONE`, 아니면 `ARTIFACT_NOT_FOUND`
- `Map<Long, List<ChatArtifact>> byMessage(Collection<Long> messageIds)`: 메시지 목록을 한 번에 읽는다

`ErrorCode` 에 `ARTIFACT_NOT_FOUND(HttpStatus.NOT_FOUND)`, `ARTIFACT_GONE(HttpStatus.GONE)` 를 더한다.

### 5. 실행 입력과 turn 끝

- `ChatService` 의 두 `agentInput` 자리에서 turn 을 시작할 때 `artifactStore.ensureFolder` 를 부르고, 입력 맨 앞에 `artifacts.agentPreamble(conversation.id())` 를 붙인다. 사진 단락은 그 뒤다
- 같은 자리에서 turn 의 시작 시각 `Instant` 를 잡는다. 폴더를 만들기 전이다
- `ChatEvent.done/stopped` 를 보내는 세 곳 바로 앞에서 `artifacts.recordTurn(conversationId, turn.messageId(), 시작 시각)` 을 부른다
- 다시 생성(`regenerate`)도 같은 규칙이다. 세 곳 가운데 하나가 그 경로다

### 6. 경로와 DTO

- `ChatController` 나 새 컨트롤러에 `GET /api/v1/chat/conversations/{conversationId}/files/**` 를 둔다. `{conversationId}` 는 공개 식별자이고 같은 컨트롤러의 다른 대화 경로처럼 `ConversationAccess.requireOwnId` 로 번호를 얻는다. `/files/` 뒤를 상대 경로로 꺼낸다
- 응답 머리글은 `docs/code-architecture.md` 「결과물 파일」 의 머리글 표 그대로다. 본문은 `AttachmentController.GET` 처럼 스트림으로 준다
- `ChatDtos.MessageView` 에 `List<ArtifactView> artifacts` 를 `attachments` 다음에 더한다. `ArtifactView(String path, long byteSize, boolean deleted)`. 사용자 메시지와 결과물이 없는 답은 빈 목록
- `messages` 는 답마다 한 번씩 읽지 않고 `byMessage` 로 한 번에 읽는다

### 7. `chat/application/ArtifactCleaner.java`

`AttachmentCleaner` 와 같은 모양이다. 같은 `assistant.attachment.cleanup-cron` 으로 돈다.
`cleanExpired(Instant now)` 가 `deleteOlderThan(now - retentionDays)` 을 부르고 지운 HTML 의 행에 `deleted_at` 을 적는다. 지운 건수를 돌려준다.

### 8. 테스트 도구

- `test/e2e/fake-hermes.ts`: 입력을 견주기 전에 맨 앞의 `[결과물 폴더]` 단락을 떼는 함수를 두고, 지금 `run.input` 을 견주는 모든 자리가 뗀 글을 견주게 한다. 사진 단락은 지금처럼 둔다
- 같은 파일: 뗀 글이 `결과물 파일 검사` 이면 단락의 둘째 줄 폴더에 `초안/index.html` 과 `초안/photo.png` 를 쓰고 답한다. HTML 은 `<img src="photo.png">` 로 사진을 부르고 `<script>` 한 줄을 품는다. PNG 는 1픽셀짜리 바이트 상수로 충분하다
- `test/e2e/run.ts`, `test/browser/fixtures.ts`: 실행마다 임시 디렉터리를 하나 더 만들어 `ASSISTANT_ARTIFACT_ROOT` 와 `ASSISTANT_ARTIFACT_AGENT_ROOT` 에 **같은 경로**를 준다. 대역이 같은 기계에서 그 폴더에 쓰기 때문이다
- `backend/src/test/java` 에서 Hermes 로 간 입력을 통째로 견주는 검사는 새 단락을 반영한다. `grep -rln "input()" backend/src/test/java` 로 찾는다

### 9. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/chat/` 에 `ArtifactTest`:

| 경우 | 기대 |
| --- | --- |
| turn 을 붙잡아 둔 사이 대화 폴더에 `a/index.html` 을 쓰고 끝낸다 | 답의 `artifacts` 에 `a/index.html` 하나 |
| turn 시작 전에 이미 있던 HTML | 묶이지 않는다 |
| 같은 파일을 다음 turn 이 다시 고친다 | 두 답에 각각 한 행 |
| Hermes 로 간 입력 | 맨 앞이 `[결과물 폴더]` 와 `/agent-side/artifacts/{대화 번호}` |
| `GET .../files/a/index.html` | 200, `text/html`, 머리글 표의 CSP 와 `nosniff` |
| `GET .../files/a/photo.png` (행 없음, 파일 있음) | 200 |
| `GET .../files/a/x.svg`, `.../files/../other/a.html` | 404 `ARTIFACT_NOT_FOUND` |
| 폴더 밖을 가리키는 심볼릭 링크 | 404 `ARTIFACT_NOT_FOUND` |
| 남의 대화 | `CONVERSATION_NOT_FOUND` |
| `cleanExpired` 뒤 그 HTML | 행에 `deleted_at`, 파일 요청은 410 `ARTIFACT_GONE`, `artifacts[].deleted` 가 true |

`V24` 는 기존 `*MigrationTest` 가 H2 에서 함께 돌린다. 새 표를 확인하는 줄을 그 검사에 더하거나 `ArtifactMigrationTest` 를 둔다.

`test/e2e/scenarios/` 에 `artifact.ts` 시나리오를 더하고 `run.ts` 의 목록에 넣는다. `결과물 파일 검사` 를 보내고, 답의 `artifacts` 와 파일 요청의 머리글을 본다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
node test/e2e/run.ts
scripts/check-public-safe.sh
```

모두 통과해야 한다. `gradlew` 의 위치는 `backend/AGENTS.md` 를 본다.
운영 MySQL 에서 `V24` 와 스키마 검증이 통과하는지는 docker 가 있으면 `mysql:8.4` 컨테이너로 확인한다. 없으면 phase 보고에 적는다.

끝나면 `tasks/plan027-html-artifact-preview/index.json` 의 이 phase 를 `completed` 로, `current_phase` 를 2 로 바꾼다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/application/ArtifactProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ArtifactService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ArtifactCleaner.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ArtifactStore.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/ChatArtifact.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ChatArtifactRepository.java` | 신규 |
| `backend/src/main/resources/db/migration/V24__chat_artifact.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` 또는 새 컨트롤러 | 수정 또는 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/resources/application.yml`, `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ArtifactTest.java` | 신규 |
| `test/e2e/fake-hermes.ts`, `test/e2e/run.ts`, `test/browser/fixtures.ts` | 수정 |
| `test/e2e/scenarios/artifact.ts` | 신규 |
