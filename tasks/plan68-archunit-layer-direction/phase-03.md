# Phase 03. MCP 토큰 필터를 mcp.presentation 으로 옮기고 shared 가 필터 타입으로 받는다

**Execution profile**: deep

## 목표

`mcp.infra.AgentTokenAuthenticationFilter` 가 `mcp.application` 을 쓰는 위반과, `shared.config.SecurityConfig` 가 `mcp` 의 필터 클래스를 쓰는 위반을 함께 없앤다.
필터는 요청을 받는 자리이므로 `mcp.presentation` 으로 옮기고, `SecurityConfig` 는 `shared.auth` 에 둔 필터 타입으로 받는다.
`LAYER_DIRECTION` 의 기준이 11 줄에서 7 줄로, `SHARED_DOES_NOT_DEPEND_ON_DOMAINS` 의 기준이 17 줄에서 14 줄로 준다.

**범위 외**: 필터의 판정(경로, `Origin` 거절, 401, `ROLE_MCP`, 요청 속성). `SecurityConfig` 의 경로 규칙과 필터 순서. `ControlPlaneJwtFilter` 와 `CurrentUser` 가 `user` 를 쓰는 위반은 뒤의 PR 이 맡는다.

## 컨텍스트

- 규칙은 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 의 `LAYER_DIRECTION` 이다. `infra` 는 `application` 만 쓸 수 있고, `infra` 가 `application` 을 쓰는 것과 `presentation` 이 `infra` 를 쓰는 것이 위반이다. `domain` 은 어느 층이나 쓴다.
- 기준 파일은 `backend/config/archunit/store/2790ecd4-faaa-4952-b703-a028b68814d5` 다. 손으로 고치지 않고 아래 명령으로 줄인다. 절차는 `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」 에 있다.
- **동작을 바꾸지 않는다.** 응답, 저장되는 값, 설정 이름, 트랜잭션이 열리는 자리가 그대로여야 한다. `ArchitectureRules.java` 를 고치지 않는다.
- 포맷은 이 phase 에서 돌리지 않는다. `spotlessApply` 결과는 team-lead 가 따로 커밋한다. import 순서를 손으로 정렬하지 않는다.
- 주석과 Javadoc 은 한국어로 쓴다. 테스트 메서드는 영문 camelCase 이름과 한국어 `@DisplayName` 을 갖는다. `gradlew` 는 `backend/` 안에 있다.
- BSD `sed` 는 `\b` 를 모른다. 여러 파일의 이름을 바꿀 때는 `perl -pi -e` 를 쓴다.
- `backend/src/main/java/com/bifos/assistant/mcp/infra/AgentTokenAuthenticationFilter.java` 는 `OncePerRequestFilter` 를 상속한 `@Component` 이고 `AgentTokenService` 와 `McpPrincipal` 을 쓴다.
- `backend/src/main/java/com/bifos/assistant/shared/config/SecurityConfig.java` 가 이 클래스를 두 곳에서 타입으로 받는다. `disableDirectAgentTokenFilterRegistration` 과 `filterChain` 이다.
- `shared` 규칙의 기준 파일은 `backend/config/archunit/store/25192a86-f325-4b77-b6d1-3c590c06ead7` 다. 17 줄 가운데 마지막 세 줄이 `SecurityConfig` 의 것이다.
- 필터의 공개 상수 `TOKEN_HASH_ATTRIBUTE` 는 `AgentTokenAuthenticationFilter.class.getName() + ".TOKEN_HASH"` 다. 클래스를 옮기면 이 문자열 값이 달라진다. 요청 속성의 이름일 뿐이고 읽는 쪽이 모두 이 상수를 쓰므로 동작은 같다. 운영 코드에서 읽는 곳과 `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java` 가 상수를 거치는지 `git grep -n "TOKEN_HASH" -- backend/src` 로 확인하고, 문자열을 직접 적은 곳이 있으면 보고한다.
- 결정의 근거는 `docs/adr/ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md` 의 S3 이다.

**근거 문서**: 위 ADR-068, `docs/backend/mcp-caller.md` 의 「어느 클래스가 무엇을 하나」, `docs/backend/packages.md` 의 「패키지와 책임」, `docs/adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md`

## 의도 메모

- **인증 경계를 건드리는 phase 다.** 필터의 본문은 한 줄도 바꾸지 않는다. 바뀌는 것은 패키지, 상속하는 타입, `SecurityConfig` 가 받는 타입뿐이다.
- 필터 순서가 그대로여야 한다. `addFilterBefore(agentTokenFilter, ControlPlaneJwtFilter.class)` 는 넘긴 객체의 실제 클래스로 순서를 등록하므로 받는 타입을 바꿔도 순서가 같다.
- Spring Boot 는 `Filter` 빈을 서블릿 필터로도 등록한다. 그것을 끄는 `FilterRegistrationBean` 이 지금처럼 같은 필터 객체를 감싸고 `setEnabled(false)` 여야 한다. 빠지면 필터가 두 번 돈다.
- `SecurityConfig` 를 새 최상위 패키지로 빼는 방법은 쓰지 않는다(ADR-068 의 「대안 기각」).

## 작업 항목

### 1. `shared/auth/AgentTokenFilter.java` 신규

`backend/src/main/java/com/bifos/assistant/shared/auth/AgentTokenFilter.java`.
`public abstract class AgentTokenFilter extends OncePerRequestFilter` 이고 본문이 없다.
Javadoc 에 「profile 토큰으로 인증하는 필터의 타입이다. `SecurityConfig` 가 구현 패키지를 모른 채 필터 순서에 넣으려고 둔다. 구현은 `mcp.presentation` 에 있다」 를 적는다.

### 2. 필터를 옮긴다

`git mv` 로 `backend/src/main/java/com/bifos/assistant/mcp/infra/AgentTokenAuthenticationFilter.java` 를
`backend/src/main/java/com/bifos/assistant/mcp/presentation/AgentTokenAuthenticationFilter.java` 로 옮긴다.
`package` 줄을 고치고 `extends OncePerRequestFilter` 를 `extends AgentTokenFilter` 로 바꾼다. 쓰지 않게 된 import 를 지우고 필요한 import 를 더한다. 그 밖의 줄은 바꾸지 않는다.

### 3. `SecurityConfig` 의 변경

`AgentTokenAuthenticationFilter` 로 받던 세 자리(메서드 인자 둘과 `FilterRegistrationBean` 의 타입 인자)를 `AgentTokenFilter` 로 바꾼다. `mcp` 의 import 를 지운다. 그 밖의 줄은 바꾸지 않는다.

### 4. 참조와 문서를 고친다

- `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java` 의 import 를 새 패키지로 고친다.
- `git grep -n "mcp\.infra\.AgentTokenAuthenticationFilter\|mcp/infra/AgentTokenAuthenticationFilter" -- backend/src docs AGENTS.md backend/AGENTS.md` 가 0 건이 되게 고친다.
- `docs/backend/mcp-caller.md` 의 표에서 `mcp.infra.AgentTokenAuthenticationFilter` 를 `mcp.presentation.AgentTokenAuthenticationFilter` 로 고친다.
- `docs/backend/packages.md` 의 「패키지와 책임」 표에서 `shared/auth` 의 책임을 「토큰 검사와 현재 사용자, profile 토큰 필터의 타입」 으로 고친다.
  그 표 아래의 「`shared` 가 `user` 와 `mcp` 를 쓰는 기존 위반은」 을 「`shared` 가 `user` 를 쓰는 기존 위반은」 으로 고친다.

### 5. 기준 파일을 줄인다

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
```

두 기준 파일이 함께 준다. 이 명령이 새 위반으로 실패하면 다시 얼리지 말고 어느 규칙의 어느 줄인지 보고한다.

### 6. 이 phase 를 검증하는 `AgentTokenFilterWiringTest.java`

`backend/src/test/java/com/bifos/assistant/mcp/AgentTokenFilterWiringTest.java` 를 새로 만든다. `@SpringBootTest` 와 `@AutoConfigureMockMvc`(이 저장소의 다른 MockMvc 테스트가 쓰는 방식을 따른다)다.

- 정상: `SecurityFilterChain` 빈의 필터 목록에서 `AgentTokenAuthenticationFilter` 가 `ControlPlaneJwtFilter` 보다 앞에 있고 각각 한 번만 있다
- 정상: `AgentTokenFilter` 타입의 `FilterRegistrationBean` 이 `isEnabled()` 가 false 다. 서블릿 필터로 한 번 더 등록되지 않는다는 뜻이다
- 실패: 토큰 없이 `POST /mcp` 를 부르면 401 이다
- 실패: `Origin` 헤더를 붙여 `POST /mcp` 를 부르면 403 이다

같은 단언이 이미 있는 테스트가 있으면 그 단언은 더하지 않고 어느 테스트에 있는지 회신에 적는다.

## 검증

```bash
# cwd: backend/
./gradlew test
./gradlew checkstyleMain checkstyleTest
test "$(wc -l < config/archunit/store/2790ecd4-faaa-4952-b703-a028b68814d5)" -eq 7
test "$(wc -l < config/archunit/store/25192a86-f325-4b77-b6d1-3c590c06ead7)" -eq 14
! grep -n "AgentTokenAuthenticationFilter\|SecurityConfig" config/archunit/store/2790ecd4-faaa-4952-b703-a028b68814d5 config/archunit/store/25192a86-f325-4b77-b6d1-3c590c06ead7
! grep -rn "com\.bifos\.assistant\.mcp" src/main/java/com/bifos/assistant/shared
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/shared/auth/AgentTokenFilter.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/infra/AgentTokenAuthenticationFilter.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/AgentTokenAuthenticationFilter.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/shared/config/SecurityConfig.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/AgentTokenFilterWiringTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java` | 수정 |
| `docs/backend/mcp-caller.md` | 수정 |
| `docs/backend/packages.md` | 수정 |
| `backend/config/archunit/store/2790ecd4-faaa-4952-b703-a028b68814d5` | 수정 |
| `backend/config/archunit/store/25192a86-f325-4b77-b6d1-3c590c06ead7` | 수정 |
