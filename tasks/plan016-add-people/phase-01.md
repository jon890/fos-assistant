# Phase 01. 로그인 허용 목록을 표로 옮긴다

**Execution profile**: standard

## 목표

누가 로그인할 수 있는지를 환경 변수가 아니라 데이터베이스가 정하게 한다.
사람을 화면에서 더하려면 그 목록이 실행 중에 바뀔 수 있어야 한다.

**범위 외**:
Hermes profile 을 만드는 것은 phase-02 가, 화면과 에이전트 생성은 phase-03 이 한다.
이 phase 는 표를 만들고 로그인 판정이 그 표를 보게 하는 데까지다.

## 컨텍스트

지금은 `web/src/auth.ts` 의 `allowedEmails()` 가 `ASSISTANT_ALLOWED_EMAILS` 를
쉼표로 끊어 읽는다. 값을 바꾸려면 컨테이너를 다시 띄워야 한다.

**근거 문서**:
`docs/data-schema.md` 의 「allowed_person」 절,
`docs/flow.md` 의 「사람을 더할 때」 절,
`docs/adr/ADR-018-사람을-더하는-것을-control-plane-이-끝낸다.md`.

### 로그인 판정은 사용자가 없는 시점에 일어난다

NextAuth 의 `signIn` 콜백은 아직 아무 사용자도 없는 시점에 돈다.
그래서 지금 쓰는 짧은 수명 JWT 를 그대로 쓸 수 없다.

**그 토큰을 그대로 쓰면 안 되는 이유가 하나 더 있다.**
`ControlPlaneJwtFilter` 는 토큰을 받으면 `UserProvisioningService.resolve` 를 불러
그 자리에서 사용자를 만든다. 허용되지 않은 주소로 그 경로를 타면 사용자가 생긴다.

## 의도 메모

- 표를 `app_user` 에 칸을 더하는 것으로 대신하는 안을 버렸다.
  허용한 사람과 들어온 적 있는 사람은 다른 것이고,
  허용 목록에 넣는 시점에는 `app_user` 가 없다.
- 허용 목록에서 빼는 것을 행 삭제로 하는 안을 버렸다.
  `enabled` 를 내린다. 이 저장소는 실행 기록이 가리키는 것을 지우지 않는다.
- 외래 키로 `app_user` 와 잇는 안을 버렸다. 만드는 순서가 반대다.

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V16__allowed_person.sql`

표 하나를 만든다. 칸과 제약은 `docs/data-schema.md` 의 「allowed_person」 절이 정한다.

- `email` 과 `hermes_profile` 에 각각 유니크 제약을 둔다
- `enabled` 는 기본값 없이 넣는 쪽이 정한다
- **이 파일에 어떤 주소도 넣지 않는다.** 이 저장소는 공개다

### 2. `backend/src/main/java/com/bifos/assistant/people/` 를 만든다

새 도메인 패키지다. 배치 규칙은 `docs/code-architecture.md` 의 「backend 패키지」가 정한다.
`presentation` 에서 `application`, `domain`, `infra` 로만 흐른다.

이 phase 에서 만드는 것이다.

| 경로 | 무엇 |
| --- | --- |
| `domain/AllowedPerson.java` | 엔티티 |
| `infra/AllowedPersonRepository.java` | `findByEmailAndEnabledTrue` 와 `existsByEmail` 과 `existsByHermesProfile` |
| `application/SignInPolicy.java` | 주소 하나를 받아 들어와도 되는지 판정한다 |

`com.bifos.assistant.user.domain.AppUser` 의 엔티티 작성 방식을 그대로 따른다.

#### 이메일은 저장할 때 소문자로 맞춘다

**정규화하는 자리는 `AllowedPerson` 엔티티 하나다.**
행을 만들 때 소문자로 바꿔 넣고, 찾을 때도 소문자로 바꿔 찾는다.

읽는 쪽에서만 맞추면 대문자가 섞인 주소가 `existsByEmail` 을 빠져나가
같은 사람이 두 행으로 들어온다. phase-03 의 `PersonRegistrar` 가 그 검사를 쓴다.

### 3. 로그인 판정 경로를 연다

`presentation/SignInController.java` 에 하나를 둔다.

```
POST /api/v1/signin/allowed
요청  { "email": "..." }
응답  { "allowed": true,  "displayName": "...", "hermesProfile": "..." }
      { "allowed": false }
```

**경로에 `/v1` 이 들어간다.** 이 저장소의 컨트롤러는 모두 `/api/v1/...` 이다.

**이 경로는 사용자를 만들지 않는다.** 판정만 한다.

#### 두 곳을 함께 열어야 닿는다

`shouldNotFilter` 만 고치면 컨트롤러에 닿지 못한다.
`backend/.../shared/config/SecurityConfig.java` 가 `anyRequest().authenticated()` 라
필터에서 빠지기만 한 경로는 Spring Security 가 앞에서 거절한다.

| 파일 | 무엇 |
| --- | --- |
| `ControlPlaneJwtFilter.java` 의 `shouldNotFilter` | 이 경로를 더한다. 지금 `/mcp` 하나가 그렇게 빠져 있다 |
| `SecurityConfig.java` 의 `authorizeHttpRequests` | 이 경로를 `permitAll()` 로 둔다. 지금 `/api/v1/me` 가 그렇게 열려 있다 |

#### 상태 코드는 그 경로가 직접 낸다

`permitAll()` 로 열면 Spring Security 가 더 이상 상태 코드를 정하지 않는다.
**그 대신 이 경로만 보는 검사를 컨트롤러 안에 둔다.**
같은 `AuthProperties.jwtSecret()` 으로 서명한 토큰을 받되
`purpose` 가 `signin` 인 것만 통과시킨다. **그 토큰에 사용자 신원을 담지 않는다.**

- 토큰이 없거나 서명이 틀리면 401
- `purpose` 가 다르면 401. 대화용 토큰으로 이 경로를 부를 수 없다

**401 을 직접 내야 한다.** 그러지 않으면 403 이 나온다.
403 을 내는 것은 `SecurityConfig` 의 `anyRequest().authenticated()` 다.
`ControlPlaneJwtFilter` 는 토큰이 없으면 아무것도 하지 않고 지나간다.
`test/e2e/scenarios/auth.ts` 가 서명이 틀린 토큰에 403 을 기대하는 것이 그 증거다.

### 4. `web/src/auth.ts` 가 그 경로를 부르게 한다

`allowedEmails()` 를 지우고 `signIn` 콜백이 Control Plane 에 묻게 한다.

- 응답의 `allowed` 가 참일 때만 참을 돌려준다
- **Control Plane 이 응답하지 않으면 거짓을 돌려준다.** 판정하지 못할 때 들여보내지 않는다
- `purpose: "signin"` 토큰을 만드는 자리는 지금 대화용 토큰을 만드는 자리 옆에 둔다

`ASSISTANT_ALLOWED_EMAILS` 를 읽는 코드를 남기지 않는다.
`web/.env.example` 과 `test/browser/playwright.config.ts` 와 `README.md` 에서도 함께 뺀다.

**`docs/adr/ADR-018` 의 그 낱말은 그대로 둔다.**
옛 절차를 적은 맥락 절이라 지우면 결정의 근거가 사라진다.
`tasks/` 아래도 건드리지 않는다. 계획서 자신이 그 낱말을 담는다.

### 5. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/people/SignInPolicyTest.java` 를 만든다.

| 무엇 | 기대 |
| --- | --- |
| 목록에 있고 `enabled` 인 주소 | 허용. `hermesProfile` 이 함께 온다 |
| 목록에 없는 주소 | 거절 |
| 목록에 있지만 `enabled` 가 아닌 주소 | 거절 |
| 대문자가 섞인 주소 | 허용. 소문자로 맞춰 찾는다 |

`SignInControllerTest` 도 만든다.

| 무엇 | 기대 |
| --- | --- |
| `purpose` 가 `signin` 인 토큰 | 200 |
| 토큰 없음 | 401 |
| `purpose` 가 없는 대화용 토큰 | 401 |
| 허용되지 않은 주소로 부른 뒤 | `app_user` 가 늘지 않았다 |

마지막 줄이 이 phase 의 핵심이다. 판정 경로가 사용자를 만들면 안 된다.

`test/e2e/scenarios/` 에도 하나 더한다.
기존 시나리오 파일의 짜임을 따르고, **`test/e2e/run.ts` 의 `SCENARIOS` 배열에 넣는다.**
배열에 넣지 않으면 그 시나리오는 돌지 않는다.

**e2e 는 웹 계층을 띄우지 않는다.** `run.ts` 가 띄우는 것은 Control Plane 과 가짜 Hermes 다.
그래서 「로그인을 시도한다」가 아니라 `POST /api/v1/signin/allowed` 를 직접 부르는 것으로 적는다.

| 무엇 | 기대 |
| --- | --- |
| 허용 목록에 없는 주소로 부른다 | `allowed` 가 거짓이다 |
| `purpose` 가 `signin` 이 아닌 토큰으로 부른다 | 401 |

**허용된 주소가 통과하는 것은 여기서 보지 않는다.**
e2e 에서 `allowed_person` 에 행을 넣을 길이 없기 때문이다.
그 행을 만드는 `PersonRegistrar` 와 `POST /api/v1/admin/people` 이 둘 다 phase-03 이고,
`test/e2e/run.ts` 는 `ASSISTANT_TESTSUPPORT_ENABLED` 를 넘기지 않아 시험용 경로도 닫혀 있다.

**허용된 주소의 통과는 phase-03 의 e2e 시나리오가 본다.** 거기서는 사람을 먼저 더한다.
`SignInPolicyTest` 와 `SignInControllerTest` 는 repository 에 직접 저장하므로 이 제약이 없다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
scripts/check-public-safe.sh
```

앞의 넷은 함께 돌려도 된다. 검사마다 빈 포트를 받아 쓴다.

아래가 아무것도 내지 않아야 한다.

```bash
# cwd: 저장소 root
grep -rn "ASSISTANT_ALLOWED_EMAILS" \
  --exclude-dir=node_modules --exclude-dir=.next \
  web/ backend/ test/ README.md
```

**저장소 전체를 훑지 않는다.** `docs/adr/ADR-018` 과 `tasks/` 가 그 낱말을 정당하게 담는다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V16__allowed_person.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/people/domain/AllowedPerson.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/people/infra/AllowedPersonRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/people/application/SignInPolicy.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/people/presentation/SignInController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/people/presentation/PeopleDtos.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/config/SecurityConfig.java` | 수정 |
| `web/src/auth.ts` | 수정 |
| `web/.env.example` | 수정 |
| `README.md` | 수정 |
| `test/browser/playwright.config.ts` | 수정 |
| `backend/src/test/java/com/bifos/assistant/people/SignInPolicyTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/people/SignInControllerTest.java` | 신규 |
| `test/e2e/scenarios/` | 추가 |
| `test/e2e/run.ts` | 수정 |

## 끝낸 뒤

`tasks/plan016-add-people/index.json` 의 이 phase 를 `completed` 로 바꾸고
`current_phase` 를 2로 올린다.

**배포하면 아무도 로그인하지 못한다.** 표가 비어 있기 때문이다.
지금 쓰는 주소를 표에 한 번 넣어야 하고, **그 절차는 비공개 저장소 `fos-home-infra` 가 소유한다.**
배포를 요청할 때 그 사실을 함께 전한다.
