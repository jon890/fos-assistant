# Phase 01. 암호화 설정과 본문 암호기

**Execution profile**: standard

## 목표

환경 변수로 받은 key 로 글 하나를 암호화하고 푸는 부품을 만든다.
key 가 없으면 기동은 되고 암호화 요청만 거절한다. 이 phase 에서는 아직 아무도 이 부품을 부르지 않는다.

**범위 외**: `Memory` 엔티티와 `MemoryService` 에 붙이는 일(phase 02), 평문으로 남은 줄을 옮기는 일(phase 03).

## 컨텍스트

- 설정 record 의 선례는 `backend/src/main/java/com/bifos/assistant/memory/application/MemoryProposalProperties.java` 다. `AssistantApplication` 에 `@ConfigurationPropertiesScan` 이 있어 record 를 두면 읽힌다. 새 record 에는 `@Validated` 를 붙인다(`ArchitectureRules.CONFIGURATION_PROPERTIES_ARE_VALIDATED`)
- 운영 설정은 `backend/src/main/resources/application.yml` 의 `assistant:` 아래에 있다. 106줄 근처에 `auth.jwt-secret: ${ASSISTANT_JWT_SECRET}` 가 있다. 검사 설정은 `backend/src/test/resources/application-test.yml` 이다
- 오류 코드는 `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` 의 enum 이다. `MEMORY_SENSITIVE_ALWAYS(HttpStatus.BAD_REQUEST)` 가 선례다. 예외는 `new ApiException(ErrorCode.X, "영문 메시지")` 로 던진다
- `MessageDigest` 는 `shared.util.Sha256` 밖에서 부르지 못한다. 이 phase 는 해시를 쓰지 않는다
- 새 의존을 더하지 않는다. `javax.crypto.Cipher`, `javax.crypto.spec.GCMParameterSpec`, `javax.crypto.spec.SecretKeySpec`, `java.security.SecureRandom`, `java.util.Base64` 만 쓴다

**근거 문서**: `docs/adr/ADR-054-민감-memory-본문은-저장할-때-암호화하고-key-는-환경-변수로-받는다.md` 의 「적용 범위」

## 의도 메모

- key 가 없을 때 평문을 돌려주는 메서드를 만들지 않는다. 암호화 요청은 거절뿐이다
- key 목록을 `Map` 으로 묶지 않고 글 하나로 받는다. 환경 변수 하나로 넘기기 쉽고, key id 의 대소문자가 바인딩에서 바뀌지 않는다
- 풀기 실패를 둘로 나눈다. key 가 목록에 없는 것은 운영 설정의 문제라 409 로 알리고, 태그가 맞지 않는 것은 데이터가 바뀐 것이라 500 이다

## 작업 항목

### 1. `ErrorCode` 에 `MEMORY_ENCRYPTION_UNAVAILABLE`

`MEMORY_SENSITIVE_ALWAYS` 바로 아래에 더한다. 상태는 `HttpStatus.CONFLICT` 다.
Javadoc: 「민감 본문을 암호화하거나 풀 key 가 없다. 평문으로 내려 저장하지 않는다(ADR-054).」

### 2. `backend/src/main/java/com/bifos/assistant/memory/application/MemoryEncryptionProperties.java`

```java
@Validated
@ConfigurationProperties(prefix = "assistant.memory.encryption")
public record MemoryEncryptionProperties(String activeKeyId, String keys)
```

- compact 생성자에서 `null` 을 빈 글로 바꾸고 앞뒤 공백을 뗀다
- 둘 다 비어 있으면 그대로 둔다(암호화 꺼짐)
- 한쪽만 비어 있으면 `IllegalArgumentException` 을 던진다. 기동에서 멈춘다
- `Map<String, byte[]> parsedKeys()`: `keys` 를 쉼표로 나누고 조각마다 첫 `:` 로 id 와 값을 나눈다. id 는 `[a-z0-9][a-z0-9-]{0,31}` 이어야 하고, 값은 `Base64.getDecoder()` 로 풀어 32바이트여야 하며, 같은 id 가 두 번 나오면 안 된다. 어긋나면 `IllegalArgumentException` 이다. **예외 메시지에 값을 넣지 않는다.** id 와 무엇이 틀렸는지만 적는다
- `toString()` 을 재정의해 `activeKeyId` 만 낸다. record 의 기본 `toString()` 은 `keys` 를 그대로 담는다
- compact 생성자가 `parsedKeys()` 를 한 번 불러 모양을 검사하고, `activeKeyId` 가 그 map 에 없으면 `IllegalArgumentException` 이다

### 3. `backend/src/main/java/com/bifos/assistant/memory/domain/StoredContent.java`

```java
/** content 칸에 적는 글과 그것을 암호화한 key 의 id 다. keyId 가 null 이면 평문이다(ADR-054). */
public record StoredContent(String content, String keyId) {
    public static StoredContent plain(String content) { ... }
    public boolean sealed() { return keyId != null; }
}
```

### 4. `backend/src/main/java/com/bifos/assistant/memory/application/MemoryContentCipher.java`

`@Component`, `@Slf4j`. 생성자가 `MemoryEncryptionProperties` 를 받아 `parsedKeys()` 를 `SecretKeySpec(bytes, "AES")` 의 map 으로 바꿔 둔다. 생성자에 가공이 있으므로 `@RequiredArgsConstructor` 를 쓰지 않는다.

| 메서드 | 동작 |
| --- | --- |
| `boolean enabled()` | `activeKeyId` 가 비어 있지 않다 |
| `StoredContent seal(String plain, String binding)` | 꺼져 있으면 `ApiException(MEMORY_ENCRYPTION_UNAVAILABLE)`. 켜져 있으면 IV 12바이트를 `SecureRandom` 으로 뽑고 `AES/GCM/NoPadding`, 태그 128비트, AAD 는 `binding` 의 UTF-8 로 암호화한다. `content` 는 `"v1." + base64url(IV) + "." + base64url(암호문과 태그)` 다. base64url 은 `Base64.getUrlEncoder().withoutPadding()` 이다. `keyId` 는 `activeKeyId` 다 |
| `String open(String stored, String keyId, String binding)` | `keyId` 가 map 에 없으면 `ApiException(MEMORY_ENCRYPTION_UNAVAILABLE)`. `stored` 가 `.` 으로 나눠 세 조각이 아니거나 첫 조각이 `v1` 이 아니면 `IllegalStateException`. 태그가 맞지 않으면(`AEADBadTagException`) `log.error` 에 key id 만 남기고 `IllegalStateException` 을 던진다 |

`binding` 은 「이 암호문이 누구의 것인가」 다. 부르는 쪽이 `USER:7` 같은 글을 준다. 이 클래스는 뜻을 모른다.

### 5. 설정 파일

`backend/src/main/resources/application.yml` 의 `assistant:` 아래, `auth:` 블록 앞이나 뒤에 더한다.

```yaml
  memory:
    encryption:
      # 민감 Memory 본문을 암호화하는 key 다(ADR-054). 둘 다 비우면 기동은 되고 민감 항목의 저장과 읽기만 거절한다
      # 새로 쓸 때 쓰는 key 의 id
      active-key-id: ${ASSISTANT_MEMORY_ENCRYPTION_ACTIVE_KEY_ID:}
      # <id>:<base64 32바이트> 를 쉼표로 이은 목록. 옛 key 를 남겨 두어야 옛 줄을 읽는다
      keys: ${ASSISTANT_MEMORY_ENCRYPTION_KEYS:}
```

**`assistant.memory` 블록이 이미 있으면 그 안에 `encryption` 만 더한다.** `grep -n "memory:" backend/src/main/resources/application.yml` 로 먼저 본다. `assistant.memory.propose` 가 같은 접두사를 쓴다.

`backend/src/test/resources/application-test.yml` 의 `assistant:` 아래에 더한다. 여기도 `memory:` 블록이 이미 있는지 먼저 본다.

```yaml
  memory:
    encryption:
      # 운영 값이 아니다. 글자 0123456789abcdef0123456789abcdef 의 base64 다
      active-key-id: test-1
      keys: "test-1:MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
```

### 6. `README.md` 의 「환경 변수」 표

`ASSISTANT_JWT_SECRET` 줄과 같은 모양으로 두 줄을 더한다. 대상은 Backend 다.

| 변수 | 설명 |
| --- | --- |
| `ASSISTANT_MEMORY_ENCRYPTION_ACTIVE_KEY_ID` | 민감 Memory 본문을 새로 암호화할 때 쓰는 key 의 id. 비우면 민감 항목을 저장하지 못한다 |
| `ASSISTANT_MEMORY_ENCRYPTION_KEYS` | `<id>:<base64 32바이트>` 를 쉼표로 이은 목록. 잃으면 민감 본문을 되찾지 못한다 |

### 7. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/memory/MemoryContentCipherTest.java`

Spring 없이 `new MemoryContentCipher(new MemoryEncryptionProperties(...))` 로 만든다.

| 입력 | 기대 |
| --- | --- |
| `seal("평문-표식-7391", "USER:7")` 뒤 같은 `binding` 으로 `open` | 원래 글이 나온다. `content` 가 `v1.` 으로 시작하고 `평문-표식-7391` 을 담지 않는다. `keyId` 가 `test-1` 이다 |
| 같은 글을 두 번 `seal` | 두 `content` 가 다르다(IV 가 다르다) |
| `binding` 을 `USER:8` 로 바꿔 `open` | `IllegalStateException` |
| `content` 의 마지막 글자를 바꿔 `open` | `IllegalStateException` |
| 목록에 없는 `keyId` 로 `open` | `ApiException` 이고 코드가 `MEMORY_ENCRYPTION_UNAVAILABLE` |
| 설정이 둘 다 빈 글인 암호기의 `seal` | `ApiException` 이고 코드가 `MEMORY_ENCRYPTION_UNAVAILABLE`. `enabled()` 가 거짓 |
| key 둘(`old-1`, `new-1`)에 `active-key-id` 가 `new-1` | `old-1` 만 가진 암호기로 암호화한 글을 풀 수 있고, 새로 암호화한 글의 `keyId` 는 `new-1` |
| `MemoryEncryptionProperties("test-1", "")` | `IllegalArgumentException` |
| `MemoryEncryptionProperties("none", "test-1:<32바이트 base64>")` | `IllegalArgumentException` |
| 값이 16바이트인 key | `IllegalArgumentException` 이고 메시지에 그 base64 값이 없다 |
| 맞는 설정의 `toString()` | key 의 base64 값을 담지 않는다 |

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test --tests '*MemoryContentCipherTest')
(cd backend && ./gradlew test)
(cd backend && ./gradlew qualityCheck)
scripts/check-public-safe.sh
```

- 모두 종료 코드 0
- `./gradlew test` 는 기존 테스트가 새 설정을 읽고도 그대로 뜨는지 본다. 테스트 설정의 key 가 틀리면 모든 `@SpringBootTest` 가 기동에서 실패한다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryEncryptionProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryContentCipher.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/StoredContent.java` | 신규 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `README.md` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryContentCipherTest.java` | 신규 |
