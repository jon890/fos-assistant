# Phase 01. 지금 화면과 사이드바 「지금」

**Execution profile**: standard

## 목표

`/now` 화면에서 카드 넷과 항목의 이유와 출처를 그리고, 사이드바 메뉴 맨 위에 「지금」 과 `NOW` 건수를 둔다.
실행 상태가 바뀌면 다음에 열 때 카드가 따라 바뀌는 것을 합성 데이터로 확인한다.

**범위 외**: 숨기기, 미루기, 사건 기록, 할 일 동작(phase-02). 새 대화 화면의 한 줄과 관리자 지표(phase-03). 판정과 API(plan79).

## 컨텍스트

- 서버 화면은 `web/src/app/usage/page.tsx` 처럼 `auth()` 로 세션을 보고 없으면 `redirect("/signin")` 한 뒤 부품을 그린다. 부품(`web/src/components/usage/usage-screen.tsx` 의 `UsageScreen`)이 `callControlPlane<T>(path)` 로 읽고 `result.ok` 가 거짓이면 `result.message` 를 그린다
- `callControlPlane` 은 `web/src/lib/control-plane.ts` 에 있다. 서버 라우트는 `web/src/app/api/usage/route.ts` 처럼 `callControlPlane` 결과를 `NextResponse.json(result.data)` 나 `errorResponse(result.code, result.message, result.status)` 로 돌려준다
- 브라우저 요청 함수는 `web/src/lib/usage-api.ts` 처럼 `fetch("/api/…", { cache: "no-store" })` 를 돌려주는 얇은 함수다
- 사이드바 메뉴는 `web/src/components/shell/main-nav.tsx` 의 `LINKS`(지금은 `/agents`, `/connections`, `/memory`, `/usage`)다. 기억 메뉴의 제안 건수는 `pathname` 이 바뀔 때마다 `fetchMemories()` 로 다시 읽어 `data-testid="memory-proposal-count"` 배지로 그린다. 「지금」 건수도 같은 방식으로 읽는다
- 카드 부품은 `web/src/components/ui/card.tsx` 의 `Card`, `CardHeader`, `CardTitle`, `CardAction`, `CardContent` 다. 배지는 `badge.tsx` 의 `Badge`(`warning`, `secondary` 등), 빈 상태는 `empty-state.tsx` 의 `EmptyState({ title, description })`, 안내는 `notice.tsx` 의 `Notice`(`warning`)다
- 뼈대는 `web/src/components/ui/page-skeleton.tsx` 의 `PageSkeleton` 이다. `shape` 에 `"cards"`, `width` 에 `"4xl"` 이 있다. 설명 문단이 없는 화면이면 `description` 을 주지 않는다
- `test/unit/loading-routes.test.ts` 는 `callControlPlane` 을 부르는 `page.tsx` 마다 `loading.tsx` 가 있는지, 그 `width` 가 `ROUTE_FRAMES` 에 적은 파일의 첫 `mx-auto … max-w-*` 틀과 같은지 본다
- 실행 상태의 문구는 `web/src/lib/execution-status.ts` 의 `executionStatusLabel`, `executionStatusVariant` 다. 상대 시각은 `web/src/lib/format.ts` 의 `formatWhen` 을 읽어 보고 맞지 않으면 같은 파일에 함수를 더한다
- 브라우저 검사는 `test/browser/fixtures.ts` 의 `test`, `expect`, `setSession` 을 쓴다. 폭은 `testInfo.project.name === "mobile"` 로 나눈다. 다른 검사와 상태가 섞이지 않게 `test/browser/usage.spec.ts` 의 `memberOf(projectName)` 와 `beforeEach` 처럼 폭마다 따로 만든 사용자로 로그인한다
- 실패한 turn 은 `hermes.busy()` 뒤에 `page.request.post("/api/chat", { data: { text, agentCode: "browser" } })` 를 보내 429 로 만든다. `finally` 에서 `hermes.clearBusy()` 한다(`test/browser/usage.spec.ts` 의 붐빔 검사). 같은 대화에 다시 보낼 때는 본문에 `conversationId` 를 준다(`web/src/app/api/chat/route.ts`)

**근거 문서**: `docs/frontend/now.md` 의 「주소와 들어오는 길」, 「카드」, 「항목 한 줄」, 「이유 문구」, 「폭별 배치」, `docs/backend/attention.md` 의 「API」 와 응답 모양, `docs/adr/ADR-074-지금-화면은-원래-기록을-읽어-만든-view-이고-정해진-카드-넷만-그린다.md`

## 의도 메모

- 화면이 카드와 항목을 다시 정렬하지 않는다. 순서 규칙은 서버 하나가 갖는다. 화면이 정렬하면 두 곳의 규칙이 어긋난다
- 이유 문구를 서버가 보내지 않고 화면이 코드로 정한다. 모델이 쓴 글로 이유를 만들지 않기 위해서다. 문구 함수는 순수 함수로 두어 단위 테스트로 표 전체를 확인한다
- `/now` 는 서버에서 읽으므로 `loading.tsx` 를 둔다. 홈 `/` 은 이 phase 에서 건드리지 않는다
- 「지금」 배지는 `nowCount` 하나만 그린다. 기억 메뉴의 제안 건수와 같은 것을 두 번 세지 않는다(`MEMORY_PROPOSED` 는 서버가 `nowCount` 에 넣지 않는다)

## Blocked 조건

- `git grep -n '/api/v1/attention' backend/src/main/java` 가 `GET /api/v1/attention` 과 `/api/v1/attention/summary` 를 여는 controller 를 찾지 못한다 → `PHASE_BLOCKED: plan79 phase-01 이 main 에 없다`

## 작업 항목

### 1. `web/src/lib/attention.ts`

응답 타입과 순수 함수만 둔다. 다른 web 파일을 import 하지 않는다(단위 테스트가 상대 경로로 읽는다).

- 타입: `AttentionCardKey`(`"failures" | "needs_me" | "delegated" | "continue"`), `AttentionLevel`(`"NOW" | "LATER"`), `AttentionWhy`(`trigger`, `signals: string[]`, `confidence`, `sources: { source: string; ref: string; asOf: string | null }[]`), `AttentionItem`(`itemKey`, `stateKey`, `attention`, `title`, `conversationId`, `agentName`, `at`, `why`, `execution: { id: number; status: string } | null`, `actionId: string | null`, `followUp: { id: string; dueAt: string | null; waiting: boolean; proposed: boolean } | null`). 칸은 `docs/backend/attention.md` 의 「API」 응답 표를 따른다, `AttentionCard`(`key`, `status: "OK" | "UNAVAILABLE"`, `moreCount`, `items`), `AttentionView`(`readAt`, `nowCount`, `cards`)
- plan79 의 응답 record 를 읽어 칸 이름을 맞춘다. 다르면 이 파일을 응답에 맞추고 `docs/backend/attention.md` 의 응답 예와 견준다
- `reasonText(why: AttentionWhy): string` — `docs/frontend/now.md` 의 「이유 문구」 표 그대로. `signals` 가 여럿이면 표의 위쪽 줄, 표에 없는 조합은 그 `trigger` 의 「없음」 줄. 모르는 `trigger` 는 빈 문자열
- `cardTitle(key)`, `cardEmptyText(key)` — 「카드」 표의 제목과 비었을 때 문구
- `allCardsEmpty(cards: AttentionCard[]): boolean` — 네 카드가 모두 `OK` 이고 항목이 0 이면 참. 하나라도 `UNAVAILABLE` 이면 거짓
- `nowCountOf(card): number` — 카드 안 `attention === "NOW"` 항목 수

### 2. `web/src/components/now/` 부품

- `now-screen.tsx` 의 `NowScreen`(async 서버 부품): `callControlPlane<AttentionView>("/api/v1/attention")`. 실패면 `<p className="text-sm">{result.message}</p>`. 바깥 틀은 `<div className="mx-auto w-full max-w-4xl">`, 제목 `h1` 은 「지금 볼 것」. `allCardsEmpty` 면 `EmptyState` 로 「지금 확인할 것이 없어요」, 아니면 `grid gap-4 md:grid-cols-2` 안에 응답 순서대로 `NowCard`
- `now-card.tsx` 의 `NowCard`: `Card` 머리에 `cardTitle`, `nowCountOf` 가 0 보다 크면 `Badge variant="warning"` 으로 그 수. `UNAVAILABLE` 이면 `Notice variant="warning"` 으로 「이 카드를 불러오지 못했어요. 잠시 뒤에 다시 열어 주세요」. 항목이 없으면 `cardEmptyText` 한 줄. `moreCount` 가 있으면 「N개 더 있어요」 링크(`failures`, `delegated` 는 `/usage?tab=executions`, `continue` 는 링크 없이 글만). `data-testid="now-card-{key}"`
- `now-item.tsx` 의 `NowItem`: 제목 링크(`prefetch={false}`, 갈 곳은 `docs/backend/attention.md` 의 「카드의 단추와 승인 경계」 표. 대화는 `/chat/{conversationId}`, 맡긴 일은 `/executions/{execution.id}`, 승인 대기는 그 대화, Memory 제안은 `/memory`. `itemKey` 를 잘라 식별자를 얻지 않는다), 이유 한 줄(`reasonText`), 출처 줄(에이전트 이름, 시각), `NOW` 면 `Badge variant="warning"` 「지금」. `data-testid="now-item"`, `data-attention` 에 `NOW`/`LATER`. 맡긴 일은 `execution.status` 로 `executionStatusLabel` 문구를 함께 그린다
- 좁은 폭에서 출처 줄과 단추가 줄바꿈되게 `flex-wrap` 을 쓰고 가로 스크롤이 생기지 않게 한다

### 3. `web/src/app/now/page.tsx`, `web/src/app/now/loading.tsx`

- `page.tsx`: `web/src/app/usage/page.tsx` 와 같은 세션 확인 뒤 `<NowScreen />`. `metadata` 의 제목은 「지금」
- `loading.tsx`: `<PageSkeleton shape="cards" width="4xl" title />`

### 4. 사이드바 「지금」

- `web/src/app/api/attention/summary/route.ts` 신규: `GET` 이 `callControlPlane<{ nowCount: number }>("/api/v1/attention/summary")` 를 돌려준다
- `web/src/lib/attention-api.ts` 신규: `fetchAttentionSummary(): Promise<Response>` 가 `/api/attention/summary` 를 `cache: "no-store"` 로 부른다
- `web/src/components/shell/main-nav.tsx`: `LINKS` 맨 앞에 `{ href: "/now", label: "지금" }`. 기억 제안 건수와 같은 `useEffect([pathname])` 로 `fetchAttentionSummary` 를 읽어 `nowCount` 가 0 보다 크면 메뉴 글자 오른쪽에 `data-testid="now-count"` 배지를 붙인다. 배지의 접근성 이름은 「지금 볼 것 N건」 이다. 실패하면 0 으로 둔다
- **사이드바의 두 수를 섞지 않는다**(`docs/frontend/now.md` 「주소와 들어오는 길」). 「지금」 배지는 메뉴 맨 위에, 웹 알림(ADR-070)의 읽지 않은 수는 사이드바 맨 아래 밝기 단추 옆 알림 단추에 있다. 그 알림 단추가 main 에 있으면 두 배지가 서로의 수를 더하거나 빼지 않는지 브라우저 검사에서 함께 본다. 없으면 「지금」 배지만 본다

### 5. `test/unit/attention.test.ts` 신규

`../../web/src/lib/attention.ts` 를 상대 경로로 import 한다.

| 입력 | 기대 |
| --- | --- |
| `docs/frontend/now.md` 「이유 문구」 표의 줄마다 `trigger` 와 `signals` | 표의 문구 |
| `FOLLOW_UP_OPEN` 에 `["OVERDUE", "WAITING"]` | 「기한이 지났어요」(위쪽 줄) |
| `FOLLOW_UP_OPEN` 에 표에 없는 `["LONG_RUNNING"]` | 「챙기고 있는 할 일이에요」 |
| 모르는 `trigger` | 빈 문자열 |
| 네 카드가 비었고 `OK` | `allCardsEmpty` 참 |
| 셋은 비었고 하나가 `UNAVAILABLE` | 거짓 |

### 6. `test/unit/loading-routes.test.ts`

`ROUTE_FRAMES` 에 `now: "components/now/now-screen.tsx"` 를 더한다.

### 7. `test/browser/now.spec.ts` 신규

폭마다 따로 만든 사용자(`now-member-${projectName}@example.com`)로 로그인한다.

| 검사 | 기대 |
| --- | --- |
| 실패한 turn 이 생긴다. `hermes.busy()` 로 「주간 장보기 목록 정리」 를 보내 429 를 받는다 | `/now` 에서 `now-card-failures` 가 첫 카드이고 그 안의 `now-item` 이 「답을 만들지 못했고 아직 다시 보내지 않았어요」 를 보인다. 사이드바 `now-count` 가 1 이상이다 |
| 같은 대화에 다시 보내 성공한다. 실패 항목의 링크에서 대화 식별자를 읽어 `conversationId` 로 보낸다 | 다시 연 `/now` 의 실패 카드에 그 항목이 없고 「실패한 일이 없어요」 가 보인다. 그 대화가 `now-card-continue` 에 있다 |
| 폭 | `mobile` 은 카드가 한 열이고 `document.documentElement.scrollWidth` 가 390 이하다. `desktop` 은 첫 두 카드의 위쪽 좌표가 같다(두 열) |
| 메뉴 | 「지금」 이 `navigation` 「주요 화면」 의 첫 링크다 |

## 검증

```bash
# cwd: 저장소 root
node --test 'test/unit/**/*.test.ts'
! grep -rn 'style={{' web/src/

# cwd: web/
pnpm lint
pnpm format:check
pnpm typecheck
AUTH_SECRET=build-time-placeholder ASSISTANT_JWT_SECRET=build-time-placeholder CONTROL_PLANE_BASE_URL=http://build-time-placeholder AUTH_GOOGLE_ID=build-time-placeholder AUTH_GOOGLE_SECRET=build-time-placeholder pnpm build
pnpm test:browser ../test/browser/now.spec.ts
pnpm test:browser ../test/browser/nav.spec.ts
pnpm test:browser ../test/browser/memory.spec.ts
```

기대: 모두 종료 코드 0. `test/unit/attention.test.ts` 와 `test/unit/loading-routes.test.ts` 가 통과한다. `nav` 와 `memory` spec 은 메뉴 순서와 기억 제안 배지가 그대로인지 본다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/attention.ts` | 신규 |
| `web/src/lib/attention-api.ts` | 신규 |
| `web/src/app/api/attention/summary/route.ts` | 신규 |
| `web/src/app/now/page.tsx` | 신규 |
| `web/src/app/now/loading.tsx` | 신규 |
| `web/src/components/now/now-screen.tsx` | 신규 |
| `web/src/components/now/now-card.tsx` | 신규 |
| `web/src/components/now/now-item.tsx` | 신규 |
| `web/src/components/shell/main-nav.tsx` | 수정 |
| `web/src/lib/format.ts` | 수정 |
| `test/unit/attention.test.ts` | 신규 |
| `test/unit/loading-routes.test.ts` | 수정 |
| `test/browser/now.spec.ts` | 신규 |
