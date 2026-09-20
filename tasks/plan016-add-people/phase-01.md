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

### 3. 로그인 판정 경로를 연다

`presentation/SignInController.java` 에 하나를 둔다.

```
POST /api/signin/allowed
요청  { "email": "..." }
응답  { "allowed": true,  "displayName": "...", "hermesProfile": "..." }
      { "allowed": false }
```

**이 경로는 사용자를 만들지 않는다.** 판정만 한다.

`backend/.../shared/auth/ControlPlaneJwtFilter.java` 의 `shouldNotFilter` 에 이 경로를 더한다.
지금 `/mcp` 하나가 그렇게 빠져 있다.

그 대신 이 경로만 보는 검사를 둔다. 같은 `AuthProperties.jwtSecret()` 으로 서명한 토큰을 받되
`purpose` 가 `signin` 인 것만 통과시킨다. **그 토큰에 사용자 신원을 담지 않는다.**

- 토큰이 없거나 서명이 틀리면 401
- `purpose` 가 다르면 401. 대화용 토큰으로 이 경로를 부를 수 없다

### 4. `web/src/auth.ts` 가 그 경로를 부르게 한다

`allowedEmails()` 를 지우고 `signIn` 콜백이 Control Plane 에 묻게 한다.

- 응답의 `allowed` 가 참일 때만 참을 돌려준다
- **Control Plane 이 응답하지 않으면 거짓을 돌려준다.** 판정하지 못할 때 들여보내지 않는다
- `purpose: "signin"` 토큰을 만드는 자리는 지금 대화용 토큰을 만드는 자리 옆에 둔다

`ASSISTANT_ALLOWED_EMAILS` 를 읽는 코드를 남기지 않는다.
`web/.env.example` 과 `test/browser` 와 `test/e2e` 에서도 함께 뺀다.
어디에 남아 있는지는 `grep -rn ASSISTANT_ALLOWED_EMAILS` 로 찾는다.

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
허용 목록에 없는 주소로 로그인을 시도하면 막히는 것을 본다.
기존 시나리오 파일의 짜임을 따른다.

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
grep -rn "ASSISTANT_ALLOWED_EMAILS" --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=.next .
```

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
| `web/src/auth.ts` | 수정 |
| `web/.env.example` | 수정 |
| `backend/src/test/java/com/bifos/assistant/people/SignInPolicyTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/people/SignInControllerTest.java` | 신규 |
| `test/e2e/scenarios/` | 추가 |

## 끝낸 뒤

`tasks/plan016-add-people/index.json` 의 이 phase 를 `completed` 로 바꾸고
`current_phase` 를 2로 올린다.

**배포하면 아무도 로그인하지 못한다.** 표가 비어 있기 때문이다.
지금 쓰는 주소를 표에 한 번 넣어야 하고, **그 절차는 비공개 저장소 `fos-home-infra` 가 소유한다.**
배포를 요청할 때 그 사실을 함께 전한다.
