# Phase 02. 서비스 토큰을 발급하고 폐기한다

**Execution profile**: standard

## 목표

사용자가 자기 문서를 읽을 서비스 토큰을 발급하고, 목록을 보고, 폐기한다.
토큰은 사용자 한 사람과 collection 목록과 collection 마다의 민감 허용에 묶인다. 이 phase 에서는 아직 그 토큰으로 읽는 경로가 없다.

**범위 외**: 토큰으로 인증하고 문서를 읽는 경로(phase 03), 화면(plan62).

## 컨텍스트

- 선례는 `agent_token` 이다. `backend/src/main/java/com/bifos/assistant/mcp/application/AgentTokenService.java` 가 32바이트를 `SecureRandom` 으로 뽑아 `Base64.getUrlEncoder().withoutPadding()` 으로 원문을 만들고 해시만 저장한다. 표는 `backend/src/main/resources/db/migration/V8__agent_token.sql` 이다. **그 표와 클래스를 고치거나 함께 쓰지 않는다.** `agent_token` 은 profile 만 증명하고 사용자 칸을 일부러 지웠다(ADR-032)
- `AgentTokenService` 는 `MessageDigest` 를 직접 부르고 `AgentToken` 은 `Instant.now()` 를 직접 부른다. 둘 다 기준 파일에 든 옛 위반이다. **새 코드는 따라 하지 않는다.** 해시는 `shared.util.Sha256.hex(raw)`(소문자 16진수 64자), 시각은 주입받은 `Clock` 의 `clock.instant()` 다
- collection 과 민감 허용을 묶는 표의 선례는 `agent_memory_collection` 이다. 엔티티는 `backend/src/main/java/com/bifos/assistant/agent/domain/AgentMemoryCollection.java` 와 `AgentMemoryCollectionId.java`(`@EmbeddedId`)다
- collection key 의 모양은 `MemoryPlacement.isCollectionKey(value)` 가 본다. 그룹의 목록은 phase 01 의 `MemoryService.collectionsFor(user)` 가 낸다
- 요청과 응답 record 는 `memory/presentation/MemoryDtos.java` 에 모은다. `application` 의 결과 타입은 `memory/application/model/` 에 타입 하나에 파일 하나로 둔다
- 가장 큰 마이그레이션은 plan60 의 `V52__memory_content_key.sql` 이다. 마이그레이션 테스트의 선례는 `backend/src/test/java/com/bifos/assistant/memory/MemoryV2MigrationTest.java` 다

**근거 문서**: `docs/adr/ADR-056-다른-서비스는-사용자에-묶인-서비스-토큰으로-문서를-읽기만-한다.md`

## 의도 메모

- 발급과 폐기는 늘 요청자 자신의 토큰만 다룬다. 관리자 경로를 만들지 않는다
- 폐기는 줄을 지우지 않고 `revoked_at` 을 적는다. 언제까지 쓰였는지가 남는다
- 원문 앞에 `fos_svc_` 를 붙인다. 설정 파일과 로그에서 이 토큰을 알아보고, `agent_token` 과 섞이지 않는다
- 만료는 발급할 때 정하고 고치지 않는다. 늘리려면 새로 발급한다

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V53__service_token.sql`

```sql
-- 다른 서비스가 사용자의 Memory 문서를 읽을 때 쓰는 토큰이다(ADR-056).
-- 원문은 저장하지 않고 SHA-256 해시만 둔다. agent_token 과 달리 사용자 한 사람에 묶인다.
CREATE TABLE service_token (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    label VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NULL,
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

`origin/main` 에 V53 가 이미 있으면 다음 번호로 옮기고 아래 테스트의 번호도 함께 고친다.

### 2. 엔티티와 저장소

모두 `@Getter @Accessors(fluent = true) @NoArgsConstructor(access = AccessLevel.PROTECTED)` 다.

- `memory/domain/ServiceToken.java`: 칸은 표와 같다. `static ServiceToken issue(Long userId, String tokenHash, String label, Instant expiresAt, Instant now)`, `void markUsed(Instant at)`, `void revoke(Instant at)`(이미 폐기됐으면 그대로 둔다), `boolean usableAt(Instant now)`(폐기되지 않았고, `expiresAt` 이 null 이거나 `now` 보다 뒤다)
- `memory/domain/ServiceTokenCollectionId.java`, `memory/domain/ServiceTokenCollection.java`: `AgentMemoryCollectionId` 와 `AgentMemoryCollection` 의 모양을 따른다. `static ServiceTokenCollection of(Long tokenId, String collection, boolean allowSensitive)`
- `memory/infra/ServiceTokenRepository.java`: `Optional<ServiceToken> findByTokenHash(String tokenHash)`, `List<ServiceToken> findByUserIdOrderByIdDesc(Long userId)`
- `memory/infra/ServiceTokenCollectionRepository.java`: `List<ServiceTokenCollection> findByIdTokenIdIn(Collection<Long> tokenIds)`. 필드 이름은 `ServiceTokenCollectionId` 에 둔 이름에 맞춘다

### 3. 결과 타입 `memory/application/model/`

- `ServiceTokenGrant.java`: `record ServiceTokenGrant(String collection, boolean allowSensitive)`
- `ServiceTokenSnapshot.java`: `record ServiceTokenSnapshot(ServiceToken token, List<ServiceTokenGrant> grants)`
- `IssuedServiceToken.java`: `record IssuedServiceToken(ServiceTokenSnapshot snapshot, String rawToken)`. Javadoc: 원문은 이때 한 번만 나온다

### 4. `memory/application/ServiceTokenService.java`

`@Service @RequiredArgsConstructor @Transactional(readOnly = true)`. `ServiceTokenRepository`, `ServiceTokenCollectionRepository`, `MemoryService`, `Clock` 을 받는다.

| 메서드 | 동작 |
| --- | --- |
| `@Transactional IssuedServiceToken issue(CurrentUser user, String label, Integer expiresInDays, List<ServiceTokenGrant> grants)` | 아래 검사 뒤 원문 `"fos_svc_" + base64url(무작위 32바이트)` 를 만들고 `Sha256.hex(raw)` 를 저장한다. `expiresInDays` 가 null 이면 `expiresAt` 이 null, 아니면 `clock.instant()` 에 그 날수를 더한다 |
| `List<ServiceTokenSnapshot> listOf(CurrentUser user)` | 요청자의 토큰을 최근 것부터. 폐기되고 만료된 것도 낸다 |
| `@Transactional void revoke(CurrentUser user, Long id)` | 토큰이 없거나 `userId` 가 요청자가 아니면 `SERVICE_TOKEN_NOT_FOUND`. 맞으면 `revoke(clock.instant())` |

`issue` 의 검사. 어긋나면 모두 `VALIDATION_FAILED` 다.

- `expiresInDays` 가 null 이 아니면 1 이상 3650 이하다
- `grants` 가 비어 있지 않고 20개 이하다
- collection 마다 `MemoryPlacement.isCollectionKey` 를 지나고, `MemoryService.collectionsFor(user)` 의 key 에 있고, 한 요청 안에서 겹치지 않는다

`ErrorCode` 에 `SERVICE_TOKEN_NOT_FOUND(HttpStatus.NOT_FOUND)` 를 더한다. Javadoc: 없는 토큰과 남의 토큰을 같은 응답으로 숨긴다.

### 5. `MemoryDtos` 와 `memory/presentation/ServiceTokenController.java`

```java
public record ServiceTokenGrantBody(@NotBlank String collection, boolean allowSensitive) {}

public record IssueServiceTokenRequest(
        @NotBlank @Size(max = 100) String label,
        Integer expiresInDays,
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

`MemoryV2MigrationTest` 의 방식이다. V52 까지 올리고 `app_user` 한 줄을 넣은 뒤 V53 를 올린다.

- `service_token` 에 줄을 넣고 `service_token_collection` 에 그 토큰의 줄을 넣을 수 있다
- 같은 `token_hash` 를 한 번 더 넣으면 실패한다
- 없는 `user_id` 로 넣으면 실패한다

### 7. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/memory/ServiceTokenServiceTest.java`

`@SpringBootTest @ActiveProfiles("test")`. 사용자는 dad(번호 1, 그룹 1)와 kid(번호 2, 그룹 1)다. 테스트 데이터베이스는 외래 키 없이 엔티티로 표를 만들므로 `app_user` 줄을 넣지 않아도 된다. `@BeforeEach` 에서 두 저장소를 비운다.

| 입력 | 기대 |
| --- | --- |
| dad 가 `label: "career-os"`, `expiresInDays: 90`, `[{identity, allowSensitive: true}]` 로 발급 | 원문이 `fos_svc_` 로 시작한다. `service_token.token_hash` 가 `Sha256.hex(원문)` 과 같고 원문과 다르다. `expiresAt` 이 지금에서 90일 뒤와 1분 안쪽으로 같다. `service_token_collection` 에 `identity`, `allow_sensitive` 참인 줄이 있다 |
| `expiresInDays: null` | `expiresAt` 이 null |
| 두 번 발급 | 원문 둘이 다르다 |
| dad 의 `listOf` | dad 의 토큰만. kid 의 `listOf` 는 비어 있다 |
| kid 가 dad 의 토큰을 `revoke` | `SERVICE_TOKEN_NOT_FOUND`. dad 의 토큰 `revokedAt` 이 null 그대로다 |
| 없는 번호를 `revoke` | 같은 `SERVICE_TOKEN_NOT_FOUND` |
| dad 가 자기 토큰을 `revoke` 하고 한 번 더 `revoke` | `revokedAt` 이 처음 값 그대로다 |
| `collections` 가 빈 목록 | `VALIDATION_FAILED` |
| collection 이 `no-such-area` | `VALIDATION_FAILED` |
| 같은 collection 이 두 번 | `VALIDATION_FAILED` |
| `expiresInDays: 0`, `expiresInDays: 3651` | `VALIDATION_FAILED` |
| 컨트롤러의 `GET` 응답을 JSON 으로 바꾼 글 | 원문과 `token_hash` 값을 담지 않는다 |

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test --tests '*ServiceTokenServiceTest' --tests '*ServiceTokenMigrationTest')
(cd backend && ./gradlew test)
(cd backend && ./gradlew qualityCheck)
scripts/check-public-safe.sh
! git grep -n "MessageDigest\|Instant.now()" -- backend/src/main/java/com/bifos/assistant/memory
```

- 모두 종료 코드 0. 마지막 줄은 일치하는 줄이 없어야 한다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V53__service_token.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
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
