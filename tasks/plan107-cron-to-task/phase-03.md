# Phase 03. 작업 화면의 모델 단계 고르기와 조용한 완료 문구

**Execution profile**: standard

## 목표

작업 만들기와 고치기 화면에 「모델 단계」 고르기를 두고, 실행 기록에 `NOTHING_TO_REPORT` 의 문구를 보인다.
phase 01, 02 의 API 를 사용자가 화면에서 쓰게 하기 위해서다.

**범위 외**: backend(phase 01, 02). 작업 목록 화면에 단계를 보이는 것은 하지 않는다.

## 컨텍스트

**근거 문서**: `docs/backend/task.md` 의 「API」 와 「화면」 절. `docs/adr/ADR-20261008-cron-to-task.md`.

- 화면 모양과 변환: `web/src/lib/task.ts` 의 `TaskView`, `TaskRequest`, `REASON_TEXTS`, `runReasonText`
- 폼: `web/src/components/task/task-form.tsx`. 「알림」 고르기(`id="task-notify"`, `NativeSelect`)와 같은 모양으로 둔다. 저장 본문은 `TaskRequest` 를 만드는 곳(지금 `missedPolicy`, `notify` 를 싣는 곳)에서 만든다
- 실행 기록의 까닭 문구: `web/src/components/task/task-detail.tsx` 가 `runReasonText` 를 쓴다
- 단계 라벨: 화면 이름은 「빠르게」, 「균형」, 「깊게」 다(`web/src/components/execution/execution-detail.tsx` 의 단계 이름과 같다)
- 브라우저 검사는 실제 backend 와 가짜 Hermes 로 돈다: `test/browser/tasks.spec.ts`. 검사 사용자의 에이전트 코드는 `browser` 다
- 단위 검사: `test/unit/task-schedule.test.ts` 가 `runReasonText` 를 확인한다

## 의도 메모

- 「기본값」 을 고르면 `modelTier: null` 을 보낸다. 고칠 때도 같다. 서버는 null 이면 작업의 단계를 지운다
- 단계 정의가 비었는지 화면이 확인하지 않는다. 서버도 확인하지 않는다

## 작업 항목

### 1. `web/src/lib/task.ts`

- 단계 타입은 새로 만들지 않는다. `import type { ModelTierCode } from "./model-tiers";` 로 가져온다. `type` 을 빼면 `node --test` 가 `model-tiers.ts` 의 런타임 import(`@/components/error-message`)를 따라가 단위 검사가 실패한다(`web/AGENTS.md` 의 「상대 경로 import 예외」). `task-form.tsx` 는 node 시험 대상이 아니라 어느 형태든 된다
- `TaskView` 에 `modelTier: ModelTierCode | null;`, `TaskRequest` 에 `modelTier: ModelTierCode | null;`
- `REASON_TEXTS` 에 `NOTHING_TO_REPORT: "알릴 것이 없어 조용히 끝냈어요"`

### 2. `web/src/components/task/task-form.tsx`

- `const [modelTier, setModelTier] = useState<ModelTierCode | "">(task?.modelTier ?? "");`
- 「알림」 `Field` 다음에 `<Field id="task-model-tier" label="모델 단계">` 와 `NativeSelect`. 선택지는 `""` 「기본값」, `FAST` 「빠르게」, `BALANCED` 「균형」, `DEEP` 「깊게」
- 저장 본문에 `modelTier: modelTier === "" ? null : modelTier`

### 3. `TaskRequest` 를 만드는 다른 곳

`git grep -n "missedPolicy" web/src` 로 `TaskRequest` 를 만드는 곳을 모두 찾아 `modelTier` 를 더한다. 타입 검사가 빠진 곳을 알려 준다.

### 4. 이 phase 를 검증하는 시험

- `test/unit/task-schedule.test.ts` 의 「까닭을 화면 문구로 바꾸고 보이지 않을 까닭은 null 이다」 에 `assert.equal(runReasonText("NOTHING_TO_REPORT"), "알릴 것이 없어 조용히 끝냈어요");` 를 더한다
- `test/browser/tasks.spec.ts` 에 하나: 새 작업 화면에서 이름, 지시, 시각을 채우고 「모델 단계」 에 「균형」 을 골라 저장하면 상세 화면의 「모델 단계」 고르기 값이 `BALANCED` 이고, `GET /api/tasks/{id}` 의 `modelTier` 가 `BALANCED` 다. 이 파일의 「새 작업을 저장하면 상세로 가고 목록에 시각이 사람 말로 보인다」 가 폼을 채우는 방법을 따른다

## 검증

```bash
node --test test/unit/task-schedule.test.ts
pnpm --dir web lint
pnpm --dir web exec tsc --noEmit
cd web && pnpm format:check
cd web && pnpm test:browser ../test/browser/tasks.spec.ts --repeat-each=3 --retries=0
```

마지막 줄은 브라우저 검사 가운데 `tasks.spec.ts` 만 CI 처럼 재시도 없이 세 번 돌린다(`web/AGENTS.md` 의 「검사」). backend 를 띄우는 방법은 `test/browser/playwright.config.ts` 가 갖는다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/task.ts` | 수정 |
| `web/src/components/task/task-form.tsx` | 수정 |
| `test/unit/task-schedule.test.ts` | 수정 |
| `test/browser/tasks.spec.ts` | 수정 |
