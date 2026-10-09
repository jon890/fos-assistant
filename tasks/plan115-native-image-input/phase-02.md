# Phase 02. 보내는 메시지의 사진을 줄여 실행 입력에 이미지 파트로 싣는다

**Execution profile**: deep

## 목표

대화 turn 의 Hermes 실행 요청에 이번 메시지의 사진을 긴 변 1600px JPEG 의 `data:` 주소로 싣는다.
원본 파일 목록과 `vision_analyze` 안내는 그대로 두되, 실은 사진과 싣지 못한 사진을 나눠 적는다.

**범위 외**: 가짜 Hermes 의 목록 입력 읽기(phase 01 에서 끝났다). web 입력창(PR #356 영역), 실행 공간 mount 코드(`hermes/SandboxAttachmentDirectory` 와 Hermes 정책).
`hermes/connectors/naver-blog/skills/naver-blog/SKILL.md` 의 「모든 사진을 `vision_analyze` 로 본다」 지시는 고치지 않는다. 커넥터 스킬의 동작 변경이라 따로 다룬다.
흐름(`runFlow`)과 다른 실행(`MemoryProposer`, `StarterSuggestionService`, `AgentRunner`, `HermesDecisionProvider`)은 사진을 싣지 않는다.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261009-native-image-input.md`, `docs/backend/attachment.md` 의 「에이전트에게 알리는 법」 과 「갈리는 지점」, `docs/hermes/runs-api.md` 의 「`/v1/runs` 에 사진을 싣는 법」

- 지금 입력은 `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentService.java` 의 `agentInput(Long conversationId, List<ChatAttachment> attached, String text)` 가 만든다. 사진이 없으면 `text` 를 그대로 돌려주고, 있으면 「[이번 메시지에 올린 사진]」 단락과 안내 두 줄을 앞에 붙인다. 순번은 `orderInConversation` 이 준다
- 부르는 곳은 `backend/src/main/java/com/bifos/assistant/chat/application/ChatTurnRunner.java` 의 `runTurn`(81행 근처)과 `runFlow`(207행 근처) 둘이다. `runTurn` 은 그 글을 `ChatTurnLifecycle.begin(...)` 의 `String text` 로 두 번(모델 해석 실패 경로, 정상 경로) 넘긴다
- `backend/src/main/java/com/bifos/assistant/chat/application/ChatTurnLifecycle.java` 의 `begin` 이 `new HermesRunCommand(agent.hermesProfile(), agent.apiBaseUrl(), text, instructions, sessionId, provider, model, reasoningEffort)` 를 만든다
- `backend/src/main/java/com/bifos/assistant/hermes/dto/HermesRunCommand.java` 는 8칸 record 다. `new HermesRunCommand(` 를 부르는 곳은 `ChatTurnLifecycle`, `memory/application/MemoryProposer.java`, `chat/application/StarterSuggestionService.java`, `orchestration/application/AgentRunner.java`, `proactive/application/HermesDecisionProvider.java` 와 시험들이다(`git grep -n "new HermesRunCommand(" backend`)
- `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesRunsClient.java` 의 `submitRequest` 가 `body.put("input", command.input())` 로 본문을 만든다
- 파일은 `backend/src/main/java/com/bifos/assistant/chat/infra/AttachmentStore.java` 의 `InputStream open(ChatAttachment)` 로 읽는다. 없으면 예외를 던진다
- `ChatAttachment` 의 접근자는 Lombok fluent 다: `id()`, `contentType()`, `storedName()`, `uploadedByUserId()`, `conversationId()`
- 디코딩 본보기는 `backend/src/main/java/com/bifos/assistant/chat/application/MpoJpegNormalizer.java` 다. 같은 패키지의 `ImageIO` 사용, `@NoArgsConstructor(access = AccessLevel.PRIVATE)` 정적 도구 모양을 따른다
- 다시 생성(`ChatService.regenerate`)은 그 질문의 보이는 첨부로 `runTurn` 을 다시 부른다. 따로 고칠 것이 없다
- **흐름 경로에도 첨부가 들어온다.** 사진을 붙인 질문을 보낸 뒤 에이전트에 흐름을 붙이고 다시 생성하면 `runFlow` 가 그 첨부로 `agentInput` 을 부른다(`backend/src/test/java/com/bifos/assistant/chat/ChatRegenerateTest.java` 의 `flowConversationRegenerationInputKeepsPreviousImageSlots`)
- `AttachmentStore.open` 이 던지는 예외는 셋이고 모두 `RuntimeException` 이다. 파일이 없으면 `ApiException(ATTACHMENT_GONE)`, 그 밖의 I/O 실패는 `UncheckedIOException`, 링크나 이름 불일치는 `IllegalArgumentException`. 행만 있고 파일이 없는 첨부를 쓰는 기존 시험이 `ChatRegenerateTest` 와 `RegenerateDeletedAttachmentTest` 에 있다
- 가짜 Hermes 는 phase 01 에서 목록 `input` 을 읽게 됐다. `context.hermes.lastSubmittedInput()` 은 첫 글 파트, `context.hermes.lastSubmittedImages()` 는 `{ label, url }` 목록이다(`test/e2e/fake-hermes/run-input.ts`)
- e2e 「사진 첨부」 시나리오(`test/e2e/scenarios/chat-attachment.ts`)는 1×1 PNG(`onePixelPng()`)를 올려 보낸다. 이 phase 뒤로 그 사진이 이미지로 실린다

## 의도 메모

- **줄인 사본을 저장하지 않는다.** 메모리에서 만들어 요청에만 싣는다(ADR 「위협과 보관」)
- 상한은 상수다. `MAX_IMAGES = 10`, `MAX_ENCODED_BYTES = 7 * 1024 * 1024`(data 주소 문자열 길이의 합). 앞 사진부터 담고, 다음 사진이 둘 중 하나를 넘기면 거기서 멈춘다. 뒤의 더 작은 사진을 골라 담지 않는다. 화면 순서와 실린 순서를 같게 두려는 것이다
- 디코딩은 `ImageReadParam.setSourceSubsampling(n, n, 0, 0)` 으로 원본을 줄여 읽는다. `n = max(1, 긴 변 / 1600)`(정수 나눗셈). 그 뒤 `Graphics2D`(`RenderingHints.VALUE_INTERPOLATION_BILINEAR`)로 긴 변 1600 이하로 줄인다. 원본 전체를 한 번에 펼치면 48MP 사진 한 장이 약 190MB 다
- EXIF 회전: JPEG 의 APP1 `Exif\0\0` 의 IFD0 에서 태그 `0x0112` 를 읽어 1~8 을 반영한다. 읽지 못하면 1(그대로)로 본다. 휴대폰 사진은 대개 이 값으로 세로를 표시한다
- 결과는 `BufferedImage.TYPE_INT_RGB` 에 흰 배경을 먼저 칠하고 그린다. PNG 의 투명 영역이 JPEG 에서 검게 되지 않게 한다
- JPEG 인코딩은 `ImageWriteParam.MODE_EXPLICIT`, 품질 `0.85f`
- 픽셀 수가 `MAX_PIXELS = 60_000_000` 을 넘는 사진은 디코딩하지 않는다(리더의 `getWidth(0)`, `getHeight(0)` 을 `(long) width * height` 로 곱해 먼저 본다. `int` 곱은 넘친다). 판정은 package-private `static boolean withinPixelLimit(long width, long height, long maxPixels)` 가 갖는다. 수만 px 의 PNG 는 subsampling 으로도 모든 행을 읽어 보내기가 수 초 늦어진다
- 실패는 사진 단위로 삼킨다. **`IOException` 과 `RuntimeException` 을 모두 잡는다**(위 `AttachmentStore.open` 의 세 예외 포함). 읽는 리더가 없다(WebP), 픽셀 상한 초과, 디코딩 예외, 파일 열기 예외면 그 사진을 싣지 않고 다음 사진으로 간다. 경고 로그에는 첨부 번호와 예외 종류만 남긴다(본문, 이름 금지)
- **흐름 경로는 사진을 싣지 않고 파일도 읽지 않는다.** `agentInput` 에 `boolean embedImages` 를 두고 `runFlow` 는 `false` 를 넘긴다. 그러면 안내 문장은 지금 그대로다
- 실은 사진이 없으면 안내 문장을 지금 그대로 둔다: `이미지는 read_file 로 읽지 말고 vision_analyze 로 본다.`
- 하나라도 실었으면 그 줄 대신 두 줄을 쓴다
  - `이 메시지에 이미지로 함께 실은 사진: 1번째, 2번째 사진. 파일로 다시 읽지 않아도 된다.`(순번은 실은 사진의 대화 순번을 `, ` 로 잇는다)
  - `그 밖의 사진과 지난 메시지의 사진은 read_file 로 읽지 말고 vision_analyze 로 본다.`
- 실행 요청의 `content` 는 `[{"type":"text","text":<입력 전체>}, {"type":"text","text":"N번째 사진"}, {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}, ...]` 이다. `detail` 은 보내지 않는다. `input` 은 `[{"role":"user","content":[...]}]` 다. 사진이 없으면 `input` 은 지금처럼 문자열이다
- 기각: `AttachmentService.agentInput` 이 `String` 을 그대로 돌려주고 사진을 따로 구하는 안. 순번 계산과 소유 검사를 두 번 하게 되고, 글의 안내 문장이 실은 사진에 맞춰 바뀌어야 해서 한 곳에서 만든다

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/hermes/dto/HermesImage.java`(신규)

```java
/** 실행 입력에 싣는 사진 한 장이다. {@code label} 은 이미지 앞에 붙는 글이고 {@code dataUrl} 은 {@code data:image/jpeg;base64,...} 다. */
public record HermesImage(String label, String dataUrl) {}
```

### 2. `HermesRunCommand` 에 사진을 더한다

- 마지막 칸 `List<HermesImage> images` 를 더한다. compact 생성자에서 null 을 `List.of()` 로, 그 밖은 `List.copyOf` 로 둔다
- 지금의 8칸 생성자를 `images = List.of()` 로 위임하는 보조 생성자로 둔다. 다른 실행(`MemoryProposer` 등)과 시험의 호출부는 고치지 않는다
- Javadoc 의 `@param input` 을 「에이전트에게 보내는 글」 로, `@param images` 를 더한다

### 3. `HttpHermesRunsClient.submitRequest`

- `command.images()` 가 비면 지금처럼 `body.put("input", command.input())`
- 아니면 위 의도 메모의 목록 모양을 `List<Map<String, Object>>` 로 만들어 `body.put("input", ...)` 한다. 순서는 글 파트, 그 뒤 사진마다 이름표 글 파트와 이미지 파트다

### 4. `backend/src/main/java/com/bifos/assistant/chat/application/AgentImageResizer.java`(신규)

- package-private 정적 도구. `static Optional<byte[]> toJpeg(byte[] original)` 이 위 의도 메모의 규칙으로 줄인 JPEG 를 낸다. 읽지 못하면 빈 값
- 상수 `LONG_SIDE = 1600`, `QUALITY = 0.85f`
- EXIF 방향 읽기는 `static int orientation(byte[] jpeg)` 로 따로 두어 시험한다. JPEG 가 아니거나 태그가 없으면 1

### 5. `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentImages.java`(신규, `@Component`)

- `AttachmentStore` 를 받는다. `List<EmbeddedImage> embed(List<ChatAttachment> attached, Map<Long, Integer> order)` 가 첨부 순서대로 파일을 읽어(`readAllBytes`) `AgentImageResizer.toJpeg` 로 줄이고 `data:image/jpeg;base64,` 주소를 만든다. 상한(`MAX_IMAGES`, `MAX_ENCODED_BYTES`)에서 멈춘다
- 상한 판정은 package-private 정적 함수 `static boolean admits(int acceptedImages, long acceptedEncodedBytes, long nextEncodedBytes, int maxImages, long maxEncodedBytes)` 하나가 갖는다. `embed` 는 사진마다 줄인 뒤 이 함수에 묻고, 거짓이면 그 자리에서 멈춘다(남은 파일은 읽지 않는다). 줄이지 못한 사진은 세지 않고 건너뛴다
- `EmbeddedImage` 는 같은 패키지의 record `(Long attachmentId, int ordinal, String dataUrl)` 다. 타입 하나에 파일 하나 규칙을 따라 `EmbeddedImage.java` 로 둔다

### 6. `AttachmentService.agentInput` 이 글과 사진을 함께 낸다

- 시그니처를 `AgentInput agentInput(Long conversationId, List<ChatAttachment> attached, String text, boolean embedImages)` 로 바꾼다. 반환은 새 record `AgentInput(String text, List<HermesImage> images)`(`chat/application/AgentInput.java`, 신규)다. 사진이 없으면 `new AgentInput(text, List.of())`. `embedImages` 가 거짓이면 `AttachmentImages` 를 부르지 않는다
- 소유 검사와 순번 계산은 그대로 두고, `AttachmentImages.embed` 결과로 안내 문장을 위 의도 메모대로 고른다. 이미지 이름표는 `N번째 사진` 이다
- 생성자에 `AttachmentImages` 를 더한다(`@RequiredArgsConstructor` 라 필드만 더한다)
- 클래스와 메서드 Javadoc 의 「`/v1/runs` 가 이미지 항목을 받지 않아」 문장을 ADR-20261009 / native-image-input 의 결정으로 고친다

### 7. `ChatTurnRunner` 와 `ChatTurnLifecycle`

- `runTurn`: `AgentInput agentInput = attachments.agentInput(conversation.id(), routed.attached(), asked, true)`, 글은 `artifacts.agentPreamble(conversation) + agentInput.text()`. `begin` 두 호출에 `agentInput.images()` 를 넘긴다
- `runFlow`: `attachments.agentInput(conversation.id(), routed.attached(), text, false).text()`. 흐름은 사진을 싣지 않는다(`docs/backend/attachment.md` 「갈리는 지점」)
- `ChatTurnLifecycle.begin` 에 `List<HermesImage> images` 를 `String text` 바로 뒤에 더하고 `HermesRunCommand` 의 9칸 생성자로 넘긴다

### 8. 시험

- `backend/src/test/java/com/bifos/assistant/chat/application/AgentImageResizerTest.java`(신규, 순수 단위. 같은 패키지의 `MpoJpegNormalizerTest` 옆이다)
  - 4000×3000 PNG(코드에서 `BufferedImage` 로 만든다)를 주면 결과가 JPEG 로 읽히고 긴 변이 1600 이다
  - 800×600 은 키우지 않는다
  - 방향 6 인 EXIF APP1 을 넣은 4000×3000 JPEG 는 결과가 세로(1200×1600)다. EXIF 조각은 시험 안에서 바이트로 만든다
  - 투명 PNG 의 투명 화소가 흰색에 가깝다(RGB 각 240 이상)
  - 이미지가 아닌 바이트는 빈 값이다
  - `withinPixelLimit(50000, 50000, MAX_PIXELS)` 는 거짓, `withinPixelLimit(8000, 6000, MAX_PIXELS)` 는 참이다
- `backend/src/test/java/com/bifos/assistant/chat/application/AttachmentImagesTest.java`(신규, 순수 단위)
  - `admits` 는 이미 담은 장수가 `maxImages` 면 거짓이다
  - 이미 6MB 를 담았고 다음이 2MB 면 상한 7MB 에서 거짓, 다음이 1MB 면 참이다(합이 상한과 같으면 참)
  - 위 둘의 상수(`MAX_IMAGES = 10`, `MAX_ENCODED_BYTES = 7 * 1024 * 1024`)로 부르는 경우도 하나 둔다
- `backend/src/test/java/com/bifos/assistant/hermes/HermesRunRequestTest.java` 에 둘을 더한다
  - 사진이 있으면 `input` 이 배열이고 마지막 항목의 `content` 가 글, 이름표, `image_url` 순이다
  - 사진이 없으면 `input` 은 지금처럼 문자열이다(기존 `sendsNoThreeKeysWhenProviderAndModelAreBothBlank` 가 이미 확인한다면 그대로 둔다)
- `backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentTurnTest.java`
  - 지금 시험은 이미지가 아닌 바이트(`IMAGE`)라 아무것도 실리지 않는다. 기존 기대값은 그대로 통과해야 한다
  - 실제 PNG 한 장(작은 `BufferedImage` 를 `ImageIO.write`)과 이미지가 아닌 한 장을 붙여 보내면, 받은 명령의 `images()` 가 한 장이고 이름표가 그 사진의 순번이며, 글에 「이 메시지에 이미지로 함께 실은 사진: 1번째 사진.」 과 「그 밖의 사진과 지난 메시지의 사진은」 이 있다
  - 실제 PNG 열한 장을 보내면 `images()` 가 열 장이다
  - 다시 생성해도 같은 사진이 다시 실린다
  - 행은 있는데 파일이 없는 사진(`AttachmentStore.delete` 로 파일만 지운다)을 붙여 보내면 실행은 돌고, `images()` 는 비고, 안내는 `이미지는 read_file 로 읽지 말고 vision_analyze 로 본다.` 그대로다
- `backend/src/test/java/com/bifos/assistant/chat/ChatRegenerateTest.java`
  - `flowConversationRegenerationInputKeepsPreviousImageSlots` 와 같은 준비로, 실제 PNG 파일을 `AttachmentStore.save` 로 저장한 첨부를 흐름으로 다시 생성하면 입력에 「이미지로 함께 실은」 이 없고 `이미지는 read_file 로 읽지 말고 vision_analyze 로 본다.` 가 있다
- `test/e2e/scenarios/chat-attachment.ts`
  - 보낸 뒤 `context.hermes.lastSubmittedImages()` 가 한 장이고, `label` 이 `1번째 사진`, `url` 이 `data:image/jpeg;base64,` 로 시작한다
  - 입력 글에 `이 메시지에 이미지로 함께 실은 사진: 1번째 사진.` 이 있다. 기존 경로, 파일 이름, 끝 글 확인은 그대로 둔다

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.chat.application.AgentImageResizerTest' --tests 'com.bifos.assistant.chat.application.AttachmentImagesTest' --tests 'com.bifos.assistant.chat.ChatAttachmentTurnTest' --tests 'com.bifos.assistant.chat.ChatRegenerateTest' --tests 'com.bifos.assistant.chat.RegenerateDeletedAttachmentTest' --tests 'com.bifos.assistant.hermes.HermesRunRequestTest'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
scripts/quality.sh check
node test/e2e/run.ts
pnpm --dir web test:browser chat-attachment
```

여섯 시험 클래스와 e2e, 사진 첨부 브라우저 검사가 통과하고 품질 검사가 종료 코드 0 이다.
e2e 와 브라우저 검사는 무겁다. 스폰 프롬프트가 알려 주는 잠금 도우미로 감싸 한 번에 하나만 돌린다.
포맷은 기능 커밋 뒤 `./gradlew spotlessApply` 결과를 따로 커밋한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/hermes/dto/HermesImage.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/dto/HermesRunCommand.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesRunsClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AgentImageResizer.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentImages.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/EmbeddedImage.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AgentInput.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatTurnRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatTurnLifecycle.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/application/AgentImageResizerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/application/AttachmentImagesTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatRegenerateTest.java` | 수정 |
| `test/e2e/scenarios/chat-attachment.ts` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/HermesRunRequestTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentTurnTest.java` | 수정 |
