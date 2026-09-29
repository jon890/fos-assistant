# Phase 04. 사용량 화면을 탭 넷으로 나누고 스킬 탭을 둔다

**Execution profile**: standard

## 목표

사용량 화면을 탭 넷(요약, 실행 기록, 스킬, 입력 지문)으로 나누고, 「스킬」 탭에서 자기 스킬 호출을 본다. 실행 기록 줄에 그 실행에서 쓴 스킬 이름을 붙인다.

**범위 외**: backend(phase 01, 02), 에이전트 상세의 스킬 절(phase 03).

## 컨텍스트

**근거 문서**: `docs/code-architecture.md` 의 「사용량 화면의 탭」, 「스킬」 절의 「호출 이력」

- backend 경로: `GET /api/v1/usage/skills` → `[{agentCode, agentName, skillName, count, lastInvokedAt, lastConversationId}]`(`lastConversationId` 는 대화의 공개 UUID, 지운 대화면 null). 실행 목록 `GET /api/v1/usage/executions` 의 줄에 `skillNames: string[]`
- 사용량 화면: `web/src/app/usage/page.tsx` 가 서버에서 실행 목록, 월 합계, 축별 합계 둘을 함께 읽고 `MonthlySummary`, `BreakdownSection`, `FingerprintSection`, `ExecutionList` 를 차례로 그린다. 실행 목록 읽기가 실패하면 페이지 전체가 실패 문구 한 줄이다
- 실행 줄은 `web/src/components/usage/execution-card.tsx`(좁은 폭), `execution-table.tsx`(넓은 폭). 타입은 `execution-list.tsx` 의 `UsageExecution`
- 뼈대는 `web/src/app/usage/loading.tsx`
- phase 03 이 만든 `web/src/lib/skill.ts` 에 타입을 더한다
- 대화로 가는 주소는 `/chat/{대화 UUID}` 다

## 의도 메모

- 탭은 주소 `?tab=` 에 남긴다. 값은 `summary`(기본), `executions`, `skills`, `fingerprints`. 모르는 값이면 `summary` 다. 탭 단추는 `Link` 로 주소만 바꾼다
- 서버 페이지가 지금처럼 네 조회를 함께 읽고, `tab=skills` 일 때만 `/api/v1/usage/skills` 를 더 읽는다. 실행 목록 조회가 실패하면 탭과 상관없이 페이지 전체가 실패 문구다(docs 그대로)
- 요약 탭은 합계 칸과 「어디에 썼나」, 입력 지문 탭은 「무엇이 달라졌나」, 실행 기록 탭은 실행 목록이다. 지문 탭의 그리지 않는 조건은 지금과 같다
- 스킬 탭은 스킬마다 에이전트 이름, 스킬 이름, 횟수, 마지막 호출을 보이고, `lastConversationId` 가 있으면 누르면 그 대화로 간다. 없으면 링크가 없다. 기록이 없으면 빈 상태 「아직 부른 스킬이 없어요.」 를 보인다. 조회가 실패하면 탭 안에 실패 문구만 보인다
- 실행 줄의 스킬 이름은 작은 글씨로 붙인다. 비었으면 그리지 않는다

## 작업 항목

### 1. 탭과 스킬 탭

- `web/src/components/usage/usage-tabs.tsx` 신규
- `web/src/components/usage/skill-usage-list.tsx` 신규
- `web/src/app/usage/page.tsx`: `searchParams.tab` 으로 탭을 정하고 기존 절을 탭 안에 옮긴다. 바깥 틀(`mx-auto w-full max-w-5xl`)은 이 파일에 남긴다. `test/unit/loading-routes.test.ts` 가 이 틀과 `loading.tsx` 의 폭이 같은지 본다
- `web/src/app/usage/loading.tsx`: 뼈대 맨 위에 탭 줄 자리를 더한다
- `web/src/lib/skill.ts`: `SkillUsageRow` 를 더한다

### 2. 실행 줄의 스킬 이름

- `web/src/components/usage/execution-list.tsx`: `UsageExecution` 에 `skillNames: string[]`
- `execution-card.tsx`, `execution-table.tsx`: `skillNames` 를 작게 붙인다

### 3. 문서

- `docs/code-architecture.md` 「아직 만들지 않은 것」: 스킬 줄에서 스킬 올리기와 관리, 호출 이력, 사용량 화면의 탭을 빼고 스킬 커맨드만 남긴다

### 4. 이 phase 를 검증하는 브라우저 검사

- `test/browser/usage.spec.ts`: 실행 기록을 찾는 기존 검사는 `/usage?tab=executions` 로 연다. 합계 칸을 보는 검사는 기본 탭에서 본다. 새 검사: 탭 넷이 보이고 `?tab=skills` 로 열면 스킬 탭이 선택돼 있다. 스킬을 부른 대화 뒤 스킬 탭에 그 스킬이 1회로 보이고 누르면 그 대화로 간다. 실행 기록 탭의 그 줄에 스킬 이름이 보인다
- `test/browser/usage-breakdown.spec.ts`: 「어디에 썼나」 검사는 기본(요약) 탭에서, 「무엇이 달라졌나」 검사는 `?tab=fingerprints` 에서 본다

## 검증

```bash
# cwd: 저장소 root
cd web && pnpm typecheck
cd web && AUTH_SECRET=build-time-placeholder ASSISTANT_JWT_SECRET=build-time-placeholder CONTROL_PLANE_BASE_URL=http://build-time-placeholder AUTH_GOOGLE_ID=build-time-placeholder AUTH_GOOGLE_SECRET=build-time-placeholder pnpm build
cd web && pnpm test:browser
node --test 'test/unit/**/*.test.ts'
grep -rn 'style={{' web/src/
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/components/usage/usage-tabs.tsx` | 신규 |
| `web/src/components/usage/skill-usage-list.tsx` | 신규 |
| `web/src/app/usage/page.tsx` | 수정 |
| `web/src/app/usage/loading.tsx` | 수정 |
| `web/src/lib/skill.ts` | 수정 |
| `web/src/components/usage/execution-list.tsx` | 수정 |
| `web/src/components/usage/execution-card.tsx` | 수정 |
| `web/src/components/usage/execution-table.tsx` | 수정 |
| `docs/code-architecture.md` | 수정 |
| `test/browser/usage.spec.ts` | 수정 |
| `test/browser/usage-breakdown.spec.ts` | 수정 |
