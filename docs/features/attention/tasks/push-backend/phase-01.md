# Phase 01. 기기 저장과 등록 API, 서명 키 읽기

**Execution profile**: standard

## 목표

사용자가 브라우저 구독(기기)을 등록하고 해제하는 Control Plane API 와 그 저장을 만든다. 다음 phase 의 보내기가 이 저장을 읽는다.

**범위 외**: 실제 보내기, 암호화, VAPID 서명(phase 02). 화면과 웹 경로(PR1b). 기기 이름 고치기·번호로 지우기·묶음·조용한 시간(PR2).

## 컨텍스트

**요구사항**: REQ1(등록과 중복 없음, 서버에서 지운 기기 판정에 쓰는 해시), REQ9(로그아웃 해제 API, 다른 사용자에게 옮기기, 접근 해제 때 비우기). REQ1 의 화면 동작은 PR1b 몫이고 이 phase 는 그 API 를 만든다.

**근거 문서**: `docs/features/attention/spec.md`, `docs/features/attention/design.md` 의 「저장」, 「Control Plane API」, 「등록의 순서」, 「설정」, `docs/adr/ADR-20261120-web-push.md`

따를 기존 패턴:

- `app_user` 컬럼을 전용 쿼리로 읽고 쓰기: `backend/src/main/java/com/bifos/assistant/connector/financial/infra/PurchaseLimitRepository.java`. `AppUser` 엔티티에는 구매 상한 컬럼처럼 `insertable = false, updatable = false` 로 매핑만 한다(`user/domain/AppUser.java:75`). 테스트 DB 는 `application-test.yml` 의 `ddl-auto: create-drop` 으로 엔티티에서 스키마를 만들므로 매핑이 없으면 H2 에 컬럼이 없다(rule-50)
- 접근 해제 사건 받기: `backend/src/main/java/com/bifos/assistant/browser/application/UserBrowserSweeper.java` 의 `onAccessRevoked`. `@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)` 와 `@Transactional(propagation = NOT_SUPPORTED)` 를 함께 단다. 사건은 `PersonAccessService.setEnabled` 의 트랜잭션 안에서 나온다
- 비밀 키 파일 읽기와 기동 검사: `backend/src/main/java/com/bifos/assistant/crypto/config/DataEncryptionProperties.java`(properties 는 함께 있음만 검사), `crypto/infra/FileKeyEncryptionKeys.java`(파일 해석은 infra 빈)
- 컨트롤러와 현재 사용자: `notification/presentation/NotificationController.java`(`CurrentUserProvider.require()`)
- 해시: `shared/util/Sha256.hex`(64자 소문자 hex, rule-20). PR1b 화면이 같은 값(SHA-256 hex)을 계산해 비교하므로 `hex16` 을 쓰지 않는다
- 통합 테스트 설정: `testsupport/BackendIntegrationTest.java`(공유 컨텍스트, rule-17. 검사 클래스 안에 빈 설정을 두지 않는다), `src/test/resources/application-test.yml`, 키 파일 예시 `src/test/resources/data-encryption/test-kek.keys`
- 마이그레이션 번호는 예약해 받았다: `backend/src/main/resources/db/migration/V20261105074427__app_user_push_devices.sql`. 다른 번호를 고르지 않는다

## 의도 메모

- 새 테이블을 만들지 않는다(사용자 결정 2026-11-20). 기기는 `app_user.push_devices` JSON 글이다
- 하위 패키지 `notification.push` 를 만들지 않는다. 기존 `notification` 의 역할 패키지에 `Push*` 클래스를 둔다(ADR-20261015 / package-layout)
- 동시 등록은 프로세스 안 잠금(`ReentrantLock` 하나)으로 하나씩 돌린다. 잠그지 않은 `LIKE` 만으로는 같은 주소가 두 사용자에게 남는다. 잠금은 트랜잭션을 열기 전에 잡고 커밋 뒤에 놓는다. 그래서 등록 메서드는 잠금을 잡은 뒤 `TransactionTemplate` 으로 트랜잭션을 연다
- 칸을 바꾸는 전용 UPDATE 는 `updated_at` 을 바꾸지 않는다(설계 「저장」)
- 주소와 `p256dh`, `auth` 원문은 응답과 로그에 내지 않는다

## 작업 항목

### 1. 마이그레이션 `V20261105074427__app_user_push_devices.sql`

`ALTER TABLE app_user ADD COLUMN push_devices TEXT NULL COMMENT '...'`. COMMENT 는 한국어로 「웹 푸시 기기 목록 JSON(version 1). 푸시 서비스 주소와 그 해시, 암호화 공개 키, 인증 값, 이름, 시간대, 등록 시각. NULL 이면 기기가 없다. 주소와 인증 값은 운영 조회 계정에 주지 않는다」 뜻으로 쓴다. 머리 주석에 ADR-20261120 / web-push 를 적는다.

### 2. `notification/config/PushProperties.java`

`@ConfigurationProperties("assistant.push")`, `@Validated`(rule-24). 칸: `vapidPublicKey`(String), `vapidPrivateKeyFile`(String. 빈 문자열을 null 로 보지 않는 Path 바인딩을 피해 `DataEncryptionProperties` 처럼 strip 한 뒤 비었는지 본다), `subject`(String), `allowedEndpointHosts`(List<String>, 기본 `fcm.googleapis.com`, `updates.push.services.mozilla.com`, `web.push.apple.com`, `.notify.windows.com`), `connectTimeout`(기본 5s), `requestTimeout`(기본 10s), `retryDelays`(List<Duration>, 기본 1s, 4s), `retryAfterMax`(기본 30s), `maxDevices`(기본 10). 검사: 공개 키, 비밀 키 파일, subject 가 strip 한 뒤 셋 다 있거나 셋 다 없다.
`application.yml` 의 `assistant` 아래 `push:` 블록을 더하고 키 셋을 `${ASSISTANT_PUSH_VAPID_PUBLIC_KEY:}`, `${ASSISTANT_PUSH_VAPID_PRIVATE_KEY_FILE:}`, `${ASSISTANT_PUSH_SUBJECT:}` 로 받는다. 값을 저장소에 적지 않는다.

### 3. `user/domain/AppUser.java` 매핑

`@Column(name = "push_devices", columnDefinition = "TEXT", insertable = false, updatable = false) private String pushDevices;` 를 더한다. 읽는 메서드는 두지 않는다(읽기는 전용 쿼리). 주석에 ADR-20261120 / web-push 와 전용 쿼리로만 쓴다는 뜻을 적는다.

### 3-1. `notification/infra/VapidKeys.java`

빈. 기동 때 키가 설정돼 있으면 비밀 키 파일(base64url 32바이트 한 줄)을 읽어 P-256 `ECPrivateKey` 로, 공개 키(base64url 비압축 65바이트)를 `ECPublicKey` 로 해석하고, 비밀 키에서 계산한 공개 점이 공개 키와 같은지 본다. 실패하면 예외로 기동을 멈춘다. 메시지에 키 값과 파일 내용을 넣지 않는다. `enabled()`, `publicKeyBase64Url()`, `privateKey()`, `publicKey()`, `subject()` 를 준다. 설정이 없으면 `enabled()` 가 거짓이다.

### 4. `notification/domain/PushDevice.java`, `PushDevices.java`, `PushEndpointPolicy.java`

- `PushDevice`(record): `UUID id`, `String endpoint`, `String endpointHash`, `String p256dh`, `String auth`, `String name`, `String timeZone`, `Instant registeredAt`. `toString` 은 주소와 키를 내지 않는다
- `PushDevices`: 불변 목록. `upsert(PushDevice, int max)`(같은 `endpointHash` 면 키·이름·시간대·`registeredAt` 을 바꾸고 `id` 는 유지, 없으면 더하고 `max` 를 넘으면 `registeredAt` 이 가장 이른 것을 뺀다), `removeByHash`, `removeById`, `containsHash`, `newestFirst()`
- `PushEndpointPolicy`: 허용 호스트 목록을 받아 `check(String endpoint)` 가 통과 여부를 낸다. 규칙: `URI` 로 해석, 스킴 `https`, 포트 없음이나 443, userinfo 없음, 호스트가 IP 리터럴(IPv4 점 넷, `[` 로 시작하는 IPv6)이 아님, 호스트가 항목과 정확히 같거나 항목이 `.` 으로 시작하면 그 문자열로 끝남. 대소문자 무시. 주소 길이 상한 1024

### 5. `notification/infra/PushDevicesCodec.java`, `PushDeviceRepository.java`

- 코덱: `{"version":1,"devices":[...]}` 를 Jackson(`tools.jackson`)으로 읽고 쓴다. 칸이 비면 빈 목록, 모르는 버전이나 깨진 JSON 은 `IllegalStateException`
- 해시는 `Sha256.hex(endpoint)` 다
- 저장소(`Repository<AppUser, Long>`, 전용 쿼리): `lockUser(userId)`(native `SELECT id FROM app_user WHERE id = :id FOR UPDATE`), `findDevicesJson(userId)`, `findUserIdsHoldingHash(hash)`(native `SELECT id FROM app_user WHERE push_devices LIKE CONCAT('%', :hash, '%') ORDER BY id`), `storeDevices(userId, json)`(native `UPDATE app_user SET push_devices = :json WHERE id = :id`, `updated_at` 은 건드리지 않는다)

### 6. `notification/application/PushDeviceService.java`, `PushRegistrationLock.java`, `PushAccessRevokedListener.java`

- `PushRegistrationLock`: `ReentrantLock` 하나를 가진 빈. `run(Supplier<T>)`
- `notification/application/dto/PushRegistration.java`(record: `endpoint`, `p256dh`, `auth`, `name`, `timeZone`. rule-70 에 따라 `application/dto`)
- `PushDeviceService.register(CurrentUser, PushRegistration)`: 키가 없으면 `PUSH_NOT_CONFIGURED`. 정책과 길이(이름은 1자부터 40자까지, `p256dh` 는 200자까지, `auth` 는 100자까지, 시간대는 `ZoneId.of` 로 해석되는 값) 검사 실패는 `VALIDATION_FAILED`. 잠금 안에서 트랜잭션을 열고 설계 「등록의 순서」 의 세 단계를 한다. 잠금 순서는 사용자 번호 오름차순. 돌려주는 값은 등록한 기기
- `list(CurrentUser)`: 최근 등록 순
- `unregisterCurrent(CurrentUser, String endpoint)`: 같은 해시면 빼고 없으면 아무것도 하지 않는다. 잠금 안에서 돈다
- `clearAll(Long userId)`: 잠금 안에서 새 트랜잭션(`TransactionTemplate`, `PROPAGATION_REQUIRES_NEW`)을 열어 칸을 비운다
- 잠금 안에서 여는 트랜잭션은 모두 `PROPAGATION_REQUIRES_NEW` 다. 커밋이 끝난 바깥 트랜잭션에 붙지 않게 하려는 것이다
- `PushAccessRevokedListener.onAccessRevoked(UserAccessRevoked)`: `UserBrowserSweeper` 와 같은 두 어노테이션으로 `clearAll` 을 부르고 예외는 로그만 남긴다

`shared/error/ErrorCode.java` 에 `PUSH_NOT_CONFIGURED(HttpStatus.CONFLICT)` 를 더한다.

### 7. `notification/presentation/PushController.java`, `PushDtos.java`

`/api/v1/push`: `GET /config` → `{enabled, publicKey}`(꺼졌으면 `publicKey` null), `GET /devices` → `[{id, name, registeredAt, endpointHash}]`, `PUT /devices` 본문 `{endpoint, keys:{p256dh, auth}, name, timeZone}` → 기기 한 줄, `DELETE /devices/current` 본문 `{endpoint}` → 204.

### 8. 문서

- `backend/docs/data-schema.md`: `app_user` 절에 「푸시 기기와 설정 컬럼」 소절(이 PR 은 `push_devices` 만, `push_settings` 는 PR2 가 더한다)(모양, 전용 쿼리와 잠금, `updated_at` 을 바꾸지 않음, 10대 상한, 모르는 버전은 오류). 「테이블을 추가하는 기준」 에 이 예외와 근거(사용자 결정 2026-11-20, 사용자 몇 명, 서버 한 대 전제, ADR-20261120 / web-push). 「본문 칸과 운영 조회」 표(그 아래 문단이 「운영 조회 계정의 계약」)에 `app_user` 의 `push_devices` 줄을 더한다. 암호화 칸은 「안 함. 운영 조회 계정에서 뺀다」 로 적는다
- `docs/features/attention.md` 의 「알림」 끝에 「웹 푸시」 소절을 짧게 더하고 계약은 스펙과 설계를 가리킨다(이 phase 는 기기와 API 만 적고, 보내기는 phase 02 가 같은 소절에 더한다)

### 9. 테스트

- `notification/PushEndpointPolicyTest`: 기본 호스트 통과, `.notify.windows.com` 접미사 통과, `evilnotify.windows.com`·`fcm.googleapis.com.attacker.example` 거부, `http`, 포트 8443, userinfo, `https://127.0.0.1/x`, `https://[::1]/x` 거부
- `notification/PushDevicesTest`: upsert 갱신은 `id` 유지, 11번째에서 가장 이른 것이 빠짐, 코덱의 왕복과 모르는 버전 오류
- 테스트 키: `src/test/resources/push/test-vapid-private.key`(테스트에서만 쓰는 P-256 비밀 키 base64url 한 줄. 이 PR 에서 새로 만들고 운영 키와 무관하다는 주석 파일은 두지 않고 `application-test.yml` 에 주석을 단다). `application-test.yml` 의 `assistant.push` 에 그 공개 키, 파일 경로, `subject: mailto:test@example.invalid`, `retry-delays: 10ms,20ms`, `retry-after-max: 50ms` 를 넣는다
- 키가 꺼진 경우: `testsupport/BackendIntegrationTest.java` 의 `@MockitoSpyBean` 목록에 `VapidKeys` 를 더하고, 검사가 `enabled()` 를 거짓으로 정한다(검사마다 초기화된다)
- `notification/PushDeviceServiceTest`(`@BackendIntegrationTest`, 실제 `app_user` 줄 둘을 만든다): 등록 뒤 목록, 같은 주소 다시 등록은 한 줄, 다른 사용자가 같은 주소 등록하면 앞 사용자 목록에서 빠짐, 두 사용자가 같은 주소를 동시에 등록해도(스레드 둘, `CountDownLatch`) 끝나면 한 사람에게만 있음, `unregisterCurrent`, 관리자 접근 끄기(`PersonAccessService.setEnabled` 를 거쳐 트랜잭션 안에서 사건이 나오게 한다)로 비움, 키가 없으면 `PUSH_NOT_CONFIGURED`, 허용 밖 주소는 `VALIDATION_FAILED`, `updated_at` 이 바뀌지 않음
- `notification/VapidKeysTest`: 맞는 쌍은 기동, 짝이 안 맞는 키와 깨진 파일은 실패, 셋 다 비면 꺼짐. 키는 테스트 안에서 `KeyPairGenerator` 로 만든다
- `notification/PushControllerTest`: 네 경로의 응답 모양, 응답에 `endpoint`·`p256dh`·`auth` 가 없음

## 검증

```bash
cd backend && ./gradlew test --tests '*PushEndpointPolicyTest' --tests '*PushDevicesTest' --tests '*PushDeviceServiceTest' --tests '*VapidKeysTest' --tests '*PushControllerTest'
cd backend && ./gradlew archTest
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
node scripts/checks/check-migration-versions.mjs origin/main
node --test test/repo/doc-links.test.ts test/repo/doc-files.test.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V20261105074427__app_user_push_devices.sql` | 신규 |
| `backend/src/main/resources/application.yml` | 수정 (`assistant.push`) |
| `backend/src/main/java/com/bifos/assistant/notification/config/PushProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/user/domain/AppUser.java` | 수정 (`push_devices` 매핑) |
| `backend/src/main/java/com/bifos/assistant/notification/infra/VapidKeys.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/application/dto/PushRegistration.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/domain/PushDevice.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/domain/PushDevices.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/domain/PushEndpointPolicy.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/infra/PushDevicesCodec.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/infra/PushDeviceRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/application/PushDeviceService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/application/PushRegistrationLock.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/application/PushAccessRevokedListener.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/presentation/PushController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/notification/presentation/PushDtos.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 (`PUSH_NOT_CONFIGURED`) |
| `backend/src/test/java/com/bifos/assistant/notification/PushEndpointPolicyTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/testsupport/BackendIntegrationTest.java` | 수정 (`VapidKeys` spy) |
| `backend/src/test/resources/application-test.yml` | 수정 (`assistant.push`) |
| `backend/src/test/resources/push/test-vapid-private.key` | 신규 |
| `backend/src/test/java/com/bifos/assistant/notification/PushDevicesTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/notification/PushDeviceServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/notification/VapidKeysTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/notification/PushControllerTest.java` | 신규 |
| `backend/docs/data-schema.md` | 수정 |
| `docs/features/attention.md` | 수정 |
