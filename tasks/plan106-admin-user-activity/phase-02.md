# Phase 02. 로그인 이벤트와 사용자 활동 화면

**Execution profile**: deep

## 목표

로그인 성공 이벤트를 기록하고 관리자 사용자 목록과 펼침 상세에 두 시각을 표시한다.

**범위 외**: backend 저장 모델과 응답 계약의 재설계, PR, push, 머지와 홈서버 접속.

## 컨텍스트

**근거 문서**: `docs/backend/people.md`의 「관리자에게 보이는 최근 활동」, `docs/backend/schema/users-agents.md`의 「allowed_person」, `docs/frontend/structure.md`의 「관리자 영역」, `docs/flow.md`의 「로그인 활동 기록」.

지금 `PersonList`의 「첫 로그인」은 `Person.joined`라는 app_user 존재 여부이며 시각이 아니다. 사용자 상세 화면은 없어 행별 펼침으로 추가한다. `SignInPolicy.admit`은 판정만 한다. 일반 토큰 필터는 매 요청 `UserProvisioningService.resolve`를 호출하므로 그곳에 로그인 시각을 쓰지 않는다.

## 의도 메모

- 지난 로그인 시각을 created_at으로 추정하지 않는다. 값이 없으면 null이다.
- 메시지 본문은 내보내지 않으며 sender_user_id를 기준으로 USER 메시지만 센다.
- 새 의존은 추가하지 않고 ADMIN 경계와 signin 서명 토큰 검증을 유지한다.

## 작업 항목

### 1. 로그인 완료 이벤트와 테스트

`web/src/auth.ts`의 기존 signIn callback은 허용 판정만 한다. events.signIn에서 user.email이 있을 때만 새 `signin-activity.ts`의 recordSignIn(email)을 호출한다. events.session이나 jwt 일반 갱신에는 넣지 않는다.
새 helper는 jose SignJWT로 purpose=signin, HS256, issuedAt, 2m expiry만 가진 토큰을 만들고 ASSISTANT_JWT_SECRET과 CONTROL_PLANE_BASE_URL을 실행 때 읽어 /api/v1/signin/completed에 {email}을 no-store POST한다. 일반 사용자 토큰과 이메일 subject는 넣지 않는다. 응답 실패/네트워크 실패는 던져 Auth.js EventError 로그에 남게 한다. fetch에는 5초 timeout을 주고 재시도하지 않는다. 새 파일은 Node 테스트가 직접 읽으므로 상대 경로 import 예외 목록 NODE_TEST_READ_FILES에 추가한다.
`test/unit/signin-activity.test.ts`에서 fetch를 대역으로 바꿔 요청 주소·method·본문·no-store·timeout signal과 jwtVerify로 서명/목적/수명을 확인한다. HTTP 실패와 네트워크 실패에서 reject됨을 확인하고 env/fetch 상태를 복원한다.

### 2. 목록과 펼침 상세

`Person`에 nullable lastLoginAt와 lastConversationAt을 추가한다. 기존 joined는 상세의 첫 로그인 여부에 유지한다.
페이지에서 readAt을 new Date().toISOString()으로 만들어 PeopleAdminPanel/PersonList에 내려 hydration의 상대 기준을 고정한다. reload 때 기준도 새로 설정한다.
새 `PersonActivity`는 null이면 「기록 없음」, 값이 있으면 기존 formatRelative와 formatFullTime을 재사용해 time dateTime과 상대/정확한 시각을 함께 표시한다. 정확한 시각은 초까지 확인할 수 있어야 하며 시간대는 「서울 시각」으로 적는다. formatFullTime은 기존 동작을 유지하고 이 컴포넌트에서 초를 추가하는 Intl.DateTimeFormat을 사용해도 된다.
PersonList는 첫 로그인 열을 마지막 로그인과 마지막 대화 두 열로 대체한다. 사용자별 「상세」 단추와 펼침 행을 두어 aria-expanded/aria-controls를 붙이고 이름·이메일·profile·첫 로그인 여부·두 시각을 표시한다. 추가 요청은 하지 않는다. 모바일에서는 표 가로 스크롤이 작동해야 하며 일반 사용자 영역에는 표시하지 않는다.

### 3. 브라우저 검증

기존 people.spec.ts의 미가입 단언을 두 칸 「기록 없음」과 상세의 joined 여부로 갱신한다. newcomer 생성은 helpers.isolatedUser 기반으로 반복 실행과 폭마다 겹치지 않게 한다. 상태 변경 요청은 clickAndWaitForResponse를 사용한다.
새 people-activity.spec.ts는 실제 Control Plane API로 허용 사용자, signin 완료(서명한 signin 토큰) 및 사용자 메시지를 만든 뒤 관리자 페이지에서 두 시각·time datetime·펼침 상세를 확인한다. 기록 없는 사용자와 MEMBER API/화면 거절도 확인한다. 마지막 시각을 응답에서 읽어 단언하고 임의 sleep은 쓰지 않는다.

## 검증

```bash
node --test test/unit/signin-activity.test.ts test/unit/relative-time.test.ts
cd web && pnpm typecheck
cd web && pnpm test:browser people.spec.ts people-activity.spec.ts --repeat-each=3 --retries=0
```

- `node --test test/unit/signin-activity.test.ts test/unit/relative-time.test.ts`가 통과한다.
- `cd web && pnpm typecheck`가 통과한다.
- `cd web && pnpm test:browser people.spec.ts people-activity.spec.ts --repeat-each=3 --retries=0`를 기본 빌드 서버로 실행하고 모바일·데스크톱에서 통과한다. heavy-lock으로 감싸서 실행한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `web/src/auth.ts` | 수정 |
| `web/src/lib/signin-activity.ts` | 신규 |
| `web/src/lib/people.ts` | 수정 |
| `web/src/components/admin/person-list.tsx` | 수정 |
| `web/src/components/admin/person-activity.tsx` | 신규 |
| `web/src/app/admin/people/people-admin-panel.tsx` | 수정 |
| `web/src/app/admin/people/page.tsx` | 수정 |
| `web/eslint.config.mjs` | 수정 |
| `test/unit/signin-activity.test.ts` | 신규 |
| `test/browser/people.spec.ts` | 수정 |
| `test/browser/people-activity.spec.ts` | 신규 |
