# Phase 03. 보내는 메시지의 사진 사본을 실행 입력에 이미지 파트로 싣고 넘친 사진은 사본 경로로 안내한다

**Execution profile**: deep

## 목표

대화 turn 의 Hermes 실행 요청에 이번 메시지의 사진을 그 사본(`{첨부 번호}.small.jpg`)의 `data:` 주소로 싣는다. 한 턴 10장, base64 합계 7MB 까지다.
싣지 못한 사진은 안내 문장에 사진마다 사본 경로를 적어 같은 턴에 `vision_analyze` 로 보게 한다.

**범위 외**: 사본 파일의 생성과 삭제 규칙(phase 02 에서 끝났다), 가짜 Hermes 의 목록 입력 읽기(phase 01 에서 끝났다). web 입력창(PR #356 영역), 실행 공간 mount 코드.
`hermes/connectors/naver-blog/skills/naver-blog/SKILL.md` 의 「모든 사진을 `vision_analyze` 로 본다」 지시는 고치지 않는다. 커넥터 스킬의 동작 변경이라 따로 다룬다.
흐름(`runFlow`)과 다른 실행(`MemoryProposer`, `StarterSuggestionService`, `AgentRunner`, `HermesDecisionProvider`)은 사진을 싣지 않는다.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261009-native-image-input.md`, `docs/backend/attachment.md` 의 「에이전트에게 알리는 법」 과 「갈리는 지점」, `docs/hermes/runs-api.md` 의 「`/v1/runs` 에 사진을 싣는 법」

- 지금 입력은 `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentService.java` 의 `agentInput(Long conversationId, List<ChatAttachment> attached, String text)` 가 만든다. 사진이 없으면 `text` 를 그대로 돌려주고, 있으면 「[이번 메시지에 올린 사진]」 단락(둘째 줄이 `agentRoot` 기준 대화 폴더 경로, 그 뒤 사진마다 `- N번째 사진: {storedName} (올린 이름: ...)`)과 안내 두 줄을 앞에 붙인다. 순번은 `orderInConversation` 이 준다
- 부르는 곳은 `backend/src/main/java/com/bifos/assistant/chat/application/ChatTurnRunner.java` 의 `runTurn`(81행 근처)과 `runFlow`(207행 근처) 둘이다. `runTurn` 은 그 글을 `ChatTurnLifecycle.begin(...)` 의 `String text` 로 두 번(모델 해석 실패 경로, 정상 경로) 넘긴다
- **흐름 경로에도 첨부가 들어온다.** 사진을 붙인 질문을 보낸 뒤 에이전트에 흐름을 붙이고 다시 생성하면 `runFlow` 가 그 첨부로 `agentInput` 을 부른다(`backend/src/test/java/com/bifos/assistant/chat/ChatRegenerateTest.java` 의 `flowConversationRegenerationInputKeepsPreviousImageSlots`)
- `backend/src/main/java/com/bifos/assistant/chat/application/ChatTurnLifecycle.java` 의 `begin` 이 `new HermesRunCommand(agent.hermesProfile(), agent.apiBaseUrl(), text, instructions, sessionId, provider, model, reasoningEffort)` 를 만든다
- `backend/src/main/java/com/bifos/assistant/hermes/dto/HermesRunCommand.java` 는 8칸 record 다. `new HermesRunCommand(` 를 부르는 곳은 `ChatTurnLifecycle`, `memory/application/MemoryProposer.java`, `chat/application/StarterSuggestionService.java`, `orchestration/application/AgentRunner.java`, `proactive/application/HermesDecisionProvider.java` 와 시험들이다(`git grep -n "new HermesRunCommand(" backend`)
- `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesRunsClient.java` 의 `submitRequest` 가 `body.put("input", command.input())` 로 본문을 만든다
- phase 02 가 만든 것: `AttachmentStore.smallName(Long)`, `hasSmall`, `readSmall`, `AttachmentImages.prepareSmall(ChatAttachment)`(동시 디코딩 둘, 최선 노력, 사본이 생겼는지 돌려준다). `ChatAttachment` 의 접근자는 Lombok fluent 다: `id()`, `storedName()`, `uploadedByUserId()`, `conversationId()`
- `AttachmentStore.readSmall` 과 `open` 이 던지는 예외는 모두 `RuntimeException` 이다(`ApiException(ATTACHMENT_GONE)`, `UncheckedIOException`, `IllegalArgumentException`). 행만 있고 파일이 없는 첨부를 쓰는 기존 시험이 `ChatRegenerateTest` 와 `RegenerateDeletedAttachmentTest` 에 있다
- 다시 생성(`ChatService.regenerate`)은 그 질문의 보이는 첨부로 `runTurn` 을 다시 부른다. 따로 고칠 것이 없다
- 가짜 Hermes 는 목록 `input` 을 읽는다. `context.hermes.lastSubmittedInput()` 은 첫 글 파트, `context.hermes.lastSubmittedImages()` 는 `{ label, url }` 목록이다(`test/e2e/fake-hermes/run-input.ts`)
- e2e 「사진 첨부」 시나리오(`test/e2e/scenarios/chat-attachment.ts`)는 1×1 PNG(`onePixelPng()`)를 HTTP 로 올려 보낸다. 이 phase 뒤로 그 사진이 이미지로 실린다
- 옛 안내 문장 `이미지는 read_file 로 읽지 말고 vision_analyze 로 본다.` 를 단언하는 시험은 `backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentTurnTest.java` 하나다

## 의도 메모

- 상한은 상수다. `MAX_IMAGES = 10`, `MAX_ENCODED_BYTES = 7 * 1024 * 1024`(data 주소 문자열 길이의 합). 판정은 package-private 정적 함수 `static boolean admits(int acceptedImages, long acceptedEncodedBytes, long nextEncodedBytes, int maxImages, long maxEncodedBytes)` 하나가 갖는다. 앞 사진부터 담고, 한 번 거짓이면 그 뒤 사진은 싣지 않는다(뒤의 더 작은 사본도). 화면 순서와 실린 순서를 같게 둔다
- 보낼 때 사진마다: 사본이 없으면 `prepareSmall` 로 한 번 만들어 본다(이 결정 전에 올린 사진, 올릴 때 만들지 못한 사진). 상한 뒤의 사진도 만들어 본다. 경로로 안내할 사본이 있어야 한다. `prepareSmall` 은 동시 디코딩 차례를 30초까지 기다린다. 그 안에 만들지 못하면 원본 경로로 안내한다
- 사진 단위 실패는 삼킨다. `readSmall` 이 던지면 그 사진을 싣지 않고 사본 없음으로 본다
- **흐름 경로는 사진을 싣지 않고 파일도 만들지 않는다.** `agentInput` 에 `boolean embedImages` 를 두고 `runFlow` 는 `false` 를 넘긴다. 이때 모든 사진이 「싣지 못한 사진」 이고, 사본 경로는 `hasSmall` 만 보고 적는다
- 안내 문장(작업 종류에 묶지 않는 일반 문구). `{dir}` 은 단락 둘째 줄의 대화 폴더 경로다. 파일 목록 단락 뒤 빈 줄 다음에 아래 순서로 줄을 잇는다
  1. `사진은 모두 N장이다.`(이번 메시지의 사진 장수)
  2. 하나라도 실었으면 `이 메시지에 이미지로 함께 실은 사진: 1번째, 2번째 사진. 이미 보이므로 파일로 다시 읽지 않아도 된다.`(순번은 실은 사진의 대화 순번을 `, ` 로 잇는다)
  3. 싣지 못한 사진이 있으면 `싣지 못한 사진은 아래 경로를 답에 필요한 만큼 vision_analyze 로 확인한다.` 다음 줄부터 사진마다 `- N번째 사진: {dir}/{첨부 번호}.small.jpg`. 사본이 없으면 `{dir}/{storedName}`
  4. `지난 메시지의 사진은 같은 폴더의 {첨부 번호}.small.jpg 를, 없으면 원본을 vision_analyze 로 본다. read_file 로 읽지 않는다.`
  5. `파일을 올리거나 고치는 도구에는 위 목록의 원본 파일을 쓴다.`
  6. 지금의 `사용자에게 사진을 가리킬 때는 파일 이름 대신 몇 번째 사진인지로 적는다.`
- 실행 요청의 `content` 는 `[{"type":"text","text":<입력 전체>}, {"type":"text","text":"N번째 사진"}, {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}, ...]` 이다. `detail` 은 보내지 않는다. `input` 은 `[{"role":"user","content":[...]}]` 다. 사진을 싣지 않으면 `input` 은 지금처럼 문자열이다
- 기각: `agentInput` 이 `String` 을 그대로 돌려주고 사진을 따로 구하는 안. 순번 계산과 소유 검사를 두 번 하게 되고 안내 문장이 실은 사진에 맞춰 바뀌어야 해서 한 곳에서 만든다

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/hermes/dto/HermesImage.java`(신규)

```java
/** 실행 입력에 싣는 사진 한 장이다. {@code label} 은 이미지 앞에 붙는 글이고 {@code dataUrl} 은 {@code data:image/jpeg;base64,...} 다. */
public record HermesImage(String label, String dataUrl) {}
```

### 2. `HermesRunCommand` 에 사진을 더한다

- 마지막 칸 `List<HermesImage> images` 를 더한다. compact 생성자에서 null 을 `List.of()` 로, 그 밖은 `List.copyOf` 로 둔다
- 지금의 8칸 생성자를 `images = List.of()` 로 위임하는 보조 생성자로 둔다. 다른 실행과 시험의 호출부는 고치지 않는다
- Javadoc 의 `@param input` 을 「에이전트에게 보내는 글」 로, `@param images` 를 더한다

### 3. `HttpHermesRunsClient.submitRequest`

- `command.images()` 가 비면 지금처럼 `body.put("input", command.input())`
- 아니면 위 의도 메모의 목록 모양을 `List<Map<String, Object>>` 로 만들어 `body.put("input", ...)` 한다

### 4. `AttachmentImages` 가 실을 사진을 고른다

- `List<AgentPhoto> photos(List<ChatAttachment> attached, Map<Long, Integer> order, boolean embed)`: 첨부 순서대로 사진 하나에 `AgentPhoto` 하나를 낸다. 위 의도 메모의 규칙을 따른다
- `static boolean admits(...)` 와 상수 `MAX_IMAGES`, `MAX_ENCODED_BYTES` 를 둔다
- `backend/src/main/java/com/bifos/assistant/chat/application/AgentPhoto.java`(신규): record `(Long attachmentId, int ordinal, String agentFileName, String dataUrl)`. `agentFileName` 은 사본이 있으면 `AttachmentStore.smallName(id)`, 없으면 `storedName()`. `dataUrl` 은 싣지 않으면 null. `boolean embedded()` 를 둔다

### 5. `AttachmentService.agentInput`

- 시그니처를 `AgentInput agentInput(Long conversationId, List<ChatAttachment> attached, String text, boolean embedImages)` 로 바꾼다. 반환은 새 record `AgentInput(String text, List<HermesImage> images)`(`chat/application/AgentInput.java`, 신규)다. 사진이 없으면 `new AgentInput(text, List.of())`
- 소유 검사와 순번 계산은 그대로 두고, `images.photos(...)` 결과로 안내 문장을 위 의도 메모대로 만든다. `AgentInput.images` 에는 `embedded()` 인 사진만 순서대로 담고 이름표는 `N번째 사진` 이다
- 클래스와 메서드 Javadoc 의 「`/v1/runs` 가 이미지 항목을 받지 않아」 문장을 ADR-20261009 / native-image-input 의 결정으로 고친다

### 6. `ChatTurnRunner` 와 `ChatTurnLifecycle`

- `runTurn`: `AgentInput agentInput = attachments.agentInput(conversation.id(), routed.attached(), asked, true)`, 글은 `artifacts.agentPreamble(conversation) + agentInput.text()`. `begin` 두 호출에 `agentInput.images()` 를 넘긴다
- `runFlow`: `attachments.agentInput(conversation.id(), routed.attached(), text, false).text()`
- `ChatTurnLifecycle.begin` 에 `List<HermesImage> images` 를 `String text` 바로 뒤에 더하고 `HermesRunCommand` 의 9칸 생성자로 넘긴다

### 7. 시험

- `backend/src/test/java/com/bifos/assistant/chat/application/AttachmentImagesTest.java`(신규, 순수 단위)
  - `admits` 는 이미 담은 장수가 `maxImages` 면 거짓이다
  - 이미 6MB 를 담았고 다음이 2MB 면 상한 7MB 에서 거짓, 다음이 1MB 면 참이다(합이 상한과 같으면 참)
  - 상수(`MAX_IMAGES = 10`, `MAX_ENCODED_BYTES = 7 * 1024 * 1024`)로 부르는 경우도 하나 둔다
- `backend/src/test/java/com/bifos/assistant/hermes/HermesRunRequestTest.java` 에 하나를 더한다
  - 사진이 있으면 `input` 이 배열이고 마지막 항목의 `content` 가 글, 이름표, `image_url` 순이다. 사진이 없을 때 문자열인 것은 기존 `sendsNoThreeKeysWhenProviderAndModelAreBothBlank` 가 확인한다
- `backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentTurnTest.java`
  - 지금 시험은 이미지가 아닌 바이트(`IMAGE`)라 사본이 없고 아무것도 실리지 않는다. 옛 문장을 단언하는 기대값(서른 장 시험)을 새 안내(전체 장수, 사진마다 원본 경로 줄, 지난 사진 줄, 원본 도구 줄)로 고친다
  - 실제 PNG 한 장(작은 `BufferedImage` 를 `ImageIO.write`)과 이미지가 아닌 한 장을 붙여 보내면 `images()` 가 한 장이고 이름표가 그 사진의 순번이다. 글에 `사진은 모두 2장이다.`, `이 메시지에 이미지로 함께 실은 사진: 1번째 사진.`, 이미지가 아닌 사진의 원본 경로 줄, `파일을 올리거나 고치는 도구에는 위 목록의 원본 파일을 쓴다.` 가 있다
  - 실제 PNG 서른 장: `images()` 가 열 장이고 11~30번째 사진의 `.small.jpg` 경로 줄이 스무 개다
  - 1600×1200 잡음 PNG 다섯 장(한 장 약 5.5MB 로 업로드 상한 안, 사본 base64 약 1.85MB): `images()` 가 세 장이고 넷째, 다섯째 사진은 `.small.jpg` 경로 줄로 안내된다
  - 다시 생성해도 같은 사진이 다시 실린다
  - 사본 파일을 지운 첨부(이 결정 전에 올린 사진과 같다)를 붙여 보내면 보낼 때 사본이 다시 생기고 실린다
  - 행은 있는데 원본도 사본도 없는 사진(`AttachmentStore.delete`)을 붙여 보내면 실행은 돌고, `images()` 는 비고, 안내에 그 사진의 원본 경로 줄이 있다
  - 시험의 업로드는 서비스(`attachments.upload`)를 직접 부르므로 사본이 올릴 때 생기지 않는다. 보낼 때 만드는 경로로 사본이 생긴다
- `backend/src/test/java/com/bifos/assistant/chat/ChatRegenerateTest.java`
  - `flowConversationRegenerationInputKeepsPreviousImageSlots` 와 같은 준비로, 행을 넣은 뒤 `AttachmentStore.save(attachment, PNG 바이트 스트림)` 로 원본 파일을 쓰고 `AttachmentService.prepareSmall` 로 사본을 만든 첨부를 흐름으로 다시 생성하면 입력에 「이미지로 함께 실은」 이 없고 그 사진의 `.small.jpg` 경로 줄이 있다
- `test/e2e/scenarios/chat-attachment.ts`
  - 보낸 뒤 `context.hermes.lastSubmittedImages()` 가 한 장이고, `label` 이 `1번째 사진`, `url` 이 `data:image/jpeg;base64,` 로 시작한다
  - 입력 글에 `이 메시지에 이미지로 함께 실은 사진: 1번째 사진.` 이 있다. 기존 경로, 파일 이름, 끝 글 확인은 그대로 둔다

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.chat.application.AttachmentImagesTest' --tests 'com.bifos.assistant.chat.ChatAttachmentTurnTest' --tests 'com.bifos.assistant.chat.ChatRegenerateTest' --tests 'com.bifos.assistant.chat.RegenerateDeletedAttachmentTest' --tests 'com.bifos.assistant.hermes.HermesRunRequestTest'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
scripts/quality.sh check
node test/e2e/run.ts
pnpm --dir web test:browser chat-attachment
```

다섯 시험 클래스와 e2e, 사진 첨부 브라우저 검사가 통과하고 품질 검사가 종료 코드 0 이다.
gradle 시험, e2e, 브라우저 검사는 무겁다. 스폰 프롬프트가 알려 주는 잠금 도우미로 감싸 한 번에 하나만 돌린다.
포맷은 기능 커밋 뒤 `./gradlew spotlessApply` 결과를 따로 커밋한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/hermes/dto/HermesImage.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/dto/HermesRunCommand.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesRunsClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentImages.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AgentPhoto.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AgentInput.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatTurnRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatTurnLifecycle.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/application/AttachmentImagesTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/hermes/HermesRunRequestTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentTurnTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatRegenerateTest.java` | 수정 |
| `test/e2e/scenarios/chat-attachment.ts` | 수정 |
