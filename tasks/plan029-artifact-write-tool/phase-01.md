# Phase 01. 대화 주인 확인과 본문 저장

**Execution profile**: deep

## 목표

토큰 사용자 소유의 대화 폴더에 HTML 과 CSS 를 쓰되, 경로와 링크로 폴더 밖에 쓰지 못하게 한다.

**범위 외**: URL 다운로드, MCP 도구 등록, 실행 입력 안내, 에이전트별 도구 설정 화면과 배포.

## 컨텍스트

**근거 문서**: [ADR-028](../../docs/adr/ADR-028-결과물은-사용자의-대화-폴더에-mcp-도구로-쓴다.md), [연동 계약](../../docs/hermes-integration.md#결과물-쓰기-도구), [패키지 배치](../../docs/code-architecture.md#mcp-로-쓰는-자리).

기존 코드는 `origin/main` 에서 읽었다. 아래 이름은 현재 코드이고 신규 타입은 작업 항목에서 따로 표시한다.

| 기존 코드 | 확인한 계약 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ArtifactStore.java` | `{root}/{대화 번호}` 폴더, `CONTENT_TYPES`, `resolveInside(Long, String)`, `ensureFolder(Long)`, `agentFolder(Long)` |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationAccess.java` | `requireOwn(CurrentUser, UUID)` 가 사용자 소유이며 지우지 않은 대화만 반환 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/Conversation.java` | 내부 `id`, UUID `publicId`, `userId`, `deletedAt` |
| `backend/src/main/java/com/bifos/assistant/chat/application/ArtifactService.java` | `recordTurn(Long, Long, Instant)` 가 turn 뒤 바뀐 HTML 을 답에 연결 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/ChatArtifact.java` | `path` 는 500자. HTML 만 기존 흐름으로 행을 만든다 |

`resolveInside` 는 대상에 `toRealPath()` 를 써서 없는 파일에는 쓸 수 없다.
`ensureFolder` 는 폴더 생성 실패를 로그로만 남기므로 쓰기가 성공했다고 판단할 근거로 쓰지 않는다.

## 의도 메모

- 경로 판정과 저장은 `ArtifactStore` 에 모은다. 읽기용 판정과 보관 계약을 유지한다
- MCP 호출에 실행 식별자가 없으므로 현재 turn 을 추정하지 않는다. 같은 사용자 소유의 다른 대화도 받는다
- 신규 테이블과 마이그레이션을 만들지 않는다. 도구 성공 시점에는 `chat_artifact` 행을 만들지 않는다

## 작업 항목

### 1. `ArtifactWriteRequest`, `ArtifactWriteResult`, `ArtifactWriteService` 를 추가한다

`backend/src/main/java/com/bifos/assistant/chat/application/` 에 타입마다 파일 하나를 둔다.
`ArtifactWriteRequest(UUID conversationId, String path, String content, String sourceUrl)` 와
`ArtifactWriteResult(String path, long byteSize)` 를 record 로 둔다.
외부 인자 `conversation_id` 는 이 record 의 UUID 로 변환하며 내부 대화 번호를 받지 않는다.
`ArtifactWriteService.write(CurrentUser user, ArtifactWriteRequest request)` 가 결과를 반환한다.

첫 동작으로 `ConversationAccess.requireOwn(user, request.conversationId())` 를 호출한다.
없는 대화, 지운 대화, 남의 대화는 기존 `CONVERSATION_NOT_FOUND` 로 같게 처리한다.
주인 확인 전에는 디스크와 DNS, HTTP 를 건드리지 않는다.
본문 방식은 `content` 가 있고 `sourceUrl` 이 없는 요청만 받는다.
URL 방식은 다음 구현 단계까지 저장하지 않으며 이 단계에서 MCP 에 공개하지 않는다.

본문 확장자는 대소문자를 가리지 않고 `html`, `css` 만 받는다.
UTF-8 로 인코딩한 바이트 수가 `5 * 1024 * 1024` 이하일 때 저장한다.
빈 본문은 0바이트 파일로 허용하고 상한보다 1바이트 큰 본문은 저장 전에 거절한다.
문자 수로 상한을 판정하지 않는다.
성공 결과에는 상대 경로와 바이트 수만 담는다.

### 2. `ArtifactStore` 에 쓰기 경로 판정과 교체를 더한다

`resolveForWrite(Long conversationId, String relativePath)` 와
`write(Long conversationId, String relativePath, byte[] content)` 를 추가한다.
쓰기 결과로 저장된 바이트 수를 반환하고 실패는 예외로 전달한다.
인자나 경로 거절은 `VALIDATION_FAILED` 로, 파일 I/O 실패는 내부 경로를 감춘 저장 실패로 구분한다.
읽기용 `resolveInside` 와 `CONTENT_TYPES` 의 기존 역할을 바꾸지 않는다.

1. 빈 값, 500자를 넘는 경로, 절대 경로, 역슬래시, NUL, 빈 조각, `.` 과 `..` 조각을 거절한다.
   허용 확장자는 `CONTENT_TYPES` 를 재사용하되 본문과 URL 방식의 구분은 서비스가 한다.
2. `ensureFolder` 를 부르고 실제 디렉터리인지 `NOFOLLOW_LINKS` 로 확인한다.
   대화 폴더 자체가 심볼릭 링크거나 일반 파일이면 거절한다.
3. 뿌리의 실제 경로와 대화 폴더를 기준으로 이미 있는 부모를 먼저 검사한다.
   폴더 밖을 가리키는 부모 링크가 있으면 부모 생성도 하지 않는다.
4. 없는 부모는 폴더 안에서 순서대로 만들고 실제 경로를 다시 풀어 대화 폴더 안인지 확인한다.
   부모 경로가 폴더가 아니거나 대상이 심볼릭 링크면 거절한다. 안쪽을 가리키는 대상 링크도 받지 않는다.
5. 검증한 부모 안에 임시 파일을 만들고 본문을 완성한다.
   최종 교체 직전에도 부모와 대상을 검사하고, 원자적으로 기존 파일을 교체한다.
   링크 교체와 판정 사이의 변경으로 밖에 쓰지 않도록 디렉터리 핸들과 `NOFOLLOW_LINKS` 를 사용한다.
   안전한 원자적 교체를 지원하지 못하면 일반 덮어쓰기로 대체하지 않고 실패시킨다.
6. 실패하면 임시 파일을 지우고 기존 대상 파일을 보존한다.
   성공한 대상은 현재 수정 시각을 갖게 하고, 동시 쓰기는 마지막으로 성공한 교체가 남게 한다.

디렉터리 핸들로 파일을 만드는 API 는
[JDK 21 SecureDirectoryStream](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/SecureDirectoryStream.html) 을 참고한다.
플랫폼의 지원 여부를 확인하고, 지원하지 않는 경로에서는 쓰기를 안전하게 거절한다.

### 3. 경로와 본문 저장 테스트를 추가한다

`backend/src/test/java/com/bifos/assistant/chat/ArtifactWriteServiceTest.java` 와
`ArtifactStoreWriteTest.java` 를 신규로 만든다. JUnit 의 임시 폴더를 쓰며 테스트 종료 시 정리한다.
기존 `ArtifactTest` 의 읽기, CSP, 보관과 답 연결 검사는 그대로 통과해야 한다.

| 입력 또는 상황 | 관측 결과 |
| --- | --- |
| 본인 대화의 `test/index.html`, `test/style.css` | 폴더가 없으면 만들고 UTF-8 바이트와 결과 크기가 일치 |
| 빈 문자열, 정확히 5MB, 한글로 5MB 초과 | 앞 둘은 저장, 마지막은 저장 전 거절 |
| 같은 경로 재작성 | 기존 파일 교체, 최신 본문과 크기 반환 |
| 같은 사용자의 다른 대화 UUID | 그 대화 폴더에 저장, 현재 대화 폴더에는 쓰지 않음 |
| 없는 대화, 남의 대화, 지운 대화 | 같은 오류, `ArtifactStore` 호출 없음 |
| 절대 경로, 빈 값, `..`, 역슬래시, NUL, SVG, 긴 경로 | 거절, 밖의 파일과 폴더에 변화 없음 |
| 대화 폴더 링크, 밖으로 향하는 부모 링크, 최종 파일 링크 | 거절, 밖에 부모도 만들지 않음 |
| 검사 뒤 링크 교체, 저장 실패, 원자적 교체 미지원 | 밖에 쓰지 않고 기존 파일 보존, 임시 파일 정리 |
| 쓰기만 성공하고 turn 은 끝나지 않음 | `chat_artifact` 행이 생기지 않음 |

## 검증

저장소의 backend 검증 명령을 실행한다. 종료 코드가 모두 0이어야 한다.

```bash
# cwd: backend/
./gradlew test --tests '*ArtifactWriteServiceTest' --tests '*ArtifactStoreWriteTest' --tests '*ArtifactTest'
./gradlew test
```

```bash
# cwd: 저장소 root
scripts/check-public-safe.sh
```

## Critical Files

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/chat/application/ArtifactWriteRequest.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ArtifactWriteResult.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ArtifactWriteService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ArtifactStore.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ArtifactWriteServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ArtifactStoreWriteTest.java` | 신규 |
