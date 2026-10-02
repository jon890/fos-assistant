# Phase 02. 웹이 `ACCESS_REVOKED` 를 받으면 세션을 끊고 로그인 화면으로 보낸다

**Execution profile**: standard

## 목표

Control Plane 이 401 과 `ACCESS_REVOKED` 로 답하면 웹이 세션 쿠키를 지우고 사용자를 `/signin` 으로 보낸다.
세션이 남아 있으면 꺼진 사용자가 화면마다 오류만 보고 로그인 화면으로 가지 못한다.

**범위 외**: Control Plane 의 판정(phase 01). 이미 열린 대화 스트림을 끊는 것.

## Blocked 조건

- `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` 에 `ACCESS_REVOKED` 가 없다 → `PHASE_BLOCKED: phase 01 이 끝나지 않았다` 출력 후 종료

## 컨텍스트

- **작업을 시작하기 전에 `web/node_modules` 가 있는지 보고, 없으면 `cd web && pnpm install --frozen-lockfile` 을 돌린다**

- Control Plane 을 부르는 곳은 `web/src/lib/control-plane.ts` 하나다.
  `requestControlPlane(path, init)` 과 `forwardControlPlane(path, init)` 이 `fetch` 한 `Response` 를 그대로 돌려주고, `callControlPlane<T>(path, init)` 은 `requestControlPlane` 을 거쳐 `readControlPlaneResult` 로 읽는다.
  API 라우트(`web/src/app/api/` 아래)와 서버 컴포넌트(`page.tsx`, `layout.tsx`)가 모두 이 세 함수를 쓴다
- 세션은 `web/src/auth.ts` 가 내보내는 `auth`, `signIn`, `signOut` 이다(next-auth 5 beta).
  `signOut` 은 쿠키를 고치므로 Route Handler 와 Server Action 에서만 된다. 서버 컴포넌트를 그리는 중에는 Next.js 가 쿠키 쓰기를 거절해 던진다.
  **이 동작을 구현 전에 `web/node_modules/next-auth/lib/actions.js` 의 `signOut` 을 읽어 확인한다.** 던지지 않고 조용히 지나가면 `PHASE_BLOCKED: signOut 이 서버 컴포넌트에서 던지지 않는다` 를 출력하고 멈춘다
- 페이지는 `const session = await auth(); if (!session?.user?.email) redirect("/signin");` 으로 시작한다. 세션이 지워지면 이 선례대로 `/signin` 으로 간다
- `web/src/app/layout.tsx` 가 화면을 처음 그릴 때마다 `readMe()`(`web/src/lib/me.ts`)를 부른다. `readMe` 는 `try/catch` 로 모든 예외를 삼키고 null 을 돌려준다
- 오류 코드를 문구로 바꾸는 곳은 `web/src/components/error-message.ts` 의 `MESSAGES` 다. 검사는 `test/unit/error-message.test.ts` 다
- `@/auth` 를 읽지 않는 순수 함수의 선례는 `web/src/lib/control-plane-result.ts` 와 `test/unit/control-plane-result.test.ts` 다. 단위 검사는 `@/auth` 를 읽는 파일을 부르지 못한다
- 브라우저 검사는 `test/browser/` 에 있다. `import { expect, setSession, test } from "./fixtures.ts";` 를 쓴다.
  기본 세션은 관리자 `TEST_EMAIL`(`test/browser/settings.ts`)이다.
  사람을 미리 더하는 선례는 `test/browser/people.spec.ts` 의 `newcomer` 와 `addPerson` 이다. `page.request.post("/api/admin/people", { data })` 로 더하고 응답 본문에 `id` 가 있다.
  끄는 것은 `page.request.patch("/api/admin/people/{id}", { data: { enabled: false } })` 다.
  `mobile` 과 `desktop` 두 project 가 같은 데이터베이스를 쓰므로 주소와 profile 이름에 `testInfo.project.name` 을 넣는다

**근거 문서**: `docs/adr/ADR-059-꺼진-사용자는-control-plane-이-요청마다-막고-웹이-세션을-끊는다.md`, `docs/frontend/structure.md` 의 「꺼진 사용자의 세션」, `web/AGENTS.md`

## 의도 메모

- 화면 58곳의 `fetch` 를 하나씩 고치지 않는다. Control Plane 응답을 받는 한곳에서 처리한다
- 401 전체를 로그아웃으로 다루지 않는다. `ACCESS_REVOKED` 코드일 때만 세션을 지운다. `UNAUTHENTICATED` 는 세션이 없을 때 웹이 스스로 만드는 답이기도 하다
- API 라우트에서 `redirect()` 를 던지지 않는다. 브라우저의 `fetch` 가 로그인 화면 HTML 을 JSON 으로 읽게 된다. 쿠키만 지우고 401 을 그대로 돌려준다
- `/signout/revoked` 는 GET 이라 다른 사이트가 이 주소로 보낼 수 있다. 그래서 스스로 Control Plane 에 다시 물어 꺼진 것이 맞을 때만 세션이 지워지게 한다

## 작업 항목

### 1. `web/src/lib/access-revoked.ts` (신규)

`@/auth` 와 `next` 를 읽지 않는다.

```ts
export const ACCESS_REVOKED = "ACCESS_REVOKED";
/** 서버 컴포넌트가 꺼진 사용자를 넘기는 라우트다. 그 라우트가 세션 쿠키를 지운다. */
export const REVOKED_SIGN_OUT_PATH = "/signout/revoked";
/** 401 응답의 본문이 꺼진 사용자라고 말하는가. JSON 이 아니거나 code 가 다르면 거짓이다. */
export function isAccessRevoked(status: number, text: string): boolean
```

### 2. `web/src/lib/control-plane.ts` 가 응답을 받는 자리에서 세션을 끊는다

파일 안에 `async function endRevokedSession(response: Response): Promise<void>` 를 둔다.

1. `response.status !== 401` 이면 바로 돌아간다
2. `isAccessRevoked(401, await response.clone().text())` 가 거짓이면 돌아간다. 원래 `response` 의 본문은 읽지 않는다
3. `await signOut({ redirect: false })` 를 부른다. Route Handler 에서는 여기서 쿠키가 지워지고 함수가 돌아간다
4. `signOut` 이 쿠키를 고칠 수 없다는 오류(서버 컴포넌트를 그리는 중이다. Next.js 의 문구는 `Cookies can only be modified in a Server Action or Route Handler` 다)를 던지면 `redirect(REVOKED_SIGN_OUT_PATH)` 를 부른다. 그 밖의 오류는 그대로 다시 던진다. 모든 오류에 `redirect` 하면 `/signout/revoked` 안에서 실패했을 때 같은 주소로 되돌아오는 루프가 된다. 어떤 오류인지 가리는 조건은 `web/node_modules/next` 의 그 오류 정의를 읽고 정한다. `redirect` 는 `next/navigation` 에서 가져온다. `redirect` 가 던지는 것을 다시 잡지 않도록 `catch` 블록 안에서 부른다

`requestControlPlane` 과 `forwardControlPlane` 이 `fetch` 가 돌려준 `Response` 를 돌려주기 전에 `await endRevokedSession(response)` 를 부른다.
`forwardControlPlane` 은 `fetch` 를 `try/catch` 로 감싸고 있다. `endRevokedSession` 은 그 `try` 밖에서 부른다. 안에서 부르면 `redirect` 가 던진 것이 502 로 바뀐다.
`callControlPlane` 은 `requestControlPlane` 을 거치므로 고치지 않는다.
`isSignInAllowed` 는 고치지 않는다.

### 3. `redirect` 를 삼키는 `try/catch` 를 고친다

`web/src/lib/me.ts` 의 `readMe` 는 `catch` 에서 null 을 돌려준다. `catch (error)` 로 받아 첫 줄에서 `unstable_rethrow(error)`(`next/navigation`)를 부른다.

같은 문제가 있는 곳을 찾는다.

```bash
grep -rnE "callControlPlane|requestControlPlane|forwardControlPlane" web/src/app web/src/lib --include="page.tsx" --include="layout.tsx" --include="*.ts"
```

**서버 컴포넌트가 부르는 경로에서** 이 세 함수의 호출을 `try/catch` 로 감싼 곳은 같은 방법으로 고친다. API 라우트(`route.ts`)는 `signOut` 이 성공해 `redirect` 가 던져지지 않으므로 고치지 않는다.

### 4. `web/src/app/signout/revoked/route.ts` (신규)

```ts
export async function GET(): Promise<never>
```

`await callControlPlane("/api/v1/me")` 를 부른 뒤 `redirect("/")` 한다.
꺼진 사용자면 2번의 `endRevokedSession` 이 이 Route Handler 안에서 쿠키를 지우고, `/` 는 세션이 없어 `/signin` 으로 간다.
켜져 있는 사용자면 아무것도 지워지지 않고 `/` 로 돌아간다.
이 까닭을 주석으로 적는다.

### 5. `web/src/components/error-message.ts` 에 문구를 더한다

`MESSAGES` 의 `UNAUTHENTICATED` 아래에 둔다.

```ts
ACCESS_REVOKED: "사용이 중지된 계정이에요. 관리자에게 문의해 주세요.",
```

### 6. `test/unit/access-revoked.test.ts` (신규)

`test/unit/control-plane-result.test.ts` 와 같은 방식으로 `web/src/lib/access-revoked.ts` 를 읽는다.

| 입력 | 기대 |
| --- | --- |
| `401`, `{"code":"ACCESS_REVOKED","message":"x"}` | 참 |
| `401`, `{"code":"UNAUTHENTICATED","message":"x"}` | 거짓 |
| `403`, `{"code":"ACCESS_REVOKED"}` | 거짓 |
| `401`, `<html>` | 거짓 |
| `401`, 빈 글 | 거짓 |

`test/unit/error-message.test.ts` 에 `describeError("ACCESS_REVOKED", "x")` 가 위 문구를 돌려주는 검사를 더한다.

### 7. `test/browser/access-revoked.spec.ts` (신규)

관리자 세션으로 사람을 더하고 끈 뒤, 그 사람의 세션으로 본다. **검사마다 다른 사람을 만든다.** 같은 주소를 두 검사가 더하면 뒤 검사가 `PERSON_EMAIL_TAKEN` 409 로 실패한다. 첫 검사는 주소 `revoked-page-${project}@example.com` 과 profile `revokedpage${project}`, 둘째 검사는 `revoked-api-${project}@example.com` 과 `revokedapi${project}` 를 쓴다. `project` 는 `testInfo.project.name` 이다.

| 검사 | 순서 | 기대 |
| --- | --- | --- |
| 꺼진 사용자가 화면을 열면 로그인 화면으로 간다 | 사람을 더한다. `setSession(context, 그 사람)` 뒤 `/` 를 열어 한 번 들어오게 한다. `setSession` 으로 관리자로 돌아가 끈다. 다시 그 사람으로 `setSession` 하고 `/agents` 를 연다 | 주소가 `/signin` 으로 끝난다. 세션 쿠키가 없다 |
| 꺼진 사용자의 API 요청은 401 이고 세션이 지워진다 | 위와 같이 끈 뒤 그 사람의 세션으로 `page.request.get("/api/me")` | 상태 401, 본문의 `code` 가 `ACCESS_REVOKED`. **응답 직후 `context.cookies()` 에 세션 쿠키가 없는지를 먼저 단언한다.** 이 단언이 없으면 API 라우트가 쿠키를 지우지 못해도 서버 컴포넌트 경로가 대신 지워 통과한다. 이어서 `/` 를 열면 `/signin` 으로 간다 |
| 켜져 있는 사용자는 `/signout/revoked` 를 열어도 로그아웃되지 않는다 | 기본 세션으로 `/signout/revoked` 를 연다 | 주소가 `/` 이고 `/signin` 이 아니다 |

세션 쿠키 이름은 `test/browser/fixtures.ts` 의 `setSession` 이 쓰는 상수와 같은 값을 쓴다. 그 상수가 내보내지지 않았으면 `context.cookies()` 에서 이름에 `session-token` 이 든 쿠키가 없는지로 본다.

## 검증

```bash
node --test 'test/unit/**/*.test.ts'
cd web && pnpm typecheck
cd web && pnpm lint && pnpm format:check
cd web && pnpm test:browser test/browser/access-revoked.spec.ts test/browser/people.spec.ts
scripts/check-public-safe.sh
```

모두 종료 코드 0 이어야 한다. `node --test` 는 저장소 root 에서 돌린다.
`pnpm test:browser` 가 `access-revoked.spec.ts` 와 `people.spec.ts` 를 실제로 돌렸는지 출력의 파일 이름으로 확인한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/access-revoked.ts` | 신규 |
| `web/src/lib/control-plane.ts` | 수정 |
| `web/src/lib/me.ts` | 수정 |
| `web/src/app/signout/revoked/route.ts` | 신규 |
| `web/src/components/error-message.ts` | 수정 |
| `test/unit/access-revoked.test.ts` | 신규 |
| `test/unit/error-message.test.ts` | 수정 |
| `test/browser/access-revoked.spec.ts` | 신규 |
