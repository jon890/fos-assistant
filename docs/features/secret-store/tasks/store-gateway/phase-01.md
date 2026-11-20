# Phase 01. server_secret 저장소와 KEK 암호화

**Execution profile**: standard

## 목표

서버 비밀값을 KEK 로 감싸 `server_secret` 테이블에 두고 읽고 쓰는 `crypto` 의 저장소를 만든다. 뒤 phase 와 뒤 PR 의 모든 사용처가 이것만 부른다.

**범위 외**: 값의 뜻(중계 서명 비밀, VAPID 등)과 화면. 중계 서명 비밀은 phase 02, 화면 API 는 phase 03 이다.

## 컨텍스트

**요구사항**: REQ4, REQ5, REQ8

**근거 문서**: `docs/features/secret-store/spec.md`, `docs/features/secret-store/design.md` 의 「인터페이스와 데이터」 「실패와 일관성」, `docs/adr/ADR-20261120-server-secret.md`, `backend/docs/adr/ADR-20261008-data-encryption.md`

- KEK 는 `crypto.domain.KeyEncryptionKeys`(`available`, `activeKeyId`, `has`, `wrap(byte[], byte[] aad)`, `unwrap(keyId, wrapped, aad)`)뒤에 있다. 구현 `crypto.infra.FileKeyEncryptionKeys`. `wrap` 은 임의 길이 바이트를 감쌀 수 있다(AES-GCM).
- 기동 때 옛 KEK 줄을 다시 감싸는 것은 `crypto.application.DataKeyRewrapper`(`ApplicationRunner`)다. 같은 기동에서 `server_secret` 줄도 다시 감싼다.
- 패키지 규칙(`backend/docs/code-architecture.md` 「패키지 배치 규칙」): `crypto` 는 루트 역할 패키지가 있어 하위 기능을 만들지 않는다. 사용처의 `infra`(뒤 PR 의 `VapidKeys`, `HermesProfileKeyStore`)가 부를 수 있게 인터페이스는 `crypto.domain` 에 둔다. `admin` 은 기능의 `application` 과 dto 만 부를 수 있어 원문 읽기 인터페이스에 닿지 못한다(REQ3 을 구조 규칙이 지킨다).
- 엔티티 기반 클래스는 `shared.domain.AuditedEntity`(`created_at`, `updated_at`)다. 이 테이블의 기본 키는 `name` 문자열이다.
- 앞부분 기준은 지금 `connector.connection.application.ConnectorValues` 의 `SECRET_PREFIX_LENGTH`(4), `SECRET_PREFIX_MIN_VALUE_LENGTH`(16) 와 그 계산이다. `connector` 가 위층이라 `crypto` 가 부를 수 없으므로 규칙을 `crypto.domain` 으로 옮기고 `ConnectorValues` 가 그것을 부른다.
- 마이그레이션 버전은 예약했다: `V20261105074431__server_secret.sql`. 새 테이블 규칙(정렬 규칙, 한국어 COMMENT, 정리 분류)은 `backend/docs/data-schema.md` 의 「마이그레이션 작성 규칙」 이 갖는다.

## 의도 메모

- 값 자체를 KEK 로 바로 감싼다. 데이터 key 층을 두지 않는다(설계 DECISION1).
- 지운 값은 줄을 남기고 `kek_id`, `sealed_value`, `prefix` 를 NULL 로 둔다(설계 DECISION4). 줄이 있으면 「가져오기 끝남」 이다. `write` 는 NULL 줄을 비어 있음으로 보고 덮는다.
- 캐시는 커밋 뒤 새 값을 바로 넣고, 읽기가 채울 때는 이름별 세대 번호가 읽기 전과 같을 때만 넣는다(설계 DECISION5). 만료 시간은 두지 않는다.
- 로그에는 이름과 사용자 번호만 남긴다. 값, 앞부분, 예외 메시지 원문을 넣지 않는다.

## 작업 항목

### 1. `V20261105074431__server_secret.sql`

`server_secret` 테이블을 만든다. 컬럼: `name VARCHAR(160) NOT NULL PRIMARY KEY`, `kek_id VARCHAR(32) NULL`, `sealed_value VARCHAR(4096) NULL`, `prefix VARCHAR(16) NULL`, `meta_json TEXT NULL`(비밀이 아닌 JSON), `updated_by BIGINT NULL`, `created_at DATETIME(6) NOT NULL`, `updated_at DATETIME(6) NULL`. `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci`. 테이블과 컬럼마다 한국어 `COMMENT`(암호문 여부, NULL 의 뜻). FK 는 두지 않는다(`updated_by` 는 지운 사용자일 수 있다).

### 2. `crypto/domain/ServerSecrets.java`(인터페이스)와 `crypto/domain/SecretPrefix.java`

`ServerSecrets`: `boolean available()`, `Optional<String> read(String name)`, `Optional<ServerSecretRecord> find(String name)`(값 없이 상태·앞부분·meta·마지막 변경), `List<ServerSecretRecord> findByPrefix(String namePrefix)`, `void write(String name, String value, Map<String,Object> meta, Long updatedBy)`, `void clear(String name, Long updatedBy)`, `boolean exists(String name)`. `available()` 이 거짓이면 `write` 는 `ApiException(ErrorCode.SECRET_STORE_UNAVAILABLE)`. `ServerSecretRecord` 는 `crypto.domain` 의 record(이름, 값 있음, 앞부분, meta, 마지막 변경 사용자, 시각).
`SecretPrefix.of(String value)`: 16자 이상이면 앞 4자(서로게이트 쌍을 자르지 않음), 아니면 null. `ConnectorValues` 의 두 상수와 계산을 이것으로 바꾼다.

### 3. `crypto/domain/entity/ServerSecret.java`, `crypto/infra/ServerSecretRepository.java`

엔티티는 `AuditedEntity` 를 잇고 `sealed(kekId, sealed, prefix, meta, by)`, `cleared(by)`, `rewrap(kekId, sealed)` 를 연다. 저장소는 `findByNameStartingWith`, `findByKekIdNotAndKekIdIsNotNull`, 이름으로 쓰기 잠금 조회(`@Lock(PESSIMISTIC_WRITE)`).

### 4. `crypto/infra/DatabaseServerSecrets.java`

`ServerSecrets` 구현. AAD 는 `("server_secret:" + name)` 의 UTF-8 바이트. `write` 와 `clear` 는 `@Transactional`(기본 전파 `REQUIRED`)이라 부른 쪽 트랜잭션이 있으면 참여하고 없으면 자기 트랜잭션을 연다. 둘 다 `TransactionSynchronizationManager` 의 커밋 뒤 콜백에서 캐시에 새 값(또는 비어 있음)을 넣고 세대를 올린다. info 로그는 `server secret written name={} by={}` 꼴로 이름과 사용자 번호만. `read` 는 캐시 → 없으면 세대를 읽어 두고 줄을 풀어 세대가 같을 때만 캐시에 넣는다. 풀지 못하면(KEK 없음, 태그 불일치) 이름과 예외 클래스 이름만 경고하고 `Optional.empty()`. `int rewrapStale()` 로 옛 KEK 줄을 다시 감싸고 KEK 가 없는 옛 줄 수를 경고한다. `void invalidateAll()` 은 캐시를 비우고 세대를 올린다(KEK 교체 뒤와 테스트 정리에 쓴다).

### 5. `crypto/application/DataKeyRewrapper.java`

`DatabaseServerSecrets` 를 직접 주입한다(application 층은 infra 를 쓸 수 있다). `run` 에서 기존 `rewrapAll()` 뒤에 `rewrapStale()` 를 부른다. 실패해도 기동을 멈추지 않는 기존 규칙을 따른다. 클래스 주석의 KEK 교체 절차에 `server_secret` 을 더한다.

### 6. 테스트 격리: `testsupport/ServerSecretsTestSupport.java` 와 `testsupport/IntegrationTestIsolation.java`

통합 테스트는 컨텍스트 하나와 H2 메모리 DB 하나를 함께 쓴다(`application-test.yml`). 그래서 `server_secret` 줄과 캐시가 테스트 사이에 남는다.
`ServerSecretsTestSupport.reset(ApplicationContext)` 는 `server_secret` 을 모두 지우고 `DatabaseServerSecrets.invalidateAll()` 을 부른다. `IntegrationTestIsolation` 의 `afterEach` 가 기존 정리 뒤에 이것을 부른다.
`beforeEach` 는 `@OverrideProperties` 에 `assistant.browser.gateway-secret` 이 있으면 그 값을 `browser_gateway` 로 저장소에 쓴다. 지금 그 설정으로 중계를 켜는 테스트(`ConnectorBindingServiceTest`, `BrowserGatewaySocketIntegrationTest`, `ConnectorBindingGatewayOffTest`)가 고치지 않고 그대로 돌게 하려는 것이다.

### 7. `shared/error/ErrorCode.java`

`SECRET_STORE_UNAVAILABLE(HttpStatus.CONFLICT)` 를 더한다.

### 8. 문서와 정리 분류

- `backend/docs/data-schema.md`: `server_secret` 절(칸, 이름 접두, NULL 의 뜻), 「본문 칸과 운영 조회」 표에 `sealed_value` 를 「함(KEK 로 직접 감쌈)」 으로 더하고, 「테이블을 추가하는 기준」 의 예외로 스펙 DECISION5 를 적는다.
- `backend/src/test/resources/purge/tables.tsv`: `server_secret|GLOBAL|name|KEEP|KEEP|KEEP|서버 비밀값이다. 사용자에 속하지 않는다.` 꼴. `purge/fixture.sql` 에 줄 하나.

### 9. 이 phase 를 검증하는 `crypto/DatabaseServerSecretsTest.java`, `crypto/SecretPrefixTest.java`

H2 스프링 테스트(기존 `DataKeyServiceTest` 의 KEK 준비 방식을 따른다). KEK 없음 사례는 공유 컨텍스트에서 KEK 를 끌 수 없으므로 가짜 `KeyEncryptionKeys`(`available()` 거짓)와 mock 저장소로 `DatabaseServerSecrets` 를 직접 만든 단위 테스트로 본다. 사례: 쓰기 뒤 줄의 `sealed_value` 가 원문을 담지 않고 `read` 가 원문을 돌려줌, 다른 이름 줄로 암호문을 옮기면 풀리지 않음, KEK 없는 구성에서 `write` 가 `SECRET_STORE_UNAVAILABLE` 이고 줄이 없음, `clear` 뒤 줄이 남고 `read` 가 비어 있음, 같은 이름에 다시 `write` 가 됨, 롤백된 쓰기는 캐시에 남지 않음, 커밋 전 읽기가 커밋 뒤 캐시를 옛 값으로 덮지 않음(세대), 옛 KEK 줄이 `rewrapStale` 로 활성 KEK 가 됨, 로그 캡처에 원문과 앞부분이 없음. `SecretPrefixTest`: 15자 null, 16자 앞 4자, 서로게이트.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.crypto.*' --tests 'com.bifos.assistant.connector.ConnectorConnectionServiceTest' --tests '*PurgeClassificationTest' --tests 'com.bifos.assistant.testsupport.IntegrationTestIsolationTest'
cd backend && ./gradlew archTest checkstyleMain checkstyleTest spotlessCheck
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V20261105074431__server_secret.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/crypto/domain/ServerSecrets.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/crypto/domain/ServerSecretRecord.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/crypto/domain/SecretPrefix.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/crypto/domain/entity/ServerSecret.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/crypto/infra/ServerSecretRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/crypto/infra/DatabaseServerSecrets.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/crypto/application/DataKeyRewrapper.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorValues.java` | 수정 (앞부분 규칙을 `SecretPrefix` 로) |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
| `backend/src/test/resources/purge/tables.tsv` | 수정 |
| `backend/src/test/resources/purge/fixture.sql` | 수정 |
| `backend/src/test/java/com/bifos/assistant/crypto/DatabaseServerSecretsTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/crypto/SecretPrefixTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/crypto/DatabaseServerSecretsWithoutKekTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/architecture/JpaAuditingRules.java` | 수정 (새 엔티티의 감사 판정) |
| `backend/src/test/java/com/bifos/assistant/shared/JpaAuditingEntityFixtures.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/shared/JpaAuditingTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/testsupport/ServerSecretsTestSupport.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/testsupport/IntegrationTestIsolation.java` | 수정 |
