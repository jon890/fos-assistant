# Phase 01. 지금 화면과 사이드바 「지금 볼 것」

**Execution profile**: standard

## 목표

`/now` 화면에서 카드 넷과 항목의 이유와 출처를 그리고, 사이드바 최상단 「새 대화」 단추 바로 아래에 「지금 볼 것」 링크와 `NOW` 건수 배지를 둔다.
실행 상태가 바뀌면 다음에 열 때 카드가 따라 바뀌는 것을 합성 데이터로 확인한다.

**범위 외**: 숨기기, 미루기, 사건 기록, 할 일 동작(phase 02). 새 대화 화면의 한 줄과 관리자 지표(phase 03). 판정과 API(plan79).

## 컨텍스트

- 서버 화면은 `web/src/app/usage/page.tsx` 처럼 `auth()` 로 세션을 보고 없으면 `redirect("/signin")` 한 뒤 부품을 그린다. 부품(`web/src/components/usage/usage-screen.tsx` 의 `UsageScreen`)이 `callControlPlane<T>(path)` 로 읽고 `result.ok` 가 거짓이면 `result.message` 를 그린다
- `callControlPlane` 은 `web/src/lib/control-plane.ts` 에 있다. 서버 라우트는 `web/src/app/api/usage/route.ts` 처럼 `callControlPlane` 결과를 `NextResponse.json(result.data)` 나 `errorResponse(result.code, result.message, result.status)` 로 돌려준다
- 브라우저 요청 함수는 `web/src/lib/usage-api.ts` 처럼 `fetch("/api/…", { cache: "no-store" })` 를 돌려주는 얇은 함수다
- 사이드바는 `web/src/components/shell/sidebar.tsx` 의 `Sidebar` 하나다. 위에서부터 앱 이름 줄, 「새 대화」 단추(`Button asChild variant="outline" className="mb-4 shrink-0 justify-start bg-background hover:bg-accent"` 안의 `Link data-testid="new-conversation-link"`), 「대화 검색」 입력, 대화 목록(`ConversationNav`), 주요 화면 메뉴(`MainNav`, 대화 목록 아래에서 줄어들고 스크롤한다), 맨 아래 줄(이름, 관리자 입구, `NotificationBell`, `ThemeToggle`) 차례다
- 넓은 폭은 `web/src/components/shell/app-shell.tsx` 가 `aside aria-label="사이드바"` 안에 `Sidebar` 를 붙박이로 그린다. 좁은 폭(`md` 미만)은 그 `aside` 를 CSS 로 숨기고, 모바일 머리(`header` 의 「사이드바 열기」 단추, 화면 제목, `NotificationBell size="icon"`, 「새 대화」 아이콘)에서 「사이드바 열기」 를 누르면 같은 `Sidebar` 를 서랍(`Sheet`)으로 연다
- 주요 화면 메뉴는 `web/src/components/shell/main-nav.tsx` 의 `LINKS` 다섯 개(`/agents` 에이전트, `/connections` 연결, `/tasks` 예약 작업, `/memory` 기억, `/usage` 사용량)다. 기억 메뉴의 제안 건수는 `pathname` 이 바뀔 때마다 `fetchMemories()` 로 다시 읽어 `<span className="ml-2 rounded-full bg-muted px-1.5 py-0.5 text-xs text-foreground" data-testid="memory-proposal-count">` 로 그린다. **이 phase 는 `MainNav` 를 고치지 않는다**
- 웹 알림 단추는 `web/src/components/notification/notification-bell.tsx` 다. 배지는 `data-testid="notification-count"`, `aria-hidden="true"`, 채운 바탕(`bg-foreground text-background`)이고, 접근성 이름은 링크의 `label`(「알림, 읽지 않은 알림 N개」)이 갖는다. 읽지 않은 수는 `/api/notifications` 목록 응답의 `unreadCount` 와 SSE 사건이 정한다
- 카드 부품은 `web/src/components/ui/card.tsx` 의 `Card`, `CardHeader`, `CardTitle`, `CardAction`, `CardContent` 다. 배지는 `badge.tsx` 의 `Badge`(`warning`, `secondary` 등), 빈 상태는 `empty-state.tsx` 의 `EmptyState({ title, description })`(두 칸 모두 필수), 안내는 `notice.tsx` 의 `Notice`(`warning`)다
- 뼈대는 `web/src/components/ui/page-skeleton.tsx` 의 `PageSkeleton` 이다. `shape` 에 `"cards"`, `width` 에 `"4xl"` 이 있다. 설명 문단이 없는 화면이면 `description` 을 주지 않는다
- `test/unit/loading-routes.test.ts` 는 `callControlPlane` 을 부르는 `page.tsx` 마다 `loading.tsx` 가 있는지, 그 `width` 가 `ROUTE_FRAMES` 에 적은 파일의 첫 `mx-auto … max-w-*` 틀과 같은지 본다
- 실행 상태의 문구는 `web/src/lib/execution-status.ts` 의 `executionStatusLabel`, `executionStatusVariant` 다
- 시각 함수는 `web/src/lib/format.ts` 에 있다. `formatWhen` 은 「10:30」 같은 절대 시각이라 「3시간 전」 을 만들지 못한다. 이 파일은 import 가 없고 `test/unit/agent-label.test.ts` 가 상대 경로로 읽는다. 에이전트 이름이 없는 줄은 `agentLabel(null)` 이 「지운 에이전트」 로 그린다
- 대화 제목은 빈 글일 수 있다. 사이드바는 `conversation.title || "새 대화"` 로 그린다(`web/src/components/shell/conversation-nav.tsx`)
- 브라우저 검사는 `test/browser/fixtures.ts` 의 `test`, `expect`, `setSession` 을 쓴다. 폭은 `testInfo.project.name === "mobile"` 로 나눈다. 좁은 폭에서 사이드바를 보려면 「사이드바 열기」 를 누른다(`test/browser/shell.spec.ts` 의 `openSidebar`)
- 씨 뿌린 `browser` 에이전트는 관리자 소유의 `PRIVATE` 라(`test/browser/fixtures.ts` 의 `seedAgents`) `MEMBER` 역할 사용자는 쓰지 못한다. `test/browser/usage.spec.ts` 의 `test.describe("MEMBER 역할 사용자의 사용량 화면", …)` 이 본보기다. `memberOf(projectName)` 로 폭마다 다른 사용자로 로그인하고, `page.request.post("/api/agents", { data: { name } })` 가 201 과 `{ code }` 를 돌려주면 그 코드로 보내고, 끝에 `GET /api/agents` 의 `ownedByMe` 줄을 `DELETE /api/agents/{code}` 로 지운다
- 실패한 turn 은 `hermes.busy()` 뒤에 `page.request.post("/api/chat", { data: { text, agentCode } })` 를 보내 429 로 만든다. `finally` 에서 `hermes.clearBusy()` 한다(`test/browser/usage.spec.ts` 의 붐빔 검사). 같은 대화에 다시 보낼 때는 본문에 `conversationId` 를 준다(`web/src/app/api/chat/route.ts`)
- `test/browser/shell.spec.ts` 의 「사이드바는 일반 화면에 있고 로그인 화면에는 없다」 검사는 메뉴 링크를 `[/^에이전트/, /^연결/, /^예약 작업/, /^기억/, /^사용량/]` 로 본다. 메뉴는 바뀌지 않으므로 이 단언은 그대로 통과한다

**근거 문서**: `docs/frontend/now.md` 의 「주소와 들어오는 길」, 「카드」, 「항목 한 줄」, 「이유 문구」, 「폭별 배치」, `docs/backend/attention.md` 의 「API」 와 응답 모양, 「출처 이름」, 「카드의 단추와 승인 경계」, `docs/adr/ADR-074-지금-화면은-원래-기록을-읽어-만든-view-이고-정해진-카드-넷만-그린다.md`

## 의도 메모

- 화면이 카드와 항목을 다시 정렬하지 않는다. 순서 규칙은 서버 하나가 갖는다. 화면이 정렬하면 두 곳의 규칙이 어긋난다
- 이유 문구를 서버가 보내지 않고 화면이 코드로 정한다. 모델이 쓴 글로 이유를 만들지 않기 위해서다. 문구 함수는 순수 함수로 두어 단위 테스트로 표 전체를 확인한다
- `/now` 는 서버에서 읽으므로 `loading.tsx` 를 둔다. 홈 `/` 은 이 phase 에서 건드리지 않는다
- **「지금 볼 것」 은 메뉴가 아니라 사이드바 최상단의 링크다.** 사용자가 정했다. 대화 목록이 길어도 스크롤 없이 늘 보인다. 주요 화면 메뉴에는 넣지 않는다
- **좁은 폭에서도 자리는 같다.** 서랍으로 연 사이드바의 「새 대화」 단추 바로 아래다. 모바일 머리에는 더하지 않는다. 그 머리에는 이미 알림 단추의 배지가 있어, 수 배지를 하나 더 붙이면 두 수가 나란히 놓여 헷갈린다. 좁은 폭에서 바로 들어오는 길은 phase 03 의 새 대화 화면 한 줄이 맡는다
- **넓은 폭에서 사이드바를 접은 머리에는 두지 않는다.** 사용자가 직접 접은 상태이고, 펴면 맨 위에 있으며 새 대화 화면의 「확인할 것 N건」 이 같은 길을 맡는다. `app-shell.tsx` 의 접힌 머리(「사이드바 펴기」, 「새 대화」)는 고치지 않는다
- 「지금 볼 것」 배지는 `nowCount` 하나만 그린다. 기억 메뉴의 제안 건수와 같은 것을 두 번 세지 않는다(`MEMORY_PROPOSED` 는 서버가 `nowCount` 에 넣지 않는다)
- 배지는 알림 배지와 모양을 다르게 한다. 알림은 채운 바탕(`bg-foreground`)이고, 「지금 볼 것」 은 기억 제안 배지와 같은 muted 바탕에 테두리가 없다
- 상대 시각은 응답의 `readAt` 을 기준으로 센다. 서버에서 그린 글과 브라우저에서 다시 그린 글이 같아 hydration 이 어긋나지 않는다

## Blocked 조건

- `backend/src/main/java/com/bifos/assistant/attention/presentation/AttentionController.java` 가 없다 → `PHASE_BLOCKED: plan79 phase-01 이 이 브랜치에 없다`

## 작업 항목

### 1. `web/src/lib/attention.ts`

응답 타입과 순수 함수만 둔다. 다른 web 파일을 import 하지 않는다(단위 테스트가 상대 경로로 읽는다).

- 타입: `AttentionCardKey`(`"failures" | "needs_me" | "delegated" | "continue"`), `AttentionLevel`(`"NOW" | "LATER"`), `AttentionWhy`(`trigger: string`, `signals: string[]`, `confidence: string`, `sources: { source: string; ref: string; asOf: string | null }[]`), `AttentionItem`(`itemKey`, `stateKey`, `attention: AttentionLevel`, `channel: "IN_APP"`, `title`, `conversationId: string | null`, `agentName: string | null`, `at: string`, `why: AttentionWhy`, `execution: { id: number; status: string } | null`, `actionId: string | null`, `followUp: { id: string; dueAt: string | null; waiting: boolean; proposed: boolean } | null`), `AttentionCard`(`key: AttentionCardKey`, `status: "OK" | "UNAVAILABLE"`, `nowCount`, `moreCount`, `items: AttentionItem[]`), `AttentionView`(`readAt: string`, `nowCount`, `cards: AttentionCard[]`). 칸은 `docs/backend/attention.md` 의 「API」 응답 표와 `attention.presentation.AttentionDtos` 의 record 를 따른다. `channel` 은 값이 `IN_APP` 하나라 타입에만 두고 화면이 읽어 가르지 않는다
- `reasonText(why: AttentionWhy): string` — `docs/frontend/now.md` 의 「이유 문구」 표 그대로. `signals` 가 여럿이면 표의 위쪽 줄, 표에 없는 조합은 그 `trigger` 의 「없음」 줄. 모르는 `trigger` 는 빈 문자열
- `cardTitle(key)`, `cardEmptyText(key)` — 「카드」 표의 제목과 비었을 때 문구
- `allCardsEmpty(cards: AttentionCard[]): boolean` — 네 카드가 모두 `OK` 이고 항목이 0 이면 참. 하나라도 `UNAVAILABLE` 이면 거짓
- `itemHref(item: AttentionItem): string | null` — 제목 링크가 갈 곳. `docs/backend/attention.md` 「카드의 단추와 승인 경계」 를 따른다

| `why.trigger` | 갈 곳 |
| --- | --- |
| `EXECUTION_FAILED`, `DELIVERY_FAILED`, `APPROVAL_PENDING`, `CONVERSATION_RECENT` | `conversationId` 가 있으면 `/chat/{conversationId}`, 없으면 `null` |
| `MEMORY_PROPOSED` | `/memory` |
| `DELEGATION_RUNNING`, `DELEGATION_FINISHED` | `execution` 이 있으면 `/executions/{execution.id}`, 없으면 `null` |
| `FOLLOW_UP_PROPOSED`, `FOLLOW_UP_OPEN` | `conversationId` 가 있으면 `/chat/{conversationId}`, 없으면 `null` |
| 그 밖 | `null` |

- `originText(item: AttentionItem): string | null` — 출처 칸의 「대화에서」 와 「직접 더함」. `followUp` 이 있을 때만 낸다. `followUp.proposed` 가 참이면 「대화에서」, 거짓이면 「직접 더함」. `followUp` 이 없으면 `null`
- `moreText(card: AttentionCard): { text: string; href: string | null } | null` — `moreCount` 가 0 이면 `null`. 글은 「{moreCount}개 더 있어요」. `failures` 와 `delegated` 는 `href` 가 `/usage?tab=executions`, `needs_me` 와 `continue` 는 `null` 이다
- `nowLinkLabel(nowCount: number): string` — 0 보다 크면 「지금 볼 것 {N}건」, 아니면 「지금 볼 것」

### 2. `web/src/lib/format.ts`

import 없이 두 함수를 더한다. 시간대는 `Asia/Seoul` 고정이다.

- `formatRelative(value: string, now: Date): string`
  - `value` 를 읽지 못하면 `"-"`
  - `now - value` 가 1분 미만(앞날짜 포함)이면 「방금」
  - 1시간 미만이면 「{분}분 전」(내림)
  - 24시간 미만이면 「{시간}시간 전」(내림)
  - 7일 미만이면 「{일}일 전」(내림)
  - 그 밖은 `new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", month: "long", day: "numeric" })` 의 글(「9월 20일」)
- `formatFullTime(value: string): string` — 읽지 못하면 `"-"`, 아니면 `new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", year: "numeric", month: "long", day: "numeric", hour: "2-digit", minute: "2-digit", hour12: false })` 의 글

### 3. `web/src/components/now/` 부품

- `now-screen.tsx` 의 `NowScreen`(async 서버 부품): `callControlPlane<AttentionView>("/api/v1/attention")`. 실패면 `<p className="text-sm">{result.message}</p>`. 바깥 틀은 `<div className="mx-auto w-full max-w-4xl">`, 제목 `h1` 은 「지금 볼 것」. `allCardsEmpty` 면 `EmptyState title="지금 확인할 것이 없어요" description="실패한 일, 내가 확인할 일, 맡긴 일이 생기면 여기에 보여요."`, 아니면 `grid gap-4 md:grid-cols-2` 안에 응답 순서대로 `NowCard`. 각 `NowCard` 에 `readAt` 을 넘긴다
- `now-card.tsx` 의 `NowCard`: `Card` 머리에 `cardTitle`, 카드의 `nowCount`(서버가 상한 전에 센 값)가 0 보다 크면 `Badge variant="warning"` 으로 그 수. 화면이 보이는 항목을 다시 세지 않는다. `UNAVAILABLE` 이면 `Notice variant="warning"` 으로 「이 카드를 불러오지 못했어요. 잠시 뒤에 다시 열어 주세요」. 항목이 없으면 `cardEmptyText` 한 줄. `moreText` 가 있으면 카드 끝에 그 글을 `href` 가 있으면 `Link`(`prefetch={false}`), 없으면 `<p className="text-sm text-muted-foreground">` 로 그린다. `data-testid="now-card-{key}"`
- `now-item.tsx` 의 `NowItem`:
  - 제목: `itemHref` 가 있으면 `Link`(`prefetch={false}`), 없으면 `<span>`. 글은 `item.title || "새 대화"`
  - 이유 한 줄: `reasonText(item.why)`
  - 출처 줄: `agentName` 이 있으면 그 이름, `originText` 가 있으면 그 글, 그리고 `<time dateTime={item.at} title={formatFullTime(item.at)}>{formatRelative(item.at, new Date(readAt))}</time>`. 셋을 「 · 」 로 잇는다. 마우스를 올리면 `title` 의 전체 시각이 보인다
  - `NOW` 면 `Badge variant="warning"` 「지금」
  - 맡긴 일은 `executionStatusLabel({ status: execution.status, errorCode: null }, false)` 문구를 함께 그린다. 판정 응답에는 오류 코드가 없으므로 `errorCode` 는 늘 `null` 로 넘긴다
  - `data-testid="now-item"`, `data-attention` 에 `NOW` 나 `LATER`
- 좁은 폭에서 출처 줄과 단추가 줄바꿈되게 `flex flex-wrap` 을 쓰고 가로 스크롤이 생기지 않게 한다. 제목은 `min-w-0 break-words` 다

### 4. `web/src/app/now/page.tsx`, `web/src/app/now/loading.tsx`

- `page.tsx`: `web/src/app/usage/page.tsx` 와 같은 세션 확인 뒤 `<NowScreen />`. `metadata` 의 제목은 「지금 볼 것」
- `loading.tsx`: `<PageSkeleton shape="cards" width="4xl" title />`

### 5. 사이드바 「지금 볼 것」

- `web/src/app/api/attention/summary/route.ts` 신규: `GET` 이 `callControlPlane<{ nowCount: number }>("/api/v1/attention/summary")` 를 `NextResponse.json(result.data)` 나 `errorResponse(result.code, result.message, result.status)` 로 돌려준다
- `web/src/lib/attention-api.ts` 신규: `fetchAttentionSummary(): Promise<Response>` 가 `/api/attention/summary` 를 `cache: "no-store"` 로 부른다
- `web/src/components/shell/now-link.tsx` 신규(`"use client"`)의 `NowLink({ onNavigate }: { onNavigate(href: string): void })`:
  - `usePathname()` 이 바뀔 때마다 `fetchAttentionSummary()` 로 `nowCount` 를 읽는다. 실패하면 0 으로 둔다
  - `Link href="/now" prefetch={false} aria-label={nowLinkLabel(nowCount)} onClick={() => onNavigate("/now")}` 를 그린다. `aria-current` 와 클래스는 `MainNav` 의 링크와 같은 규칙(`/now` 이면 `page`, `bg-accent font-medium text-foreground`, 아니면 `text-muted-foreground`)에 `mb-4 flex shrink-0 items-center rounded-md px-3 py-2 text-sm hover:bg-accent hover:text-foreground` 를 쓴다
  - 글은 「지금 볼 것」 이다. `nowCount` 가 0 보다 크면 그 오른쪽에 `<span aria-hidden="true" data-testid="now-count" className="ml-2 rounded-full bg-muted px-1.5 py-0.5 text-xs text-foreground">{nowCount}</span>` 를 붙인다. 테두리를 두지 않는다. 접근성 이름은 링크의 `aria-label` 이 갖는다
  - 링크 안에 `NavPending` 을 둔다(`MainNav` 와 같다)
- `web/src/components/shell/sidebar.tsx`: 「새 대화」 `Button` 의 `mb-4` 를 `mb-2` 로 바꾸고, 그 바로 아래(「대화 검색」 `Input` 위)에 `<NowLink onNavigate={onNavigate} />` 를 둔다
- **사이드바의 두 수를 섞지 않는다.** 「지금 볼 것」 배지는 사이드바 최상단에, 웹 알림(ADR-070)의 읽지 않은 수는 사이드바 맨 아래 알림 단추에 있다. 「지금 볼 것」 배지는 `data-testid="now-count"` 로 다른 이름을 쓰고, 두 배지가 서로의 수를 더하거나 빼지 않는지 브라우저 검사의 「배지」 줄이 본다

### 6. 문서

- `docs/frontend/now.md`:
  - 「주소와 들어오는 길」 표의 「사이드바 메뉴 맨 위」 줄을 「| 사이드바 최상단, 「새 대화」 단추 바로 아래 | 「지금 볼 것」 링크. `nowCount` 가 0 보다 크면 그 수를 배지로 붙인다. 주요 화면 메뉴에는 넣지 않는다. 좁은 폭에서는 서랍으로 연 사이드바의 같은 자리다 |」 로 바꾼다
  - 「**사이드바의 두 수를 헷갈리지 않게 둔다.**」 문단의 「「지금」 메뉴의 수는 … 메뉴 글자 오른쪽에 붙는다.」 를 「「지금 볼 것」 링크의 수는 아직 남은 일 가운데 지금 볼 것이고, 링크 글자 오른쪽에 붙는다. 배지는 기억 제안 배지와 같은 muted 바탕이고 테두리가 없어 채운 바탕의 알림 배지와 모양이 다르다.」 로 바꾼다. 「「지금」 배지의 접근성 이름은 「지금 볼 것 N건」 이고」 를 「「지금 볼 것」 링크의 접근성 이름은 `aria-label` 의 「지금 볼 것 N건」 이고, 0 이면 「지금 볼 것」 이다. 배지 자체는 낭독기에서 숨긴다.」 로 바꾼다
  - 「카드」 절의 「네 카드가 모두 비면 …」 줄에 설명 문구 「실패한 일, 내가 확인할 일, 맡긴 일이 생기면 여기에 보여요.」 를 더한다
  - 「카드」 절의 `moreCount` 줄을 「`moreCount` 가 있으면 카드 끝에 「N개 더 있어요」 를 그린다. 실패와 맡긴 일은 `/usage?tab=executions` 로 가는 링크이고, 내 차례와 이어서 하기는 링크 없이 글만 그린다. 내 차례는 승인 대기, 기억 제안, 할 일이 섞여 한 화면으로 보낼 곳이 없고, 이어서 하기의 나머지 대화는 사이드바 목록에 있다」 로 바꾼다
  - 「항목 한 줄」 표의 출처 줄을 「에이전트 이름, 할 일이면 「대화에서」(에이전트가 제안했다)나 「직접 더함」(사람이 더했다), 시각. 시각은 응답의 `readAt` 을 기준으로 1분 미만 「방금」, 「N분 전」, 「N시간 전」, 7일 미만 「N일 전」, 그 밖은 「9월 20일」 처럼 적고, `time` 요소의 `title` 로 전체 시각을 보인다」 로 바꾼다. 제목 줄에 「빈 대화 제목은 「새 대화」 로 그린다」 를 더한다
- `docs/adr/ADR-074-지금-화면은-원래-기록을-읽어-만든-view-이고-정해진-카드-넷만-그린다.md` 의 결정 줄 「주소는 `/now` 이고 사이드바 메뉴 맨 위의 「지금」 이다.」 를 「주소는 `/now` 이고 사이드바 최상단 「새 대화」 단추 바로 아래의 「지금 볼 것」 링크로 들어간다.」 로 바꾼다
- `docs/frontend/now.md` 「시나리오」 의 넓은 화면 단락에서 「사이드바 「지금」 에 3 이 붙는다.」 를 「사이드바 「지금 볼 것」 에 3 이 붙는다.」 로 고친다
- `docs/frontend/shell.md` 의 「사이드바는 위에서부터 세 구역이다.」 를 「사이드바는 위에서부터 네 구역이다.」 로 고치고, 그 아래 표의 대화 목록 줄 앞에 「| 「새 대화」 단추와 「지금 볼 것」 링크 | 줄어들지 않는다. 대화 목록이 길어도 스크롤 없이 보인다 |」 줄을 더한다. 표 아래에 「「지금 볼 것」 은 주요 화면 메뉴가 아니다. 좁은 폭에서는 서랍으로 연 사이드바의 같은 자리이고, 사이드바를 접은 머리에는 두지 않는다.」 를 더한다
- `docs/prd.md` 「범위와 확인 방법」 표의 ADR-072 줄(「지금 봐야 할 것만 화면 안에서 먼저 알린다」) 확인 칸의 「`GET /api/v1/attention/summary` 의 `nowCount` 에 든다」 를 「사이드바 「지금 볼 것」 의 건수에 든다」 로 되돌린다. 화면이 생겼으므로 사람이 보는 자리로 확인한다
- `docs/adr/ADR-072-먼저-알리기의-기본값은-알리지-않음이고-control-plane-기록에서-정한-신호만-화면-안에-올린다.md` 의 판정 표 `NOW` 줄 「사이드바 「지금」 메뉴의 건수」 를 「사이드바 「지금 볼 것」 링크의 건수」 로 바꾼다

### 7. `test/unit/attention.test.ts` 신규

`../../web/src/lib/attention.ts` 를 상대 경로로 import 한다.

| 입력 | 기대 |
| --- | --- |
| `docs/frontend/now.md` 「이유 문구」 표의 줄마다 `trigger` 와 `signals` | 표의 문구 |
| `FOLLOW_UP_OPEN` 에 `["OVERDUE", "WAITING"]` | 「기한이 지났어요」(위쪽 줄) |
| `FOLLOW_UP_OPEN` 에 표에 없는 `["LONG_RUNNING"]` | 「챙기고 있는 할 일이에요」 |
| 모르는 `trigger` | 빈 문자열 |
| 네 카드가 비었고 `OK` | `allCardsEmpty` 참 |
| 셋은 비었고 하나가 `UNAVAILABLE` | 거짓 |
| `itemHref` 에 `DELEGATION_RUNNING` 과 `execution: { id: 41, status: "RUNNING" }` | `/executions/41` |
| `itemHref` 에 `conversationId` 가 `null` 인 `FOLLOW_UP_OPEN` | `null` |
| `originText` 에 `followUp.proposed` 참, 거짓, `followUp` 없음 | 「대화에서」, 「직접 더함」, `null` |
| `moreText` 에 `failures` 와 `moreCount` 2, `needs_me` 와 `moreCount` 1 | `{ text: "2개 더 있어요", href: "/usage?tab=executions" }`, `{ text: "1개 더 있어요", href: null }` |
| `nowLinkLabel(0)`, `nowLinkLabel(3)` | 「지금 볼 것」, 「지금 볼 것 3건」 |

### 8. `test/unit/relative-time.test.ts` 신규

`../../web/src/lib/format.ts` 를 상대 경로로 import 한다. `now` 는 `new Date("2026-10-05T12:00:00Z")` 다.

| 입력 | 기대 |
| --- | --- |
| `"2026-10-05T11:59:30Z"` | 「방금」 |
| `"2026-10-05T12:00:30Z"`(앞날짜) | 「방금」 |
| `"2026-10-05T11:15:00Z"` | 「45분 전」 |
| `"2026-10-05T09:00:00Z"` | 「3시간 전」 |
| `"2026-10-03T12:00:00Z"` | 「2일 전」 |
| `"2026-09-20T03:00:00Z"` | 「9월 20일」 |
| `"not-a-date"` | `"-"` |
| `formatFullTime("not-a-date")` | `"-"` |

### 9. `test/unit/loading-routes.test.ts`

`ROUTE_FRAMES` 에 `now: "components/now/now-screen.tsx"` 를 더한다.

### 10. `test/browser/now.spec.ts` 신규

파일 전체를 `test.describe("지금 화면", …)` 하나로 둔다.

- `memberOf(projectName)` 는 `{ email: \`now-member-${projectName}@example.com\`, name: "지금 화면 보는 사용자" }` 다. `test.beforeEach` 에서 `setSession(context, memberOf(testInfo.project.name))` 뒤 `GET /api/me` 가 성공하는지 본다
- 도우미 `ownAgent(page)`: `GET /api/agents` 의 `ownedByMe` 줄이 있으면 그 `code`, 없으면 `POST /api/agents`(`{ name: "장보기 비서" }`)가 201 인지 보고 그 `code`
- 도우미 `failTurn(page, hermes, text, conversationId?)`: `hermes.busy()` 뒤 `page.request.post("/api/chat", { data: { text, agentCode: await ownAgent(page), conversationId } })` 가 429 인지 보고, `finally` 에서 `hermes.clearBusy()`
- 파일 끝 `test.afterAll(async ({ browser }, testInfo) => …)`: `browser.newContext({ baseURL: WEB_BASE_URL })`(`test/browser/settings.ts`)로 새 문맥을 열어 `setSession` 으로 그 사용자로 로그인하고, `context.request` 로 `GET /api/agents` 의 `ownedByMe` 줄마다 `DELETE /api/agents/{code}` 가 204 인지 본 뒤 문맥을 닫는다. phase 02 와 03 이 같은 사용자와 에이전트를 다시 쓰므로 검사마다 지우지 않는다

| 검사 | 기대 |
| --- | --- |
| `failTurn` 으로 「주간 장보기 목록 정리」 를 보낸다 | `/now` 에서 `now-card-failures` 가 첫 카드이고 그 안의 `now-item` 이 「답을 만들지 못했고 아직 다시 보내지 않았어요」 를 보인다. 그 줄의 `time` 요소에 `title` 속성이 있다. 사이드바 `now-count` 가 1 이상이다 |
| 같은 대화에 다시 보내 성공한다. 실패 항목의 제목 링크 `href` 에서 대화 식별자를 읽어 `conversationId` 로 보낸다 | 다시 연 `/now` 의 실패 카드에 그 항목이 없고 「실패한 일이 없어요」 가 보인다. 그 대화가 `now-card-continue` 에 있다 |
| 폭 | `mobile` 은 카드가 한 열이고 `document.documentElement.scrollWidth` 가 390 이하다. `desktop` 은 첫 두 카드의 위쪽 좌표가 같다(두 열) |
| 자리 | 사이드바(좁은 폭은 「사이드바 열기」 를 누른 뒤)의 `getByRole("link", { name: /^지금 볼 것/ })` 이 보이고, 그 위쪽 좌표가 `new-conversation-link` 의 아래쪽 좌표 이상이며 「대화 검색」 입력의 위쪽 좌표보다 작다. `navigation` 「주요 화면」 안에는 그 링크가 없다 |
| 배지 | 승인 대기 하나가 알림 한 줄과 지금 볼 것 한 항목을 함께 만든 상태를 두 경로의 대역으로 만든다. `page.route("**/api/attention/summary", …)` 는 `{ nowCount: 1 }`, `page.route("**/api/notifications?**", …)` 는 `unreadCount: 1` 과 읽지 않은 `APPROVAL_REQUESTED` 한 줄, `**/api/notifications/events` 는 붙잡아 두고, `**/api/notifications/read-all` 은 대역 상태를 읽음으로 바꾸고 204 를 돌려준다(`test/browser/notifications.spec.ts` 의 `routeNotifications` 와 같은 방법). **두 배지는 `page.getByRole("complementary", { name: "사이드바" })` 안에서만 찾는다.** 모바일 머리의 `NotificationBell` 도 `notification-count` 를 그리고 `md:hidden` 이라 넓은 폭에서도 DOM 에 남아, 범위 없이 찾으면 둘이 잡힌다(`test/browser/notifications.spec.ts` 84줄이 범위를 정하는 까닭). 좁은 폭은 「사이드바 열기」 를 누른 뒤 찾는다. 서랍이 열리면 붙박이 `aside` 를 그리지 않아 범위 안에 하나만 있다. 그 범위 안의 `notification-count` 와 `now-count` 가 모두 「1」 이다. `/notifications` 로 가서 「모두 읽음」 을 누르고, 좁은 폭은 「사이드바 열기」 를 다시 누른 뒤 같은 범위에서 본다. `notification-count` 는 0 개이고 `now-count` 는 「1」 그대로다. 링크의 접근성 이름은 「지금 볼 것 1건」 이다 |

`test/browser/shell.spec.ts` 의 「사이드바는 일반 화면에 있고 로그인 화면에는 없다」 검사에 아래를 더한다. 메뉴 링크 단언(`[/^에이전트/, …, /^사용량/]`)은 그대로 둔다.

- `sidebar.getByRole("link", { name: /^지금 볼 것/ })` 가 보인다
- `menu.getByRole("link", { name: /^지금 볼 것/ })` 는 0 개다
- 그 검사 위의 주석 「사이드바는 대화 목록, 주요 화면 메뉴, 맨 아래 줄의 세 구역이다.」 를 「사이드바는 맨 위의 「새 대화」 와 「지금 볼 것」, 대화 목록, 주요 화면 메뉴, 맨 아래 줄이다.」 로 고친다

## 검증

```bash
# cwd: 저장소 root
node --test 'test/unit/**/*.test.ts'
! grep -rn 'style={{' web/src/
```

```bash
# cwd: web/
pnpm lint
pnpm format:check
pnpm typecheck
AUTH_SECRET=build-time-placeholder ASSISTANT_JWT_SECRET=build-time-placeholder CONTROL_PLANE_BASE_URL=http://build-time-placeholder AUTH_GOOGLE_ID=build-time-placeholder AUTH_GOOGLE_SECRET=build-time-placeholder pnpm build
pnpm test:browser ../test/browser/now.spec.ts
pnpm test:browser ../test/browser/shell.spec.ts
pnpm test:browser ../test/browser/nav.spec.ts
pnpm test:browser ../test/browser/memory.spec.ts
pnpm test:browser ../test/browser/design-tokens.spec.ts
```

기대: 모두 종료 코드 0. `test/unit/attention.test.ts`, `test/unit/relative-time.test.ts`, `test/unit/loading-routes.test.ts` 가 통과한다.
`shell` spec 은 새 링크의 자리와 메뉴가 그대로인지, `nav` 와 `memory` spec 은 메뉴와 기억 제안 배지가 그대로인지, `design-tokens` spec 은 사이드바 바탕과 메뉴 글자의 대비가 그대로인지 본다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/attention.ts` | 신규 |
| `docs/prd.md` | 수정 |
| `web/src/lib/attention-api.ts` | 신규 |
| `web/src/lib/format.ts` | 수정 |
| `web/src/app/api/attention/summary/route.ts` | 신규 |
| `web/src/app/now/page.tsx` | 신규 |
| `web/src/app/now/loading.tsx` | 신규 |
| `web/src/components/now/now-screen.tsx` | 신규 |
| `web/src/components/now/now-card.tsx` | 신규 |
| `web/src/components/now/now-item.tsx` | 신규 |
| `web/src/components/shell/now-link.tsx` | 신규 |
| `web/src/components/shell/sidebar.tsx` | 수정 |
| `test/unit/attention.test.ts` | 신규 |
| `test/unit/relative-time.test.ts` | 신규 |
| `test/unit/loading-routes.test.ts` | 수정 |
| `test/browser/now.spec.ts` | 신규 |
| `test/browser/shell.spec.ts` | 수정 |
| `docs/frontend/now.md` | 수정 |
| `docs/frontend/shell.md` | 수정 |
| `docs/adr/ADR-074-지금-화면은-원래-기록을-읽어-만든-view-이고-정해진-카드-넷만-그린다.md` | 수정 |
| `docs/adr/ADR-072-먼저-알리기의-기본값은-알리지-않음이고-control-plane-기록에서-정한-신호만-화면-안에-올린다.md` | 수정 |
