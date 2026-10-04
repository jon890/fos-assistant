# Phase 03. 새 대화 화면의 한 줄과 관리자 지표, 문서 마감

**Execution profile**: standard

## 목표

새 대화 화면에 「확인할 것 N건」 한 줄을 그린 뒤에 채우고, 관리자 사용량 화면에 먼저 알리기 지표 절을 둔다.
이 plan 의 마지막 phase 로서 지금 화면 문서의 「아직 구현 전이다」 표시를 지우고 PRD 의 구현된 줄을 옮긴다.

**범위 외**: 판정과 지표 계산(plan79). 첫 반응 시간 절(plan78). 이 계획서 디렉터리 삭제(`build-with-teams` 의 마감 단계).

## 컨텍스트

- 새 대화 화면의 머리는 `web/src/components/chat/start-screen.tsx` 의 `StartScreenHeader`(`"use client"`)다. `<div className="mx-auto mt-auto w-full max-w-3xl pb-2">` 안에 인사 `<h1 className="mb-6 text-center text-2xl font-semibold">` 가 있고 그 아래에 에이전트 카드(`AgentPicker`)나 뼈대가 온다. `web/src/components/chat/conversation-session.tsx` 가 `startScreen` 일 때 이 머리를 그린다. 입력창은 그 아래의 `data-testid="composer-shell"` 이다
- 같은 파일의 `StarterPrompts` 주석이 적었듯, 늦게 도착하는 내용이 높이를 바꾸면 입력창이 밀린다. 한 줄의 자리는 처음부터 같은 높이로 잡는다
- 홈 `/`(`web/src/app/page.tsx`)은 서버에서 읽는 것이 없고 `loading.tsx` 를 두지 않는다(`docs/frontend/structure.md`). 이 phase 도 그 규칙을 지킨다
- phase-01 이 `web/src/lib/attention-api.ts` 의 `fetchAttentionSummary()` 와 `/api/attention/summary` 라우트를 만들었다. `test/browser/now.spec.ts` 에 `memberOf`, `ownAgent`, `failTurn` 도우미와 파일 끝 `afterAll` 이 있다
- 관리자 사용량은 `web/src/app/admin/usage/page.tsx` 가 `UsageScreen admin={true}` 로 그린다. `web/src/components/usage/usage-screen.tsx` 는 `Promise.all` 로 읽고, 관리자일 때만 나눠 보기 둘을 더 읽어 요약 탭(`tab === "summary"`)의 `BreakdownSection` 으로 그린다. 실패하면 `<p className="mb-8 text-sm">{result.message}</p>` 다
- 지표 응답은 `GET /api/v1/admin/attention/metrics?days=30` 의 `{ days, rows }` 이고 줄은 `attention.presentation.AttentionDtos.MetricRow(String trigger, long shown, long hidden, long snoozed, long acted, long nowShown, long nowHiddenWithoutAction, long staleShown, Long medianSecondsToFirstAction)` 다(`docs/backend/attention.md` 의 「지표」). `shown` 이 0 인 `trigger` 는 줄이 없다
- `web/src/lib/format.ts` 의 `formatDuration(milliseconds)` 는 밀리초를 받는다. 1초 미만이면 「Nms」, 그 밖은 「N.N초」 다
- 표는 `web/src/components/ui/table.tsx` 를 쓴다. 관리자 영역 안이므로 수를 그대로 그려도 된다
- `test/browser/usage.spec.ts` 의 `test.describe("MEMBER 역할 사용자의 사용량 화면", …)` 이 `MEMBER` 역할 사용자로 `/usage` 를 연다. 기본 사용자(`TEST_EMAIL`)는 관리자다

**근거 문서**: `docs/frontend/now.md` 의 「주소와 들어오는 길」, `docs/backend/attention.md` 의 「지표」, `docs/adr/ADR-074-지금-화면은-원래-기록을-읽어-만든-view-이고-정해진-카드-넷만-그린다.md`, `docs/prd.md` 의 「답하는 비서에서 먼저 챙기는 비서로」

## 의도 메모

- 한 줄은 홈의 첫 표시를 기다리게 하지 않는다. 사용자가 고른 조건이다. 그린 뒤 브라우저가 읽고, 0 이거나 실패면 아무것도 그리지 않는다
- 지표 절은 관리자 영역에만 둔다. 일반 사용량 화면에 역할로 갈리는 표시를 새로 만들지 않는다(ADR-063)
- 비율은 순수 함수로 두고 단위 테스트로 본다. 브라우저 검사는 절이 있는지만 본다
- PRD 의 줄은 그 ADR 의 「아직 구현 전」 을 지우는 PR 이 옮긴다. 이 PR 은 ADR-073 과 ADR-074 의 줄만 옮긴다. ADR-071 과 첫 반응 시간은 다른 작업이 끝낼 때 옮긴다

## Blocked 조건

- `backend/src/main/java/com/bifos/assistant/attention/presentation/AttentionAdminController.java` 가 없다 → `PHASE_BLOCKED: plan79 의 지표 API 가 이 브랜치에 없다`. 경로는 `@RequestMapping` 과 `@GetMapping` 으로 나뉘어 있어 경로 글 전체로 찾지 않는다
- `git grep -q follow_up_propose -- backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` 가 실패한다 → `PHASE_BLOCKED: plan80 phase-03 이 이 브랜치에 없다`. 이 phase 가 ADR-073 의 PRD 줄을 옮기므로 제안 도구가 먼저 있어야 한다
- `docs/adr/ADR-073-할-일은-에이전트가-제안하고-사람이-받아들인-것만-챙긴다.md` 의 `status` 줄에 「아직 구현 전」 이 남아 있다 → 위와 같은 문구로 멈춘다

## 작업 항목

### 1. `web/src/components/chat/attention-line.tsx` 신규

`"use client"`. 처음 그릴 때 `fetchAttentionSummary()` 를 한 번 부른다. `nowCount` 가 0 보다 크면 `/now` 로 가는 `Link`(`prefetch={false}`, `className="text-sm text-muted-foreground hover:text-foreground"`, `data-testid="attention-line"`)로 「확인할 것 {N}건」 을 그린다. 0 이거나 실패하면 링크를 그리지 않는다.
자리는 늘 `<div className="mb-4 flex h-5 items-center justify-center">` 로 잡는다. 링크가 생겨도 높이가 바뀌지 않게 하려는 것이다.

### 2. `web/src/components/chat/start-screen.tsx`

`StartScreenHeader` 의 인사 `h1` 바로 아래에 `<AttentionLine />` 을 둔다. `h1` 의 `mb-6` 을 `mb-2` 로 바꾼다. 인사와 한 줄 사이가 8px, 한 줄의 자리가 20px, 한 줄과 에이전트 카드 사이가 16px 다.

### 3. 관리자 지표

- `web/src/lib/attention.ts` 에 `ratioText(part: number, whole: number): string` 을 더한다. `whole` 이 0 이면 「-」, 아니면 `Math.round(part * 100 / whole)` 에 「%」 를 붙인 글이다
- `web/src/components/usage/attention-metrics-section.tsx` 신규. 절 제목 `h2` 「먼저 알리기」. 줄이 없으면 「아직 지금 화면에 보인 항목이 없어요.」 한 줄을 그린다. 표의 열과 값은 아래다

| 열 | 값 |
| --- | --- |
| 종류 | `reasonText({ trigger, signals: [], confidence: "", sources: [] })`. `docs/frontend/now.md` 의 「이유 문구」 표에서 `signals` 가 「없음」 인 줄의 문구다 |
| 보인 수 | `shown` |
| 숨김 비율 | `ratioText(hidden, shown)` |
| 미루기 비율 | `ratioText(snoozed, shown)` |
| 행동 비율 | `ratioText(acted, shown)` |
| 지금 표시의 헛보임 | `ratioText(nowHiddenWithoutAction, nowShown)`. `nowShown` 이 0 이면 「-」 |
| 첫 행동까지 | `medianSecondsToFirstAction` 이 `null` 이면 「-」, 아니면 `formatDuration(medianSecondsToFirstAction * 1000)` |
| 오래된 항목 비율 | `ratioText(staleShown, shown)` |

- `web/src/components/usage/usage-screen.tsx` 의 `Promise.all` 에 `isAdmin && tab === "summary" ? callControlPlane<AttentionMetrics>("/api/v1/admin/attention/metrics?days=30") : null` 을 더한다. `AttentionMetrics`(`days`, `rows`)와 `AttentionMetricRow` 타입은 `attention-metrics-section.tsx` 에 둔다. 요약 탭의 나눠 보기 아래에 그린다. 실패하면 그 절 자리에 `<p className="mb-8 text-sm">{result.message}</p>`

### 4. 문서 마감

- `docs/adr/ADR-074-지금-화면은-원래-기록을-읽어-만든-view-이고-정해진-카드-넷만-그린다.md` 의 `status` 줄을 `` - **status**: `accepted` `` 로 둔다. `docs/adr/INDEX.md` 의 ADR-074 줄의 상태를 `Accepted` 로 둔다
- `docs/frontend/now.md` 머리의 「**아직 구현 전이다.** 구현한 PR 이 이 단락을 지운다.」 를 지운다
- `docs/README.md` 의 `frontend/now.md` 줄 끝 「아직 구현 전이다」 를 지운다
- `docs/flow.md` 「지금 화면을 열 때」 의 「**아직 구현 전이다.** 구현한 PR 이 이 줄을 지운다.」 를 지운다. 「할 일을 제안할 때」 의 같은 줄은 plan80 phase 03 이 이미 지웠으므로(Blocked 조건이 확인한다) 이 파일에 「아직 구현 전」 이 남지 않는다
- `docs/prd.md` 「답하는 비서에서 먼저 챙기는 비서로」 표에서 ADR-073 줄(「대화에서 나온 할 일을 에이전트가 제안하고 …」)과 ADR-074 줄(「지금 화면이 실행 상태와 할 일에 따라 …」)을 「범위와 확인 방법」 표 끝으로 옮긴다. ADR-071 줄과 첫 반응 시간 줄은 남긴다. 그 두 줄은 ADR-071 의 `status` 와 `docs/model-tiers.md` 「첫 반응 시간」 의 「아직 구현 전」 을 지우는 PR 이 옮긴다. 줄이 남으므로 그 절의 머리 문단(「**아직 구현 전이다.** …」)도 남긴다

### 5. 검사

`test/browser/now.spec.ts` 에 더한다. phase-01 의 사용자 구성과 도우미를 쓴다.

| 검사 | 기대 |
| --- | --- |
| `failTurn` 으로 실패한 turn 을 하나 만든다. `loading.spec.ts` 의 `heldUntilReleased` 와 같은 방법으로 `page.route("**/api/attention/summary", …)` 를 건다. 그 경로의 요청은 모두 같은 `Promise` 를 기다렸다가 `route.continue()` 한다. 사이드바 `NowLink` 의 요청도 같은 경로라 함께 붙잡힌다. 그 뒤 `/` 를 연다 | 인사 `h1` 과 `composer-shell` 이 보이고 `attention-line` 은 0 개다. 이 사용자는 자기 에이전트 하나만 쓰므로 `StartScreenHeader` 가 카드 대신 이름 한 줄을 그린다. 그래서 「장보기 비서」 글이 보이고 「에이전트를 읽는 중」 status 가 0 개가 될 때까지 기다린 뒤 `composer-shell` 의 `boundingBox().y` 를 적어 둔다. 읽는 동안의 `h-20` 뼈대가 한 줄로 바뀌며 입력창이 움직인 값을 적지 않게 하려는 것이다 |
| 붙잡아 둔 요청을 한꺼번에 풀어 준다(그 `Promise` 를 푼다) | `attention-line` 이 `/^확인할 것 \d+건$/` 으로 보이고, `composer-shell` 의 `boundingBox().y` 가 적어 둔 값과 같다 |
| `attention-line` 을 누른다 | 주소가 `/now` 다 |
| `page.route("**/api/attention/summary", …)` 로 500 을 돌려주고 `/` 를 연다 | `attention-line` 이 없고 인사와 `composer-shell` 은 그대로다 |

`test/browser/usage.spec.ts`:

- 기본 사용자(관리자)로 `/admin/usage` 를 연 요약 탭에 `getByRole("heading", { name: "먼저 알리기" })` 가 보인다. 새 검사 하나로 더한다
- `test.describe("MEMBER 역할 사용자의 사용량 화면", …)` 안에 `/usage` 에 그 제목이 0 개인 검사를 더한다

`test/unit/attention.test.ts` 에 더한다.

| 입력 | 기대 |
| --- | --- |
| `ratioText(1, 4)`, `ratioText(2, 3)` | 「25%」, 「67%」 |
| `ratioText(0, 0)` | 「-」 |

시작 화면이 바뀌므로 `test/browser/start-screen.spec.ts` 와 `test/browser/loading.spec.ts` 를 함께 돌려 기존 동작이 그대로인지 본다.

## 검증

```bash
# cwd: 저장소 root
node --test 'test/unit/**/*.test.ts'
! grep -rn 'style={{' web/src/
! grep -n '아직 구현 전' docs/frontend/now.md
! grep -n 'ADR-074.*아직 구현 전' docs/adr/INDEX.md
! grep -n '아직 구현 전' docs/adr/ADR-074-지금-화면은-원래-기록을-읽어-만든-view-이고-정해진-카드-넷만-그린다.md
! grep -n 'frontend/now.md.*아직 구현 전' docs/README.md
! grep -n '아직 구현 전' docs/flow.md
```

```bash
# cwd: web/
pnpm lint
pnpm format:check
pnpm typecheck
AUTH_SECRET=build-time-placeholder ASSISTANT_JWT_SECRET=build-time-placeholder CONTROL_PLANE_BASE_URL=http://build-time-placeholder AUTH_GOOGLE_ID=build-time-placeholder AUTH_GOOGLE_SECRET=build-time-placeholder pnpm build
pnpm test:browser ../test/browser/now.spec.ts
pnpm test:browser ../test/browser/usage.spec.ts
pnpm test:browser ../test/browser/start-screen.spec.ts
pnpm test:browser ../test/browser/loading.spec.ts
```

기대: 모두 종료 코드 0. `node --test` 의 `test/unit/doc-references.test.ts` 가 옮긴 문서의 링크를 그대로 통과한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/components/chat/attention-line.tsx` | 신규 |
| `web/src/components/chat/start-screen.tsx` | 수정 |
| `web/src/components/usage/attention-metrics-section.tsx` | 신규 |
| `web/src/components/usage/usage-screen.tsx` | 수정 |
| `web/src/lib/attention.ts` | 수정 |
| `test/unit/attention.test.ts` | 수정 |
| `test/browser/now.spec.ts` | 수정 |
| `test/browser/usage.spec.ts` | 수정 |
| `docs/adr/ADR-074-지금-화면은-원래-기록을-읽어-만든-view-이고-정해진-카드-넷만-그린다.md` | 수정 |
| `docs/adr/INDEX.md` | 수정 |
| `docs/frontend/now.md` | 수정 |
| `docs/README.md` | 수정 |
| `docs/flow.md` | 수정 |
| `docs/prd.md` | 수정 |
