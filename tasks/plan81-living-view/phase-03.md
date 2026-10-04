# Phase 03. 새 대화 화면의 한 줄과 관리자 지표, 문서 마감

**Execution profile**: standard

## 목표

새 대화 화면에 「확인할 것 N건」 한 줄을 그린 뒤에 채우고, 관리자 사용량 화면에 먼저 알리기 지표 절을 둔다.
이 plan 의 마지막 phase 로서 지금 화면 문서의 「아직 구현 전이다」 표시를 지우고 PRD 의 구현된 줄을 옮긴다.

**범위 외**: 판정과 지표 계산(plan79). 첫 반응 시간 절(plan78). 이 계획서 디렉터리 삭제(`build-with-teams` 의 마감 단계).

## 컨텍스트

- 새 대화 화면의 머리는 `web/src/components/chat/start-screen.tsx` 의 `StartScreenHeader`(`"use client"`)다. 인사 `h1` 아래에 에이전트 카드(`AgentPicker`)가 온다. `web/src/components/chat/conversation-session.tsx` 가 `startScreen` 일 때 이 머리를 그린다
- 같은 파일의 `StarterPrompts` 주석이 적었듯, 늦게 도착하는 내용이 높이를 바꾸면 입력창이 밀린다. 한 줄의 자리는 처음부터 같은 높이로 잡는다
- 홈 `/`(`web/src/app/page.tsx`)은 서버에서 읽는 것이 없고 `loading.tsx` 를 두지 않는다(`docs/frontend/structure.md`). 이 phase 도 그 규칙을 지킨다
- phase-01 이 `web/src/lib/attention-api.ts` 의 `fetchAttentionSummary()` 와 `/api/attention/summary` 라우트를 만들었다
- 관리자 사용량은 `web/src/app/admin/usage/page.tsx` 가 `UsageScreen admin={true}` 로 그린다. `web/src/components/usage/usage-screen.tsx` 는 관리자일 때만 `callControlPlane` 으로 나눠 보기를 더 읽는다. 지표도 관리자이고 `tab === "summary"` 일 때만 읽는다
- 지표 응답은 `GET /api/v1/admin/attention/metrics?days=30` 이고 `trigger` 별로 센다(`docs/backend/attention.md` 의 「지표」). plan79 의 응답 record 를 읽어 칸 이름을 맞춘다
- 표는 `web/src/components/ui/table.tsx` 를 쓴다. 관리자 영역 안이므로 수를 그대로 그려도 된다

**근거 문서**: `docs/frontend/now.md` 의 「주소와 들어오는 길」, `docs/backend/attention.md` 의 「지표」, `docs/adr/ADR-074-지금-화면은-원래-기록을-읽어-만든-view-이고-정해진-카드-넷만-그린다.md`, `docs/prd.md` 의 「답하는 비서에서 먼저 챙기는 비서로」

## 의도 메모

- 한 줄은 홈의 첫 표시를 기다리게 하지 않는다. 사용자가 고른 조건이다. 그린 뒤 브라우저가 읽고, 0 이거나 실패면 아무것도 그리지 않는다
- 지표 절은 관리자 영역에만 둔다. 일반 사용량 화면에 역할로 갈리는 표시를 새로 만들지 않는다(ADR-063)
- PRD 의 줄은 그 줄의 구현이 main 에 들어왔을 때만 옮긴다. 다른 plan 이 아직 끝나지 않았으면 그 줄은 남긴다

## Blocked 조건

- `backend/src/main/java/com/bifos/assistant/attention/presentation/AttentionAdminController.java` 가 없다 → `PHASE_BLOCKED: plan79 의 지표 API 가 main 에 없다`. 경로는 `@RequestMapping` 과 `@GetMapping` 으로 나뉘어 있어 경로 글 전체로 찾지 않는다

## 작업 항목

### 1. `web/src/components/chat/attention-line.tsx` 신규

`"use client"`. 처음 그릴 때 `fetchAttentionSummary()` 를 한 번 부른다. `nowCount` 가 0 보다 크면 `/now` 로 가는 `Link`(`prefetch={false}`)로 「확인할 것 {N}건」 을 그린다. 0 이거나 실패하면 빈 자리만 둔다.
자리는 늘 `min-h-5` 로 잡아 채워질 때 높이가 바뀌지 않게 한다. `data-testid="attention-line"` 을 링크에 붙인다.

### 2. `web/src/components/chat/start-screen.tsx`

`StartScreenHeader` 의 인사 `h1` 바로 아래에 `<AttentionLine />` 을 둔다. `h1` 의 `mb-6` 은 둘의 간격에 맞춰 나눈다.

### 3. `web/src/components/usage/attention-metrics-section.tsx` 신규, `web/src/components/usage/usage-screen.tsx`

- 절 제목 「먼저 알리기」. 표의 열은 「종류」(`trigger` 를 사람 말로, `docs/frontend/now.md` 의 「이유 문구」 표에서 `signals` 가 「없음」 인 줄의 문구를 쓴다), 「보인 수」, 「숨김 비율」, 「미루기 비율」, 「행동 비율」, 「지금 표시의 헛보임」, 「첫 행동까지」, 「오래된 항목 비율」
- 비율은 백분율 정수, 시간은 `web/src/lib/format.ts` 의 `formatDuration` 으로 그린다. 보인 수가 0 인 줄은 비율 대신 「-」
- `usage-screen.tsx` 의 `Promise.all` 에 `isAdmin && tab === "summary" ? callControlPlane<…>("/api/v1/admin/attention/metrics?days=30") : null` 을 더하고, 요약 탭의 나눠 보기 아래에 그린다. 실패하면 그 절 자리에 `result.message` 한 줄

### 4. 문서 마감

- `docs/adr/ADR-074-지금-화면은-원래-기록을-읽어-만든-view-이고-정해진-카드-넷만-그린다.md` 의 `status` 에서 「아직 구현 전이다.」 를 지운다. `docs/adr/INDEX.md` 의 ADR-074 줄의 상태를 「Accepted」 로 둔다
- `docs/frontend/now.md` 머리의 「아직 구현 전이다. 구현한 PR 이 이 단락을 지운다.」 를 지운다
- `docs/frontend/structure.md` 의 `/now` 줄과 `docs/README.md` 의 `frontend/now.md` 줄에서 「아직 구현 전이다」 를 지운다
- `docs/flow.md` 「지금 화면을 열 때」 의 「**아직 구현 전이다.** 구현한 PR 이 이 줄을 지운다.」 를 지운다
- `docs/prd.md` 의 「답하는 비서에서 먼저 챙기는 비서로」 표에서 줄마다 아래를 보고, 맞으면 그 줄을 「범위와 확인 방법」 표 끝으로 옮긴다

  | 줄 | 옮기는 조건 |
  | --- | --- |
  | 문맥 묶음(ADR-071) | ADR-071 의 `status` 에 「아직 구현 전이다」 가 없다 |
  | 먼저 알리기(ADR-072) | ADR-072 의 `status` 에 「아직 구현 전이다」 가 없다 |
  | 할 일(ADR-073) | ADR-073 의 `status` 에 「아직 구현 전이다」 가 없다 |
  | 지금 화면(ADR-074) | 이 phase 가 지웠다 |
  | 첫 반응 시간 | `docs/model-tiers.md` 의 「첫 반응 시간」 절에 「아직 구현 전이다」 가 없다 |

  다섯 줄이 모두 옮겨지면 그 절의 머리 문단(「아직 구현 전이다. …」)과 표를 지우고 「다음 후보」 목록만 남긴다. 하나라도 남으면 머리 문단을 남긴다

### 5. 검사

- `test/browser/now.spec.ts`: 실패한 turn 이 있는 사용자로 `/` 를 열면 인사가 먼저 보이고, 이어서 `attention-line` 이 「확인할 것 N건」(N ≥ 1)으로 보인다. 누르면 `/now` 로 간다. `page.route("**/api/attention/summary", …)` 로 500 을 돌려주면 `attention-line` 이 없고 인사와 입력창은 그대로다
- `test/browser/usage.spec.ts`: 관리자 영역 `/admin/usage` 의 요약 탭에 「먼저 알리기」 절이 보이고, `MEMBER` 역할 사용자의 `/usage` 에는 그 절이 없다(이 파일의 `test.describe("MEMBER 역할 사용자의 사용량 화면", …)` 안에 더한다)
- 시작 화면이 바뀌므로 `test/browser/start-screen.spec.ts` 와 `test/browser/loading.spec.ts` 를 함께 돌려 기존 동작이 그대로인지 본다

## 검증

```bash
# cwd: 저장소 root
node --test 'test/unit/**/*.test.ts'
! grep -rn 'style={{' web/src/
! grep -n '아직 구현 전' docs/frontend/now.md
! grep -n 'ADR-074.*아직 구현 전' docs/adr/INDEX.md

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
| `test/browser/now.spec.ts` | 수정 |
| `test/browser/usage.spec.ts` | 수정 |
| `docs/adr/ADR-074-지금-화면은-원래-기록을-읽어-만든-view-이고-정해진-카드-넷만-그린다.md` | 수정 |
| `docs/adr/INDEX.md` | 수정 |
| `docs/frontend/now.md` | 수정 |
| `docs/frontend/structure.md` | 수정 |
| `docs/README.md` | 수정 |
| `docs/flow.md` | 수정 |
| `docs/prd.md` | 수정 |
