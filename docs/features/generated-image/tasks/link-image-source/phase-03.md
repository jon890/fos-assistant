# Phase 03. 쓴 링크를 그림 행에 남기고 「그림 지시문 보기」 에 보인다

**Execution profile**: standard

## 목표

그림 올리기가 `source_links` 를 받아 그림 행에 암호화해 두고, 메시지 응답과 화면의 「그림 지시문 보기」 가 참고한 링크를 보인다.

**범위 외**: fos-ctx 가 `source_links` 를 보내는 일(phase 04).

## 컨텍스트

**요구사항**: REQ22

**근거 문서**: `docs/features/generated-image/design.md` 의 「PR5」 의 「쓴 링크를 그림에 남기기」, 「화면」

- 올리기 서명 확인은 `backend/src/main/java/com/bifos/assistant/mcp/application/dto/GeneratedImageRequest.java` 의 `verify` 다. 서명 메시지는 `VERSION_LINE` 부터 지시문 SHA-256 까지 일곱 줄이다.
- 저장은 `mcp/application/GeneratedImageUploadService.java` 가 `chat/artifact/application/GeneratedImageService.java` 의 `save` 를 부른다. 지시문 암호화는 `GeneratedImageService.seal`(AAD `chat_artifact_prompt:user:<id>:call:<callKey>`)과 `promptOf` 다. `OwnedTextCipher.seal` 은 key 를 고를 수 없다.
- 행은 `chat/artifact/domain/ChatArtifact.java` 의 `generation_prompt`, `generation_prompt_key_id` 옆에 칸을 둔다.
- 메시지 응답은 `chat/turn/presentation/ChatDtos.java` 의 `ArtifactView.from(ChatArtifact, GeneratedImageDetail)` 이고 `chat/artifact/application/dto/GeneratedImageDetail.java` 가 지시문과 보관본 번호를 나른다.
- 화면은 `web/src/components/chat/artifact/generated-images.tsx` 의 `PromptDetails` 와 `web/src/components/chat/message-types.ts` 의 `MessageArtifact` 다.
- 마이그레이션 버전은 예약해 두었다: `backend/src/main/resources/db/migration/V20261105074423__chat_artifact_source_links.sql`. 다른 이름으로 만들지 않는다. main 을 합친 뒤에는 `orch queue reserve --module backend --name chat_artifact_source_links` 로 다시 확인한다.

## 의도 메모

- 서명은 `source_links` 가 있을 때만 여덟째 줄(주소를 `\n` 으로 이은 글의 SHA-256)을 더한다. 없으면 지금 일곱 줄이라 링크 없는 올리기는 옛 fos-ctx 와 그대로 통한다.
- 링크는 지시문과 따로 봉하고 key 번호를 자기 칸에 둔다(설계 DECISION18). AAD 는 지시문 AAD 에 `:links` 를 붙인다.
- 보관본에는 링크를 넣지 않는다.

## 작업 항목

### 1. 마이그레이션과 엔티티

`V20261105074423__chat_artifact_source_links.sql`: `chat_artifact` 에 `generation_source_links LONGTEXT NULL`, `generation_source_links_key_id BIGINT NULL` 을 더한다. 기존 마이그레이션의 칸 추가 형식(`V20261105074419__chat_artifact_file_space.sql`)을 따른다. `ChatArtifact` 에 두 칸과 그림 행 생성 정적 메서드의 인자를 더한다. `backend/docs/data-schema.md` 의 「chat_artifact」 표에 두 칸을 적는다.

### 2. `GeneratedImageRequest`

`List<String> sourceLinks` 를 더한다. `meta.source_links` 는 없거나 문자열 배열이고, 항목은 `https://` 로 시작하는 2,048자 이하, 16개 이하다. 어기면 `MCP_CALL_CONTEXT_INVALID`. 비지 않으면 서명 메시지 끝에 여덟째 줄을 더해 확인한다.

### 3. `GeneratedImageService` 와 `GeneratedImageUploadService`

`save` 가 `sourceLinks` 를 받아 JSON 배열로 바꿔 봉해 두 칸에 쓴다. 비면 두 칸 모두 null. `seal` 이 실패하면 지시문처럼 평문으로 내려 저장하지 않는다(`GeneratedImageService.seal` 과 같은 처리). 지금 `save` 를 직접 부르는 테스트(`ArtifactTest`, `ImageSourceEndpointTest`)는 새 인자에 빈 목록을 넘기도록 고친다. 같은 호출의 되풀이 올리기는 지금처럼 처음 행을 돌려준다. `sourceLinksOf(ChatArtifact, Long ownerUserId)` 를 더해 풀고, 풀지 못하면 빈 목록이다. `GeneratedImageDetail` 에 `List<String> sourceLinks` 를 더하고 지금 지시문을 채우는 곳에서 함께 채운다.

### 4. 메시지 응답

`ArtifactView` 에 `sourceLinks`(문자열 배열, 그림이 아니거나 없으면 빈 배열)를 더한다.

### 5. 화면

`MessageArtifact` 에 `sourceLinks?: string[]` 를 더한다. `PromptDetails` 가 `prompt` 와 `sourceLinks` 를 받아, 지시문 아래에 「참고한 링크」 와 목록을 그린다. 주소마다 `<a href target="_blank" rel="noopener noreferrer">` 이고 글자는 주소 그대로다. `https://` 로 시작하지 않는 값은 글자로만 그린다. 지시문이 비어도 링크가 있으면 접힌 칸을 보인다. 크게 보기 화면의 `PromptDetails` 도 같다. 목록에 `data-testid="generated-image-source-links"` 를 둔다.

### 6. 테스트

- `backend/src/test/java/com/bifos/assistant/mcp/GeneratedImageEndpointTest.java` 수정: `source_links` 가 든 올리기가 여덟째 줄 서명으로 받아지고, 일곱 줄 서명이면 거절되고, `source_links` 없는 올리기는 지금처럼 받아진다. 암호화를 켠 설정에서 칸이 암호문이고 key 번호가 차는지, `http://` 항목과 17개 항목이 거절되는지 본다. 서명 값은 phase 04 의 Python 테스트와 같은 고정 입력과 기대값을 쓴다(테스트 안에 그 값을 적고 주석으로 짝을 밝힌다).
- `backend/src/test/java/com/bifos/assistant/chat/GeneratedImageTurnTest.java` 수정: 링크가 든 그림의 메시지 응답 `sourceLinks` 가 올린 순서대로 나오고, 링크 없는 그림은 빈 배열이다.

### 7. 화면 브라우저 테스트

이 저장소에는 화면 컴포넌트 단위 테스트 틀이 없어 설계의 「화면 단위 테스트」 를 브라우저 테스트로 대신한다. `web/test/browser/generated-image.spec.ts` 에 응답에 `sourceLinks` 가 든 그림의 「그림 지시문 보기」 를 펼치면 `generated-image-source-links` 안에 링크가 보이고 `target="_blank"`, `rel="noopener noreferrer"` 인지, 링크 없는 그림에는 목록이 없는지 넓은 화면과 휴대폰 폭에서 본다. 느린 테스트라 실행은 phase 04 의 검증에서 한 번 한다.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.mcp.GeneratedImageEndpointTest' --tests 'com.bifos.assistant.chat.GeneratedImageTurnTest' --tests '*JpaAuditingMigrationTest'
cd backend && ./gradlew archTest checkstyleMain checkstyleTest spotlessCheck
pnpm --dir web typecheck
pnpm --dir web lint
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V20261105074423__chat_artifact_source_links.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/domain/ChatArtifact.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/dto/GeneratedImageRequest.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/GeneratedImageUploadService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/application/GeneratedImageService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/application/dto/GeneratedImageDetail.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/presentation/ChatDtos.java` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/GeneratedImageEndpointTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/GeneratedImageTurnTest.java` | 수정 |
| `web/src/components/chat/message-types.ts` | 수정 |
| `web/src/components/chat/artifact/generated-images.tsx` | 수정 |
| `web/test/browser/generated-image.spec.ts` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ArtifactTest.java` | 수정 (save 인자) |
| `backend/src/test/java/com/bifos/assistant/mcp/ImageSourceEndpointTest.java` | 수정 (save 인자) |
