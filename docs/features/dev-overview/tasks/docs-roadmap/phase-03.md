# Phase 03. mermaid 블록을 손그림과 노드 색으로 그리기

**Execution profile**: standard

## 목표

문서 본문의 ` ```mermaid ` 블록을 브라우저에서 그림으로 그린다. 손그림을 지원하는 그림 종류는 기본이 손그림이고, sequence 같은 종류는 mermaid 기본 모양이다. 문법이 틀린 블록만 원문과 오류 문구로 남는다.

**범위 외**: 다른 화면(채팅 등)의 mermaid. 브라우저 테스트는 phase 4 에서 쓴다.

## 컨텍스트

**요구사항**: REQ5

**근거 문서**: `docs/features/dev-overview/spec.md` 의 REQ5, DECISION8, DECISION16, `docs/features/dev-overview/design.md` 의 「mermaid」

- phase 2 의 `web/src/components/dev-docs/markdown.tsx` 의 `code` 부품에서 `language-mermaid` 만 이 phase 의 부품으로 넘긴다.
- 지금 문서의 mermaid 는 대부분 `sequenceDiagram` 이고 손그림 flowchart 는 `docs/features/naver-shopping/design.md` 에 있다.

## 의도 메모

- mermaid 는 무거우므로 블록이 있을 때만 `import("mermaid")` 로 늦게 불러 다른 화면의 번들을 늘리지 않는다.
- `securityLevel: "strict"` 는 문서의 설정 머리가 덮지 못한다. 라벨 HTML 을 정리하고 클릭 동작을 막는다.
- 어두운 테마여도 흰 바탕 상자 안에 그린다(스펙 DECISION16). 테마 변수를 화면 테마에 맞추지 않는다.

## 작업 항목

### 1. 의존성

`web/` 에서 `pnpm add mermaid` 로 `package.json` 과 `pnpm-lock.yaml` 을 고친다.

### 2. `web/src/components/dev-docs/mermaid-block.tsx` (`"use client"`)

- props: `source: string`.
- 처음 한 번 `mermaid.initialize({ startOnLoad: false, securityLevel: "strict", theme: "default", look: "handDrawn" })`. 모듈 변수로 한 번만 한다.
- 블록마다 `useId()` 를 바탕으로 한 고유 id(영숫자만)로 `mermaid.render(id, source)` 를 부르고, 결과 SVG 를 흰 바탕 상자(`bg-white` 와 테두리, 가로 넘침은 스크롤)에 넣는다.
- 실패하면 `document.getElementById(id)` 와 `document.getElementById("d" + id)` 같은 mermaid 가 `body` 에 남긴 임시 요소를 지우고, 원문 코드 블록과 「그림을 그리지 못했어요」 를 보인다.
- 그리기 전에는 원문 코드 블록을 보인다(그림을 못 그리는 환경에서도 내용이 보인다).
- 그림 상자에 `data-testid="mermaid-diagram"`, 실패 문구에 `role="status"`.

### 3. `web/src/components/dev-docs/markdown.tsx`

`code` 부품에서 언어가 `mermaid` 면 `MermaidBlock` 을 쓴다.

### 4. `web/src/lib/dev-docs/mermaid.ts` 와 단위 테스트

- `mermaidRenderId(reactId: string): string`: `useId()` 값의 `:` 같은 글자를 뺀 `mmd-<영숫자>` 를 돌려준다.
- `web/test/unit/dev-docs-mermaid.test.ts`: 반환 값이 `/^mmd-[A-Za-z0-9]+$/` 이고 다른 입력은 다른 값이다.

## 검증

```bash
cd web && pnpm typecheck
cd web && pnpm lint
cd web && pnpm format:check
cd web && node --test test/unit/dev-docs-mermaid.test.ts
cd web && pnpm test:node
```

모두 종료 코드 0.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/package.json` | 수정 (mermaid) |
| `web/pnpm-lock.yaml` | 수정 |
| `web/src/components/dev-docs/mermaid-block.tsx` | 신규 |
| `web/src/components/dev-docs/markdown.tsx` | 수정 (mermaid 블록) |
| `web/src/lib/dev-docs/mermaid.ts` | 신규 |
| `web/test/unit/dev-docs-mermaid.test.ts` | 신규 |
