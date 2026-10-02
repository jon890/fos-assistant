# Phase 02. 서비스 토큰을 발급하고 폐기한다

**Execution profile**: standard

## 목표

사용자가 자기 문서를 읽을 서비스 토큰을 발급하고, 목록을 보고, 폐기한다.
토큰은 사용자 한 사람과 collection 목록과 collection 마다의 민감 허용에 묶인다. 이 phase 에서는 아직 그 토큰으로 읽는 경로가 없다.
만료는 필수다. 관리자가 허용 목록에서 사용자를 끄면 그 사용자의 토큰을 모두 폐기한다.

**범위 외**: 토큰으로 인증하고 문서를 읽는 경로(phase 03), 화면(plan62).

## 컨텍스트

- 선례는 `agent_token` 이다. `backend/src/main/java/com/bifos/assistant/mcp/application/AgentTokenService.java` 가 32바이트를 `SecureRandom` 으로 뽑아 `Base64.getUrlEncoder().withoutPadding()` 으로 원문을 만들고 해시만 저장한다. 표는 `backend/src/main/resources/db/migration/V8__agent_token.sql` 이다. **그 표와 클래스를 고치거나 함께 쓰지 않는다.** `agent_token` 은 profile 만 증명하고 사용자 칸을 일부러 지웠다(ADR-032)
- `AgentTokenService` 는 `MessageDigest` 를 직접 부르고 `AgentToken` 은 `Instant.now()` 를 직접 부른다. 둘 다 기준 파일에 든 옛 위반이다. **새 코드는 따라 하지 않는다.** 해시는 `shared.util.Sha256.hex(raw)`(소문자 16진수 64자), 시각은 주입받은 `Clock` 의 `clock.instant()` 다
- collection 과 민감 허용을 묶는 표의 선례는 `agent_memory_collection` 이다. 엔티티는 `backend/src/main/java/com/bifos/assistant/agent/domain/AgentMemoryCollection.java` 와 `AgentMemoryCollectionId.java`(`@EmbeddedId`)다
- collection key 의 모양은 `MemoryPlacement.isCollectionKey(value)` 가 본다. 그룹의 목록은 phase 01 의 `MemoryService.collectionsFor(user)` 가 낸다
- 요청과 응답 record 는 `memory/presentation/MemoryDtos.java` 에 모은다. `application` 의 결과 타입은 `memory/application/model/` 에 타입 하나에 파일 하나로 둔다
- `backend/src/main/java/com/bifos/assistant/people/presentation/PeopleAdminController.java` 의 `update(id, request)` 가 허용 목록의 `enabled` 를 올리고 내린다. 끌 때 하는 다른 일이 없다. `users.findByEmail(saved.email())` 로 그 사람의 `app_user` 를 찾는 코드가 이미 있다
- `memory` 는 `people` 과 `user` 를 import 하지 못한다. 순환이 된다. `shared` 는 어느 도메인도 import 하지 못한다. 그래서 사건 타입을 `shared.auth` 에 두고 `people` 이 내고 `memory` 가 받는다. 사건을 받는 선례는 `agent/application/AgentMemoryCollectionService.java` 의 `@EventListener` 다
- 가장 큰 마이그레이션은 `V53__memory_content_key.sql` 이다. 다른 작업이 V54 를 먼저 썼을 수 있다. 마이그레이션 테스트의 선례는 `backend/src/test/java/com/bifos/assistant/memory/MemoryV2MigrationTest.java` 다

**근거 문서**: `docs/adr/ADR-056-다른-서비스는-사용자에-묶인-서비스-토큰으로-문서를-읽기만-한다.md`

## 의도 메모

- 발급과 폐기는 늘 요청자 자신의 토큰만 다룬다. 관리자 경로를 만들지 않는다
- 폐기는 줄을 지우지 않고 `revoked_at` 을 적는다. 언제까지 쓰였는지가 남는다
- 원문 앞에 `fos_svc_` 를 붙인다. 설정 파일과 로그에서 이 토큰을 알아보고, `agent_token` 과 섞이지 않는다
- 만료는 발급할 때 정하고 고치지 않는다. 늘리려면 새로 발급한다
- **만료 없는 토큰을 만들지 못한다.** 이 토큰은 민감 문서를 실행 없이 읽는다. 잊힌 토큰이 저절로 죽어야 한다(ADR-056)
- 사용자를 끄면 토큰을 폐기하고, 다시 켜도 되살리지 않는다. 인증할 때의 주인 확인(phase 03)과 둘 다 둔다. 폐기가 한 번 실패해도 인증이 막고, 다시 켰을 때 옛 토큰이 되살아나지 않는다
- 사건은 `enabled` 를 내린 저장과 같은 요청 안에서 동기로 처리한다. 폐기가 실패하면 그 요청이 실패로 보인다

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V54__service_token.sql`

```sql
-- 다른 서비스가 사용자의 Memory 문서를 읽을 때 쓰는 토큰이다(ADR-056).
-- 원문은 저장하지 않고 SHA-256 해시만 둔다. agent_token 과 달리 사용자 한 사람에 묶인다.
CREATE TABLE service_token (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    label VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    last_used_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_service_token_hash (token_hash),
    CONSTRAINT fk_service_token_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 토큰이 받는 collection 이다. agent_memory_collection 과 같은 모양이고 같은 세 조건으로 판정한다(ADR-053).
CREATE TABLE service_token_collection (
    token_id BIGINT NOT NULL,
    collection VARCHAR(64) NOT NULL,
    allow_sensitive BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (token_id, collection),
    CONSTRAINT fk_service_token_collection_token FOREIGN KEY (token_id) REFERENCES service_token(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

`origin/main` 에 V54 가 이미 있으면 다음 번호로 옮기고 아래 테스트의 번호도 함께 고친다.

### 2. 엔티티와 저장소

모두 `@Getter @Accessors(fluent = true) @NoArgsConstructor(access = AccessLevel.PROTECTED)` 다.

- `memory/domain/ServiceToken.java`: 칸은 표와 같다. `static ServiceToken issue(Long userId, String tokenHash, String label, Instant expiresAt, Instant now)`, `void markUsed(Instant at)`, `void revoke(Instant at)`(이미 폐기됐으면 그대로 둔다), `boolean usableAt(Instant now)`(폐기되지 않았고 `expiresAt` 이 `now` 보다 뒤다). `expiresAt` 은 `nullable = false` 다
- `memory/domain/ServiceTokenCollectionId.java`, `memory/domain/ServiceTokenCollection.java`: `AgentMemoryCollectionId` 와 `AgentMemoryCollection` 의 모양을 따른다. `static ServiceTokenCollection of(Long tokenId, String collection, boolean allowSensitive)`
- `memory/infra/ServiceTokenRepository.java`: `Optional<ServiceToken> findByTokenHash(String tokenHash)`, `List<ServiceToken> findByUserIdOrderByIdDesc(Long userId)`, `List<ServiceToken> findByUserIdAndRevokedAtIsNull(Long userId)`
- `memory/infra/ServiceTokenCollectionRepository.java`: `List<ServiceTokenCollection> findByIdTokenIdIn(Collection<Long> tokenIds)`. 필드 이름은 `ServiceTokenCollectionId` 에 둔 이름에 맞춘다

### 3. 결과 타입 `memory/application/model/`

- `ServiceTokenGrant.java`: `record ServiceTokenGrant(String collection, boolean allowSensitive)`
- `ServiceTokenSnapshot.java`: `record ServiceTokenSnapshot(ServiceToken token, List<ServiceTokenGrant> grants)`
- `IssuedServiceToken.java`: `record IssuedServiceToken(ServiceTokenSnapshot snapshot, String rawToken)`. Javadoc: 원문은 이때 한 번만 나온다

### 4. `memory/application/ServiceTokenService.java`

`@Service @RequiredArgsConstructor @Transactional(readOnly = true)`. `ServiceTokenRepository`, `ServiceTokenCollectionRepository`, `MemoryService`, `Clock` 을 받는다.

| 메서드 | 동작 |
| --- | --- |
| `@Transactional IssuedServiceToken issue(CurrentUser user, String label, int expiresInDays, List<ServiceTokenGrant> grants)` | 아래 검사 뒤 원문 `"fos_svc_" + base64url(무작위 32바이트)` 를 만들고 `Sha256.hex(raw)` 를 저장한다. `expiresAt` 은 `clock.instant()` 에 그 날수를 더한 값이다 |
| `List<ServiceTokenSnapshot> listOf(CurrentUser user)` | 요청자의 토큰을 최근 것부터. 폐기되고 만료된 것도 낸다 |
| `@Transactional void revoke(CurrentUser user, Long id)` | 토큰이 없거나 `userId` 가 요청자가 아니면 `SERVICE_TOKEN_NOT_FOUND`. 맞으면 `revoke(clock.instant())` |
| `@EventListener @Transactional void revokeAllOf(UserAccessRevoked event)` | `findByUserIdAndRevokedAtIsNull(event.userId())` 의 토큰을 모두 `revoke(clock.instant())` 한다. `log.info` 에 사용자 번호와 폐기한 수만 남긴다 |

`issue` 의 검사. 어긋나면 모두 `VALIDATION_FAILED` 다.

- `expiresInDays` 가 1 이상 365 이하다
- `grants` 가 비어 있지 않고 20개 이하다
- collection 마다 `MemoryPlacement.isCollectionKey` 를 지나고, `MemoryService.collectionsFor(user)` 의 key 에 있고, 한 요청 안에서 겹치지 않는다

`ErrorCode` 에 `SERVICE_TOKEN_NOT_FOUND(HttpStatus.NOT_FOUND)` 를 더한다. Javadoc: 없는 토큰과 남의 토큰을 같은 응답으로 숨긴다.

### 4-1. 사용자를 끈 사건

`backend/src/main/java/com/bifos/assistant/shared/auth/UserAccessRevoked.java`:

```java
/** 관리자가 허용 목록에서 사용자를 껐다. 그 사용자가 가진 것을 거둘 쪽이 받는다(ADR-056). */
public record UserAccessRevoked(Long userId) {}
```

`PeopleAdminController.update`: `ApplicationEventPublisher` 를 주입받는다. 요청이 `enabled: false` 이고 `users.findByEmail(saved.email())` 가 사용자를 찾으면 `people.save(person)` 뒤에 `events.publishEvent(new UserAccessRevoked(user.id()))` 를 낸다. 아직 로그인한 적이 없는 사람은 `app_user` 가 없어 내지 않는다. 켤 때는 내지 않는다.
그 메서드의 Javadoc 「내려도 이미 만들어진 에이전트는 그대로 둔다 …」 뒤에 「그 사람의 서비스 토큰은 모두 폐기한다. 다시 올려도 되살아나지 않는다(ADR-056)」 를 더한다.

### 5. `MemoryDtos` 와 `memory/presentation/ServiceTokenController.java`

```java
public record ServiceTokenGrantBody(@NotBlank String collection, boolean allowSensitive) {}

public record IssueServiceTokenRequest(
        @NotBlank @Size(max = 100) String label,
        @NotNull @Min(1) @Max(365) Integer expiresInDays,
        @NotEmpty @Valid List<ServiceTokenGrantBody> collections) {}

public record ServiceTokenView(
        Long id, String label, Instant createdAt, Instant expiresAt,
        Instant lastUsedAt, Instant revokedAt, List<ServiceTokenGrantBody> collections) {}

public record IssuedServiceTokenView(ServiceTokenView info, String token) {}
```

| 경로 | 동작 |
| --- | --- |
| `POST /api/v1/service-tokens` | `issue`. 응답은 `IssuedServiceTokenView` |
| `GET /api/v1/service-tokens` | `listOf`. 응답은 `List<ServiceTokenView>`. 원문과 해시가 없다 |
| `DELETE /api/v1/service-tokens/{id}` | `revoke` |

모두 `CurrentUserProvider.require()` 의 사용자로 부른다. `requireAdmin()` 을 쓰지 않는다.

### 6. 마이그레이션 테스트 `backend/src/test/java/com/bifos/assistant/memory/ServiceTokenMigrationTest.java`

`MemoryV2MigrationTest` 의 방식이다. V53 까지 올리고 `app_user` 한 줄을 넣은 뒤 V54 를 올린다.

- `service_token` 에 줄을 넣고 `service_token_collection` 에 그 토큰의 줄을 넣을 수 있다
- 같은 `token_hash` 를 한 번 더 넣으면 실패한다
- 없는 `user_id` 로 넣으면 실패한다

### 7. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/memory/ServiceTokenServiceTest.java`

`@SpringBootTest @ActiveProfiles("test")`. 사용자는 dad(번호 1, 그룹 1)와 kid(번호 2, 그룹 1)다. 테스트 데이터베이스는 외래 키 없이 엔티티로 표를 만들므로 `app_user` 줄을 넣지 않아도 된다. `@BeforeEach` 에서 두 저장소를 비운다.

| 입력 | 기대 |
| --- | --- |
| dad 가 `label: "career-os"`, `expiresInDays: 90`, `[{identity, allowSensitive: true}]` 로 발급 | 원문이 `fos_svc_` 로 시작한다. `service_token.token_hash` 가 `Sha256.hex(원문)` 과 같고 원문과 다르다. `expiresAt` 이 지금에서 90일 뒤와 1분 안쪽으로 같다. `service_token_collection` 에 `identity`, `allow_sensitive` 참인 줄이 있다 |
| `IssueServiceTokenRequest` 의 `expiresInDays` 가 null, 0, 366 | `jakarta.validation.Validator` 로 검사하면 위반이 있다. 90 은 위반이 없다 |
| 두 번 발급 | 원문 둘이 다르다 |
| dad 의 `listOf` | dad 의 토큰만. kid 의 `listOf` 는 비어 있다 |
| kid 가 dad 의 토큰을 `revoke` | `SERVICE_TOKEN_NOT_FOUND`. dad 의 토큰 `revokedAt` 이 null 그대로다 |
| 없는 번호를 `revoke` | 같은 `SERVICE_TOKEN_NOT_FOUND` |
| dad 가 자기 토큰을 `revoke` 하고 한 번 더 `revoke` | `revokedAt` 이 처음 값 그대로다 |
| `collections` 가 빈 목록 | `VALIDATION_FAILED` |
| collection 이 `no-such-area` | `VALIDATION_FAILED` |
| 같은 collection 이 두 번 | `VALIDATION_FAILED` |
| 서비스에 `expiresInDays: 0`, `expiresInDays: 366` | `VALIDATION_FAILED` |
| dad 가 토큰 둘을 발급하고 하나를 폐기한 뒤 `ApplicationEventPublisher.publishEvent(new UserAccessRevoked(1L))` | dad 의 남은 토큰의 `revokedAt` 이 채워진다. 먼저 폐기한 토큰의 `revokedAt` 은 처음 값 그대로다. kid 의 토큰은 `revokedAt` 이 null 그대로다 |
| `PeopleAdminController.update` 로 dad 의 메일 주소를 가진 `AllowedPerson` 을 끈다. `AppUser` 와 `AllowedPerson` 줄을 저장소로 먼저 넣고 `CurrentUserProvider` 는 mock 으로 둔다 | dad 의 `AppUser` 번호로 발급한 토큰의 `revokedAt` 이 채워진다. 다시 켜도 `revokedAt` 이 그대로다 |
| `app_user` 가 없는 `AllowedPerson` 을 끈다 | 예외가 없다 |
| 컨트롤러의 `GET` 응답을 JSON 으로 바꾼 글 | 원문과 `token_hash` 값을 담지 않는다 |

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test --tests '*ServiceTokenServiceTest' --tests '*ServiceTokenMigrationTest')
(cd backend && ./gradlew test)
(cd backend && ./gradlew qualityCheck)
scripts/check-public-safe.sh
! git grep -n "MessageDigest\|Instant.now()" -- backend/src/main/java/com/bifos/assistant/memory
! git grep -n "import com.bifos.assistant.people\|import com.bifos.assistant.user" -- backend/src/main/java/com/bifos/assistant/memory
```

- 모두 종료 코드 0. 마지막 두 줄은 일치하는 줄이 없어야 한다
- `./gradlew test` 가 `ArchitectureRulesTest` 를 돌린다. 새 패키지 순환이 없어야 한다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V54__service_token.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/auth/UserAccessRevoked.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/people/presentation/PeopleAdminController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/ServiceToken.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/ServiceTokenCollection.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/ServiceTokenCollectionId.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/infra/ServiceTokenRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/infra/ServiceTokenCollectionRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/model/ServiceTokenGrant.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/model/ServiceTokenSnapshot.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/model/IssuedServiceToken.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/ServiceTokenService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/presentation/MemoryDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/presentation/ServiceTokenController.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/memory/ServiceTokenMigrationTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/memory/ServiceTokenServiceTest.java` | 신규 |
