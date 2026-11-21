# Phase 01. 찾은 사진의 지침과 「비서가 찾음」 표시

**Execution profile**: standard

## 목표

비서가 상품 이름만 들어도 사진을 찾아 그리고, 받지 못하면 다른 사진으로 다시 시도하며, 찾은 사진을 쓴 그림에 「비서가 찾음」 이 보이게 한다.

**범위 외**: 받기 판정과 해시 저장(PR1 이 끝냈다), 운영 왕복(REQ10, 배포 뒤 코디네이터).

## 컨텍스트

**요구사항**: REQ1, REQ4, REQ5, REQ6, REQ7, REQ10

**근거 문서**: `docs/features/agent-found-image/spec.md`, `docs/features/agent-found-image/design.md` 의 「그림 규칙과 막는 글」, 「「비서가 찾음」 표시(PR2)」

- 받기 판정은 `ImageSourceService.read` 의 `link:` 분기다. `ConversationLinks.findSentLink` 다음에 `FoundLinks.find` 를 본다
- 링크 머리는 `ImageSourceController.LINK_HEADER` 와 fos-ctx `image_source.LINK_HEADER` 가 같은 글자다
- 그림 올리기 서명은 `GeneratedImageRequest.verify` 와 fos-ctx `generated_image.sign_generated_image` 가 같은 줄을 만든다
- 참고 링크 저장은 `GeneratedImageService.save` 와 `sourceLinksOf`, 응답은 `ChatDtos` 의 그림 결과물, 화면은 `generated-images.tsx` 의 `PromptDetails` 다

## 의도 메모

- 표시를 링크 글에 섞지 않고 새 칸에 둔다(설계 DECISION8)
- 시도 횟수는 fos-ctx 가 세지 않는다. 호출마다 따로 도는 hook 이 그림 한 장의 시도를 묶을 길이 없다

## 작업 항목

### 1. Control Plane

- `ImageSourceResult` 에 `found` 를 더하고 `ImageSourceService` 가 2번 판정으로 통과하면 참으로 둔다. `ImageSourceController` 가 `X-Image-Source-Found: 1` 을 낸다
- `GeneratedImageRequest` 가 `found_links` 를 읽고 `source_links` 의 부분인지 확인하며, 있으면 서명 아홉째 줄을 더한다
- 마이그레이션 `V20261105074441__chat_artifact_found_link_flags.sql` 로 `chat_artifact.generation_found_link_flags` 를 더하고 `ChatArtifact`, `GeneratedImageService`, `GeneratedImageDetail`, `ChatDtos` 를 잇는다
- `ArtifactService.IMAGE_RULES` 에 설계 표의 다섯 줄을 더하고 마지막 줄에 예외를 넣는다

### 2. fos-ctx

- `image_source._fetch` 가 머리를 읽어 찾은 링크를 따로 기억하고 `found_links_for` 로 내준다
- `hooks.transform_tool_result` 와 `generated_image.publish` 가 `found_links` 를 meta 와 서명에 넣는다
- `LINK_REASONS` 의 다음 방법을 다른 사진으로 다시 부르기로 바꾼다

### 3. 화면

- `MessageArtifact.foundSourceLinks` 를 더하고 `PromptDetails` 가 그 링크 옆에 「비서가 찾음」 을 붙인다

### 4. 테스트

- `backend/src/test/java/com/bifos/assistant/mcp/FoundLinkEndpointTest.java`: 찾은 주소 받기의 머리, 사용자 링크에 머리 없음, `found_links` 올리기와 응답, `source_links` 밖 `found_links` 거절
- `backend/src/test/java/com/bifos/assistant/chat/ArtifactTest.java`: 그림 규칙 글
- `hermes/tests/test_image_source.py`, `hermes/tests/test_generated_image.py`: 머리 읽기, `found_links` 서명, 다시 시도 글
- `web/test/browser/generated-image.spec.ts`: 「비서가 찾음」 표시

### 5. 문서

- `backend/docs/data-schema.md` 의 그림 행 칸 표, `hermes/plugins/fos-ctx/README.md` 의 그림 올리기 계약, `docs/features/agent-found-image.md` 의 흐름

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ1, REQ4, REQ7 | 없음 | `ArtifactTest` 가 실행 입력의 그림 규칙 글을 대조한다 | 이름만 말해도 찾기, 고르기, 사람과 캐릭터 줄이 있다 | 백엔드 통합 테스트(H2) | worker | 없음 | PR 전 |
| REQ5 | 찾은 주소를 알린 대화와 사용자가 보낸 링크가 있는 대화 | `FoundLinkEndpointTest` 에서 두 길로 받기와 `found_links` 를 넣은 그림 올리기를 하고 메시지 응답을 읽는다 | 찾은 주소에만 머리가 있고 응답의 `foundSourceLinks` 에 그 주소만 있다. `source_links` 밖의 `found_links` 는 거절된다 | 백엔드 통합 테스트(H2) | worker | 없음 | PR 전 |
| REQ5 | 머리를 주는 가짜 받기 경로 | `hermes/tests/test_image_source.py`, `test_generated_image.py` | 머리를 받은 링크만 `found_links` 로 가고 서명이 맞는다 | pytest | worker | 없음 | PR 전 |
| REQ5 | `foundSourceLinks` 가 있는 메시지 응답 | `web/test/browser/generated-image.spec.ts` | 「비서가 찾음」 이 찾은 링크에만 붙는다 | 브라우저 테스트 | worker | 없음 | PR 전 |
| REQ6 | 받기 경로가 `IMAGE_SOURCE_LINK_FAILED` 를 주는 첫 링크와 열리는 둘째 링크 | `hermes/tests/test_image_source.py` 에서 첫 호출의 막는 글을 읽고 둘째 링크로 다시 부른다 | 첫 글의 다음 방법이 다른 사진으로 다시 부르기이고 둘째 호출은 사본을 만든다 | pytest | worker | 없음 | PR 전 |
| REQ10 | 운영 배포 | 「GGRN 양말 신은 아이 그려 줘」 만 보낸다 | 답에 그림과 출처 링크가 있고 참고한 링크에 「비서가 찾음」 이 보인다 | 운영 | 코디네이터 | 배포: PR 머지 뒤 fos-ctx 와 Control Plane 배포 | PR 뒤 |

## 검증

```bash
cd backend && ./gradlew test --tests '*FoundLinkEndpointTest' --tests '*ArtifactTest' --tests '*GeneratedImage*'
cd hermes && python3 -m pytest tests/test_image_source.py tests/test_generated_image.py -q
cd web && pnpm typecheck && pnpm test:browser generated-image
scripts/dev/check-local.sh --fast
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/mcp/application/ImageSourceService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/dto/ImageSourceResult.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/ImageSourceController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/dto/GeneratedImageRequest.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/GeneratedImageUploadService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/domain/ChatArtifact.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/application/GeneratedImageService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/application/dto/GeneratedImageDetail.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/artifact/application/ArtifactService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/resources/db/migration/V20261105074441__chat_artifact_found_link_flags.sql` | 신규 |
| `hermes/plugins/fos-ctx/image_source.py` | 수정 |
| `hermes/plugins/fos-ctx/generated_image.py` | 수정 |
| `hermes/plugins/fos-ctx/hooks.py` | 수정 |
| `web/src/components/chat/message-types.ts` | 수정 |
| `web/src/components/chat/artifact/generated-images.tsx` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/FoundLinkEndpointTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ArtifactTest.java` | 수정 |
| `hermes/tests/test_image_source.py` | 수정 |
| `hermes/tests/test_generated_image.py` | 수정 |
| `web/test/browser/generated-image.spec.ts` | 수정 |
