# Phase 02. 서비스 토큰 절과 문서 갱신

**Execution profile**: standard

## 목표

사용자가 `/memory` 화면에서 서비스 토큰을 만들고, 목록을 보고, 폐기한다.
토큰 원문은 만든 직후 한 번만 보인다. 만료가 가까운 토큰과 만료된 토큰에 표시를 단다.
세 plan 으로 만든 화면을 `docs/` 에 적는다.

**범위 외**: 관리자가 다른 사용자의 토큰을 보는 화면. 그런 경로는 없다.

## 컨텍스트

- phase 01 이 `MemoryList` 의 `after` prop, `web/src/components/memory/document-section.tsx`(`MemoryCollectionOption` 타입을 내보낸다), `/api/memory-collections` 라우트를 만들었다. `web/src/app/memory/page.tsx` 가 `after` 에 `DocumentSection` 을 넘긴다
- Control Plane 의 경로(plan61 이 만들었다)
  - `POST /api/v1/service-tokens` ← `{label, expiresInDays, collections: [{collection, allowSensitive}]}`. `expiresInDays` 가 `null` 이면 만료가 없다. 응답은 `{info: {id, label, createdAt, expiresAt, lastUsedAt, revokedAt, collections}, token}` 이다. **`token` 은 이 응답에만 있다**
  - `GET /api/v1/service-tokens` → `[{id, label, createdAt, expiresAt, lastUsedAt, revokedAt, collections}]`
  - `DELETE /api/v1/service-tokens/{id}`. 없는 토큰과 남의 토큰은 404 `SERVICE_TOKEN_NOT_FOUND`
- 쓸 수 있는 부품: `@/components/ui/copy-button` 의 `CopyButton({ text, label })`, `@/components/ui/notice` 의 `Notice`, `@/components/ui/badge` 의 `Badge`(의미 색 변형이 있다. 변형 이름은 `badge.tsx` 를 읽어 확인한다), `@/components/ui/alert-dialog`
- `web/src/lib/` 의 순수 함수는 `test/unit/` 의 `node --test` 가 검사한다. 선례는 `web/src/lib/execution-status.ts` 와 `test/unit/execution-status.test.ts` 다. 단위 검사는 `../../web/src/lib/<파일>.ts` 를 바로 import 하므로 그 파일은 `@/` 별칭과 React 를 import 하지 않는다
- 문서가 지금 적고 있는 것
  - `docs/frontend/structure.md` 의 화면 표에 `/memory` 가 「개인과 그룹 공용 Memory」 로 있다. `docs/code-architecture.md` 의 「Memory 에서 아직 만들지 않은 것」 목록에 「collection 탭, 문서(`DOCUMENT`) 편집과 판 이력 화면, 출처 표시, 민감 항목 표시」 가 있다
  - `web/AGENTS.md` 의 「화면 문구」 에 「안에서 부르는 이름 | 화면에서 쓰는 말」 표가 있다
  - `docs/adr/ADR-057-문서는-사람이-화면에서-직접-쓰고-고친다.md` 의 `status` 가 「화면은 아직 구현 전이다」 로 적혀 있다

**근거 문서**: `docs/adr/ADR-056-다른-서비스는-사용자에-묶인-서비스-토큰으로-문서를-읽기만-한다.md` 의 「적용 범위」

## 의도 메모

- 토큰 원문을 화면의 상태에만 둔다. 새로고침하면 사라진다. 브라우저 저장소와 주소에 두지 않는다
- 만료 표시의 기준(14일)은 순수 함수 하나에 두고 단위 검사로 본다. 브라우저 검사에서 시각을 다루면 흔들린다
- 민감 허용은 영역마다 따로 고른다. 「모두 허용」 을 두지 않는다
- 화면의 만료 기본값은 90일이다. 만료 없음은 사용자가 스스로 골라야 한다

## 작업 항목

### 1. `web/src/lib/service-token.ts`

```ts
export type ServiceTokenStatus = "active" | "expiring" | "expired" | "revoked";

/** 만료까지 이 날수 이하로 남으면 「곧 만료」 로 보인다(ADR-056). */
export const EXPIRING_WITHIN_DAYS = 14;

export function serviceTokenStatus(
  token: { expiresAt: string | null; revokedAt: string | null },
  now: Date,
): ServiceTokenStatus
```

판정 순서: `revokedAt` 이 있으면 `revoked`. `expiresAt` 이 있고 `now` 이전이거나 같으면 `expired`. `expiresAt` 이 있고 남은 시간이 14일 이하면 `expiring`. 그 밖은 `active`.

### 2. `test/unit/service-token.test.ts`

| 입력 | 기대 |
| --- | --- |
| `expiresAt: null`, `revokedAt: null` | `active` |
| 만료가 30일 뒤 | `active` |
| 만료가 정확히 14일 뒤 | `expiring` |
| 만료가 3일 뒤 | `expiring` |
| 만료가 1초 전 | `expired` |
| 만료가 3일 뒤이고 `revokedAt` 이 있다 | `revoked` |
| 만료가 지났고 `revokedAt` 이 있다 | `revoked` |

`now` 는 `new Date("2026-10-02T00:00:00Z")` 로 고정한다.

### 3. 서버 라우트

- `web/src/app/api/service-tokens/route.ts`: `GET`, `POST` → `/api/v1/service-tokens`
- `web/src/app/api/service-tokens/[id]/route.ts`: `DELETE` → `/api/v1/service-tokens/{id}`. `id` 가 숫자가 아니면 400 `VALIDATION_FAILED` 와 「토큰 번호가 올바르지 않아요.」

`POST` 의 응답은 원문을 담는다. `Cache-Control: no-store` 를 붙인다.

### 4. `web/src/components/error-message.ts`

`MESSAGES` 에 `SERVICE_TOKEN_NOT_FOUND: "토큰을 찾지 못했어요. 화면을 새로고침해 확인해 주세요."` 를 더한다.

### 5. `web/src/components/memory/service-token-panel.tsx`

`"use client"`. `ServiceTokenPanel({ initialTokens, collections })`.

- `<section>` 에 `<h2>외부 서비스 연결</h2>` 와 설명 「다른 프로그램이 내 문서를 읽을 때 쓰는 토큰이에요. 문서를 읽기만 하고 고치지 못해요.」

발급 폼. 제목은 「새 토큰」 이다.

| 칸 | 모양 |
| --- | --- |
| 이름 | `Input`, `maxLength={100}`, 필수 |
| 만료 | `NativeSelect`. 「30일」(30), 「90일」(90, 처음 값), 「1년」(365), 「만료 없음」(빈 값은 `expiresInDays: null` 로 보낸다) |
| 읽을 영역 | `collections` 마다 체크박스 하나(`displayName`). 고른 영역 아래에만 「민감한 문서도 읽기」 체크박스가 보인다 |
| 토큰 만들기 | `Button type="submit"`. 영역을 하나도 고르지 않으면 `disabled` |

만든 직후에만 `Notice` 를 보인다.

- 문구: 「토큰은 지금 한 번만 보여요. 안전한 곳에 옮겨 적어 주세요.」
- 원문을 `<code data-testid="issued-service-token">` 에 보이고 `CopyButton` 을 `label="토큰 복사"` 로 둔다
- 「확인했어요」 단추가 이 상자를 닫고 원문을 상태에서 지운다

목록. 토큰마다 `<article>` 하나다.

- 이름, 읽는 영역의 `displayName` 목록. 민감 허용이 있는 영역에는 「민감 포함」 을 붙인다
- 「마지막 사용 {날짜}」. 쓴 적이 없으면 「아직 쓰지 않았어요」. 날짜 형식은 `web/src/lib/format.ts` 에 있는 함수를 쓴다
- 「{날짜}에 만료」. 만료가 없으면 「만료 없음」
- `serviceTokenStatus(token, new Date())` 로 배지를 단다: `expiring` 은 경고 색 「곧 만료돼요」, `expired` 는 「만료됐어요」, `revoked` 는 「폐기했어요」, `active` 는 배지가 없다
- `active` 와 `expiring` 에만 「폐기」 단추가 있다. `alert-dialog` 로 「이 토큰을 쓰는 프로그램은 더 이상 문서를 읽지 못해요.」 를 확인받고 `DELETE` 를 보낸다
- 토큰이 없으면 「아직 만든 토큰이 없어요.」

### 6. `web/src/app/memory/page.tsx`

`Promise.all` 에 `callControlPlane<ServiceToken[]>("/api/v1/service-tokens")` 를 더한다. 성공하면 `after` 에 `DocumentSection` 다음으로 `ServiceTokenPanel` 을 넘긴다. 실패하면 그 절만 뺀다.

### 7. 이 phase 를 검증하는 `test/browser/service-token.spec.ts`

토큰 이름은 `` `career-os ${testInfo.project.name}` `` 처럼 project 마다 다르게 둔다.

| 검사 | 하는 일 | 기대 |
| --- | --- | --- |
| 토큰을 만들면 한 번만 보인다 | `/memory` 에서 이름을 적고 「신원」 과 그 아래 「민감한 문서도 읽기」 를 고른 뒤 「토큰 만들기」 | 만료 선택의 처음 값이 「90일」 이다. `issued-service-token` 의 글이 `fos_svc_` 로 시작한다. 목록에 그 이름과 「신원」 과 「민감 포함」 과 「아직 쓰지 않았어요」 가 보인다 |
| 〃 | 새로고침 | `issued-service-token` 이 없다. 목록에 그 이름은 있다 |
| 만든 토큰으로 문서를 읽는다 | 문서 절에서 「신원」 에 민감 문서를 만들고, 토큰을 만든 뒤, `test/browser/settings.ts` 의 `CONTROL_PLANE_BASE_URL` 을 import 해 `GET /api/v1/service/memory-documents/identity/{문서 이름}` 을 그 토큰으로 부른다 | 200 이고 `content` 가 넣은 글이다. 새로고침하면 목록의 그 토큰에 「마지막 사용」 날짜가 보인다 |
| 폐기하면 표시가 바뀐다 | 「폐기」 뒤 확인 | 「폐기했어요」 가 보이고 그 토큰의 「폐기」 단추가 없다 |
| 만료 표시를 단다 | `page.route("**/api/service-tokens", ...)` 로 `GET` 응답을 바꿔, 만료가 3일 뒤인 토큰과 어제 만료된 토큰을 돌려준다. 시각은 검사 안에서 `Date.now()` 로 계산한다 | 「곧 만료돼요」 와 「만료됐어요」 가 각각 보인다. 만료된 토큰에는 「폐기」 단추가 없다 |
| 영역을 고르지 않으면 만들지 못한다 | 이름만 적는다 | 「토큰 만들기」 가 `disabled` 다 |
| 좁은 화면에서 가로로 넘치지 않는다 | 토큰을 만든 직후 `scrollWidth` 를 읽는다 | 화면 폭 이하다. 원문이 길어 넘치기 쉬운 자리다 |

Control Plane 은 `import { CONTROL_PLANE_BASE_URL } from "./settings.ts";` 로 주소를 얻어 `fetch` 로 부른다. `test/browser/fixtures.ts` 가 같은 값을 쓴다. 검사가 만든 문서와 토큰은 끝에 지우고 폐기한다.

### 8. 문서

- `docs/frontend/structure.md` 의 화면 표에서 `/memory` 줄을 「개인과 그룹 공용 Memory, 개인 문서, 서비스 토큰」 으로 고친다
- `docs/code-architecture.md` 의 「Memory 에서 아직 만들지 않은 것」 목록에서 문서 편집 화면을 적은 줄을 「collection 탭, 문서의 판 이력 화면, 출처 표시」 로 고친다
- `web/AGENTS.md` 의 「화면 문구」 표에 줄을 더한다: `DOCUMENT` | 문서. collection 은 「영역」, 민감 항목은 「민감」, 판은 「N번째 판」. 서비스 토큰 | 외부 서비스 연결 토큰. 절 제목은 「외부 서비스 연결」
- `docs/adr/ADR-057-문서는-사람이-화면에서-직접-쓰고-고친다.md` 의 `status` 줄을 `` `accepted` `` 만 남기고 고친다
- `docs/adr/INDEX.md` 의 ADR-057 줄에서 「화면은 아직 구현 전이다」 를 지운다

## 검증

```bash
# cwd: 저장소 root
node --test test/unit/service-token.test.ts
node --test 'test/unit/**/*.test.ts'
(cd web && pnpm lint && pnpm format:check && pnpm typecheck)
(cd web && pnpm test:browser service-token.spec.ts memory-document.spec.ts memory.spec.ts)
scripts/check-public-safe.sh
scripts/quality.sh check
! grep -rn 'style={{' web/src/components/memory
! grep -rn 'localStorage\|sessionStorage' web/src/components/memory
! grep -n "구현 전" docs/adr/ADR-057-문서는-사람이-화면에서-직접-쓰고-고친다.md
```

- 모두 종료 코드 0. 마지막 세 줄은 일치하는 줄이 없어야 한다
- `pnpm test:browser` 는 웹을 먼저 빌드한다. 처음 받은 checkout 이면 `(cd web && pnpm install && pnpm exec playwright install chromium)` 을 먼저 돌린다
- 브라우저 검사는 `mobile` 과 `desktop` 두 project 가 모두 통과해야 한다
- 전체 브라우저 검사는 로컬에서 돌리지 않는다. PR 의 CI `browser-mobile` 과 `browser-desktop` 이 통과한 것을 확인으로 본다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/service-token.ts` | 신규 |
| `test/unit/service-token.test.ts` | 신규 |
| `web/src/app/api/service-tokens/route.ts` | 신규 |
| `web/src/app/api/service-tokens/[id]/route.ts` | 신규 |
| `web/src/components/error-message.ts` | 수정 |
| `web/src/components/memory/service-token-panel.tsx` | 신규 |
| `web/src/app/memory/page.tsx` | 수정 |
| `test/browser/service-token.spec.ts` | 신규 |
| `docs/code-architecture.md` | 수정 |
| `docs/frontend/structure.md` | 수정 |
| `web/AGENTS.md` | 수정 |
| `docs/adr/ADR-057-문서는-사람이-화면에서-직접-쓰고-고친다.md` | 수정 |
| `docs/adr/INDEX.md` | 수정 |
