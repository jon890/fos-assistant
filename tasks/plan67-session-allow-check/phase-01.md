# Phase 01. Control Plane 이 꺼진 사용자의 웹 토큰을 요청마다 거절한다

**Execution profile**: standard

## 목표

관리자가 허용 목록에서 끈 사용자가 웹 토큰으로 부르면 `ControlPlaneJwtFilter` 가 401 과 `ACCESS_REVOKED` 로 답한다.
허용 목록을 로그인할 때만 확인해서, 지금은 세션이 남은 동안 모든 사용자 API 가 열려 있다.

**범위 외**: 웹이 그 응답을 받아 세션을 끊는 것(phase 02). `/mcp`, `/internal/hermes/` 아래 경로, 서비스 토큰 경로.

## 컨텍스트

- 필터는 `backend/src/main/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilter.java` 다.
  `authenticate(String token)` 이 서명을 검증하고 `users.resolve(email, name)` 으로 `AppUser` 를 얻어 `SecurityContextHolder` 에 넣는다.
  서명이 틀리면 `JwtException` 을 잡아 로그만 남기고 인증 없이 지나가며, 그때 Spring Security 가 403 으로 답한다. 이 동작은 그대로 둔다
- `users` 는 `backend/src/main/java/com/bifos/assistant/user/domain/UserProvisioningService.java` 다. 이미 `SignInPolicy` 를 주입받는다.
  `resolve(String email, String displayName)` 은 `@Transactional` 이고 없으면 `app_user` 를 만든다
- 로그인 판정은 `backend/src/main/java/com/bifos/assistant/people/application/SignInPolicy.java` 의 `admit(String email)` 이다.
  주소를 `AllowedPerson.normalizeEmail(email)` 로 맞춘 뒤 `AllowedPersonRepository.findByEmailAndEnabledTrue` 로 찾는다
- 허용 목록 저장소는 `backend/src/main/java/com/bifos/assistant/people/infra/AllowedPersonRepository.java` 다. `allowed_person.email` 은 유일 키이고 정규화한 주소가 들어 있다
- 오류 코드는 `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` 의 enum 이고, 응답 본문은 같은 패키지의 record `ErrorResponse(String code, String message, List<String> missingToolsets)` 다. 인자 둘짜리 생성자가 있고 `missingToolsets` 는 null 이면 JSON 에서 빠진다
- 관리자가 끄는 경로는 `PATCH /api/v1/admin/people/{id}` 본문 `{"enabled": false}` 다
- e2e 의 `dad@example.com` 과 `kid@example.com` 은 허용 목록에 줄이 없다. `aunt@example.com` 은 `test/e2e/scenarios/people.ts` 가 허용 목록에 더한다. 토큰은 `context.tokens.dad`, `context.tokens.aunt` 다

**근거 문서**: `docs/adr/ADR-059-꺼진-사용자는-control-plane-이-요청마다-막고-웹이-세션을-끊는다.md`, `docs/backend/people.md` 의 「사용자를 껐을 때」

## 의도 메모

- **줄이 꺼져 있을 때만 막는다. 줄이 없는 주소는 막지 않는다.** 켜진 줄이 있어야만 통과시키는 안은 ADR-059 가 기각했다. `admit` 을 그대로 쓰면 그 기각한 안이 된다
- 꺼진 주소로는 `app_user` 를 찾지도 만들지도 않는다. 판정을 `resolve` 보다 먼저 한다
- 두 표를 한 쿼리로 합치지 않는다. 같은 트랜잭션에서 허용 목록을 먼저 읽고 사용자를 읽는다
- 401 을 필터가 직접 쓴다. 인증 없이 지나가게 두면 Spring Security 가 403 으로 답해 웹이 꺼진 것을 구분하지 못한다
- `UserAccessPolicy`(서비스 토큰 발급이 쓴다)는 고치지 않는다

## 작업 항목

### 1. `ErrorCode` 에 `ACCESS_REVOKED` 를 더한다

`UNAUTHENTICATED` 바로 아래에 둔다.

```java
/** 관리자가 허용 목록에서 끈 사용자의 웹 토큰이다. 웹이 이 코드를 받으면 세션을 끊는다(ADR-059). */
ACCESS_REVOKED(HttpStatus.UNAUTHORIZED),
```

### 2. `AllowedPersonRepository` 와 `SignInPolicy` 에 꺼졌는지 묻는 조회를 더한다

- `AllowedPersonRepository`: `boolean existsByEmailAndEnabledFalse(String email);` 주소는 정규화한 값이어야 한다는 주석을 붙인다
- `SignInPolicy`:

```java
/** 그 주소의 줄이 꺼져 있는가. 줄이 없으면 거짓이다(ADR-059). */
@Transactional(readOnly = true)
public boolean revoked(String email)
```

`email` 이 null 이거나 비면 거짓을 돌려준다. `AllowedPerson.normalizeEmail(email)` 을 지난 값으로 조회한다.

### 3. `UserProvisioningService` 에 `resolveAllowed` 를 더한다

```java
/**
 * 꺼진 주소면 비어 있는 값을, 아니면 {@link #resolve} 의 사용자를 돌려준다.
 */
@Transactional
public Optional<AppUser> resolveAllowed(String email, String displayName)
```

`signInPolicy.revoked(email)` 이 참이면 `Optional.empty()` 를 돌려주고 `resolve` 를 부르지 않는다.
`resolve` 는 그대로 둔다. `FirstSignInTest` 가 쓴다.
클래스 주석의 「이 경로는 `ControlPlaneJwtFilter` 가 매 요청 부른다」 를 필터가 부르는 것이 `resolveAllowed` 라는 사실에 맞게 고친다.

### 4. `ControlPlaneJwtFilter` 가 꺼진 사용자에게 401 로 답한다

- `authenticate` 가 판정 결과를 돌려주게 바꾼다. 꺼진 사용자면 `doFilterInternal` 이 `chain.doFilter` 를 부르지 않고 응답을 쓴다
- `users.resolve(...)` 호출을 `users.resolveAllowed(...)` 로 바꾼다
- 응답: 상태 401, `Content-Type: application/json`, 문자 집합 UTF-8, 본문은 `ErrorResponse` 를 JSON 으로 쓴 `{"code":"ACCESS_REVOKED","message":"access was revoked"}`.
  `response.setStatus(401)` 뒤 본문을 직접 쓴다. `response.sendError` 는 쓰지 않는다. ERROR 디스패치가 돌아 본문이 바뀐다.
  본문은 Spring 이 주는 `tools.jackson.databind.ObjectMapper` 를 생성자로 받아 쓴다. `com.fasterxml` 의 것을 쓰면 `ArchitectureRules.NO_JACKSON_2_DATABIND` 에 걸린다(`backend/AGENTS.md` 의 「기술 주의점」). 생성자가 바뀌므로 `ControlPlaneJwtFilterTest` 의 `new ControlPlaneJwtFilter(...)` 세 곳도 고친다
- 메일 주소를 로그에 남기지 않는다. `log.warn("rejected a revoked user")` 정도로 둔다
- 클래스 주석과 `UNFILTERED_PATHS` 주석을 한국어로 고쳐 이 판정을 적는다

### 5. `ControlPlaneJwtFilterTest` 에 판정 검사를 더한다

`UserProvisioningService` 는 지금처럼 `mock` 으로 둔다. `MockHttpServletRequest`, `MockHttpServletResponse`, `MockFilterChain` 을 쓴다.
토큰은 `Jwts.builder().subject(email).claim("name", ...).signWith(Keys.hmacShaKeyFor(...))` 로 만든다. 선례는 `backend/src/test/java/com/bifos/assistant/chat/ArtifactTest.java` 의 토큰 생성 메서드다.

| 입력 | 기대 |
| --- | --- |
| `resolveAllowed` 가 `Optional.empty()` | 상태 401, 본문의 `code` 가 `ACCESS_REVOKED`, `MockFilterChain.getRequest()` 가 null |
| `resolveAllowed` 가 사용자를 돌려준다 | 체인이 이어지고 `SecurityContextHolder` 에 `CurrentUser` 가 있다. 검사 끝에 `SecurityContextHolder.clearContext()` |
| 서명이 틀린 토큰 | 체인이 이어지고 응답 상태가 200 그대로다. `resolveAllowed` 를 부르지 않는다 |

### 6. `backend/src/test/java/com/bifos/assistant/user/RevokedUserTest.java` (신규)

`FirstSignInTest` 와 같은 구성(`@SpringBootTest`, `@ActiveProfiles("test")`, `@MockitoBean HermesModelClient`, `@BeforeEach` 에서 `agents`, `users`, `people` 을 비운다)으로 실제 데이터베이스에서 본다.

| 입력 | 기대 |
| --- | --- |
| 허용 목록 줄을 만들고 `disable()` 해 저장한 주소로 `resolveAllowed` | 비어 있다. `users.findByEmail` 도 비어 있다 |
| 같은 주소를 대문자를 섞어 넘긴다 | 비어 있다 |
| 켜진 줄이 있는 주소 | 사용자가 생긴다 |
| 줄이 없는 주소 | 사용자가 생긴다 |
| 이미 들어온 사용자의 줄을 끈다 | 비어 있다. 다시 켜면 같은 `id` 의 사용자가 돌아온다 |

### 7. `test/e2e/scenarios/people.ts` 의 사용 중지 단계에 요청 거절을 더한다

「사용 중지하면 로그인 판정이 거짓이 된다」 단계 안, 로그인 판정 검사 뒤에 더한다. 이 시점에 aunt 는 이미 들어온 사용자다.

- `call(context, "/agents", { token: context.tokens.aunt })` 가 401 이고 본문의 `code` 가 `ACCESS_REVOKED` 다
- `call(context, "/me", { token: context.tokens.aunt })` 도 401 이다. 이 경로는 `permitAll` 이라 필터가 직접 막는지 본다

「다시 허용하면 통과한다」 단계 끝에 `call(context, "/agents", { token: context.tokens.aunt })` 가 200 인지 더한다.
단계 이름을 「사용 중지하면 로그인 판정이 거짓이 되고 이미 들어온 사람의 요청도 막힌다」 로 고친다.

## 검증

```bash
cd backend && ./gradlew test --tests '*ControlPlaneJwtFilterTest' --tests '*RevokedUserTest' --tests '*FirstSignInTest' --tests '*SignInPolicyTest'
cd backend && ./gradlew test
cd backend && ./gradlew qualityCheck
node test/e2e/run.ts
scripts/check-public-safe.sh
```

모두 종료 코드 0 이어야 한다. `node test/e2e/run.ts` 는 저장소 root 에서 돌린다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/people/infra/AllowedPersonRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/people/application/SignInPolicy.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/user/domain/UserProvisioningService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilter.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilterTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/user/RevokedUserTest.java` | 신규 |
| `test/e2e/scenarios/people.ts` | 수정 |
