# Phase 04. 「기억했어요」 줄 아래에 저장한 본문 전문을 보인다

**Execution profile**: standard

## 목표

대화의 「기억했어요」 와 「기억을 고쳤어요」 줄 아래에 저장한 본문 전문을 보인다. 모델이 다듬은 본문이 바로 저장되므로 사람이 그 자리에서 내용을 읽고 [고치기] 나 [되돌리기] 를 누를 수 있게 한다.

**범위 외**: 바로 저장 판정(phase 01 부터 03). 제안 카드는 이미 본문을 보인다.

## 컨텍스트

**근거 문서**: `docs/backend/memory.md` 의 「대화에 보이는 것」 표, `docs/adr/ADR-20261008-memory-remember-guard.md` 의 결정 「오판은 사람이 그 자리에서 알아채고 고친다」

- 부품은 `web/src/components/chat/memory-capture-list.tsx` 의 `RememberedLine` 이다. 지금은 「기억했어요: 제목」 과 [고치기] [되돌리기] 만 그린다.
  같은 파일의 `ProposalCard` 가 본문을 `<p className="mt-1 whitespace-pre-wrap">{capture.content}</p>` 로 그리고, 민감 항목이면 「민감한 내용이라 여기서 보이지 않아요.」 를 그린다. 같은 모양을 따른다.
- 응답 칸 `content` 는 민감 항목이면 비어 온다(`docs/backend/memory.md` 「대화에 보이는 것」). `capture.sensitive` 로 나눈다.
- 모델이 쓴 글이라 평문으로만 그린다(ADR-009). 마크다운이나 HTML 로 그리지 않는다.
- 화면 문구는 해요체다(`web/AGENTS.md` 「화면 문구」). 색과 간격은 Tailwind 토큰 클래스만 쓴다.
- 브라우저 시험은 `test/browser/memory-capture.spec.ts` 다. 보조 함수 `capture(executionId, overrides)` 의 기본 본문이 「사과를 좋아해요.」 다.

## 의도 메모

- 고치기 칸이 열려 있을 때(`actions.mode !== null`)는 본문 대신 편집 칸이 보이므로 본문 줄을 숨긴다. 같은 글이 두 번 보이지 않게 한다.

## 작업 항목

### 1. `web/src/components/chat/memory-capture-list.tsx` 수정

`RememberedLine` 의 제목 줄과 `CaptureEditor` 사이에, `actions.mode === null` 일 때 본문을 그린다.
민감 항목이면 「민감한 내용이라 여기서 보이지 않아요.」(`text-xs`), 아니면 `data-testid="memory-capture-content"` 를 단 `<p className="mt-1 whitespace-pre-wrap break-words text-foreground">{capture.content}</p>`.
부품 주석의 「저장된 기록의 「기억했어요」 줄이다.」 를 「저장된 기록의 「기억했어요」 줄과 그 본문이다.」 로 고친다.

### 2. `test/browser/memory-capture.spec.ts` 수정

- 「바로 저장한 기록이 답 아래에 보이고 되돌리면 사라진다」 에서 `memory-capture-content` 가 「사과를 좋아해요.」 를 담는지 단언한다.
- 시험 하나를 더한다: `capture(0, { sensitive: true, content: "" })` 이면 본문 칸이 없고 「민감한 내용이라 여기서 보이지 않아요.」 가 보인다.

## 검증

`web/node_modules` 가 없으면 먼저 `cd web && pnpm install --frozen-lockfile` 을 돌린다.

```bash
cd web && pnpm lint && pnpm typecheck && pnpm format:check
cd web && pnpm test:browser memory-capture.spec.ts
```

기대값: 모두 통과. `grep -rn 'style={{' web/src/components/chat/memory-capture-list.tsx` 결과가 없다.
브라우저 검사는 여러 워커가 같은 Mac 에서 돌므로 작업자 환경의 무거운 검사 잠금 도우미가 있으면 그것으로 감싸 돌린다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/components/chat/memory-capture-list.tsx` | 수정 |
| `test/browser/memory-capture.spec.ts` | 수정 |
