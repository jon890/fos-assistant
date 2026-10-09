# Phase 01. 사본을 원하는 긴 변으로 다시 줄이는 함수

**Execution profile**: standard

## 목표

`AgentImageResizer` 에 긴 변 1600px 사본(JPEG)을 더 작은 긴 변으로 다시 줄이는 함수와, 줄였을 때의 크기를 디코딩 없이 계산하는 함수를 더한다.
다음 phase 가 사진이 많을 때 모든 사진을 같은 크기 단계로 줄여 싣는 데 쓴다.

**범위 외**: 실을 사진과 단계를 고르는 일(`AttachmentImages`)과 지시문(`AttachmentService`)은 phase 02 가 맡는다.

## 컨텍스트

**근거 문서**: `backend/docs/adr/ADR-20261009-native-image-input.md` 의 「결정」 표(「한 턴에 싣는 양」, 「줄인 단계」)와 「상한의 근거」, `docs/backend/attachment.md` 의 「에이전트에게 알리는 법」 표

- 대상 파일: `backend/src/main/java/com/bifos/assistant/chat/application/AgentImageResizer.java` (package-private `final class`, Lombok `@NoArgsConstructor(access = AccessLevel.PRIVATE)`)
  - 지금 상수: `LONG_SIDE = 1600`, `QUALITY = 0.85f`, `MAX_PIXELS = 60_000_000L`
  - 지금 함수: `static Optional<byte[]> toJpeg(byte[] original)`, `static boolean withinPixelLimit(long, long, long)`, `static int orientation(byte[] jpeg)`, private `decode(byte[])`, `draw(BufferedImage, int orientation)`, `encode(BufferedImage)`
  - `draw` 는 긴 변이 `LONG_SIDE` 를 넘으면 긴 변을 `LONG_SIDE` 로 두고 짧은 변을 `Math.max(1, (int) Math.round((double) 짧은 변 * LONG_SIDE / 긴 변))` 로 정한다. 가로와 세로가 같으면 가로를 긴 변으로 본다
- 사본 파일은 `toJpeg` 가 만든 JPEG 라 EXIF 가 없고 이미 바로 서 있다. 다시 줄일 때 방향을 읽지 않는다(방향 1)
- 테스트: `backend/src/test/java/com/bifos/assistant/chat/application/AgentImageResizerTest.java`. 같은 파일의 기존 테스트 모양(PNG 를 만들어 `toJpeg` 로 줄이고 `ImageIO.read` 로 크기를 확인)을 따른다
- 테스트 이름 규칙: 메서드는 영문 동사로 시작하고 `@DisplayName` 에 한국어 문장을 단다(`backend/AGENTS.md` 「테스트 이름」)
- 주석과 Javadoc 은 한국어로 쓴다

## 의도 메모

- 크기 계산(`scaledSize`)을 `draw` 와 한 곳에서 쓴다. 단계를 고를 때 계산한 화소와 실제로 줄인 사진의 화소가 어긋나면 예산을 넘을 수 있다
- 새 record 를 만들지 않고 `java.awt.Dimension` 을 쓴다. `application` 패키지는 타입 하나에 파일 하나라 작은 값 하나에 파일을 늘리지 않는다
- 원본을 받는 `toJpeg` 의 동작은 바꾸지 않는다(1600px, EXIF 회전, subsampling)

## 작업 항목

### 1. `AgentImageResizer.java` 에 함수 셋을 더한다

- `static Dimension scaledSize(int width, int height, int longSide)`: 긴 변이 `longSide` 를 넘으면 위의 `draw` 규칙과 같은 식으로 줄인 크기, 넘지 않으면 그대로. `draw` 가 이 함수를 쓰도록 바꾼다(`LONG_SIDE` 를 넘긴다)
- `static Optional<Dimension> dimensions(byte[] image)`: `ImageIO` 리더로 머리의 가로와 세로만 읽는다. 디코딩하지 않는다. 리더가 없거나 읽지 못하면 빈 값
- `static Optional<byte[]> shrink(byte[] jpeg, int longSide)`: 사본 JPEG 를 디코딩해 `scaledSize(…, longSide)` 크기로 흰 바탕에 그리고 `QUALITY` 로 인코딩한다. 긴 변이 이미 `longSide` 이하면 받은 바이트를 그대로 돌려준다. 방향은 1 로 본다. 디코딩, 인코딩 실패나 `IOException`, `RuntimeException` 이면 빈 값
- `draw` 의 크기 계산을 `scaledSize` 로 바꿀 때 기존 `toJpeg` 결과가 같아야 한다(아래 기존 테스트가 확인한다)

### 2. 이 phase 를 검증하는 `AgentImageResizerTest.java`

- `scaledSize`: 1600×1200 을 768 로 → 768×576, 1200×1600 을 1024 로 → 768×1024, 800×600 을 1024 로 → 800×600(키우지 않음), 3000×1 을 768 로 → 768×1(짧은 변 최소 1)
- `dimensions`: `toJpeg` 로 만든 사본에서 가로와 세로를 읽는다. 이미지가 아닌 바이트는 빈 값
- `shrink`: 1600×1200 사본을 1024 로 줄이면 `ImageIO.read` 결과가 1024×768 이다. 긴 변 768 사본을 1024 로 부르면 받은 바이트와 같은 배열 내용을 돌려준다. 이미지가 아닌 바이트는 빈 값
- 기존 `toJpeg` 테스트가 그대로 통과한다

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.chat.application.AgentImageResizerTest'
cd backend && ./gradlew checkstyleMain checkstyleTest
```

기대: 두 명령 모두 종료 코드 0.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/application/AgentImageResizer.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/application/AgentImageResizerTest.java` | 수정 |
