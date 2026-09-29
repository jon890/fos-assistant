# Phase 02. 사용량 화면의 실행 기록에 요청한 effort 를 보인다

**Execution profile**: standard

## 목표

실행 한 줄에 모델과 함께 요청한 effort 를 보여, 쓰면서 모은 기록으로 모델과 effort 를 견줄 수 있게 한다.

**범위 외**: 모델과 effort 로 묶어 합계를 내는 축은 만들지 않는다. 필요해지면 다음에 정한다.

## 컨텍스트

**근거 문서**: `docs/code-architecture.md` 「사용량 화면의 절」, `docs/data-schema.md` 「agent_execution」

- 실행 한 줄은 `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageDtos.java` 의 `ExecutionView` 다. `provider`, `model` 이 있고 effort 는 없다. `AgentExecution.reasoningEffort()` 는 이미 있다
- 화면은 `web/src/components/usage/execution-list.tsx` 의 타입과 `execution-table.tsx`(넓은 폭), `execution-card.tsx`(좁은 폭)가 그린다
- `UsageDtos.ExecutionView.from` 은 package-private 이다. 실행 목록 응답은 `backend/src/test/java/com/bifos/assistant/usage/UsageControllerTest.java` 가 `controller.myExecutions(...)` 로 받아 본다
- `/usage` 는 서버 컴포넌트가 `callControlPlane` 으로 실행 목록을 직접 읽으므로 `page.route` 로 가짜 응답을 줄 수 없다. `test/browser/usage.spec.ts` 의 「막힌 모델로 실패한 실행」 검사처럼 실제 실행을 만들어 본다

## 의도 메모

- effort 가 비면 「기본」 으로 보인다. 그 칸이 생기기 전 실행도 「기본」 으로 보인다. 실제로 어떤 값이 돌았는지는 모르므로 이름 옆에 붙여 쓰지 않는다
- 표에는 모델 칸 안에 한 줄 더 작게 둔다. 칸을 늘리면 좁은 폭에서 가로로 넘친다

## 작업 항목

### 1. `UsageDtos.ExecutionView` 에 `reasoningEffort`

`execution.reasoningEffort()` 를 싣는다.

### 2. 화면

`execution-list.tsx` 의 타입에 `reasoningEffort: string | null` 을 더하고, `execution-table.tsx` 와 `execution-card.tsx` 가 모델 옆에 `high` 또는 「기본」 을 보인다.

### 3. 이 phase 를 검증하는 테스트

- `UsageControllerTest.java`: builder 에 `.reasoningEffort("high")` 를 준 실행과 주지 않은 실행을 두고, `myExecutions` 응답에서 각각 `"high"` 와 null 이 나온다
- `test/browser/usage.spec.ts`: 실제 실행 두 개를 만든다
  - 하나는 `POST /api/chat/conversations` 로 빈 대화를 만들고 `PUT /api/chat/conversations/{id}/model` 로 `{ provider: null, model: null, reasoningEffort: "high" }` 를 저장한 뒤 고유한 글로 보낸다
  - 다른 하나는 기본값으로 고유한 글로 보낸다
  - 다른 검사가 남긴 실행이 목록에 섞여 있으므로 이 검사의 두 실행 줄(데스크톱은 `tr`, 모바일은 카드)을 특정한 뒤 그 줄 안에서 `high` 와 「기본」 을 본다. `/api/chat` 응답의 `executionId` 로 `a[href="/executions/{executionId}"]` 를 찾고 `ancestor::tr` 이나 `ancestor::article` 로 그 줄을 잡는다

## 검증

`AGENTS.md` 「확인」 절의 여섯 명령을 적힌 순서대로 모두 돌린다. 새 워크트리라 `web` 에서 `pnpm install --frozen-lockfile` 이 먼저 필요하다. 첫 줄은 이 phase 의 검사만 먼저 돌리는 것이다.

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests '*UsageControllerTest'
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
| `backend/src/test/java/com/bifos/assistant/usage/UsageControllerTest.java` | 수정 |
| `test/browser/usage.spec.ts` | 수정 |
| `tasks/plan036-model-choice-web/index.json` | 수정 |
