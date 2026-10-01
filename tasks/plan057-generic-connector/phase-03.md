# Phase 03. 연결 화면을 manifest 로 그리는 범용 화면으로 바꾼다

**Execution profile**: standard

## 목표

「연결」 메뉴에 커넥터 카드 목록을 두고, 커넥터마다 manifest 의 `fields` 로 입력 칸을 그리는 화면 하나로 모든 커넥터를 다룬다. 가계부 전용 화면, 라우트, 문구를 없앤다.

**범위 외**: backend(phase 02), e2e 와 서비스 이름 검사(phase 04).

## 컨텍스트

지금 가계부 전용 파일(모두 지우거나 범용으로 바꾼다):

| 지금 | 바뀐 뒤 |
| --- | --- |
| `web/src/app/connections/accountbook/page.tsx` | `web/src/app/connections/page.tsx`(카드 목록), `web/src/app/connections/[id]/page.tsx`(연결 화면). 옛 경로는 `/connections/fos-accountbook` 으로 넘기는 페이지만 남긴다 |
| `web/src/components/connector/accountbook-connection-panel.tsx` | `web/src/components/connector/connector-connection-panel.tsx` |
| `web/src/components/connector/accountbook-admin-panel.tsx` | `web/src/components/connector/connector-admin-panel.tsx` |
| `web/src/app/api/connections/accountbook/route.ts`, `check/route.ts`, `families/route.ts` | `web/src/app/api/connectors/route.ts`, `web/src/app/api/connections/[id]/route.ts`, `web/src/app/api/connections/[id]/check/route.ts`, `web/src/app/api/connections/[id]/options/[fieldKey]/route.ts` |
| `web/src/app/api/admin/connections/accountbook/route.ts`, `[userId]/confirm/route.ts` | `web/src/app/api/admin/connections/route.ts`, `web/src/app/api/admin/connections/[id]/[userId]/confirm/route.ts` |
| `web/src/lib/connection.ts`, `web/src/lib/connection-route.ts` | 범용 타입과 라우트 도우미로 고친다 |
| `web/src/components/shell/main-nav.tsx` 의 「가계부 연결」 | 「연결」, `/connections` |
| `web/src/components/agent/agent-detail-body.tsx` 의 「가계부 연결 화면에서 …」 문구 | 서비스 이름 없는 「연결 화면에서 …」 |
| `test/browser/accountbook-connection.spec.ts` | `test/browser/connector-connection.spec.ts` |

지금 라우트 도우미의 원칙은 그대로 둔다: 본문은 `readJsonBody` 로 읽고, Control Plane 응답은 계약 칸만 골라 넘기고, 오류 원문을 브라우저로 넘기지 않는다(`web/src/lib/connection-route.ts` 의 `connectorCall`, `safeConnection`).

**근거 문서**: `docs/connectors.md` 의 「Control Plane API」 와 「저장과 비밀값」, `docs/flow.md` 의 「커넥터 연결」, `web/AGENTS.md`

## 의도 메모

- **카탈로그와 연결 상태는 브라우저에서 읽는다.** 부품이 `/api/connectors`, `/api/connections/[id]`, `/api/admin/connections` 를 불러 그린다. 서버 컴포넌트가 Control Plane 을 직접 부르지 않는다. 브라우저 검사는 실제 Control Plane 과 Hermes 대역을 띄우는데 대역의 커넥터 경로는 phase 04 에서 생기고, 브라우저 가로채기는 서버 쪽 호출에 닿지 않기 때문이다. 모르는 id 의 404 화면도 그 응답(`CONNECTOR_NOT_FOUND`)으로 정한다
- **새 부품은 `fetch` 를 직접 쓰지 않는다**(`web/AGENTS.md` 의 `no-restricted-globals`). 호출 함수는 `web/src/lib/` 에 두고 부품이 그것을 쓴다. 지우는 파일을 가리키는 `web/eslint-suppressions.json` 의 항목은 함께 뺀다. 남기면 `pnpm lint` 가 쓰지 않는 기준으로 실패한다. 새 항목은 더하지 않는다
- 화면 문구에 서비스 이름을 쓰지 않는다. 제목과 설명과 칸 라벨은 manifest 에서 온다. 상태와 오류 문구는 공통 어휘로 정한다
- 비밀 칸은 `type="password"` 로 받고, 제출한 직후와 선택지 조회가 실패했을 때 비운다. 저장된 상태에는 앞 8자만 보인다
- 선택지 칸은 비밀 칸이 모두 채워진 뒤 「불러오기」 로 부른다. `autoSelectSingle` 이 참이고 하나뿐이면 고른다. 비밀 칸을 바꾸면 불러온 선택지를 비운다
- 관리자 반영 대기 목록은 커넥터 이름과 함께 한 목록으로 보인다. 반영 완료 단추 옆에 「공유 gateway 를 재시작한 뒤 누른다」 를 적는다

## 작업 항목

### 1. 서버 라우트

위 표의 경로를 만든다. 각 라우트는 `docs/connectors.md` 표의 Control Plane 경로를 부르고 응답 칸만 넘긴다. `[id]` 와 `[fieldKey]` 는 `^[a-z0-9][a-z0-9-]{0,63}$`, `^[a-z][a-z0-9_]{0,31}$` 이 아니면 Control Plane 을 부르지 않고 400 이다.

### 2. 화면

- `/connections`: 카탈로그 카드(제목, 설명, 내 상태 배지). 비면 「연결할 수 있는 서비스가 없어요」. 관리자에게는 아래에 반영 대기 목록
- `/connections/[id]`: manifest 칸 렌더러, 등록, 연결 확인, 해제. 모르는 id 는 404 화면. 운영 목록에서 빠진 연결은 「지금은 쓸 수 없어요」 와 해제만
- 상태 배지와 오류 문구: `DISCONNECTED` 연결 안 됨, `PENDING` 준비 중, `READY` 연결됨, 재시작 대기는 「관리자 반영을 기다려요」. 오류는 `CONNECTOR_CREDENTIAL_REJECTED` 「입력한 값을 확인하지 못했어요」, `CONNECTOR_FORBIDDEN` 「이 값으로는 쓸 수 없어요」, `CONNECTOR_UNAVAILABLE` 「서비스에 닿지 못했어요. 잠시 뒤 다시 해 주세요」, `CONNECTOR_OPERATION_FAILED` 「연결을 마치지 못했어요」, `VALIDATION_FAILED` 「입력 형식을 확인해 주세요」
- 메뉴 「연결」

### 3. 검사 `test/browser/connector-connection.spec.ts`

지금 가계부 화면 검사가 지키는 동작을 시험 커넥터(`demo-notes`, phase 01 의 manifest 와 같은 칸)로 옮긴다. 브라우저 쪽에서 `/api/connectors`, `/api/connections/**` 를 가로채는 지금 방식을 그대로 쓴다.
- 정상: 카드 목록 → 연결 화면 → 비밀 칸 입력 → 선택지 불러오기(하나면 자동 선택) → 연결 → 준비 중 → 확인 → 연결됨
- 실패: 확인 도구 거절 문구, 선택지 조회 실패 뒤 비밀 칸이 비는지, 모르는 id 의 404
- 옛 경로 `/connections/accountbook` 이 `/connections/fos-accountbook` 으로 넘어가는지(이 검사만 그 이름을 쓴다)
- 관리자 반영 대기 목록과 반영 완료
- 모바일과 데스크톱 두 폭

## 검증

```bash
# cwd: 저장소 root
cd web && pnpm typecheck && cd ..
# 다른 브라우저 검사가 돌고 있으면 끝난 뒤에 돌린다
cd web && pnpm test:browser connector-connection && cd ..
! git grep -niE "accountbook|ACCOUNTBOOK_|fab_|가계부" -- web/src ':!web/src/app/connections/accountbook/page.tsx'
scripts/quality.sh check
scripts/check-public-safe.sh
```

- 위 명령 뒤에 단위 검사 전체(`test/unit` 아래 모든 `*.test.ts` 를 `node --test` 로)도 돌린다. 새 `page.tsx` 가 라우트 규칙 검사(`test/unit/loading-routes.test.ts`)에 걸리는지 여기서 본다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/app/connections/page.tsx` | 신규 |
| `web/src/app/connections/[id]/page.tsx` | 신규 |
| `web/src/app/connections/accountbook/page.tsx` | 수정 |
| `web/src/components/connector/connector-connection-panel.tsx` | 신규 |
| `web/src/components/connector/connector-admin-panel.tsx` | 신규 |
| `web/src/components/connector/connector-catalog.tsx` | 신규 |
| `web/src/components/connector/accountbook-connection-panel.tsx` | 삭제 |
| `web/src/components/connector/accountbook-admin-panel.tsx` | 삭제 |
| `web/src/app/api/connectors/route.ts` | 신규 |
| `web/src/app/api/connections/[id]/route.ts` | 신규 |
| `web/src/app/api/connections/[id]/check/route.ts` | 신규 |
| `web/src/app/api/connections/[id]/options/[fieldKey]/route.ts` | 신규 |
| `web/src/app/api/connections/accountbook/route.ts` | 삭제 |
| `web/src/app/api/connections/accountbook/check/route.ts` | 삭제 |
| `web/src/app/api/connections/accountbook/families/route.ts` | 삭제 |
| `web/src/app/api/admin/connections/route.ts` | 신규 |
| `web/src/app/api/admin/connections/[id]/[userId]/confirm/route.ts` | 신규 |
| `web/src/app/api/admin/connections/accountbook/route.ts` | 삭제 |
| `web/src/app/api/admin/connections/accountbook/[userId]/confirm/route.ts` | 삭제 |
| `web/src/lib/connection.ts` | 수정 |
| `web/src/lib/connection-route.ts` | 수정 |
| `web/src/components/shell/main-nav.tsx` | 수정 |
| `web/src/components/agent/agent-detail-body.tsx` | 수정 |
| `web/eslint-suppressions.json` | 수정 |
| `test/browser/connector-connection.spec.ts` | 신규 |
| `test/browser/accountbook-connection.spec.ts` | 삭제 |
