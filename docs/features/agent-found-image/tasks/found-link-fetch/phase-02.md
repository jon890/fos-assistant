# Phase 02. Control Plane 이 찾은 주소를 남기고 받는다

**Execution profile**: standard

## 목표

fos-ctx 가 알린 주소 후보를 대화마다 해시로 남기는 경로를 만들고, 그림 바탕 받기 경로가 사용자 메시지에 없는 `link:` 를 이 해시로 한 번 더 확인하게 한다. 그림 규칙의 주소 줄을 고친다.

**범위 외**: fos-ctx 의 뽑기와 알리기(phase 3). 「비서가 찾음」 표시와 응답 머리 `X-Image-Source-Found`(뒤 PR).

## 컨텍스트

- 받기 경로: `backend/src/main/java/com/bifos/assistant/mcp/application/ImageSourceService.java` 의 `link:` 분기가 `ConversationLinks.findSentLink` 로 사용자 메시지를 보고 `LinkImageService.read` 로 받는다
- 본보기 경로: `backend/src/main/java/com/bifos/assistant/mcp/presentation/ImageSourceController.java`. profile 토큰 인증, 본문의 `_fos_ctx` 를 `McpCallerResolver.resolve(principal, 도구 이름, ctx)` 로 확인한다
- 전용 질의로만 읽고 쓰는 칸의 본보기: `backend/src/main/java/com/bifos/assistant/chat/conversation/infra/ConversationRepository.java` 의 `findPendingMessages`, `writePendingMessages`
- 엔티티가 매핑하지 않는 칸을 테스트 스키마에 더하는 본보기: `backend/src/test/resources/memory-embedding-columns.sql` 과 `application-test.yml` 의 `hibernate.hbm2ddl.import_files`
- 대화를 지우는 자리: `backend/src/main/java/com/bifos/assistant/chat/turn/application/ChatConversationManagement.java` 가 `pendingMessages.clear(conversationId)` 를 부르는 곳

**요구사항**: REQ2, REQ3, REQ8

**근거 문서**: `docs/features/agent-found-image/spec.md`, `docs/features/agent-found-image/design.md`, `docs/adr/ADR-20261121-found-link-image-source.md`

## 의도 메모

- 주소 원문을 저장하지 않는다. 해시만 둔다
- 칸을 대화 엔티티에 매핑하지 않는다. 대화를 읽을 때마다 함께 읽히기 때문이다
- 더하기는 `UPDATE` 한 문장이다. 읽고 고쳐 쓰지 않는다
- 마이그레이션 파일 이름은 예약한 `V20261105074439__conversation_found_link_hashes.sql` 이다. 다른 번호를 고르지 않는다. DDL 만 담는다

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V20261105074439__conversation_found_link_hashes.sql`

`ALTER TABLE conversation ADD COLUMN found_link_hashes MEDIUMTEXT NULL;` 한 문장이다. 주석은 한국어로 칸의 뜻과 ADR-20261121 / found-link-image-source 를 적는다.

### 2. 테스트 스키마: `backend/src/test/resources/conversation-found-link-hashes-column.sql`, `backend/src/test/resources/application-test.yml`

새 SQL 파일에 같은 칸을 더하는 `ALTER TABLE conversation ADD COLUMN found_link_hashes CLOB NULL;` 를 두고(H2 가 받는 형이면 `MEDIUMTEXT` 그대로 쓴다), `hibernate.hbm2ddl.import_files` 값에 쉼표로 이어 더한다. 주석도 함께 고친다.

### 3. `backend/src/main/java/com/bifos/assistant/chat/conversation/infra/ConversationRepository.java` 에 전용 질의 셋

- `findFoundLinkHashes(id)`: `select found_link_hashes from conversation where id = :id`
- `appendFoundLinkHashes(id, hashes, limit)`: `update conversation set found_link_hashes = right(concat(coalesce(found_link_hashes, ''), :hashes), :limit) where id = :id and deleted_at is null`. `@Modifying`
- `clearFoundLinkHashes(id)`: `update conversation set found_link_hashes = null where id = :id`. `@Modifying`

모두 `nativeQuery = true` 다. H2 와 MySQL 에서 같은 문장이 도는지 테스트로 본다. H2 가 CLOB 에 `right` 를 받지 못하면 `substring` 과 `char_length` 로 같은 뜻의 문장을 쓴다.

### 4. `backend/src/main/java/com/bifos/assistant/chat/conversation/application/LinkTarget.java` 에 해시와 변환

- `key()`: 비교 글(`IDN.toASCII` 한 소문자 호스트 + `rest`)을 돌려준다. 호스트를 punycode 로 바꾸지 못하면 소문자 호스트를 쓴다
- `hash()`: `key()` 의 UTF-8 SHA-256 앞 16바이트를 소문자 16진수 32자로
- 정적 `decodeEntities(String)`: `&amp;`, `&#38;`, `&#x26;`(대소문자 무시)을 `&` 로

### 5. `backend/src/main/java/com/bifos/assistant/chat/conversation/application/FoundLinks.java` 를 만든다

`@Service`. `ConversationRepository` 만 쓴다.

- `void record(Long conversationId, List<FoundPage> pages)`: 설계 「같은 주소로 보는 규칙」 대로 후보마다 꼴을 만들어 해시를 모은다. 후보는 앞뒤 공백을 떼고, 비었거나 2,048자를 넘거나 공백이 든 것은 버린다. 절대 주소(`http://`, `https://`)는 그대로, `//` 로 시작하면 `https:` 를 붙이고, 그 밖은 기준 주소가 http(s) 절대 주소일 때 `java.net.URI.resolve` 로 푼다(풀지 못하면 버린다). 적힌 꼴과 엔티티를 푼 꼴 둘 다 만든다. 푼 결과가 http(s) 가 아니면 버린다. 해시가 기준 주소의 해시와 같으면 버린다. 한 호출 안의 중복을 빼고 순서를 지켜 이어 붙여 `appendFoundLinkHashes` 를 한 번 부른다. 해시가 없으면 부르지 않는다
- `Optional<URI> find(Long conversationId, String link)`: 엔티티를 푼 글이 가리킨 글과 다르면 푼 글을 먼저, 그다음 가리킨 글 그대로를 본다. 저장된 글을 32자씩 잘라 비교한다. 맞으면 그 해시를 `appendFoundLinkHashes` 로 뒤에 다시 붙이고 `LinkTarget.fetchUri()` 를 돌려준다
- 상수 `MAX_HASHES = 20_000`, `HASH_CHARS = 32`, `MAX_LINK_CHARS = 2_048`
- `record FoundPage(String base, List<String> links)` 는 `backend/src/main/java/com/bifos/assistant/chat/conversation/application/dto/FoundPage.java` 에 둔다
- 트랜잭션: `record` 와 `find` 는 `@Transactional` 이다(`@Modifying` 질의를 부른다)

### 6. `backend/src/main/java/com/bifos/assistant/mcp/application/FoundLinkService.java` 와 `backend/src/main/java/com/bifos/assistant/mcp/presentation/FoundLinkController.java`

- 경로 `POST /internal/hermes/found-links`, 본문은 `JsonNode`. 응답은 204
- 서비스: 본문이 객체가 아니면 `MCP_CALL_CONTEXT_INVALID`. `callers.resolve(principal, "found_links", body.get("_fos_ctx"))` 로 origin 실행을 찾는다. origin 의 `conversationId` 가 null 이면 아무것도 남기지 않고 끝낸다
- `pages` 는 배열이고 항목마다 `base`(글이나 null)와 `links`(글의 배열)다. 모양이 틀리거나 모든 페이지의 링크 합이 2,000개를 넘으면 `VALIDATION_FAILED`. 글이 아닌 링크 항목도 `VALIDATION_FAILED` 다
- `FoundLinks.record(origin.conversationId(), pages)` 를 부른다
- 로그에 주소를 남기지 않는다. 남긴 해시 수만 debug 로 남긴다
- 컨트롤러 머리 설명에 계약의 자리(`hermes/plugins/fos-ctx/README.md` 의 「찾은 주소를 알린다」)를 적는다

### 7. 새 경로를 profile 토큰 경로 세 곳에 더한다

`backend/src/main/java/com/bifos/assistant/mcp/presentation/AgentTokenAuthenticationFilter.java` 의 `AGENT_TOKEN_PATHS`, `backend/src/main/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilter.java` 의 제외 경로 목록과 그 설명, `backend/src/main/java/com/bifos/assistant/shared/config/SecurityConfig.java` 의 허용 경로 목록에 `/internal/hermes/found-links` 를 더한다.

### 8. `backend/src/main/java/com/bifos/assistant/mcp/application/ImageSourceService.java` 의 `link:` 분기

`links.findSentLink(...)` 가 비면 `foundLinks.find(origin.conversationId(), ref.link())` 를 본다. 둘 다 비면 지금처럼 `IMAGE_SOURCE_UNKNOWN` 이다. 그 뒤(길이 상한, `linkImages.read`, 응답)는 같다. 클래스 머리 설명에 ADR-20261121 / found-link-image-source 를 더한다.

### 9. 대화를 지울 때 칸을 비운다

`backend/src/main/java/com/bifos/assistant/chat/turn/application/ChatConversationManagement.java` 에서 `pendingMessages.clear(conversationId)` 를 부르는 자리에 `foundLinks.clear(conversationId)` 를 더한다. `FoundLinks.clear` 는 `clearFoundLinkHashes` 를 부른다.

### 10. `backend/src/main/java/com/bifos/assistant/chat/artifact/application/ArtifactService.java` 의 `IMAGE_RULES`

「검색이나 페이지에서 찾은 주소는 쓰지 않는다.」 문장을 지우고 줄 하나를 더한다. 줄 수를 세는 머리 문장이 있으면 함께 고친다.

```text
- 웹 검색(web_search)과 페이지 읽기(web_extract) 결과에 나온 페이지 주소나 사진 주소의 사진을 바탕이나 참고로 쓸 때도 link:<결과에 나온 주소 그대로> 로 적는다. 주소를 고치거나 지어내지 않는다. 스스로 적어 넣어 읽은 주소는 쓰지 못한다. 결과에 / 나 // 로 시작하는 꼴로 나온 사진 주소는 그 페이지의 주소를 기준으로 완전한 https:// 주소로 적는다.
```

### 11. 문서

- `backend/docs/data-schema.md` 의 `conversation` 절에 `found_link_hashes` 를 적는다(뜻, 해시 32자 이어 붙임, 엔티티가 읽지 않음, 지울 때 비움). 「지운 대화」 설명에서 대기 메시지와 함께 비운다고 적는다
- `docs/features/agent-found-image.md` 의 `covers:` 에 `FoundLinks.java`, `FoundLinkService.java` 를 더하고 「아직 구현하지 않았다」 문단을 PR1 의 동작(찾은 주소를 `link:` 로 받는다, 이름만 말해도 찾기는 뒤 PR)으로 바꾼다
- `docs/features/generated-image/spec.md` 의 범위 밖 줄 「비서가 검색해 찾은 페이지의 사진을 참고 그림으로 쓰기」 와 REQ21, DECISION34 에 비서가 찾은 사진 스펙(`docs/features/agent-found-image/spec.md`)이 바꿨다는 가리킴을 더한다. 요구사항 ID 와 확인 줄은 그대로 둔다
- `docs/features/generated-image/design.md` 의 「대화에 있는 링크인가」 끝에 찾은 주소 판정은 `docs/features/agent-found-image/design.md` 가 갖는다는 한 줄을 더하고, 「실행 입력의 그림 규칙」 의 「검색이나 페이지에서 찾은 주소는 쓰지 않는다」 를 고친다

### 12. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/chat/conversation/application/FoundLinksTest.java`(신규, 저장소를 쓰는 통합 테스트): 절대 주소, `&amp;` 꼴과 푼 꼴, `//` 꼴, 상대 주소(기준 있음과 없음)를 알린 뒤 `find` 가 통과하는 것. 글자를 하나 바꾼 주소와 쿼리를 더한 주소는 빈 값. 기준 주소와 같은 후보는 남지 않음. 두 해시의 경계에 걸친 32자를 해시로 갖는 가짜 값이 통과하지 않음. 20,000개를 넘기면 가장 오래된 주소가 빈 값이고 최근 주소는 통과. `find` 로 통과한 주소는 그 뒤 20,000개 가까이 더 알려도 남음. `clear` 뒤와 지운 대화에는 남지 않음. `&amp;` 꼴로 가리키면 돌려주는 주소에 `&amp;` 가 없음
- `backend/src/test/java/com/bifos/assistant/mcp/FoundLinkEndpointTest.java`(신규): `ImageSourceEndpointTest` 의 준비(서명 `McpCallSigner`, 가짜 받기 객체)를 본보기로 쓴다. 알린 주소를 `link:` 로 가리키면 200 이고 가짜 받기 객체가 그 주소를 받는다. 알리지 않은 주소, 다른 대화의 실행이 알린 주소, 다른 사용자의 실행이 알린 주소는 422 `IMAGE_SOURCE_UNKNOWN` 이고 가짜 받기 객체가 불리지 않는다. 서명이 틀린 알리기와 토큰 없는 알리기는 거절되고 남지 않는다. 링크 2,001개는 400 이다. 받기 객체가 `BLOCKED`, `NO_IMAGE` 로 거절하면 찾은 주소도 `IMAGE_SOURCE_LINK_BLOCKED`, `IMAGE_SOURCE_LINK_NO_IMAGE` 다(REQ3). 찾은 주소를 저장되는 꼴(`https://`, 퍼센트 인코딩한 한글 경로)로 다시 가리켜도 통과한다(REQ8). 비서가 맡긴 에이전트의 실행(위임 자식)이 알린 주소가 부모 대화에서 통과하는지 확인하고, 그 실행의 origin 에 대화가 없으면 204 로 끝나고 남지 않는 것을 확인한다
- `backend/src/test/java/com/bifos/assistant/chat/ArtifactTest.java`: `IMAGE_RULES` 의 글을 보는 단언을 새 줄에 맞춘다. 「찾은 주소는 쓰지 않는다」 가 없고 `web_search` 가 든 줄이 있다
- 대화를 지운 뒤 칸이 NULL 인 것은 `FoundLinksTest` 에서 `ChatConversationManagement` 의 지우기를 불러 확인한다

## 변경 파일

| 파일 | 종류 |
| --- | --- |
| `backend/src/main/resources/db/migration/V20261105074439__conversation_found_link_hashes.sql` | 신규 |
| `backend/src/test/resources/conversation-found-link-hashes-column.sql` | 신규 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/conversation/infra/ConversationRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/conversation/application/LinkTarget.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/conversation/application/FoundLinks.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/conversation/application/dto/FoundPage.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/FoundLinkService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/FoundLinkController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/AgentTokenAuthenticationFilter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/config/SecurityConfig.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/ImageSourceService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/ChatConversationManagement.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/application/ArtifactService.java` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
| `docs/features/agent-found-image.md` | 수정 |
| `docs/features/generated-image/spec.md` | 수정 |
| `docs/features/generated-image/design.md` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/conversation/application/FoundLinksTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/FoundLinkEndpointTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ArtifactTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/**/*.java` | 신규·수정 |

마지막 glob 줄은 경로 목록을 세는 기존 테스트(`AgentTokenFilterWiringTest` 등)나 모듈 의존을 보는 테스트가 새 경로와 새 클래스 때문에 깨질 때 고치는 자리다.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.chat.conversation.application.*' --tests 'com.bifos.assistant.mcp.*' --tests 'com.bifos.assistant.chat.ArtifactTest'
node --test test/repo/doc-links.test.ts test/repo/doc-files.test.ts
```

모두 통과한다. MySQL 에서 `UPDATE` 문장이 도는지는 Draft CI 의 `backend-mysql` 이 본다. 로컬에서 MySQL 검사를 돌리지 않는다.
