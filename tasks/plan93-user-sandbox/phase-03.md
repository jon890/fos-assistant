# Phase 03. 도구 화면이 실행 공간을 알린다

**Execution profile**: standard

## 목표

관리자가 셸·파일 도구를 켤 때 확인 창이 실제로 닿는 범위를 알리고, 실행 공간이 준비되지 않은 저장 실패를 사람 말로 보인다.

**범위 외**: 서버(phase 01, 02).

## 컨텍스트

**근거 문서**: `docs/backend/agent.md` 의 「도구 변경이 갈리는 지점」, `docs/adr/ADR-084-셸과-파일-도구는-사용자별-docker-실행-공간에서만-돈다.md`

- 흐름과 화면 문구: `docs/backend/agent.md` 의 「도구 변경이 갈리는 지점」. 실패 문구는 「격리된 실행 공간이 준비되지 않아 이 도구를 켤 수 없어요」.
- 확인 문구 함수: `web/src/components/agent/agent-tools-section.tsx` 의 `confirmationDescription`. 지금 `terminal`, `file`, `code_execution` 은 「이 도구는 홈서버 파일과 셸에 닿을 수 있어요.」
- 오류 문구 표: `web/src/components/error-message.ts`
- 브라우저 검사: `test/browser/agent-tools.spec.ts` 의 「관리자가 terminal 도구를 켤 때 확인 창을 거친다」 가 지금 문구를 단언한다.

## 작업 항목

### 1. `web/src/components/agent/agent-tools-section.tsx`

세 도구의 문구를 「이 도구는 이 사용자만의 격리된 실행 공간에서 셸과 파일을 다뤄요. 다른 사용자의 파일과 서버 비밀에는 닿지 않지만 인터넷에는 나갈 수 있어요.」 로 바꾼다.

### 2. `web/src/components/error-message.ts`

`AGENT_SANDBOX_UNAVAILABLE: "격리된 실행 공간이 준비되지 않아 이 도구를 켤 수 없어요."` 를 더한다.

### 3. `test/browser/agent-tools.spec.ts`

확인 창 문구 단언을 새 문구로 바꾼다.

### 4. `test/unit/error-message.test.ts`

기존 단언과 같은 모양으로 `describeError("AGENT_SANDBOX_UNAVAILABLE", ...)` 가 「격리된 실행 공간이 준비되지 않아 이 도구를 켤 수 없어요.」 를 돌려주는지 단언한다.

## 검증

```bash
# cwd: 저장소 root
node --test test/unit/error-message.test.ts
cd web && pnpm lint && pnpm format:check && pnpm typecheck
cd web && pnpm test:browser agent-tools.spec.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/components/agent/agent-tools-section.tsx` | 수정 |
| `web/src/components/error-message.ts` | 수정 |
| `test/browser/agent-tools.spec.ts` | 수정 |
| `test/unit/error-message.test.ts` | 수정 |
