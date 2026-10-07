# Phase 03. 연결 화면이 커넥터를 아이콘, 링크, 도구 요약이 있는 카드로 그린다

**Execution profile**: standard

## 목표

`/connections` 의 커넥터 카드가 아이콘, 이름, 연결 상태, 설명, 링크, 도구 수와 위험도별 수, 붙인 에이전트 수를 보인다.
`/connections/{id}` 의 머리도 아이콘과 링크를 보인다. 휴대폰 폭에서 가로로 넘치지 않는다.

**범위 외**: 에이전트 상세의 연결 목록(`web/src/components/agent/agent-connections-section.tsx`). Control Plane 이 아이콘을 싣지 않는다.

## 컨텍스트

- 응답 형식: `GET /api/v1/connectors` 의 항목에 `icon`(`data:image/svg+xml;base64,...` 나 `data:image/png;base64,...`, 또는 null)과 `link`(`https://` 글이나 null)가 있다. 옛 Control Plane 은 두 칸을 내지 않으므로 undefined 도 null 로 다룬다
- 타입과 도우미는 `web/src/lib/connection.ts` 의 `ConnectorSummary`, `toolRiskLabel`, `connectionStatusLabel` 이다
- 목록 카드는 `web/src/components/connector/connector-catalog.tsx` 다. 지금은 카드 전체가 `<Link>` 라 안에 외부 링크를 넣으면 `<a>` 가 겹친다. 이름을 `<Link>` 로 두고 `after:absolute after:inset-0` 로 카드 전체를 덮고, 외부 링크는 `relative z-10` 으로 그 위에 둔다. `Card` 에 `relative` 를 준다. `Card` 는 `overflow-hidden` 이라 초점 표시는 `Card` 의 `has-[[data-slot=card-link]:focus-visible]:ring-3 has-[[data-slot=card-link]:focus-visible]:ring-ring/50` 으로 한다. 색을 함께 주지 않으면 `Card` 의 흐린 `ring-foreground/10` 이 그대로 쓰인다
- 상세 머리는 `web/src/components/connector/connector-connection-panel.tsx` 의 `<CardTitle>{title}</CardTitle>` 와 `connector?.description` 블록이다. **이 파일은 길이 기준 파일(452줄)이라 늘어나면 `node scripts/check-file-length.mjs` 가 실패한다.** 머리 블록을 새 컴포넌트 한 줄로 바꿔 줄 수를 줄인다
- `<img>` 선례는 `web/src/components/chat/message-bubble.tsx` 다. 아이콘은 `lucide-react` 의 `Plug`, `ExternalLink` 를 쓴다
- 브라우저 시험은 `test/browser/connector-connection.spec.ts` 처럼 `page.route("**/api/connectors", ...)` 로 목록을 꾸민다. `import { expect, test } from "./fixtures.ts"` 를 쓴다
- 단위 시험은 `test/unit/connector-tool-label.test.ts` 처럼 `node:test` 로 `web/src/lib/connection.ts` 를 import 한다

**근거 문서**: `docs/adr/ADR-20261008-connector-card.md`, `docs/connectors.md` 의 「아이콘과 링크」, `docs/frontend/structure.md` 의 `/connections` 와 `/connections/{id}` 줄

## 의도 메모

- 아이콘은 `<img>` 로만 그린다. `dangerouslySetInnerHTML`, `<object>`, 인라인 SVG 로 넣지 않는다
- 서버가 검증했어도 화면이 한 번 더 모양을 본다. 모양이 틀리면 기본 아이콘과 링크 없음으로 그린다
- 도구 요약은 위험도 enum 순서(`READ`, `SENSITIVE`, `WRITE`, `DESTRUCTIVE`, `FINANCIAL`)로 0개가 아닌 것만 보인다

## 작업 항목

### 1. `web/src/lib/connection.ts`

- `ConnectorSummary` 에 `icon: string | null` 과 `link: string | null` 를 더한다(주석: 옛 Control Plane 은 내지 않는다)
- `export function connectorIconSrc(icon: string | null | undefined): string | null`. `/^data:image\/(svg\+xml|png);base64,[A-Za-z0-9+/]+={0,2}$/` 에 맞으면 그대로, 아니면 null
- `export function connectorLinkHref(link: string | null | undefined): string | null`. `new URL(link)` 가 되고 `protocol === "https:"`, `username` 과 `password` 가 비었으면 `url.href`, 아니면 null. 예외는 null
- `export function toolRiskCounts(tools: ConnectorTool[]): { risk: ToolRisk; label: string; count: number }[]`. 위 순서로 0개가 아닌 것만

### 2. 신규 `web/src/components/connector/connector-identity.tsx`

- `ConnectorIcon({ icon, className })`: `connectorIconSrc` 가 값을 주면 `<img data-testid="connector-icon" src alt="" className="size-10 shrink-0 rounded-md object-contain">`, 아니면 같은 크기의 둥근 칸 안 `Plug` 아이콘(`data-testid="connector-icon-default"`, `aria-hidden`)
- `ConnectorLink({ link, title, className })`: `connectorLinkHref` 가 null 이면 null 을 돌려준다. 값을 주면 `<a data-testid="connector-link" href target="_blank" rel="noopener noreferrer">사이트 열기 <ExternalLink aria-hidden/> <span className="sr-only">({title}, 새 탭)</span></a>`. 카드마다 링크 이름이 같으면 화면 낭독기가 구분하지 못해 제목을 넣는다
- `ConnectorHeading({ connector, title })`: 상세 머리. 아이콘, `CardTitle`, 있으면 `CardDescription`, `ConnectorLink` 를 한 줄 묶음(`flex items-start gap-3`, 글 쪽 `min-w-0 break-words`)으로 그린다

### 3. `web/src/components/connector/connector-catalog.tsx` 카드

- `<li>` 안에 `<Card data-testid="connector-card" className="relative transition-colors hover:bg-accent ...">`
- 머리: 아이콘과 글 묶음을 `flex items-start gap-3` 로 두고 글 묶음과 이름 줄에 `min-w-0`, 이름과 설명에 `break-words` 를 준다. `CardHeader` 가 grid 라 `min-w-0` 이 없으면 공백 없는 긴 이름이 넘친다. 그 안에 `ConnectorIcon`, 이름 `<Link data-slot="card-link" prefetch={false} href={`/connections/${id}`} className="after:absolute after:inset-0 focus-visible:outline-none">`, 상태 `Badge`, 설명
- 본문: `available` 이고 도구가 있으면 `<p data-testid="connector-tool-summary">도구 {n}개 · 조회 1 · 쓰기 2</p>`. `·` 앞뒤 구분은 `toolRiskCounts` 의 `label` 과 `count` 로 만든다. 쓸 수 없으면 「지금은 쓸 수 없어요.」, 연결이 있으면 「붙인 에이전트 n개」(`data-testid="connector-binding-count"` 유지), 그리고 `ConnectorLink`(`relative z-10`)
- 줄은 `flex-wrap` 과 `min-w-0` 으로 좁은 폭에서 줄바꿈한다

### 4. `web/src/components/connector/connector-connection-panel.tsx`

`<CardTitle>{title}</CardTitle>` 와 그 아래 `connector?.description` 블록을 `<ConnectorHeading connector={connector} title={title} />` 로 바꾼다. 쓰지 않게 된 import 를 지운다. 줄 수가 452 이하여야 한다.

### 5. 이 phase 를 검증하는 시험

- 신규 `test/unit/connector-card.test.ts`: `connectorIconSrc` 가 svg 와 png data URL 을 받고 `data:text/html`, `javascript:`, 일반 주소, base64 밖 글자, null 과 undefined 를 null 로 돌린다. `connectorLinkHref` 가 `https://example.com/a` 를 받고 `http:`, `javascript:`, `https://user:pw@example.com/`, 깨진 글, null 을 null 로 돌린다. `toolRiskCounts` 가 순서와 0개 제외를 지킨다
- 신규 `test/browser/connector-card.spec.ts`:
  - 아이콘과 링크가 있는 커넥터의 카드가 `connector-icon` 의 `src` 를 data URL 로, `connector-link` 의 `href`, `target="_blank"`, `rel="noopener noreferrer"` 를 갖고, 도구 요약 「도구 3개 · 조회 1 · 쓰기 1 · 되돌리기 어려운 쓰기 1」 을 보인다. 링크의 접근 이름에 커넥터 제목이 든다
  - 칸이 없거나 `icon: "data:text/html;base64,PHNjcmlwdD4="`, `link: "javascript:alert(1)"` 인 커넥터는 `connector-icon-default` 를 보이고 `connector-link` 가 없다
  - 카드의 설명 글을 누르면 `/connections/<id>` 로 간다. 설명 요소는 링크의 덧씌움 아래에 있어 `getByText(...).click()` 은 「intercepts pointer events」 로 실패한다. 설명 요소의 `boundingBox()` 중심을 `page.mouse.click` 으로 누른다
  - 외부 링크를 누르면 새 탭이 열리고 현재 주소는 바뀌지 않는다. 새 탭의 요청은 `page.route` 에 걸리지 않으므로 `context.route("https://example.com/**", route => route.fulfill({ body: "ok" }))` 로 막아 밖으로 나가지 않게 한다. 링크 값은 `https://example.com/...` 로 둔다
  - 상세 화면 머리에 아이콘과 `connector-link` 가 보인다
  - 폭 360 에서 공백 없는 80자 이름과 80자 설명의 카드가 넘치지 않는다. `Card` 가 `overflow-hidden` 이라 문서 `scrollWidth` 로는 잡히지 않으므로, 이름과 설명 요소의 `boundingBox()` 오른쪽 끝이 카드의 `boundingBox()` 오른쪽 끝 안에 있는지 본다. 이름과 설명 요소 각각의 `scrollWidth <= clientWidth` 와 문서의 `scrollWidth <= innerWidth` 도 함께 본다. 상자 비교는 `min-w-0` 누락을, 요소의 `scrollWidth` 는 `break-words` 누락을 잡는다
- `test/browser/connector-connection.spec.ts` 의 기존 시험이 그대로 통과한다(카드 클릭으로 상세에 들어가는 시험 포함)

## 검증

모두 종료 코드 0 이어야 한다.

```bash
node --test test/unit/connector-card.test.ts test/unit/connector-tool-label.test.ts
pnpm --dir web typecheck
pnpm --dir web lint
pnpm --dir web format:check
(cd web && pnpm test:browser ../test/browser/connector-card.spec.ts ../test/browser/connector-connection.spec.ts --repeat-each=3 --retries=0)
node scripts/check-file-length.mjs
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/connection.ts` | 수정 |
| `web/src/components/connector/connector-identity.tsx` | 신규 |
| `web/src/components/connector/connector-catalog.tsx` | 수정 |
| `web/src/components/connector/connector-connection-panel.tsx` | 수정 |
| `test/unit/connector-card.test.ts` | 신규 |
| `test/browser/connector-card.spec.ts` | 신규 |
