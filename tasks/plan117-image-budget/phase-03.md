# Phase 03. 30장을 모두 싣고 이번 메시지 사진의 도구 안내를 없앤다

**Execution profile**: deep

## 목표

한 번에 보낼 수 있는 30장이 언제나 모두 실행 입력에 실리게 768px 단계에 JPEG 품질 단계를 더한다.
이번 메시지 사진을 `vision_analyze` 로 보게 하는 안내를 없앤다. 화면의 장수 상한 안내를 「나눠 보내 달라」 로 바꾸고, 화면과 Control Plane 의 상한 값을 시험으로 묶는다.

**범위 외**: 단계 선택(1600, 1280, 1024, 768)과 화소 예산은 앞 phase 가 만들었다. 흐름이 붙은 에이전트(`embed` 거짓)의 안내는 바꾸지 않는다. 운영 확인은 원격 검증 목록이 갖는다.

## 컨텍스트

**근거 문서**: `backend/docs/adr/ADR-20261009-native-image-input.md` 의 「결정」 표(「한 턴에 싣는 양」, 「줄인 단계」, 「한 번에 보낼 수 있는 장수」, 「싣지 못한 사진」, 「지시문」)와 「상한의 근거」, `docs/backend/attachment.md` 의 「에이전트에게 알리는 법」 과 「갈리는 지점」. 이 phase 의 동작과 문구는 그 두 문서와 같아야 한다

- `backend/src/main/java/com/bifos/assistant/chat/application/AgentImageResizer.java`: `QUALITY = 0.85f`, `static Optional<byte[]> shrink(byte[] jpeg, int longSide)`(긴 변이 이미 `longSide` 이하면 받은 바이트를 그대로 돌려준다), private `encode(BufferedImage)` 는 `QUALITY` 로 인코딩한다
- `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentImages.java`: `LONG_SIDES = List.of(AgentImageResizer.LONG_SIDE, 1280, 1024, 768)`, `MAX_PIXELS`, `MAX_ENCODED_BYTES`. 싣는 흐름은 `embedCandidates`(단계를 고르고 내린다), `encodeAt`(한 단계에서 후보마다 주소를 만든다. 길이 합이 상한을 넘으면 null), `encodedAt(Candidate, int longSide)`(크기가 그대로면 사본을 그대로, 아니면 디코딩 차례를 얻어 `shrink`), `admitInOrder`, `place` 가 맡는다. `embedCandidates` 는 마지막 단계(768)에서만 앞에서부터 담는다
- `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentService.java`: `agentInput(Long conversationId, List<ChatAttachment> attached, String text, boolean embedImages)` 와 private static `photoGuidance(String directory, List<AgentPhoto> photos)`. 지금 `photoGuidance` 는 줄여 실었을 때 「사진이 많아 긴 변 …px 로 줄여 실었다. … vision_analyze 로 본다.」 줄과, 싣지 못한 사진의 경로 목록과 「싣지 못한 사진은 아래 경로를 답에 필요한 만큼 vision_analyze 로 확인한다.」 줄을 붙인다. `agentInput` 은 「지난 메시지의 사진은 …」 줄 뒤에 「vision_analyze 는 한 번에 한 장씩, 앞 호출의 결과를 받은 뒤 다음 사진을 부른다.」 줄을 붙인다
- `backend/src/main/java/com/bifos/assistant/chat/infra/AttachmentProperties.java` 의 `DEFAULT_MAX_FILES = 30`, `backend/src/main/resources/application.yml` 의 `max-files: 30`
- `web/src/components/chat/composer-attachment-utils.ts` 의 `export const MAX_ATTACHMENTS = 30;`, `web/src/components/chat/use-composer-attachments.ts` 216행 근처의 넘침 안내 `` `한 번에 ${MAX_ATTACHMENTS}장까지 올릴 수 있어요. ${overflowCount}장은 올리지 못했어요.` ``
- `test/browser/chat-attachment.spec.ts` 는 넘침 안내에 `30장까지` 가 들어 있는지만 본다
- `test/unit/*.test.ts` 는 `node --test` 로 돈다(`import test from "node:test"`, `import assert from "node:assert/strict"`). 예: `test/unit/fake-hermes-run-input.test.ts`
- 테스트 이름 규칙은 `backend/AGENTS.md` 「테스트 이름」, 주석은 한국어다
- 기능 커밋 뒤 `cd backend && ./gradlew spotlessApply` 결과를 따로 커밋한다. 이 phase 에서 고친 Java 파일만 대상이다

## 의도 메모

- 품질 단계는 768px 에서만 둔다. 더 큰 단계에서 품질을 낮추는 것보다 긴 변을 줄이는 쪽이 글씨 판독에 덜 해롭다(ADR 측정)
- 최악인 완전한 잡음 사진 30장도 768px 품질 0.4 면 7MB 안에 든다(ADR 측정). 그래서 「상한 안의 사진은 모두 실린다」 를 시험으로 보장할 수 있다
- 이번 메시지 사진을 도구로 보게 하지 않는 까닭은 운영에서 사본 읽기도 잘렸기 때문이다(ADR 「상한의 근거」). 싣지 못한 사진은 사용자에게 다시 올려 달라고 하게 한다
- 상한 값의 소스를 런타임 API 로 옮기지 않는다. 지금 화면은 상수를 쓰고 값은 30 하나다. 세 곳(화면 상수, 서버 기본값, 서버 설정)이 같다는 것을 단위 시험으로 묶는다

## 작업 항목

### 1. `AgentImageResizer.java`

- `static Optional<byte[]> shrink(byte[] jpeg, int longSide, float quality)` 를 더한다. 긴 변이 이미 `longSide` 이하이고 `quality` 가 `QUALITY` 와 같으면 받은 바이트를 그대로 돌려준다. 긴 변이 이하라도 `quality` 가 다르면 같은 크기로 다시 인코딩한다. 그 밖에는 지금 `shrink` 와 같다
- 기존 `shrink(byte[], int)` 는 `shrink(jpeg, longSide, QUALITY)` 에 위임한다
- private `encode(BufferedImage)` 를 `encode(BufferedImage image, float quality)` 로 바꾸고, `toJpeg` 는 `QUALITY` 를 넘긴다

### 2. `AttachmentImages.java`

- `static final List<Float> SMALLEST_QUALITIES = List.of(AgentImageResizer.QUALITY, 0.6f, 0.4f);` 를 둔다. Javadoc: 가장 작은 단계(768)에서 바이트가 넘칠 때 차례로 낮추는 JPEG 품질. 근거는 ADR 「상한의 근거」
- `encodedAt` 이 품질을 받게 한다: `encodedAt(Candidate candidate, int longSide, float quality)`. 크기가 그대로이고 `quality == AgentImageResizer.QUALITY` 일 때만 사본을 그대로 쓴다. 아니면 지금처럼 디코딩 차례를 얻어 `AgentImageResizer.shrink(copy, longSide, quality)` 를 부른다. 실패 로그에 `quality` 도 남긴다
- `encodeAt` 도 품질을 받아 넘긴다
- `embedCandidates`: 768 보다 큰 단계는 지금처럼 품질 `QUALITY` 로 모두 담기는지 본다. 768 단계에서는 `SMALLEST_QUALITIES` 를 차례로 시도하며, 마지막 품질 앞까지는 모두 담길 때만 확정한다. 마지막 품질(0.4)에서만 앞에서부터 담는다(지금의 768 처리와 같다)
- 클래스 Javadoc 과 `photos` Javadoc 에 품질 단계를 적는다

### 3. `AttachmentService.java` 의 지시문

`agentInput` 의 `embedImages` 가 참일 때와 거짓일 때 안내를 나눈다. `photoGuidance` 에 `boolean embedImages` 인자를 더한다.

- `embedImages` 참
  1. `사진은 모두 {N}장이다.\n` (그대로)
  2. 실은 사진이 있으면 `이 메시지에 이미지로 함께 실은 사진: …번째 사진. 이미 보이므로 파일로 다시 읽지 않아도 된다.\n` (그대로)
  3. 「사진이 많아 긴 변 …px 로 줄여 실었다. …」 줄을 없앤다
  4. 싣지 못한 사진이 있으면 경로 목록 대신 한 줄: `입력에 싣지 못한 사진: {순번}번째, …, {순번}번째 사진. 이 사진은 도구로 읽지 말고, 사용자에게 볼 수 없었다고 알리고 JPEG 나 PNG 로 다시 올려 달라고 한다.\n`
- `embedImages` 거짓(흐름): 지금처럼 `싣지 못한 사진은 아래 경로를 답에 필요한 만큼 vision_analyze 로 확인한다.\n` 와 사진마다 경로 줄을 적는다
- 두 경우 공통: 「지난 메시지의 사진은 …」 줄은 그대로 두고, 그 뒤의 줄을 `지난 사진을 vision_analyze 로 볼 때는 한 번에 한 장씩, 앞 호출의 결과를 받은 뒤 다음 사진을 부른다.\n` 로 바꾼다
- `agentInput` 과 `photoGuidance` 의 Javadoc 을 고친다

### 4. 화면의 넘침 안내

`web/src/components/chat/use-composer-attachments.ts` 의 넘침 안내를 `` `사진이 너무 많으면 한 번에 이해하기 어려워요. 한 번에 ${MAX_ATTACHMENTS}장까지 보내고 나머지는 나눠 보내 주세요. ${overflowCount}장은 올리지 못했어요.` `` 로 바꾼다. `30장까지` 가 그대로 들어 있어 `test/browser/chat-attachment.spec.ts` 는 고치지 않는다

### 5. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/chat/application/AgentImageResizerTest.java`
- `shrink(jpeg, longSide, quality)`: 긴 변 768 사본을 768, 품질 0.4 로 부르면 크기는 같고 바이트가 받은 것보다 작다. 품질 `QUALITY` 로 부르면 받은 바이트와 같은 내용이다

`backend/src/test/java/com/bifos/assistant/chat/application/AttachmentImagesTest.java`
- `SMALLEST_QUALITIES` 가 0.85, 0.6, 0.4 이다

`backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentTurnTest.java`
- 새 테스트: `noisePng(1600, 1200)` 을 `properties.maxFiles()` 장(30) 올려 보내면 모두 실리고(이름표 1번째부터 30번째), data URL 길이 합이 7MB 이하이며, 실린 사진의 긴 변이 768 이고, 지시문에 `입력에 싣지 못한 사진` 이 없다
- 잡음 스물네 장과 작은 사진 한 장을 보내는 기존 테스트는 이제 스물다섯 장이 모두 실린다. 「뒤의 더 작은 사본도 싣지 않는다」 를 확인하던 그 테스트를 「스물다섯 장이 모두 768px 로 실린다」 로 바꾼다. 「한 번 예산에 닿으면 뒤 사진을 싣지 않는다」 규칙은 `AttachmentImagesTest` 의 `admits` 단언이 계속 확인한다
- 열한 장 테스트에서 「사진이 많아 긴 변 1280px 로 줄여 실었다.」 단언을 지우고, 그 줄이 없다는 것을 단언한다. 긴 변 1280 단언은 그대로 둔다
- 「사진과 이미지가 아닌 파일을 함께 보내면…」 테스트의 기대를 바꾼다: `입력에 싣지 못한 사진: 2번째 사진. 이 사진은 도구로 읽지 말고, 사용자에게 볼 수 없었다고 알리고 JPEG 나 PNG 로 다시 올려 달라고 한다.\n` 가 있고, `싣지 못한 사진은 아래 경로를` 이 없다
- 「사진 서른 장을 붙이면 Hermes와 다시 생성 입력과 말풍선이 선택 순서를 지킨다」(이미지가 아닌 바이트 서른 장, 아무것도 싣지 않는다)의 기대 문자열을 새 문구로 바꾼다: 경로 목록 대신 「입력에 싣지 못한 사진: 1번째, …, 30번째 사진. …」 한 줄, 그리고 바뀐 「지난 사진을 vision_analyze 로 볼 때는 …」 줄
- 「원본도 사본도 없는 사진을 보내도 실행은 돌고 원본 경로로 안내한다」 테스트가 경로 안내를 단언하면 새 문구로 바꾼다. 위 첫 줄의 파일 목록(`- 1번째 사진: {첨부 번호}.png (올린 이름: …)`)은 그대로라 원본 이름은 입력에 남는다
- 흐름 경로(`embed` 거짓)의 안내를 확인하는 테스트가 있으면 그대로 통과해야 한다. 없으면 `ChatRegenerateTest` 나 흐름 테스트에서 `싣지 못한 사진은 아래 경로를` 이 그대로인지 하나 더한다

`test/unit/attachment-limit.test.ts` (신규)
- `web/src/components/chat/composer-attachment-utils.ts` 의 `MAX_ATTACHMENTS`, `backend/src/main/java/com/bifos/assistant/chat/infra/AttachmentProperties.java` 의 `DEFAULT_MAX_FILES`, `backend/src/main/resources/application.yml` 의 `max-files` 를 파일 글에서 정규식으로 읽어 셋이 같은 수인지 단언한다. 어느 하나를 찾지 못하면 실패한다. 파일 경로는 `import.meta.dirname` 기준으로 저장소 루트를 찾는다

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.chat.application.AttachmentImagesTest' --tests 'com.bifos.assistant.chat.application.AgentImageResizerTest' --tests 'com.bifos.assistant.chat.ChatAttachmentTurnTest' --tests 'com.bifos.assistant.chat.ChatRegenerateTest'
node --test test/unit/attachment-limit.test.ts
cd web && pnpm exec tsc --noEmit
cd backend && ./gradlew qualityCheck
git grep -n "줄여 실었다" -- backend/src/main
```

기대: 앞의 네 명령은 종료 코드 0. `qualityCheck` 는 포맷 커밋 뒤에 돌린다. 마지막 `git grep` 은 출력이 없다(종료 코드 1).

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/application/AgentImageResizer.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentImages.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/application/AgentImageResizerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/application/AttachmentImagesTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentTurnTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatRegenerateTest.java` | 수정 |
| `web/src/components/chat/use-composer-attachments.ts` | 수정 |
| `test/unit/attachment-limit.test.ts` | 신규 |
