# Phase 03. 사용량 화면의 완전성 표시와 e2e

**Execution profile**: standard

## 목표

`/usage` 의 요약 탭이 금액을 확인하지 못한 하위 에이전트 수를 `ADMIN` 에게 보이고,
전체 흐름 검사가 자식 금액이 합계에 한 번만 들어가는 것을 확인한다.

**범위 외**: 실행 나무와 실행 상세 화면. backend 의 합계 규칙(phase 02).

## 컨텍스트

- `GET /api/v1/usage/monthly-cost` 응답에 `pricedSubagents`, `pendingSubagents`, `unconfirmedSubagents`, `unpricedSubagents` 가 있다(phase 02). `estimatedCostMicros`, `actualCostMicros` 는 자식을 포함한다
- `GET /api/v1/usage/breakdown` 의 줄에 `subagents` 가 있다. 모델 축에는 `executions` 가 0 이고 `subagents` 만 있는 줄이 생길 수 있다
- 합계 칸은 `web/src/components/usage/monthly-summary.tsx` 의 `MonthlySummary` 가 그린다. `isAdmin` 이 아니면 실행 건수만 그린다. 「가격을 찾지 못한 실행」 `Stat` 이 조건부 칸의 선례다
- 축 표는 `web/src/components/usage/breakdown-table.tsx` 다. 넓은 화면은 표, 좁은 화면은 카드로 그린다
- 화면 문구 규칙은 `web/AGENTS.md` 의 「화면 문구」 다. 해요체로 쓰고 금액과 내부 값은 `ADMIN` 에게만 그린다
- 순수 함수 검사는 `test/unit/` 에 있고 `web/src/lib/` 를 직접 import 한다. 선례는 `test/unit/provider-label.test.ts` 다
- 대역 Hermes 는 `test/e2e/fake-hermes.ts` 다. 입력이 「자식 늦은 완료 검사」, 「자식 완료 사건 없음 검사」, 「압축 뒤 자식 완료 검사」 면 `childUsages` 에 자식을 넣고 `subagent.start` 를 보낸다. 자식 session 응답(`SESSION_PATH` 분기)은 `model: "example-fast"`, `input_tokens: 100`, `cache_read_tokens: 50`, `cache_write_tokens: 10`, `output_tokens: 20` 이고 provider 를 주지 않는다
- e2e 는 `test/e2e/scenarios/streaming.ts` 가 위 세 입력으로 실행 나무의 완료 사건을 기다린다. `test/e2e/scenarios/usage-cost.ts` 가 월 합계를 검사한다. 시나리오 순서는 `test/e2e/run.ts` 가 정한다
- 표본 가격표는 `backend/src/test/resources/pricing/models-dev-sample.json` 이다
- 브라우저 검사는 `test/browser/usage.spec.ts` 와 `test/browser/usage-breakdown.spec.ts` 다. 「이번 달 합계와 가격을 찾지 못한 실행을 구분한다」 가 합계 칸의 선례다
- 하위 에이전트는 orca 명령을 쓰지 않는다

**근거 문서**: `docs/frontend/structure.md` 의 「탭 안의 절」, `docs/model-tiers.md` 의 「합계와 완전성」, `web/AGENTS.md`

## 의도 메모

- 건수 셋을 한 칸에 모은다. 칸을 셋으로 나누면 합계 칸이 완전성 숫자로 찬다
- `MEMBER` 역할에게는 그리지 않는다. 금액 이야기이기 때문이다
- 대역 Hermes 의 기존 세 입력은 provider 를 주지 않는 지금 동작을 유지한다. 실제 v0.21.5 가 그렇다

## 작업 항목

### 1. `web/src/lib/subagent-gap.ts`(신규)

```ts
export type SubagentGap = { pending: number; unconfirmed: number; unpriced: number };
export function subagentGapTotal(gap: SubagentGap): number
export function subagentGapDetail(gap: SubagentGap): string
```

`subagentGapDetail` 은 0 이 아닌 것만 쉼표로 이어 「확인 중 2건, 확인 실패 1건, 가격 미확인 3건」 처럼 돌려준다. 셋 다 0 이면 빈 문자열이다.

### 2. `web/src/components/usage/monthly-summary.tsx`

- `MonthlyCost` 타입에 `pricedSubagents`, `pendingSubagents`, `unconfirmedSubagents`, `unpricedSubagents`(number)를 더한다
- `ADMIN` 화면에서 `subagentGapTotal` 이 0 보다 크면 「가격을 찾지 못한 실행」 다음에 `Stat` 을 그린다. label 은 「금액을 확인하지 못한 하위 에이전트」, value 는 「N건」, detail 은 `subagentGapDetail` 뒤에 「환산 합계에서 제외됨」 을 붙인다
- 아래 안내 문구는 그대로 둔다

### 3. `web/src/components/usage/breakdown-table.tsx`

- `BreakdownRow` 타입에 `subagents: number` 를 더한다
- `subagents` 가 0 보다 크면 표의 실행 칸 아래와 카드의 실행 줄에 「하위 에이전트 N건」 을 작게 적는다

### 4. `test/e2e/fake-hermes.ts` 와 e2e 시나리오

- 입력 「자식 provider 확인 검사」 를 더한다. 기존 세 입력과 같은 흐름이되 자식 session 응답에 `billing_provider` 와 표본 가격표에 있는 모델을 싣는다. provider 와 모델은 `backend/src/test/resources/pricing/models-dev-sample.json` 에서 고르고, 부모 바인딩(`test/e2e/scenarios/binding.ts` 의 `DAD_BINDING`)과 다른 짝이 있으면 그것을 쓴다. `childUsages` 의 값에 provider 와 모델을 담을 칸을 더한다
- `test/e2e/scenarios/streaming.ts` 에 step 을 더한다
  - 「자식 provider 확인 검사」 를 보내고 `/usage/monthly-cost` 의 `pricedSubagents` 가 1 늘 때까지 기다린다(기존 자식 검사와 같은 20초 기한). 늘기 전과 뒤의 `estimatedCostMicros` 차이가 부모 실행의 금액과 자식 금액(표본 단가로 계산한 상수)의 합과 같다
  - 5초 더 기다린 뒤에도 `pricedSubagents` 와 `estimatedCostMicros` 가 그대로다(다시 조회해도 두 번 더하지 않는다)
  - 기존 세 입력의 자식은 `unpricedSubagents` 로 세어진다
- `test/e2e/scenarios/usage-cost.ts` 의 합계 기대값이 위 자식 금액 때문에 달라지면 그 상수를 고치고, 까닭을 그 파일의 주석에 적는다. 달라지지 않으면 고치지 않는다

### 5. 이 phase 를 검증하는 테스트

- `test/unit/subagent-gap.test.ts`(신규): 셋 다 0 이면 합계 0 과 빈 문자열, 하나만 0 이 아니면 그 하나만, 셋 다 있으면 순서대로 쉼표로 이어진 문구
- `test/browser/usage.spec.ts`: 「자식 완료 사건 없음 검사」 를 보낸 뒤 `/usage` 를 다시 열어 가며(30초 기한) 「금액을 확인하지 못한 하위 에이전트」 가 보이는지 본다. `MEMBER` 역할 화면에는 그 문구가 없는지 기존 `MEMBER` 검사에 한 줄 더한다
- `test/browser/usage-breakdown.spec.ts`: 줄 타입에 `subagents` 가 필요해 고칠 곳이 있으면 고친다

## 검증

```bash
cd web && pnpm typecheck && pnpm build
node --test 'test/unit/**/*.test.ts'
node test/e2e/run.ts
cd web && pnpm test:browser test/browser/usage.spec.ts test/browser/usage-breakdown.spec.ts
scripts/quality.sh check
scripts/check-public-safe.sh
```

모두 종료 코드 0 이다. `pnpm build` 에 필요한 환경 변수는 `web/AGENTS.md` 에 있다.
브라우저 검사는 위 두 spec 만 돌린다. 전체 브라우저 검사는 PR 의 CI 가 돌린다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/subagent-gap.ts` | 신규 |
| `web/src/components/usage/monthly-summary.tsx` | 수정 |
| `web/src/components/usage/breakdown-table.tsx` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/streaming.ts` | 수정 |
| `test/e2e/scenarios/usage-cost.ts` | 수정 |
| `test/unit/subagent-gap.test.ts` | 신규 |
| `test/browser/usage.spec.ts` | 수정 |
| `test/browser/usage-breakdown.spec.ts` | 수정 |
