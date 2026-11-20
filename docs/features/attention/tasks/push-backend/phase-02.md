# Phase 02. 알림 커밋 뒤 웹 푸시 보내기

**Execution profile**: standard

## 목표

알림이 커밋되면 그 사용자의 등록 기기로 RFC 8291 암호화와 RFC 8292 VAPID 서명을 붙인 웹 푸시를 보낸다. 알림을 만든 일은 보내기를 기다리지 않는다.

**범위 외**: 서비스 워커와 화면(PR1b). 묶음 끄기와 조용한 시간(PR2). 기기 저장과 등록(phase 01).

## 컨텍스트

**요구사항**: REQ5(그 사용자의 모든 기기로 같은 제목과 본문, 남의 기기로 가지 않음), REQ7(알림을 만든 일과 떨어져 커밋 뒤 비동기), REQ8(410·404 정리, 일시 실패 다시 시도 뒤 버림, 로그에 주소와 키 없음).

**근거 문서**: `docs/features/attention/spec.md`, `docs/features/attention/design.md` 의 「보내기」, 「실패와 일관성」, `docs/adr/ADR-20261120-web-push.md`

따를 기존 패턴:

- 커밋 뒤에만 도는 동작: `notification/application/NotificationService.java` 의 `afterCommit`. 이번에는 `ApplicationEventPublisher` 로 `NotificationCreated` 를 내고 `@TransactionalEventListener(phase = AFTER_COMMIT)` 로 받는다. 되돌아간 트랜잭션은 사건이 없다
- 요청 밖 스레드: `shared/concurrent/BackgroundTasks.start(name, task)`(rule-21, rule-22). 테스트는 이 빈을 바꿔 끼운 구현이 스레드를 join 한다
- HTTP: `java.net.http.HttpClient`(예: `memory/application/OpenAiCompatibleEmbeddingClient.java`). `followRedirects(NEVER)`, 연결 제한 시간은 `PushProperties.connectTimeout`, 요청마다 `requestTimeout`
- 키: phase 01 의 `VapidKeys`

## 의도 메모

- 운영 코드가 PR 상한(1500줄)을 넘으면 phase 02 를 떼어 다음 PR 로 나눌지 코디네이터에게 묻는다. `규모:예외` 라벨은 스스로 붙이지 않는다

- 외부 웹 푸시 라이브러리를 쓰지 않는다(ADR). JDK 의 `KeyPairGenerator("EC")` P-256, `KeyAgreement("ECDH")`, `Mac("HmacSHA256")` 로 HKDF, `Cipher("AES/GCM/NoPadding")`, `Signature("SHA256withECDSAinP1363Format")`
- 다시 시도의 기다림은 `PushProperties.retryDelays` 로 받아 테스트가 짧게 준다. `Thread.sleep` 은 가상 스레드 안에서만 한다
- 보낼 때 주소를 `PushEndpointPolicy` 로 다시 검사하고, 같은 해시가 다른 사용자 줄에도 있으면 보내지 않는다(설계 「등록의 순서」 끝 문단)
- 로그는 사용자 번호, 기기 번호, 상태 코드, 시도 횟수만. 제목과 본문도 로그에 남기지 않는다

## 작업 항목

### 1. `notification/application/dto/NotificationCreated.java` 와 `NotificationService` 수정

record: `Long notificationId`, `Long userId`, `NotificationKind kind`, `String title`, `String body`, `UUID publicId`, `Instant createdAt`. `NotificationService` 생성자에 `ApplicationEventPublisher` 를 더하고 `notify(...)` 에서 저장한 뒤 사건을 낸다. `afterCommit` 의 SSE 경로는 그대로 둔다. `toString` 은 제목과 본문을 내지 않는다.

### 2. `notification/infra/WebPushEncryptor.java`

`byte[] encrypt(byte[] plaintext, byte[] uaPublic65, byte[] authSecret16)` → RFC 8291 `aes128gcm` 본문(머리: salt 16, rs 4096, idlen 65, 서버 공개 키). 테스트를 위해 서버 키 쌍과 salt 를 받는 패키지 범위 오버로드를 둔다. 평문 끝에 구분자 `0x02` 를 붙인다. 글이 3993 바이트를 넘으면 `IllegalArgumentException`.

### 3. `notification/infra/VapidSigner.java`

`String authorization(String endpoint, Instant now)` → `vapid t=<JWT>, k=<공개 키 base64url>`. JWT 머리 `{"typ":"JWT","alg":"ES256"}`, 본문 `aud`(주소의 `scheme://host`), `exp`(now + 12시간), `sub`(`VapidKeys.subject()`). 서명은 P1363 형식 64바이트.

### 4. `notification/infra/WebPushClient.java`(인터페이스)와 `HttpWebPushClient.java`

`WebPushClient` 는 `PushResponse send(String endpoint, String authorization, byte[] body)` 하나를 둔 인터페이스이고 `HttpWebPushClient` 가 운영 구현이다. `PushResponse` 는 같은 패키지의 record 다. 운영 구현은 → 상태 코드와 `Retry-After`(초, 없으면 null). 머리 `Content-Encoding: aes128gcm`, `Content-Type: application/octet-stream`, `TTL: 86400`, `Urgency: normal`. 연결 실패는 상태 0 으로 돌려준다.

### 5. `notification/application/PushDispatcher.java` 와 `PushDeviceService.removeIfUnchanged`

- `onCreated(NotificationCreated)`: `@TransactionalEventListener(phase = AFTER_COMMIT)`. 키가 꺼졌으면 바로 끝낸다. 아니면 `BackgroundTasks.start("push-" + publicId, ...)` 로 `deliver` 를 띄운다. 이 메서드 안에서 예외가 나가지 않게 한다
- `deliver`: 기기 목록을 잠그지 않고 읽고, 기기마다 정책 재검사와 겹침 검사를 한 뒤 글 `{"title","body","url":"/n/<publicId>","tag":"<publicId>"}` 를 암호화해 보낸다. 응답 처리는 설계 「보내기」 표대로: 2xx 끝, 404·410 은 `removeIfUnchanged(userId, device)`, 429·5xx·0 은 `retryDelays` 만큼 다시(429 와 503 의 `Retry-After` 는 `retryAfterMax` 안에서 따른다), 그 밖은 경고 로그
- `PushDeviceService.removeIfUnchanged(Long userId, PushDevice sent)`: 잠금 안 트랜잭션에서 보낼 때 읽은 기기와 번호와 `registeredAt` 이 같을 때만 뺀다. 그 사이 다시 등록한 기기는 지우지 않는다(rule-68)

### 6. 문서

`docs/features/attention.md` 의 「웹 푸시」 소절에 보내기(커밋 뒤, 같은 글, 실패 처리, 키가 없으면 꺼짐)를 한두 줄로 더하고 계약은 설계를 가리킨다. 「갈리는 지점(알림)」 표에 「푸시 서비스가 실패한다」 한 줄을 더한다.

### 7. 테스트

- `notification/infra/WebPushEncryptorTest`(패키지 범위 오버로드를 부르려고 infra 패키지에 둔다): RFC 8291 부록 A 의 값(사용자 에이전트 공개 키, auth secret, 서버 키 쌍, salt, 평문 「When I grow up, I want to be a watermelon」)으로 같은 본문을 만든다. 예시 값은 RFC 원문에서 그대로 옮긴다
- `notification/VapidSignerTest`: 머리 모양, `aud` 가 주소의 출처, `exp` 가 12시간 뒤, 서명이 공개 키로 검증된다
- `notification/WebPushClientTest`: JDK `HttpServer`(http, 127.0.0.1)로 머리(`TTL`, `Content-Encoding`, `Urgency`, `Authorization`)와 본문이 그대로 가는지, 302 를 따르지 않는지, `Retry-After` 를 읽는지, 연결 실패가 상태 0 인지 본다. 클라이언트는 주소 정책을 보지 않으므로 http 로 띄울 수 있다
- 대역: `testsupport/RecordingWebPushClient.java` 를 `IntegrationTestDoubles` 에 `@Primary` 빈으로 올린다. 받은 주소·머리·본문을 모으고, 정해 두지 않으면 네트워크 없이 201 을 돌려준다. 검사가 상태 코드 목록과 지연(`CountDownLatch` 로 검사 안에서 풀기)을 정하고, `IntegrationTestIsolation` 이 검사마다 되돌릴 수 있게 `reset()` 을 둔다(검사 클래스 안에 빈 설정을 두지 않는다, rule-17). 지연은 검사 안에서 반드시 풀어 공통 확장의 join 상한(30초)에 걸리지 않게 한다
- `notification/PushDispatcherTest`(`@BackendIntegrationTest`, 기기 주소는 허용 호스트의 `https` 주소로 등록한다. 기기 키는 검사 안에서 만든 P-256 키 쌍과 임의 auth 16바이트): 201 이면 한 번 보냄과 머리(`TTL`, `Content-Encoding`, `Authorization` 이 `vapid t=`), 받은 본문을 검사의 기기 비밀 키와 auth 로 RFC 8291 대로 풀면 제목·본문·`url` `/n/<공개 식별자>` 가 나옴(풀기 함수는 검사 안에 둔다), 410 이면 기기가 목록에서 빠짐, 500 이면 1 + `retryDelays` 횟수만 보냄, 다른 사용자 기기로는 가지 않음, 같은 해시가 다른 사용자에게도 있으면 보내지 않음, 허용 밖 주소가 저장된 기기(검사가 `PushDeviceRepository.storeDevices` 로 JSON 을 직접 쓴다)는 보내지 않음, 410·500 경로의 로그(`OutputCaptureExtension`)에 기기 주소와 키가 없음, 알림 트랜잭션이 되돌아가면 보내지 않음, 가짜 서버가 응답을 늦춰도 알림을 만든 서비스 호출이 바로 커밋되고 돌아옴, 키가 꺼졌으면 아무것도 보내지 않음
- 기존 `NotificationServiceTest` 가 그대로 통과하는지 함께 돌린다

## 검증

```bash
cd backend && ./gradlew test --tests '*WebPushEncryptorTest' --tests '*VapidSignerTest' --tests '*PushDispatcherTest' --tests '*WebPushClientTest' --tests '*NotificationServiceTest' --tests '*Push*Test'
cd backend && ./gradlew archTest
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
cd backend && ./gradlew qualityCheck
node --test test/repo/doc-links.test.ts test/repo/doc-files.test.ts
scripts/dev/check-local.sh --fast
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/notification/application/dto/NotificationCreated.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/application/NotificationService.java` | 수정 (사건 내기) |
| `backend/src/main/java/com/bifos/assistant/notification/infra/WebPushEncryptor.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/infra/VapidSigner.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/infra/WebPushClient.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/application/PushDispatcher.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/application/PushDeviceService.java` | 수정 (`removeIfUnchanged`) |
| `backend/src/test/java/com/bifos/assistant/notification/infra/WebPushEncryptorTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/notification/VapidSignerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/notification/PushDispatcherTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/notification/WebPushClientTest.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/infra/HttpWebPushClient.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/infra/PushResponse.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/testsupport/RecordingWebPushClient.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/testsupport/IntegrationTestDoubles.java` | 수정 (대역 등록) |
| `backend/src/test/java/com/bifos/assistant/testsupport/IntegrationTestIsolation.java` | 수정 (대역 되돌리기) |
| `backend/src/test/java/com/bifos/assistant/notification/NotificationServiceTest.java` | 수정 (생성자에 사건 발행기를 넘긴다) |
| `docs/features/attention.md` | 수정 |
