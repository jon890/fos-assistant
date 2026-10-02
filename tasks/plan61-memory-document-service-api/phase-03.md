# Phase 03. 서비스 토큰으로 문서를 읽는다

**Execution profile**: deep

## 목표

`GET /api/v1/service/memory-documents/{collection}/{documentKey}` 가 서비스 토큰을 받아 그 토큰 주인의 문서 본문과 판 번호를 낸다.
틀린 토큰, 폐기된 토큰, 만료된 토큰, 주인이 허용 목록에서 꺼진 토큰을 구분하지 않고, 읽을 수 없는 문서와 없는 문서를 구분하지 않는다.

**범위 외**: e2e 시나리오와 문서 갱신(phase 04), 화면(plan62).

## 컨텍스트

- phase 02 가 `memory.domain.ServiceToken`(`usableAt(now)`, `markUsed(at)`), `memory.infra.ServiceTokenRepository.findByTokenHash`, `ServiceTokenCollectionRepository.findByIdTokenIdIn`, `memory.application.ServiceTokenService` 를 만들었다. 원문의 해시는 `Sha256.hex(raw)` 다
- phase 01 이 `MemoryRepository.findByScopeAndOwnerUserIdAndCollectionAndDocumentKey(scope, ownerUserId, collection, documentKey)` 와 `MemoryService.contentOf(memory)` 를 쓸 수 있게 했다
- 로그인을 받아들이는 판정은 `people/application/SignInPolicy.java` 의 `admit(email)` 이다. 허용 목록에 그 메일 주소가 `enabled` 로 있으면 값을 낸다. 사용자의 메일 주소는 `user/infra/AppUserRepository.java` 의 `findById` 로 읽는다. **`memory` 는 이 둘을 import 하지 못한다.** 순환이 된다
- 테스트와 e2e 의 사용자는 토큰을 바로 만들어 들어오므로 허용 목록에 줄이 없을 수 있다. 주인 확인을 넣으면 허용 목록에 없는 사용자의 서비스 토큰은 401 이다
- 판정에 쓰는 값은 `memory/application/model/MemoryAccess.java` 다. `MemoryAccess.of(Set<String> collections, Set<String> sensitiveCollections)` 로 만들고 `allows(collection, sensitivity)` 로 본다. 에이전트의 실행이 쓰는 것과 같은 타입이다
- 인증 경계가 지금 둘 있다
  - `backend/src/main/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilter.java`: 웹의 JWT. `UNFILTERED_PATHS` 에 든 경로는 `shouldNotFilter` 가 건너뛴다. 지금은 `Set.contains(request.getRequestURI())` 로 정확히 같은 경로만 본다. JWT 가 아닌 글이 오면 경고 로그를 남기고 인증 없이 지나간다
  - `backend/src/main/java/com/bifos/assistant/mcp/infra/AgentTokenAuthenticationFilter.java`: profile 토큰. 정해진 세 경로만 맡는다. **고치지 않는다**
- `backend/src/main/java/com/bifos/assistant/shared/config/SecurityConfig.java` 의 `authorizeHttpRequests` 는 `/api/v1/signin/allowed` 를 `permitAll` 로 두고 그 컨트롤러가 토큰을 스스로 검사한다. 그 밖은 `authenticated()` 다
- `shared` 는 다른 최상위 패키지를 import 하지 못한다. `SecurityConfig` 가 `mcp.infra.AgentTokenAuthenticationFilter` 를 import 하는 것은 기준 파일에 든 옛 위반이다. **새 간선 `shared -> memory` 를 만들면 `./gradlew test` 가 실패한다**
- HTTP 층을 실제로 지나는 테스트의 선례는 `backend/src/test/java/com/bifos/assistant/mcp/SubagentSessionEndpointTest.java` 다. `@SpringBootTest(webEnvironment = RANDOM_PORT)` 와 `@LocalServerPort` 로 진짜 요청을 보낸다. 웹 JWT 를 만드는 방법은 `backend/src/test/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilterTest.java` 를 읽는다

**근거 문서**: `docs/adr/ADR-056-다른-서비스는-사용자에-묶인-서비스-토큰으로-문서를-읽기만-한다.md` 의 「적용 범위」 첫 표

## 의도 메모

- 인증을 Spring Security 필터가 아니라 `memory` 패키지의 `HandlerInterceptor` 로 한다. 필터로 하면 `SecurityConfig` 가 `memory` 를 import 해야 한다. `SecurityConfig` 에는 경로를 `permitAll` 로 여는 글자만 둔다
- 그래서 `/api/v1/service/` 아래의 모든 경로는 인터셉터가 막는다. 컨트롤러가 검사를 빠뜨려도 열리지 않게 경로 패턴으로 건다
- 요청자는 토큰이 정한다. 경로와 본문은 사용자를 정하지 못한다
- 꺼내는 방식(`retrieval`)으로 거르지 않는다. 그 칸은 에이전트의 실행에 싣는 방식이다(ADR-056)
- **`SecurityConfig` 의 응답 코드를 바꾸지 않는다.** 인증 진입점을 더하면 기존 e2e 의 403 단언이 깨진다. 401 은 서비스 경로의 인터셉터만 낸다
- 주인 확인은 `shared.auth` 의 인터페이스로 묻고 `people` 이 구현한다. `memory` 가 `people` 을 import 하지 않게 하기 위해서다
- 주인 확인은 인증마다 한다. 끌 때의 폐기(phase 02)가 실패했거나 사건 없이 줄만 바뀐 경우를 막는다
- 401 과 404 의 응답을 경우마다 같게 한다. 다르게 답하면 토큰이나 문서가 있다는 사실이 새어 나간다

## 작업 항목

### 1. `memory/application/model/ServicePrincipal.java`

```java
/** 서비스 토큰이 증명한 요청자다. 사용자 한 사람과 그 토큰이 받는 collection 이다(ADR-056). */
public record ServicePrincipal(Long tokenId, Long userId, MemoryAccess access, Instant expiresAt) {}
```

### 1-1. 주인의 허용 여부를 묻는 자리

`backend/src/main/java/com/bifos/assistant/shared/auth/UserAccessPolicy.java`:

```java
/** 그 사용자가 지금도 들어올 수 있는가. 로그인 판정과 같은 답을 낸다(ADR-056). */
public interface UserAccessPolicy {
    boolean allowed(Long userId);
}
```

`backend/src/main/java/com/bifos/assistant/people/application/AllowedUserAccessPolicy.java`: `@Component @RequiredArgsConstructor`, `UserAccessPolicy` 를 구현한다. `AppUserRepository.findById(userId)` 로 메일 주소를 얻어 `SignInPolicy.admit(email).isPresent()` 를 낸다. 사용자가 없으면 `false` 다. `@Transactional(readOnly = true)`.

### 2. `ServiceTokenService.authenticate`

```java
@Transactional
public ServicePrincipal authenticate(String raw)
```

- `findByTokenHash(Sha256.hex(raw))` 로 찾는다. 없거나, `usableAt(clock.instant())` 가 거짓이거나, `UserAccessPolicy.allowed(token.userId())` 가 거짓이면 `new ApiException(ErrorCode.UNAUTHENTICATED, "invalid service token")` 이다. **없는 것, 폐기된 것, 만료된 것, 주인이 꺼진 것의 메시지가 같다**
- 거절한 요청은 `markUsed` 를 적지 않는다
- `markUsed(clock.instant())` 를 적는다
- 그 토큰의 `ServiceTokenCollection` 줄로 `MemoryAccess.of(모든 collection, allowSensitive 가 참인 collection)` 을 만든다
- 폐기되거나 만료되거나 주인이 꺼진 토큰이 쓰이면 `log.warn` 에 토큰 번호만 남긴다. 원문과 해시는 남기지 않는다

### 3. `MemoryService.documentForService`

```java
public Memory documentForService(Long userId, MemoryAccess access, String collection, String documentKey)
```

`findByScopeAndOwnerUserIdAndCollectionAndDocumentKey(MemoryScope.USER, userId, collection, documentKey)` 로 읽는다.
줄이 없거나, `status` 가 `ACCEPTED` 가 아니거나, `entryType` 이 `DOCUMENT` 가 아니거나, `access.allows(memory.collection(), memory.sensitivity())` 가 거짓이면 모두 private `notFound()` 다.
Javadoc 에 「없는 문서와 읽을 수 없는 문서를 같은 응답으로 숨긴다」 를 적는다.

### 4. `memory/presentation/ServiceTokenInterceptor.java`

`@Component @RequiredArgsConstructor`, `HandlerInterceptor` 를 구현한다.

```java
public static final String PRINCIPAL_ATTRIBUTE = ServiceTokenInterceptor.class.getName() + ".PRINCIPAL";
```

`preHandle`:

1. `Origin` 머리말이 있으면 403 을 적고 `false`. 본문을 쓰지 않는다
2. `Authorization` 이 없거나 `Bearer ` 로 시작하지 않거나 뒤가 비어 있으면 401 을 적고 `false`
3. `tokens.authenticate(raw)` 가 `ApiException` 을 던지면 401 을 적고 `false`
4. 통과하면 `request.setAttribute(PRINCIPAL_ATTRIBUTE, principal)` 뒤 `true`

상태만 적을 때는 `response.setStatus(...)` 를 쓴다. `sendError` 는 오류 화면으로 넘어가 본문이 생긴다.

### 5. `memory/presentation/ServiceApiConfig.java`

`@Configuration @RequiredArgsConstructor`, `WebMvcConfigurer` 를 구현한다.

```java
@Override
public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(interceptor).addPathPatterns("/api/v1/service/**");
}
```

Javadoc: 「이 경로는 `SecurityConfig` 가 `permitAll` 로 열어 둔다. 인증은 이 인터셉터가 한다. 경로를 더하면 두 곳을 함께 본다.」

### 6. `SecurityConfig` 와 `ControlPlaneJwtFilter`

- `SecurityConfig`: `/api/v1/signin/allowed` 의 `permitAll` 아래에 더한다. import 는 더하지 않는다

  ```java
  // 다른 서비스가 서비스 토큰으로 부르는 경로다(ADR-056). 인증은 memory 의 인터셉터가 한다.
  .requestMatchers("/api/v1/service/**")
  .permitAll()
  ```

- `ControlPlaneJwtFilter.shouldNotFilter`: `UNFILTERED_PATHS.contains(uri) || uri.startsWith("/api/v1/service/")` 로 바꾼다. 접두사는 `private static final String SERVICE_API_PREFIX = "/api/v1/service/";` 로 둔다. `UNFILTERED_PATHS` 의 Javadoc 에 「`/api/v1/service/` 아래는 서비스 토큰을 쓰는 다른 인증 경계다(ADR-056)」 를 더한다

**`/api/v1/service-tokens` 는 이 접두사에 걸리지 않는다.** 그 경로는 웹 JWT 로 인증한다. 접두사 끝의 `/` 를 빼지 않는다.

### 7. `MemoryDtos` 와 `memory/presentation/MemoryDocumentServiceController.java`

```java
public record ServiceDocumentView(
        String collection, String documentKey, String title,
        String content, int revision, Instant updatedAt) {}
```

```java
@RestController
@RequestMapping("/api/v1/service/memory-documents")
@RequiredArgsConstructor
@Slf4j
public class MemoryDocumentServiceController {

    @GetMapping("/{collection}/{documentKey}")
    public ResponseEntity<ServiceDocumentView> read(
            @RequestAttribute(ServiceTokenInterceptor.PRINCIPAL_ATTRIBUTE) ServicePrincipal principal,
            @PathVariable String collection,
            @PathVariable String documentKey)
```

- `memories.documentForService(principal.userId(), principal.access(), collection, documentKey)` 뒤 `memories.contentOf(memory)` 로 본문을 낸다
- 응답 머리말: `Cache-Control: no-store` 와 `X-Service-Token-Expires-At`. 뒤의 것은 `principal.expiresAt()` 을 `Instant.toString()` 으로 적는다. 만료는 늘 있다
- `log.info("service document read userId={} tokenId={} collection={} documentKey={} revision={}", ...)`. 본문과 제목은 적지 않는다

### 8. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/memory/MemoryDocumentServiceApiTest.java`

`@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT) @ActiveProfiles("test")`. `SubagentSessionEndpointTest` 처럼 진짜 요청을 보낸다. 준비는 서비스로 한다. `@BeforeEach` 에서 `AppUserRepository` 와 `AllowedPersonRepository` 로 dad 와 kid 의 `AppUser` 와 켜진 `AllowedPerson`(`AllowedPerson.of(email, 이름, profile)`)을 넣고, 저장된 `AppUser.id()` 로 `CurrentUser` 를 만든다. 번호를 1 과 2 로 가정하지 않는다. dad 가 `identity` 에 `application-profile` 민감 문서(본문 `평문-표식-7391`)와 `career` 에 `position-notes` 일반 문서를 만든다. kid(번호 2)가 `identity` 에 같은 이름의 문서(본문 `kid-표식-1200`)를 만든다.

| 입력 | 기대 |
| --- | --- |
| dad 의 토큰(`identity`, 민감 허용)으로 `identity/application-profile` | 200. `content` 가 `평문-표식-7391`, `revision` 이 1, `collection`, `documentKey`, `title`, `updatedAt` 이 있다. `Cache-Control` 이 `no-store` |
| 같은 토큰을 `expiresInDays: 90` 으로 발급했다 | 응답에 `X-Service-Token-Expires-At` 이 있고 값이 지금에서 90일 뒤와 1분 안쪽으로 같다 |
| dad 가 문서를 고친 뒤 다시 읽는다 | `revision` 이 2 이고 `content` 가 고친 글이다 |
| 읽은 뒤 `service_token.last_used_at` | 채워져 있다 |
| `Authorization` 없음 | 401, 본문 길이 0 |
| 틀린 토큰 `fos_svc_wrong` | 401, 본문 길이 0 |
| 폐기한 토큰 | 401, 본문 길이 0 |
| `JdbcTemplate` 으로 `expires_at` 을 어제로 바꾼 토큰 | 401, 본문 길이 0 |
| 웹 JWT 를 `Bearer` 로 실어 서비스 경로를 부른다 | 401, 본문 길이 0 |
| `AllowedPersonRepository` 로 dad 의 줄을 `disable()` 하고 저장한다. 사건을 내지 않아 토큰은 폐기되지 않은 채다 | 401, 본문 길이 0. `service_token.revoked_at` 이 null 그대로이고 `last_used_at` 이 바뀌지 않는다 |
| 위 줄을 다시 `enable()` 한다 | 200. 폐기되지 않은 토큰이라 다시 통한다 |
| `PeopleAdminController.update` 로 dad 를 끄고 다시 켠다 | 401. 끌 때 폐기됐고 켜도 되살아나지 않는다 |
| 허용 목록에 줄이 없는 사용자의 번호로 발급한 토큰 | 401, 본문 길이 0 |
| 위 401 들의 상태와 머리말과 본문 | 틀린 토큰의 401 과 같다. `WWW-Authenticate` 같은 머리말로도 갈리지 않는다 |
| 맞는 토큰에 `Origin: https://example.com` | 403 |
| 서비스 토큰을 `Bearer` 로 실어 `GET /api/v1/memories` | 403. 서비스 토큰이 사용자 API 를 열지 못한다. 이 저장소는 인증하지 못한 사용자 API 요청에 403 으로 답한다(`test/e2e/scenarios/auth.ts`) |
| dad 의 토큰이 `career` 만 받는다. `identity/application-profile` 을 읽는다 | 404, 코드 `MEMORY_NOT_FOUND` |
| dad 의 토큰이 `identity` 를 민감 허용 없이 받는다 | 404. 위와 본문이 글자까지 같다 |
| 없는 이름 `identity/no-such-document` | 404. 위와 본문이 글자까지 같다 |
| dad 의 토큰으로 읽은 `identity/application-profile` | `kid-표식-1200` 을 담지 않는다. 같은 이름의 남의 문서가 나오지 않는다 |
| kid 의 문서만 있고 dad 에게는 없는 이름을 dad 의 토큰으로 읽는다 | 404 |
| `MemoryService.proposeUser` 로 만든 승인 전 항목과 종류가 `MEMORY` 인 항목 | 서비스 경로로 읽히지 않는다. 이름이 없어 경로로 가리킬 수 없으므로 `documentForService` 를 직접 불러 `MEMORY_NOT_FOUND` 를 본다. `JdbcTemplate` 으로 `document_key` 를 채우고 `status` 를 `PROPOSED` 로 둔 줄을 넣어 본다 |

404 의 본문에는 시각이나 경로처럼 요청마다 달라지는 칸이 있을 수 있다. 그런 칸이 있으면 `code` 와 `message` 만 견준다.

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test --tests '*MemoryDocumentServiceApiTest' --tests '*ServiceTokenServiceTest' --tests '*ControlPlaneJwtFilterTest' --tests '*ArchitectureRulesTest')
(cd backend && ./gradlew test)
(cd backend && ./gradlew qualityCheck)
node test/e2e/run.ts
scripts/check-public-safe.sh
! git grep -n "import com.bifos.assistant.memory" -- backend/src/main/java/com/bifos/assistant/shared
! git grep -n "import com.bifos.assistant.people\|import com.bifos.assistant.user" -- backend/src/main/java/com/bifos/assistant/memory
```

- 모두 종료 코드 0. 마지막 두 줄은 일치하는 줄이 없어야 한다
- `node test/e2e/run.ts` 는 `./gradlew test` 뒤에 돌린다. 인증 경계를 고쳤으므로 기존 시나리오의 로그인과 MCP 호출이 그대로인지 본다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/memory/application/model/ServicePrincipal.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/shared/auth/UserAccessPolicy.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/people/application/AllowedUserAccessPolicy.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/ServiceTokenService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/presentation/ServiceTokenInterceptor.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/presentation/ServiceApiConfig.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/presentation/MemoryDocumentServiceController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/presentation/MemoryDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/config/SecurityConfig.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilter.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryDocumentServiceApiTest.java` | 신규 |
