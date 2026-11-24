# Phase 04. 실행 트리의 「모름」, 실패 표시, 관리자 오류 요약과 문서

**Execution profile**: standard

## 목표

실행 트리의 도구 줄이 모르는 시간을 「모름」 으로, 실패한 도구를 「실패」 로 보이고, 관리자에게는 오류 종류와 요약을 보인다. 바뀐 계약을 문서에 적는다.

**범위 외**: 모델 호출 줄(PR4).

## 컨텍스트

**요구사항**: REQ1, REQ3, REQ12

**근거 문서**: `docs/features/run-trace/spec.md`, `docs/features/execution.md` 의 「실행 하나를 다시 볼 때」, 「도구를 보이는 말」

- `web/src/components/execution/execution-event-row.tsx` 가 `formatDuration(row.durationMs ?? 0)` 으로 0ms 를 그린다. `mergeEvents`(`execution-node.tsx`)는 실패 여부를 줄에 옮기지 않는다.
- 대화의 작업 과정 블록(`activity-timeline.tsx`)은 시간이 없으면 이미 그리지 않는다. 고치지 않는다(스펙 DECISION14).

## 의도 메모

- 오류 종류와 요약은 응답에 오는 것만 그린다. `MEMBER` 응답에는 서버가 null 로 보내므로 화면에서 역할을 다시 판정하지 않아도 되지만, 관리자 표시 조건(`isAdmin`)도 함께 둔다.

## 작업 항목

### 1. `web/src/components/execution/execution-tree.tsx`, `execution-node.tsx`, `execution-event-row.tsx`

`ExecutionEventView` 에 `errorType?`, `errorSummary?` 를 더한다. 도구 줄에 `failed`, `errorType`, `errorSummary` 를 옮긴다. 끝난 줄의 시간은 null 이면 「모름」. 실패한 줄은 「실패」 를 붙이고, 관리자에게는 오류 종류와 요약을 줄 아래에 보인다.

### 2. `web/src/lib/tool-row-text.ts` (신규)와 `web/test/unit/tool-row-text.test.ts` (신규)

줄 글자를 만드는 순수 함수(`toolDurationText`, `toolFailureText`)를 따로 두고 단위 테스트로 본다. null 시간이 「모름」, 끝나지 않은 줄은 「끝나지 않음」, 실패 줄의 오류 종류와 요약은 관리자에게만 나온다.

### 3. 문서

`docs/features/execution.md`(도구 시간, 「모름」, 실패 표시, 오류 요약, #319 빈 내용), `backend/docs/data-schema.md` 의 「execution_event」(세 칸), `hermes/docs/hermes-contract.md` 의 「plugin hook」(`post_tool_call` 이 주는 것과 fos-ctx 가 보내는 것), `docs/features/chat.md` 의 역할별로 빼는 값 표(오류 종류와 요약), `docs/features/run-trace.md` 의 「아직 구현하지 않았다」.

## 검증

```bash
cd web && node --test test/unit/tool-row-text.test.ts
cd web && pnpm lint && pnpm format:check && pnpm typecheck && pnpm test:node
node --test test/repo/doc-links.test.ts test/repo/doc-files.test.ts
scripts/dev/check-local.sh --fast
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/components/execution/execution-tree.tsx` | 수정 |
| `web/src/components/execution/execution-node.tsx` | 수정 |
| `web/src/components/execution/execution-event-row.tsx` | 수정 |
| `web/src/lib/tool-row-text.ts` | 신규 |
| `web/test/unit/tool-row-text.test.ts` | 신규 |
| `docs/features/execution.md` | 수정 |
| `docs/features/chat.md` | 수정 |
| `docs/features/run-trace.md` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
| `hermes/docs/hermes-contract.md` | 수정 |
