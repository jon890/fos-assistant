# Phase 01. 화면이 현재 사용자의 역할을 알게 한다

**Execution profile**: standard

## 목표

Control Plane 이 현재 사용자를 돌려주는 경로를 만들고,
`에이전트 관리` 메뉴를 관리자에게만 보인다.

지금은 그 메뉴가 모든 구성원에게 보인다.
누르면 서버가 `FORBIDDEN` 으로 거절하므로 자료가 새지는 않지만, 쓸 수 없는 메뉴가 보인다.
화면이 역할을 알 방법이 없어서 그렇다.

**범위 외**

- 권한 검사를 화면으로 옮기지 않는다. **서버의 `requireAdmin` 을 그대로 둔다.**
  화면은 보이는 것만 정한다. 화면이 권한의 근거가 되면 브라우저를 고쳐 넘을 수 있다.
- 역할을 바꾸는 화면은 만들지 않는다.
- 구성원을 초대하거나 지우는 흐름은 만들지 않는다.

## 컨텍스트

`CurrentUser` 가 이미 `id`, `email`, `displayName`, `role` 을 들고 있다.
`role` 은 `ADMIN` 또는 `MEMBER` 다. 첫 사용자가 `ADMIN` 이 된다.

그런데 그것을 돌려주는 엔드포인트가 없다.
웹 세션에도 역할이 없다. `web/src/auth.ts` 는 메일 주소와 이름만 담는다.

**Spring Boot 4 는 Jackson 3 을 쓴다.** `tools.jackson` 을 import 한다.

따를 패턴을 경로로 짚는다.

| 무엇 | 경로 |
| --- | --- |
| 현재 사용자 | `backend/src/main/java/com/bifos/assistant/shared/auth/CurrentUser.java` |
| 현재 사용자 조회 | `backend/src/main/java/com/bifos/assistant/shared/auth/CurrentUserProvider.java` |
| 가장 단순한 컨트롤러 | `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentController.java` |
| 서버에서 직접 읽는 화면 | `web/src/app/usage/page.tsx` |
| Control Plane 호출 | `web/src/lib/control-plane.ts` |
| 머리와 메뉴 | `web/src/app/layout.tsx` |

**근거 문서**: `docs/code-architecture.md` 의 「web 화면 구조」 절

## 의도 메모

- 역할을 NextAuth 세션에 넣지 않는다. 세션은 로그인할 때 한 번 만들어져 오래 남는다.
  관리자로 바꾸거나 내려도 그 사람이 다시 로그인할 때까지 옛 값이 남는다.
  대신 화면을 그릴 때마다 Control Plane 에 묻는다.
- `layout.tsx` 는 서버 컴포넌트다. 거기서 직접 부른다. 브라우저가 부르지 않는다.
- 부르지 못하면 메뉴를 **숨긴다.** 보이게 하는 쪽으로 기울이지 않는다.
  못 읽었을 때 보여 주면, 서버가 흔들릴 때마다 쓸 수 없는 메뉴가 나타난다.
- 이름도 함께 받아 머리에 보인다. 여러 사람이 쓰는 화면에서 누구로 로그인했는지 보여야 한다.

## 작업 항목

### 1. 현재 사용자 조회를 더한다

`backend/src/main/java/com/bifos/assistant/user/presentation/MeController.java` 를 만든다.

- `GET /api/v1/me` 가 `id`, `email`, `displayName`, `role` 을 돌려준다.
- `CurrentUserProvider` 로 지금 사용자를 꺼낸다. 관리자만 쓰는 것이 아니다.
- 응답 DTO 는 `record` 로 둔다.

### 2. 서버 라우트를 더한다

`web/src/app/api/me/route.ts` 를 만든다.

- `GET` 이 `callControlPlane` 으로 `/api/v1/me` 를 부른다.
- `web/src/app/api/usage/route.ts` 와 같은 형태를 따른다.

브라우저가 쓸 일이 없어도 만든다.
뒤에 클라이언트 컴포넌트가 필요해질 때 두 곳에서 다르게 부르는 것을 막는다.

### 3. 서버에서 읽는 도우미를 더한다

`web/src/lib/me.ts` 를 만든다.

- `readMe()` 가 `callControlPlane<Me>("/api/v1/me")` 를 부른다.
- 실패하면 `null` 을 돌려준다. 예외를 던지지 않는다. 머리가 깨지면 모든 화면이 깨진다.

### 4. 메뉴를 역할에 따라 보인다

`web/src/app/layout.tsx` 를 고친다.

- `readMe()` 로 현재 사용자를 읽는다.
- `role` 이 `ADMIN` 일 때만 `에이전트 관리` 링크를 그린다.
- 읽지 못했으면 그리지 않는다.
- 로그인한 사람의 이름을 머리에 보인다. 좁은 화면에서는 이름을 줄인다.
- 로그인 화면에서는 이것을 부르지 않는다. 세션이 없으면 `readMe()` 가 `null` 이다.

### 5. 이 phase 를 검증하는 테스트

백엔드는 기존 e2e 에 더한다.
`test/e2e/scenarios/` 아래의 파일 형태를 따르고 `test/e2e/run.ts` 에 등록한다.

- 관리자가 `GET /api/v1/me` 를 부르면 `role` 이 `ADMIN` 이다.
- 구성원이 부르면 `role` 이 `MEMBER` 다.
- 토큰이 없으면 401 이다.

브라우저는 `test/browser/nav.spec.ts` 를 만든다.
하네스는 `test/browser/fixtures.ts` 를 쓴다.

- 관리자로 들어가면 `에이전트 관리` 링크가 보인다.
- 구성원으로 들어가면 그 링크가 보이지 않는다.
- 머리에 로그인한 사람의 이름이 보인다.

**구성원 세션을 만들 방법이 하네스에 있는지 먼저 본다.**
지금 하네스가 관리자 한 사람만 만든다면 구성원을 더 만들 수 있게 넓힌다.
넓힐 수 없으면 그 사실과 이유를 보고에 적고, 백엔드 e2e 로만 그 경우를 확인한다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
```

```bash
# cwd: 저장소 root
cd web && pnpm typecheck
cd web && pnpm build
cd web && pnpm test:browser
```

빌드는 자리표시자 환경 변수가 필요하다. `web/Dockerfile` 이 쓰는 것과 같다.

```bash
# cwd: 저장소 root
node test/e2e/run.ts
```

```bash
# cwd: 저장소 root
grep -rn "requireAdmin" backend/src/main/java/com/bifos/assistant/agent/presentation/AgentAdminController.java
```

**`requireAdmin` 호출이 그대로 남아 있어야 한다.** 화면이 메뉴를 숨기는 것으로 서버 검사를 대신하지 않는다.

```bash
# cwd: 저장소 root
grep -rn "com.fasterxml.jackson" backend/src && echo "실패: Jackson 2 참조" || echo "통과"
```

## Critical Files

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/user/presentation/MeController.java` | 신규 |
| `web/src/app/api/me/route.ts` | 신규 |
| `web/src/lib/me.ts` | 신규 |
| `web/src/app/layout.tsx` | 수정 |
| `test/e2e/scenarios/` | 신규 |
| `test/e2e/run.ts` | 수정 |
| `test/browser/nav.spec.ts` | 신규 |
| `test/browser/fixtures.ts` | 수정 가능 |

## 끝낸 뒤

`tasks/plan006-admin-menu/index.json` 의 이 phase 를 `completed` 로 바꾸고
plan 의 `status` 를 `completed` 로 바꾼다.
