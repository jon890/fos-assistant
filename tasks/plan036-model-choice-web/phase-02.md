# Phase 02. 사용량 화면의 실행 기록에 요청한 effort 를 보인다

**Execution profile**: fast

## 목표

실행 한 줄에 모델과 함께 요청한 effort 를 보여, 쓰면서 모은 기록으로 모델과 effort 를 견줄 수 있게 한다.

**범위 외**: 모델과 effort 로 묶어 합계를 내는 축은 만들지 않는다. 필요해지면 다음에 정한다.

## 컨텍스트

**근거 문서**: `docs/code-architecture.md` 「사용량 화면의 절」, `docs/data-schema.md` 「agent_execution」

- 실행 한 줄은 `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageDtos.java` 의 `ExecutionView` 다. `provider`, `model` 이 있고 effort 는 없다. `AgentExecution.reasoningEffort()` 는 plan034 가 더했다
- 화면은 `web/src/components/usage/execution-list.tsx` 의 타입과 `execution-table.tsx`(넓은 폭), `execution-card.tsx`(좁은 폭)가 그린다
- 기존 검사 `test/browser/usage.spec.ts` 가 실행 기록을 가짜 응답과 실제 실행 둘로 본다

## 의도 메모

- effort 가 비면 「기본」 으로 보인다. 그 칸이 생기기 전 실행도 「기본」 으로 보인다. 실제로 어떤 값이 돌았는지는 모르므로 이름 옆에 붙여 쓰지 않는다
- 표에는 모델 칸 안에 한 줄 더 작게 둔다. 칸을 늘리면 좁은 폭에서 가로로 넘친다

## 작업 항목

### 1. `UsageDtos.ExecutionView` 에 `reasoningEffort`

`execution.reasoningEffort()` 를 싣는다.

### 2. 화면

`execution-list.tsx` 의 타입에 `reasoningEffort: string | null` 을 더하고, `execution-table.tsx` 와 `execution-card.tsx` 가 모델 옆에 `high` 또는 「기본」 을 보인다.

### 3. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/usage/UsageCostRecordingTest.java`: effort 를 고른 대화의 실행 목록 응답에 `reasoningEffort` 가 실린다. 고르지 않은 실행은 null 이다
- `test/browser/usage.spec.ts`: 실행 기록 가짜 응답에 `reasoningEffort: "high"` 인 줄과 null 인 줄을 두고, 두 폭에서 `high` 와 「기본」 이 보인다

## 검증

`AGENTS.md` 「확인」 절의 여섯 명령을 적힌 순서대로 모두 돌린다. 새 워크트리라 `web` 에서 `pnpm install --frozen-lockfile` 이 먼저 필요하다. 첫 줄은 이 phase 의 검사만 먼저 돌리는 것이다.

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests '*UsageCostRecordingTest'
cd web && pnpm test:browser usage.spec.ts
cd backend && ./gradlew test
cd web && pnpm typecheck
cd web && AUTH_SECRET=build-time-placeholder ASSISTANT_JWT_SECRET=build-time-placeholder CONTROL_PLANE_BASE_URL=http://build-time-placeholder AUTH_GOOGLE_ID=build-time-placeholder AUTH_GOOGLE_SECRET=build-time-placeholder pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
```

- 모두 통과한다
- 모두 통과하면 `tasks/plan036-model-choice-web/index.json` 의 `status` 를 `completed` 로, `current_phase` 를 `2` 로 바꾼다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageDtos.java` | 수정 |
| `web/src/components/usage/execution-list.tsx` | 수정 |
| `web/src/components/usage/execution-table.tsx` | 수정 |
| `web/src/components/usage/execution-card.tsx` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/UsageCostRecordingTest.java` | 수정 |
| `test/browser/usage.spec.ts` | 수정 |
| `tasks/plan036-model-choice-web/index.json` | 수정 |
