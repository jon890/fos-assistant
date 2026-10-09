# Phase 02. 사진마다 줄인 사본 파일을 원본 옆에 두고 원본과 함께 지운다

**Execution profile**: deep

## 목표

사진을 올리면 원본 옆에 긴 변 1600px JPEG 사본 `{첨부 번호}.small.jpg` 를 만들고, 원본을 지우는 모든 경로에서 사본도 함께 지운다.
실행 입력에 싣는 일과 안내 문장은 phase 03 이 한다. 이 phase 가 끝나도 실행 요청은 바뀌지 않는다.

**범위 외**: 실행 입력에 싣기, 안내 문장, `HermesRunCommand`(phase 03). web 입력창(PR #356 영역), 실행 공간 mount 코드(`hermes/SandboxAttachmentDirectory` 와 Hermes 정책).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261009-native-image-input.md`, `docs/backend/attachment.md` 의 머리 목록과 「에이전트에게 알리는 법」 표(사본 파일, 사본을 지우는 때), 「갈리는 지점」

- 파일 저장소는 `backend/src/main/java/com/bifos/assistant/chat/infra/AttachmentStore.java` 다. 메서드는 모두 `synchronized` 다
  - `save(ChatAttachment, InputStream)` 이 `privatePath` 에 새 파일을 쓴다(`Files.copy`, 이미 있으면 실패)
  - `open(ChatAttachment)` 은 파일이 없으면 `ApiException(ErrorCode.ATTACHMENT_GONE)`, 그 밖의 I/O 실패는 `UncheckedIOException`, 링크나 이름 불일치는 `IllegalArgumentException` 을 던진다
  - `delete(ChatAttachment)` 는 `Files.deleteIfExists(privatePath(attachment))` 다
  - `privatePath` 는 `{root}/users/{userDirectoryKey}/{대화 번호}/{storedName}` 을 만들고 `checkedPath` 가 경로 조각마다 심볼릭 링크를 거절한다. `checkedStoredName` 이 `storedName()` 과 `{id}.{확장자}` 가 같은지 본다
- 지우는 경로 셋이 모두 `AttachmentStore.delete` 를 부른다: `chat/application/AttachmentService.java` 의 `deleteByUser`, `chat/application/AttachmentCleaner.java`(보관 기간), `chat/application/ConversationPurger.java`(대화 정리)
- 올리기: `chat/presentation/AttachmentController.java` 의 `upload` 가 `AttachmentService.upload(...)` 를 부르고 `view(saved)` 를 돌려준다. `AttachmentService.upload` 는 `@Transactional` 이고 그 안에서 `store.save` 한다. JPEG 면 `MpoJpegNormalizer.normalize` 를 거친 바이트(`jpeg`)를 메모리에 갖고 있다
- 화면은 고른 사진을 모두 동시에 올린다(`web/src/components/chat/use-composer-attachments.ts` 의 `uploadOne` 반복). 서른 장이면 업로드 요청 서른 개가 한꺼번에 온다. DB 연결 풀은 기본 10개다
- 디코딩 본보기는 `chat/application/MpoJpegNormalizer.java` 다(`ImageIO`, `@NoArgsConstructor(access = AccessLevel.PRIVATE)` 정적 도구). 그 시험 `backend/src/test/java/com/bifos/assistant/chat/application/MpoJpegNormalizerTest.java` 옆에 새 순수 시험을 둔다
- 실제 HTTP 로 올리는 시험 본보기는 `backend/src/test/java/com/bifos/assistant/chat/AttachmentUploadLimitTest.java` 다(`@BackendIntegrationTest`, `@LocalServerPort`, `HttpClient`, multipart 본문을 직접 만든다). 업로드 상한은 시험에서도 10MB 다
- 파일 저장소 시험은 `backend/src/test/java/com/bifos/assistant/chat/infra/AttachmentStoreIsolationTest.java` 다(`new AttachmentStore(new AttachmentProperties(root.toString(), "/images", null, null, null))`)

## 의도 메모

- 사본 이름은 `{첨부 번호}.small.jpg`, 위치는 원본과 같은 폴더다. 경로 규칙과 링크 검사는 `AttachmentStore` 하나가 갖는다. 사본 경로는 `privatePath` 의 부모 폴더에 `smallName(id)` 를 붙이고 `checkedPath` 를 지난다
- **사본 만들기는 트랜잭션 밖이다.** `AttachmentService.upload` 안에서 하지 않는다. 컨트롤러가 `upload` 가 돌려준 뒤 `AttachmentService.prepareSmall(saved)` 를 부른다. 트랜잭션 안에서 디코딩하면 동시 업로드 서른 개가 DB 연결 열 개를 디코딩 시간만큼 쥔다
- **동시 디코딩은 둘까지다.** `AttachmentImages` 가 `Semaphore(2)` 를 갖고 `tryAcquire(30, TimeUnit.SECONDS)` 로 차례를 기다린다. 30초 안에 얻지 못할 때만 만들지 않고 돌아간다(그 사진은 phase 03 의 보낼 때 경로가 다시 만든다). 한 장의 디코딩이 수십 MB 를 쓰므로 동시 업로드가 힙을 한꺼번에 쓰지 않게 한다. 트랜잭션 밖이라 기다리는 동안 쥐는 것은 요청 스레드뿐이다. 화면은 고른 사진을 모두 동시에 올리므로 바로 건너뛰면 둘을 뺀 나머지에 사본이 생기지 않는다. 기다리다 끊기면(`InterruptedException`) 인터럽트 표시를 되살리고 거짓을 돌려준다
- **사본은 최선 노력이다.** 만들지 못해도 올리기는 성공한다. `IOException` 과 `RuntimeException`(위 `open` 의 세 예외 포함)을 잡고 경고 로그에 첨부 번호와 예외 종류만 남긴다(본문, 이름 금지)
- 사본 쓰기(`saveSmall`): 같은 폴더의 임시 파일 `{첨부 번호}.small.jpg.tmp` 에 쓰고 `Files.move(tmp, target, ATOMIC_MOVE)` 한다. 실행 공간이 반쯤 쓴 사본을 읽지 않게 한다
  - **같은 `synchronized` 안에서 원본이 있는지 먼저 본다. 없으면 쓰지 않는다.** 디코딩은 lock 밖에서 돌기 때문에 그 사이 `delete` 가 끝났을 수 있다. 원본 없이 사본을 쓰면 `deleted_at` 이 적힌 행이라 다시 지울 경로가 없어 사본이 디스크에 남는다
  - 사본이 이미 있으면 다시 쓰지 않는다
- `delete` 는 원본, 사본, 남은 임시 파일을 모두 `deleteIfExists` 한다
- 줄이는 규칙(`AgentImageResizer`)
  - 픽셀 수가 `MAX_PIXELS = 60_000_000` 을 넘으면 디코딩하지 않는다. 리더의 `getWidth(0)`, `getHeight(0)` 을 `(long) width * height` 로 곱한다(`int` 곱은 넘친다). 판정은 package-private `static boolean withinPixelLimit(long width, long height, long maxPixels)` 가 갖는다
  - 디코딩은 `ImageReadParam.setSourceSubsampling(n, n, 0, 0)`, `n = max(1, 긴 변 / 1600)`(정수 나눗셈)로 줄여 읽고, `Graphics2D`(`RenderingHints.VALUE_INTERPOLATION_BILINEAR`)로 긴 변 1600 이하로 줄인다. 작은 사진은 키우지 않는다
  - EXIF 회전: JPEG 의 APP1 `Exif\0\0` 의 IFD0 에서 태그 `0x0112` 를 읽어 1~8 을 반영한다. 읽지 못하면 1
  - 결과는 `BufferedImage.TYPE_INT_RGB` 에 흰 배경을 먼저 칠하고 그린다(투명 PNG)
  - JPEG 인코딩은 `ImageWriteParam.MODE_EXPLICIT`, 품질 `0.85f`
  - 읽는 리더가 없는 형식(WebP)은 빈 값이다

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/chat/application/AgentImageResizer.java`(신규)

- package-private 정적 도구. `static Optional<byte[]> toJpeg(byte[] original)` 이 위 규칙으로 줄인 JPEG 를 낸다. 읽지 못하면 빈 값
- 상수 `LONG_SIDE = 1600`, `QUALITY = 0.85f`, `MAX_PIXELS = 60_000_000L`
- `static int orientation(byte[] jpeg)`, `static boolean withinPixelLimit(long width, long height, long maxPixels)` 를 따로 두어 시험한다

### 2. `AttachmentStore`

- `public static String smallName(Long attachmentId)` → `attachmentId + ".small.jpg"`
- `public synchronized void saveSmall(ChatAttachment attachment, byte[] jpeg)`: 위 의도 메모 규칙(원본 확인, 이미 있으면 그대로, 임시 파일과 `ATOMIC_MOVE`). I/O 실패는 임시 파일을 지우고 `UncheckedIOException`
- `public synchronized boolean hasSmall(ChatAttachment attachment)`
- `public synchronized byte[] readSmall(ChatAttachment attachment)`: 없으면 `ApiException(ErrorCode.ATTACHMENT_GONE)`, I/O 실패는 `UncheckedIOException`. `LinkOption.NOFOLLOW_LINKS` 로 읽는다
- `delete` 가 원본, 사본, 임시 파일을 지운다
- 클래스 Javadoc 의 경로 설명에 사본을 더한다

### 3. `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentImages.java`(신규, `@Component`)

- `AttachmentStore` 를 받는다. 필드 `Semaphore decoding = new Semaphore(2)`
- `public boolean prepareSmall(ChatAttachment attachment)`: 사본이 이미 있으면 참. 아니면 `tryAcquire(30, TimeUnit.SECONDS)` 실패 시 거짓. 얻으면 `store.open` 으로 원본을 읽고 `AgentImageResizer.toJpeg`, 값이 있으면 `store.saveSmall`. 끝나면 `release()`. 예외는 위 의도 메모대로 삼키고 거짓. 사본이 생겼는지(`hasSmall`)를 돌려준다
- 이 클래스는 phase 03 에서 입력에 실을 사진을 고르는 일도 맡는다. 이 phase 에서는 위 메서드만 둔다

### 4. `AttachmentService` 와 `AttachmentController`

- `AttachmentService` 에 `AttachmentImages` 필드를 더하고(`@RequiredArgsConstructor`), 트랜잭션 없는 `public void prepareSmall(ChatAttachment attachment)` 를 둔다. `images.prepareSmall(attachment)` 를 부르고 결과는 쓰지 않는다
- `AttachmentController.upload` 가 `attachments.upload(...)` 가 돌려준 뒤 `attachments.prepareSmall(saved)` 를 부르고 `view(saved)` 를 돌려준다
- `AttachmentService.upload` 의 `@Transactional` 과 본문은 바꾸지 않는다

### 5. 시험

- `backend/src/test/java/com/bifos/assistant/chat/application/AgentImageResizerTest.java`(신규, 순수 단위)
  - 4000×3000 PNG(코드에서 `BufferedImage` 로 만든다)를 주면 결과가 JPEG 로 읽히고 긴 변이 1600 이다
  - 800×600 은 키우지 않는다
  - 방향 6 인 EXIF APP1 을 넣은 4000×3000 JPEG 는 결과가 세로(1200×1600)다. EXIF 조각은 시험 안에서 바이트로 만든다
  - 투명 PNG 의 투명 화소가 흰색에 가깝다(RGB 각 240 이상)
  - 3MB 를 넘는 JPEG(4000×3000 그라데이션에 ±8 잡음, 품질 0.95 로 쓰면 약 4MB)의 사본은 긴 변 1600 이고 1MB 이하다
  - 이미지가 아닌 바이트는 빈 값이다
  - `withinPixelLimit(50000, 50000, MAX_PIXELS)` 는 거짓, `withinPixelLimit(8000, 6000, MAX_PIXELS)` 는 참이다
- `AttachmentStoreIsolationTest` 에 더한다
  - 원본을 `save` 한 뒤 `saveSmall` 하면 `hasSmall` 이 참이고 `readSmall` 이 같은 바이트다. 사본은 원본과 같은 폴더의 `{id}.small.jpg` 다
  - `delete` 가 원본과 사본을 함께 지운다
  - 원본을 지운 뒤의 `saveSmall` 은 아무것도 쓰지 않는다(`hasSmall` 거짓)
  - 사본 경로에 심볼릭 링크가 있으면 `saveSmall` 과 `readSmall` 이 거절한다(원본의 링크 시험과 같은 모양)
- `AttachmentUploadLimitTest` 에 하나를 더한다
  - 실제 HTTP 로 작은 실제 PNG 를 올리면 응답이 200 이고 그 첨부의 `{id}.small.jpg` 가 원본 옆에 있다. 이미지가 아닌 바이트를 `image/png` 로 올리면 200 이고 사본이 없다
  - 실제 PNG 여섯 장(디코딩이 겹치도록 각 2000×1500 이상, 업로드 상한 10MB 안)을 HTTP 로 동시에 올리면 여섯 모두 `{id}.small.jpg` 가 생긴다. 동시 요청 모양은 기존 `concurrentUploadsKeepTheUnboundAttachmentLimit` 을 따른다

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.chat.application.AgentImageResizerTest' --tests 'com.bifos.assistant.chat.infra.AttachmentStoreIsolationTest' --tests 'com.bifos.assistant.chat.AttachmentUploadLimitTest' --tests 'com.bifos.assistant.chat.AttachmentServiceTest' --tests 'com.bifos.assistant.chat.AttachmentCleanerTest' --tests 'com.bifos.assistant.chat.ConversationPurgerTest' --tests 'com.bifos.assistant.chat.ChatAttachmentTurnTest'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
scripts/quality.sh check
```

일곱 시험 클래스가 통과하고 품질 검사가 종료 코드 0 이다.
gradle 시험은 무겁다. 스폰 프롬프트가 알려 주는 잠금 도우미로 감싸 돌린다.
포맷은 기능 커밋 뒤 `./gradlew spotlessApply` 결과를 따로 커밋한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/application/AgentImageResizer.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentImages.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/AttachmentStore.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/AttachmentController.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/application/AgentImageResizerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/infra/AttachmentStoreIsolationTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/AttachmentUploadLimitTest.java` | 수정 |
