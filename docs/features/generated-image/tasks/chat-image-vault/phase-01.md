# Phase 01. Control Plane 이 올라온 그림을 대화 결과물로 저장하고 답에 묶는다

**Execution profile**: standard

## 목표

fos-ctx 가 올린 그림을 요청자의 대화 결과물 폴더에 저장하고 `chat_artifact` 행으로 남겨, turn 이 끝날 때 답에 묶고 메시지 응답으로 내보낸다.

**범위 외**: fos-ctx 의 hook(Phase 02), 화면(Phase 03), 스펙의 REQ11 이후.

## 컨텍스트

- 올리기 경로의 계약, 저장 규칙, 묶기 규칙, 응답 칸은 설계의 「인터페이스와 데이터」 와 「실패와 일관성」 이 갖는다. 이 phase 는 그 절을 그대로 구현한다
- 호출자 확인은 `backend/src/main/java/com/bifos/assistant/mcp/application/AttachmentInspectService.java` 와 `McpCallerResolver` 의 패턴을 따른다. 서명 검증은 `ToolGuardRequest.verify` 처럼 요청 record 가 맡는다
- 404 를 내지 않는 규칙은 `mcp/presentation/ToolGuardController.java` 와 같다
- 파일 쓰기는 `chat/artifact/infra/ArtifactStore.write` 를 쓴다. 지시문 암호화는 `crypto/domain/TextCipher` 를 쓰고 `followup/infra/FollowUpSuggestionBodies.java` 의 모양을 따른다
- 마이그레이션 파일 이름은 `V20261105074416__chat_artifact_generated_image.sql` 이다. 병렬 작업과 번호가 겹치지 않게 작업 대기열 도구로 예약한 값이라 다른 이름을 고르지 않는다

**요구사항**: REQ1, REQ2, REQ4, REQ5, REQ6, REQ7, REQ9, REQ10

**근거 문서**: `docs/features/generated-image/spec.md`, `docs/features/generated-image/design.md`, `docs/adr/ADR-20261119-generated-image.md`

## 의도 메모

- 그림을 JSON 의 base64 로 받지 않는다. multipart 의 `image` 조각으로 받는다
- 묶기는 turn 시작 시각으로 자르지 않는다. 맡긴 실행은 부모 turn 뒤에 돈다
- 제목의 겹침은 파일이 아니라 그 대화의 그림 행으로 본다

## 작업 항목

### 1. 마이그레이션과 엔티티

`chat_artifact` 에 `generation_prompt`(LONGTEXT, NULL), `generation_prompt_key_id`(BIGINT, NULL), `generation_call_key`(CHAR(64), NULL, 유일) 를 더한다. `ChatArtifact` 에 칸과 `generatedImage(...)` 정적 생성자, 답에 묶는 메서드를 더한다. `backend/docs/data-schema.md` 의 `chat_artifact` 절을 고친다. purge 분류(`tables.tsv`)는 표 단위라 고치지 않는다.

### 2. 저장 서비스 `GeneratedImageService`(`chat.artifact.application`)

형식(머리 바이트)과 상한 검사, 제목 정리와 번호 붙이기, 파일 쓰기, 지시문 암호화, 행 만들기, 되풀이 요청 처리를 맡는다. 상한은 `ArtifactProperties` 의 새 값 `generated-image-max-bytes` 다. 대화마다 한 번에 하나만 돌게 잠근다.

### 3. 올리기 경로(`mcp`)

`GeneratedImageController` 와 요청 record, `GeneratedImageUploadService` 를 만든다. `AgentTokenAuthenticationFilter`, `ControlPlaneJwtFilter`, `SecurityConfig` 의 경로 목록에 새 경로를 더한다. origin 실행은 `SessionOwnerResolver.resolve` 로 찾는다. 끝난 origin 은 받고 중지한 origin 과 중지한 트리는 그 메서드가 거절한다. 대화 없는 실행은 거절한다. `*_NOT_FOUND` 를 포함한 저장 실패는 500 으로 답한다.

### 4. 답에 묶기와 응답

`ArtifactService.recordTurn` 이 묶이지 않은 그림 행을 답에 묶는다. `ChatDtos.ArtifactView` 에 `kind`, `prompt`, `vaultItemId` 를 더하고 `ChatController` 가 채운다. 보관본은 같은 사용자, 대화, 경로의 살아 있는 보관 사본 가운데 가장 새것이다.

### 5. 실행 입력의 단락

`ArtifactService.agentPreamble` 에 설계의 「실행 입력의 단락」 줄을 더하고 `source_url` 줄을 좁힌다. 단락의 첫 줄 `[결과물 폴더]` 는 바꾸지 않는다. 가짜 Hermes 가 그 줄로 단락을 찾는다. 단락 글을 대조하는 `backend/src/test/java/com/bifos/assistant/chat/ArtifactTest.java` 를 함께 고친다.

### 6. 테스트와 문서

`backend/src/test/java/com/bifos/assistant/mcp/GeneratedImageEndpointTest.java` 가 서명한 올리기로 파일과 행, 제목 번호, 되풀이 요청, 형식과 크기 거절, 중지한 실행의 거절과 끝난 origin 의 수락, 다른 사용자의 파일 접근 거절, 404 를 내지 않는 것을 본다. `backend/src/test/java/com/bifos/assistant/chat/GeneratedImageTurnTest.java` 가 답에 묶기(맡긴 실행 뒤의 다음 답 포함), 응답의 세 칸, 자동 보관(중지한 답은 보관하지 않음), 중지한 답과 답 없는 turn 뒤의 묶기, `ArtifactCleaner.cleanExpired` 를 돌린 뒤 그림 행의 `deleted_at` 과 남은 지시문, 지워진 그림의 보관본 번호를 본다. 보관 기간 정리 코드는 고치지 않는다. 행 쪽 대조가 그림 행에도 지운 표시를 적는다. `docs/features/attachment.md` 의 「에이전트에게 알리는 법」 과 「답에 묶는 법」 을 고친다.

## 검증

```bash
cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test --tests '*GeneratedImage*' --tests '*ArtifactTest' --tests '*ArtifactAutoVaultTest' --tests '*PurgeClassificationTest' --tests '*ChatServiceTest'
cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew qualityCheck
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V20261105074416__chat_artifact_generated_image.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/domain/ChatArtifact.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/infra/ChatArtifactRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/config/ArtifactProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/application/GeneratedImageService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/application/ArtifactService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/application/ChatArtifactWriter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/GeneratedImageController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/GeneratedImageUploadService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/dto/GeneratedImageRequest.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/AgentTokenAuthenticationFilter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/config/SecurityConfig.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/presentation/ChatController.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/GeneratedImageEndpointTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/GeneratedImageTurnTest.java` | 신규 |
| `backend/docs/data-schema.md` | 수정 |
| `docs/features/attachment.md` | 수정 |
