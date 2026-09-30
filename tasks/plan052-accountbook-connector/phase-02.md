# Phase 02. 가계부 연결 화면과 비밀값 입력을 만든다

**Execution profile**: standard

## 목표

로그인 사용자가 자기 토큰을 등록하고 연결 상태를 확인하며 해제한다.
토큰 원문은 제출 직후 화면에서 제거한다.
**범위 외**: 임의 plugin 설치 UI, 운영 재시작 자동화.

## 컨텍스트

**근거 문서**: `docs/connectors.md`, `docs/flow.md`의 가계부 연결.
phase 01의 API를 사용한다.
`web/src/lib/control-plane.ts`의 `callControlPlane`이 현재 세션의 신원으로 서버에서 호출한다.
`web/src/components/shell/main-nav.tsx`에 가계부 연결 링크를 추가한다.
`test/browser/fixtures.ts`가 로그인과 backend를 준비하며 API mock은 Playwright route로 한다.

## 의도 메모

토큰은 password input이며 저장 후 재노출과 localStorage 저장을 하지 않는다.
문구는 해요체이며 PENDING은 연결 확인 버튼과 관리자 반영 대기를 구분한다.
가계부에서 별도로 토큰을 폐기해야 한다고 안내한다.

## 작업 항목

### 1. 서버 라우트와 타입

`web/src/lib/connection.ts`에 docs의 상태 타입을 둔다.
`web/src/app/api/connections/accountbook/route.ts`는 GET, POST, DELETE를 전달한다.
POST는 token과 familyUuid만 새 객체로 만든다. 다른 profile 입력은 전달하지 않는다.
정해진 오류 코드만 한국어 고정 메시지로 바꾸며 upstream 본문을 내보내지 않는다.
오류 코드는 phase 01이 정한 `ACCOUNTBOOK_TOKEN_REJECTED`, `ACCOUNTBOOK_FAMILY_FORBIDDEN`,
`ACCOUNTBOOK_UNAVAILABLE`, `CONNECTOR_OPERATION_FAILED`와 기존 `UNAUTHENTICATED`, `VALIDATION_FAILED`다.
`check/route.ts`는 POST를 전달한다.
ADMIN 목록과 반영 완료 서버 라우트를 추가한다. 요청 userId는 양의 정수만 받으며 권한과 그룹은 backend가 검사한다.

### 2. 화면

`web/src/app/connections/accountbook/page.tsx`는 auth를 확인하고 연결 상태를 읽는다.
`AccountbookConnectionPanel`은 입력과 상태, 등록, 확인, 해제를 처리한다.
네트워크 실패에도 token 입력은 비운다. busy 동안 중복 제출을 막는다.
READY이면 agentCode 링크를 보인다.
해제한 `DISCONNECTED` 상태에서도 restartRequired가 참이면 관리자 반영 대기와 가계부 토큰 폐기를 안내한다.
같은 페이지에 ADMIN 전용 대기 목록과 반영 완료 버튼을 둔다. 목록에는 다른 사용자 토큰을 표시하지 않는다.
connectorManaged 에이전트는 일반 관리 화면의 편집 버튼을 숨긴다.

### 3. 브라우저 테스트

`accountbook-connection.spec.ts`는 등록, READY와 PENDING, 확인 재시도, 해제와 고정 오류,
실패 뒤 token 입력 제거, 모바일 가로 넘침 없음을 확인한다.
해제 응답의 DISCONNECTED와 restartRequired=true를 함께 표시하고 가계부 토큰 폐기 안내가 남는지 확인한다.
mock 응답에 raw token을 넣지 않는다.
관리자 목록과 반영 완료, 다른 사용자 토큰 미표시도 확인한다.

## 검증

```bash
cd web && pnpm typecheck
cd web && pnpm test:browser accountbook-connection.spec.ts
```

마감 때 AGENTS.md의 전체 명령을 지정 순서대로 메인 세션이 직접 실행한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `web/src/lib/connection.ts` | 신규 |
| `web/src/lib/connection-route.ts` | 신규 |
| `web/src/app/api/connections/accountbook/route.ts` | 신규 |
| `web/src/app/api/connections/accountbook/check/route.ts` | 신규 |
| `web/src/app/api/admin/connections/accountbook/route.ts` | 신규 |
| `web/src/app/api/admin/connections/accountbook/[userId]/confirm/route.ts` | 신규 |
| `web/src/app/connections/accountbook/page.tsx` | 신규 |
| `web/src/components/connector/accountbook-connection-panel.tsx` | 신규 |
| `web/src/components/connector/accountbook-admin-panel.tsx` | 신규 |
| `web/src/lib/agent.ts` | 수정 |
| `web/src/app/agents/[code]/page.tsx` | 수정 |
| `web/src/components/shell/main-nav.tsx` | 수정 |
| `test/browser/accountbook-connection.spec.ts` | 신규 |
