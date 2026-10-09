# Phase 02. 화소 예산과 크기 단계로 모든 사진을 싣고 지시문을 바꾼다

**Execution profile**: deep

## 목표

보내는 메시지의 사진을 고정 10장 대신 화소 합계 예산과 base64 합계 예산 안에서 모두 싣는다.
사진이 많으면 모든 사진을 같은 크기 단계(긴 변 1600, 1280, 1024, 768px)로 줄인다.
지시문에 줄여 실은 긴 변과 「`vision_analyze` 는 한 장씩 차례로」 를 더한다.

**범위 외**: 사본을 줄이는 함수는 phase 01 이 만들었다(`AgentImageResizer.scaledSize`, `dimensions`, `shrink`). 흐름이 붙은 에이전트(`embed` 거짓) 경로는 바꾸지 않는다.

## 컨텍스트

**근거 문서**: `backend/docs/adr/ADR-20261009-native-image-input.md` 의 「결정」 표와 「상한의 근거」, `docs/backend/attachment.md` 의 「에이전트에게 알리는 법」 과 「갈리는 지점」. 이 phase 의 동작과 문구는 그 두 문서와 같아야 한다

- 대상 코드
  - `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentImages.java`: `photos(List<ChatAttachment> attached, Map<Long, Integer> order, boolean embed)` 가 사진마다 `AgentPhoto` 를 만든다. 지금 상수는 `MAX_IMAGES = 10`, `MAX_ENCODED_BYTES = 7L * 1024 * 1024`, `SEND_WAIT_SECONDS = 5`, `DECODING_SLOTS = 2`. 디코딩 차례는 `Semaphore decoding` 이다. 사본은 `store.readSmall(attachment)`(byte[])로 읽고, 경로 이름은 `AttachmentStore.smallName(id)` 다
  - `backend/src/main/java/com/bifos/assistant/chat/application/AgentPhoto.java`: `record AgentPhoto(Long attachmentId, int ordinal, String agentFileName, String dataUrl)` 와 `embedded()`. 생성하는 곳은 `AttachmentImages` 하나다
  - `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentService.java`: `agentInput(...)` 과 `photoGuidance(String directory, List<AgentPhoto> photos)` 가 지시문을 만든다
  - `AgentImageResizer.LONG_SIDE` 는 1600 이다
- 테스트
  - `backend/src/test/java/com/bifos/assistant/chat/application/AttachmentImagesTest.java`: 지금은 `admits` 와 `MAX_IMAGES` 를 단언한다
  - `backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentTurnTest.java`: 지시문 전체 문자열과 실린 이미지 이름표를 단언한다. 도우미 `upload`(이미지가 아닌 바이트), `uploadPng(user, conversationId, name, bytes)`, `png(w, h)`, `noisePng(w, h)`, `agentDirectory(conversationId)` 가 있다
- 테스트 이름 규칙은 `backend/AGENTS.md` 「테스트 이름」, 주석은 한국어다
- 기능 커밋과 포맷 커밋을 나눈다. `cd backend && ./gradlew spotlessApply` 결과는 따로 커밋한다(`backend/AGENTS.md` 「포맷」). phase 01 이 포맷 커밋을 이미 했으므로 이 phase 의 포맷 대상은 이 phase 가 고친 파일뿐이다

## 의도 메모

- 사본 URL 로 넘기는 방식은 접었다. 까닭은 ADR 「대안 기각」 에 있다. 이 phase 에서 공개 경로를 만들지 않는다
- 화소 예산 값 19,200,000 은 개정 전 상한(1600×1200 열 장)과 같다. 도구 호출 한 번의 이미지 토큰이 개정 전 최대보다 커지지 않게 하려는 값이다
- 모든 사진을 같은 단계로 싣는다. 앞 사진만 크게 싣는 방식은 기각했다(ADR)
- 한 번 예산에 닿으면 그 뒤 사진은 더 작아도 싣지 않는다. 화면 순서와 실린 순서를 같게 두는 기존 규칙이다
- 1600 단계에서는 사본 바이트를 그대로 싣는다. 더 작은 단계에서만 `shrink` 로 다시 줄인다. 다시 줄인 결과는 파일로 남기지 않는다
- `vision_analyze` 를 한 장씩 부르라는 줄은 사진이 있을 때 늘 붙인다. 지난 메시지의 사진도 같은 도구로 보기 때문이다

## 작업 항목

### 1. `AgentPhoto.java`

- 마지막 칸 `int longSide` 를 더한다. 실행 입력에 실은 사진의 단계(1600, 1280, 1024, 768)이고, 싣지 않았으면 0 이다. Javadoc `@param` 을 더한다

### 2. `AttachmentImages.java`

- `MAX_IMAGES` 를 지우고 다음을 둔다
  - `static final long MAX_PIXELS = 19_200_000L;` Javadoc: 한 턴에 싣는 화소 합 상한. 1600×1200 열 장. 근거는 ADR 「상한의 근거」
  - `static final List<Integer> LONG_SIDES = List.of(AgentImageResizer.LONG_SIDE, 1280, 1024, 768);`
  - `MAX_ENCODED_BYTES` 는 그대로 7MB
- 순수 함수(테스트 대상)
  - `static long pixelsAt(Dimension size, int longSide)`: `AgentImageResizer.scaledSize(size.width, size.height, longSide)` 의 가로×세로를 `long` 으로 곱한다
  - `static int chooseLongSide(List<Dimension> sizes, long maxPixels)`: `LONG_SIDES` 를 큰 것부터 보며 `sizes` 의 `pixelsAt` 합이 `maxPixels` 이하인 첫 단계를 돌려준다. 없으면 마지막 단계(768)다. 빈 목록이면 1600 이다
  - `admits` 를 `static boolean admits(long acceptedPixels, long acceptedEncodedBytes, long nextPixels, long nextEncodedBytes, long maxPixels, long maxEncodedBytes)` 로 바꾼다. 두 합이 모두 상한 이하일 때만 참이다
- `photos(...)` 의 `embed` 참 경로
  1. 첨부 순서대로 지금처럼 순번을 확인하고 `prepareSmall(attachment, SEND_WAIT_SECONDS)` 로 사본을 준비한다. 사본이 있으면 `store.readSmall` 로 바이트를, `AgentImageResizer.dimensions` 로 크기를 읽는다. 읽기 실패나 크기를 모르면 그 사진은 후보에서 빠진다(읽기 실패면 지금처럼 원본 이름으로 안내하고, 크기만 모르면 사본 이름으로 안내한다)
  2. 후보의 크기 목록으로 `chooseLongSide(sizes, MAX_PIXELS)` 를 고른다
  3. 고른 단계부터 `LONG_SIDES` 의 끝까지 차례로 시도한다. 한 단계에서 후보마다 data URL 을 만든다. `scaledSize(크기, 단계)` 가 사본 크기와 같으면(1600 단계이거나 사본이 이미 그 단계 이하) 디코딩 차례 없이 사본 바이트를 그대로 싣는다. 줄여야 하면 `decoding.tryAcquire(SEND_WAIT_SECONDS, TimeUnit.SECONDS)` 로 차례를 얻은 뒤 `shrink(사본 바이트, 단계)` 를 부르고 `finally` 에서 놓는다. 차례를 얻지 못했거나 빈 값이면 그 사진은 이 단계에서 data URL 이 없다. `InterruptedException` 은 `prepareSmall` 처럼 interrupt 를 복원하고 그 사진을 실패로 본다
  4. 그 단계에서 실을 수 있는 후보의 화소 합이 `MAX_PIXELS` 이하이고 data URL 길이 합이 `MAX_ENCODED_BYTES` 이하면 그 단계로 확정한다. 아니고 마지막 단계가 아니면 다음 단계로 내려가 다시 만든다
  5. 마지막 단계(768)에서도 넘치면 앞에서부터 `admits` 로 담는다. data URL 이 없는 사진은 건너뛰고 담기를 이어 간다. 멈추는 것은 `admits` 가 거짓일 때뿐이고, 그 뒤 사진은 더 작아도 싣지 않는다
  6. 실은 사진은 `AgentPhoto(..., dataUrl, 단계)`, 싣지 않은 사진은 `dataUrl` null 과 `longSide` 0 이다. `agentFileName` 규칙(사본이 있으면 `smallName`, 없으면 `storedName`)은 그대로다
- 단계마다 data URL 을 만드는 일과 앞에서부터 담는 일은 도우미 메서드로 나눈다. `photos()` 한 메서드에 모두 넣으면 Checkstyle `MethodLength`(60행) 경고에 걸린다
- `embed` 거짓 경로는 바꾸지 않는다(`longSide` 0)
- 실패 로그는 지금처럼 첨부 번호와 예외 종류만 남긴다. 사진 본문을 남기지 않는다
- 클래스와 `photos` 의 Javadoc 을 새 규칙으로 고친다

### 3. `AttachmentService.java` 의 지시문

`photoGuidance` 와 `agentInput` 의 문구를 아래로 맞춘다. 줄 순서도 이대로다.

1. `사진은 모두 {N}장이다.\n` (그대로)
2. 실은 사진이 있으면 `이 메시지에 이미지로 함께 실은 사진: {순번}번째, …, {순번}번째 사진. 이미 보이므로 파일로 다시 읽지 않아도 된다.\n` (그대로)
3. 실은 사진의 단계가 1600 보다 작으면(1600 단계에서는 긴 변을 적지 않고 이 줄도 없다) `사진이 많아 긴 변 {단계}px 로 줄여 실었다. 작은 글씨나 세부가 필요한 사진만 같은 폴더의 {첨부 번호}.small.jpg 를 vision_analyze 로 본다.\n` (`{첨부 번호}` 는 글자 그대로 둔다)
4. 싣지 못한 사진 목록 줄(그대로)
5. `지난 메시지의 사진은 같은 폴더의 {첨부 번호}.small.jpg 를, 없으면 원본을 vision_analyze 로 본다. read_file 로 읽지 않는다.\n` (그대로)
6. 새 줄 `vision_analyze 는 한 번에 한 장씩, 앞 호출의 결과를 받은 뒤 다음 사진을 부른다.\n`
7. 원본 파일 줄과 순번 줄(그대로)

`agentInput` 의 Javadoc 에서 「상한 밖」 설명을 새 규칙으로 고친다.

### 4. 이 phase 를 검증하는 테스트

`AttachmentImagesTest.java`
- `MAX_IMAGES` 단언을 지우고 `MAX_PIXELS` 가 19,200,000, `MAX_ENCODED_BYTES` 가 7MB, `LONG_SIDES` 가 1600, 1280, 1024, 768 임을 단언한다
- `chooseLongSide`: 4:3 1600×1200 열 장 → 1600, 열한 장 → 1280, 열여섯 장 → 1024, 서른 장 → 768, 1200×1600 세로 사진 열한 장 → 1280, 768 에서도 넘치는 목록(1600×1200 마흔다섯 장) → 768, 빈 목록 → 1600
- `admits`: 화소 합이 상한을 넘으면 거짓, 바이트 합이 넘으면 거짓, 둘 다 상한과 같으면 참

`ChatAttachmentTurnTest.java` (기존 테스트를 새 동작으로 고친다)
- 「사진 서른 장을 보내면 앞의 열 장만 싣고…」 → 작은 PNG(`png(8, 6)`) 서른 장을 보내면 서른 장 모두 이름표 순서대로 실리고, 사본 경로 줄(`.small.jpg` 로 끝나는 `- ` 줄)이 없고, 줄인 단계 줄이 없다
- 새 테스트: 1600×1200 단색 PNG 열한 장을 보내면 열한 장 모두 실리고, 각 data URL 을 디코딩한 크기가 1280×960 이며, 지시문에 `사진이 많아 긴 변 1280px 로 줄여 실었다.` 가 있다. 기존 `png(1600, 1200)` 이 단색(검정) `TYPE_INT_RGB` 라 그대로 쓴다
- 「사본 합이 7MB 에 닿으면…」(잡음 다섯 장) → 다섯 장 모두 실리고, data URL 길이 합이 7MB 이하이며, 실린 사진의 긴 변이 1280 이다(1600 에서 약 9.7MB 라 넘치고 1280 에서 약 4.9MB)
- 「한 번 바이트 상한에 닿으면 뒤의 더 작은 사본도 싣지 않는다」 → 768 단계에서도 넘치게 만든다. `noisePng(1600, 1200)` 스물네 장 뒤에 `png(8, 6)` 한 장을 붙여 스물다섯 장을 보낸다. 잡음 한 장의 768 data URL 은 약 342KB 라 스물두 장부터 7MB 를 넘고, 앞의 약 스물한 장이 실린다(이 값은 주석에만 적는다). 단언: 실린 사진은 1번째부터 이어진 앞부분이다. 실린 장수는 25보다 적다. data URL 길이 합은 7MB 이하다. 실린 사진의 긴 변은 768 이다. 25번째 사진은 싣지 않고 사본 경로로 안내한다
- 「사진 서른 장을 붙이면 Hermes와 다시 생성 입력과 말풍선이 선택 순서를 지킨다」 의 기대 문자열에 위 6번 줄을 「지난 메시지의 사진은…」 줄 바로 뒤에 더한다
- 「사진과 이미지가 아닌 파일을 함께 보내면…」 은 그대로 통과해야 한다(작은 사진 하나는 1600 단계라 줄인 단계 줄이 없다)

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.chat.application.AttachmentImagesTest' --tests 'com.bifos.assistant.chat.application.AgentImageResizerTest' --tests 'com.bifos.assistant.chat.ChatAttachmentTurnTest' --tests 'com.bifos.assistant.chat.ChatRegenerateTest'
cd backend && ./gradlew qualityCheck
git grep -n "MAX_IMAGES" -- backend/src
```

기대: 앞의 두 명령은 종료 코드 0. 마지막 `git grep` 은 출력이 없다(종료 코드 1). `qualityCheck` 는 포맷 커밋 뒤에 돌린다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/application/AgentPhoto.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentImages.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/application/AttachmentImagesTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentTurnTest.java` | 수정 |
