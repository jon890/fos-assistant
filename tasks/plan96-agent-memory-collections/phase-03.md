# Phase 03. 관리자 영역 상세의 「기억 영역」 절

**Execution profile**: standard

## 목표

`/admin/agents/{code}` 에 「기억 영역」 절을 더한다. 관리자가 영역마다 받음과 민감 허용을 고르고, 빠진 영역의 항목 수를 보고, 저장하고, 최근 변경을 본다.

**범위 외**: Control Plane API 는 Phase 02 가 만들었다. 일반 상세 `/agents/{code}` 에는 그리지 않는다.

## 컨텍스트

- Control Plane 경로: `GET`, `PUT /api/v1/admin/agents/{code}/memory-collections`. 응답 칸은 `docs/backend/memory.md` 의 「관리자가 에이전트의 collection 을 바꿀 때」 의 JSON 과 표가 정한다.
- 브라우저는 Control Plane 을 직접 부르지 않는다. 서버 라우트 선례는 `web/src/app/api/admin/agents/[code]/model-settings/route.ts`(GET)와 `web/src/app/api/admin/agents/[code]/model-default/route.ts`(PUT, `readJsonBody`)다. 코드 형식은 `AGENT_CODE_PATTERN`(`@/lib/agent`)으로 본다.
- 스스로 읽는 관리 절의 선례는 `web/src/components/agent/agent-model-section.tsx` 다(`useEffect` 로 읽기, loading, failed, loaded 상태, `Notice`). 이 절도 같은 모양으로 둔다.
- 절을 끼우는 자리는 `web/src/components/agent/agent-detail-body.tsx` 의 `{adminAgent ? <AgentModelSection code={code} /> : null}` 바로 뒤다. `adminAgent` 는 관리자 영역이고 옛 커넥터 에이전트가 아닐 때만 있다(`agent-detail-loader.tsx`).
- 화면 문구 규칙(해요체, 관리자 영역, 색 토큰, `Notice`, `Badge`)은 `web/AGENTS.md` 가 갖는다. `style={{` 를 쓰지 않는다.
- `test/unit/*.test.ts` 는 `node --test` 로 web 파일을 읽는다. 그 파일은 `@/` 런타임 import 를 쓰지 못한다. 순수 함수 파일은 런타임 import 없이 쓴다(`web/src/lib/memory-source.ts` 처럼).

**근거 문서**: `docs/frontend/structure.md` 의 「기억 영역 절」(문구와 상태 표), `docs/backend/memory.md` 의 「관리자가 에이전트의 collection 을 바꿀 때」, `docs/adr/ADR-20261008-agent-memory-grants-admin.md`

## 의도 메모

- 저장은 고른 전체를 보낸다. 바뀐 것이 없으면 단추를 끈다.
- 「민감 항목까지」 는 받는 영역에서만 켤 수 있고, 받음을 끄면 함께 꺼진다.
- 빠진 영역 안내는 저장된 값이 아니라 지금 고른 값(초안)을 기준으로 그린다. 고르는 즉시 안내가 바뀐다.
- 확인 창을 두지 않는다. 변경은 표에 기록된다.

## 작업 항목

### 1. `web/src/lib/agent-memory.ts` (런타임 import 없음)

- 타입: `AgentMemoryCollection`(key, displayName, listed, granted, allowSensitive, entryCount, sensitiveEntryCount), `AgentMemoryChange`(collection, changeType `"GRANTED" | "REVOKED" | "SENSITIVE_CHANGED"`, allowSensitive, changedByName `string | null`, changedAt), `AgentMemorySetting`(countedFor `"OWNER" | "GROUP"`, ownerName, collections, changes), `AgentMemoryDraft = Record<string, { granted: boolean; allowSensitive: boolean }>`.
- `draftOf(setting): AgentMemoryDraft`.
- `grantsOf(draft): { collection: string; allowSensitive: boolean }[]` — 받는 것만, key 순서.
- `draftChanged(setting, draft): boolean`.
- `missingNotice(collection, draftRow): string | null` — 받지 않고 `entryCount > 0` 이면 「항목 N개가 있지만 받지 않아요.」, 받고 민감 허용이 꺼졌고 `sensitiveEntryCount > 0` 이면 「민감 항목 N개는 받지 않아요.」, 아니면 null.
- `countedForLabel(setting)` — 「주인(<이름>)의 기억과 그룹 기억을 셌어요.」 또는 「주인이 없어 그룹 기억만 셌어요.」
- `changeLabel(change, displayName)` — `displayName` 은 지금 응답의 `collections` 에서 그 key 의 이름이고, 없으면 key 그대로다. 「<영역> 붙임」, 「<영역> 붙임(민감 항목 허용)」, 「<영역> 뗌」, 「<영역> 민감 항목 허용」, 「<영역> 민감 항목 허용 끔」.

### 2. `web/src/lib/agent-memory-api.ts`

`memoryRequest`(`web/src/lib/memory-api.ts`)로 호출 함수 둘을 둔다. 선례는 `web/src/lib/service-token-api.ts` 다.

- `getAgentMemorySetting(code): Promise<MemoryApiResult<AgentMemorySetting>>` — `GET /api/admin/agents/${code}/memory-collections`, 실패 문구 「기억 영역을 불러오지 못했어요.」
- `saveAgentMemorySetting(code, collections): Promise<MemoryApiResult<AgentMemorySetting>>` — `PUT`, 본문 `{ collections }`, 실패 문구 「기억 영역을 저장하지 못했어요.」

### 2-1. `web/src/app/api/admin/agents/[code]/memory-collections/route.ts`

`GET` 과 `PUT`. 둘 다 코드 형식 검사 뒤 `callControlPlane` 으로 `/api/v1/admin/agents/${code}/memory-collections` 를 부르고, 실패면 `errorResponse(result.code, result.message, result.status)`, 성공이면 `NextResponse.json(result.data)`. PUT 은 `readJsonBody` 로 본문을 받는다.

### 3. `web/src/components/agent/agent-memory-section.tsx`

`"use client"`. `AgentMemorySection({ code })`. `section` 에 `aria-label="기억 영역"`, `data-testid="agent-memory-section"`, 모델 절과 같은 테두리 클래스. 제목 「기억 영역」, 설명 「이 에이전트가 대화에 쓸 수 있는 기억의 영역이에요. 민감 항목은 영역마다 따로 허용해요.」 상태는 `docs/frontend/structure.md` 「기억 영역 절」 의 표대로다.

- 읽는 중: 「기억 영역을 불러오고 있어요.」 / 실패: `Notice variant="error"` 「기억 영역을 불러오지 못했어요. <메시지>」. 컴포넌트는 `fetch` 를 직접 부르지 않는다(`web/eslint.config.mjs` 가 `src/components/**` 의 전역 `fetch` 를 막는다). 작업 항목 2 의 호출 함수를 쓴다.
- 영역 한 줄: 체크박스의 접근 이름은 「<영역> 받음」(예: 「커리어 받음」), 체크박스 「민감 항목까지」 의 접근 이름은 「<영역> 민감 항목까지」, 「항목 N개」, `listed` 가 거짓이면 `Badge variant="outline"` 「목록에 없는 영역」, `missingNotice` 가 있으면 경고 색 글(`text-warning` 같은 의미 색 토큰. 없으면 `Notice variant="warning"`).
- 받는 영역이 없으면 `Notice variant="warning"` 「받는 영역이 없으면 이 에이전트는 기억을 쓰지 않아요.」
- `countedForLabel` 한 줄(`text-muted-foreground`).
- 단추 「기억 영역 저장」: `draftChanged` 가 거짓이거나 저장 중이면 `disabled`. `saveAgentMemorySetting(code, grantsOf(draft))`. 성공하면 응답으로 상태와 초안을 바꾸고 `role="status"` 로 「저장했어요.」. 실패하면 초안을 두고 `Notice variant="error" role="alert"`.
- 「최근 변경」 소제목과 목록: 각 줄 `<time dateTime=...>` 로 시각(`Asia/Seoul`, 월 일 시:분), 바꾼 사람(null 이면 「알 수 없는 사용자」), `changeLabel`. 비었으면 「아직 바꾼 기록이 없어요.」

### 4. `web/src/components/agent/agent-detail-body.tsx`

`import { AgentMemorySection } from "./agent-memory-section";` 와 `{adminAgent ? <AgentModelSection code={code} /> : null}` 뒤에 `{adminAgent ? <AgentMemorySection code={code} /> : null}`.

### 5. 이 phase 를 검증하는 `test/unit/agent-memory.test.ts`

`node:test` 와 `node:assert/strict`. `../../web/src/lib/agent-memory.ts` 를 import 한다. 확인할 것:

- 받지 않는 영역에 항목 3개 → 「항목 3개가 있지만 받지 않아요.」. 받고 민감 허용이 꺼진 영역에 민감 1개 → 「민감 항목 1개는 받지 않아요.」. 민감 허용을 켜면 null.
- `grantsOf` 가 받는 영역만 key 순서로 낸다. 빈 초안이면 빈 배열.
- `draftChanged` 가 같은 값이면 거짓, 민감 허용 하나만 바뀌어도 참.
- `changeLabel` 다섯 경우.

### 6. 이 phase 를 검증하는 `test/browser/agent-memory.spec.ts`

`./fixtures.ts` 의 `test`, `expect`, `PERSONA_EMPTY_AGENT_CODE` 를 쓴다(아무도 대화하지 않는 에이전트다). `./memory-page.ts` 의 `createDocument`, `cleanupMemories` 로 `career` 문서 하나(민감)를 만든다. 시험이 끝나면 `try/finally` 에서 `PUT /api/admin/agents/<code>/memory-collections` 로 `[{ collection: "core", allowSensitive: false }]` 를 되돌리고 문서를 지운다.

- 「관리자가 빠진 영역을 보고 붙이면 다시 열어도 남고 최근 변경에 보인다」: `/admin/agents/<code>` 를 연다 → 절 안 「커리어」 줄에 「항목 1개가 있지만 받지 않아요.」 → 「커리어 받음」 과 「커리어 민감 항목까지」 를 켠다 → 「기억 영역 저장」 을 `clickAndWaitForResponse` 로 누르고 「저장했어요.」 → 다시 열면 두 체크박스가 켜져 있고 안내가 없고 최근 변경 목록의 첫 줄(`.first()`)에 「커리어 붙임(민감 항목 허용)」. 반복 실행에서 같은 기록이 쌓이므로 `getByText` 로 전체에서 찾지 않는다.
- 「저장이 실패하면 고른 값이 남고 오류를 보인다」: `page.route` 로 그 PUT 을 409 `{code:"AGENT_BUSY", message:"busy"}` 로 바꿔 누른 뒤 오류 `alert` 와 켜 둔 체크박스가 남는지 본다.

`clickAndWaitForResponse` 와 준비 대기는 `web/AGENTS.md` 의 「브라우저 시험의 독립성과 대기」 를 따른다. 그 도우미의 위치는 `test/browser/` 안에서 `grep -rn "export async function clickAndWaitForResponse" test/browser` 로 찾는다.

## 검증

```bash
node --test test/unit/agent-memory.test.ts
cd web && pnpm lint && pnpm typecheck && pnpm format:check
grep -rn 'style={{' web/src/components/agent/agent-memory-section.tsx || true
scripts/check-local.sh agent-memory.spec.ts
```

앞의 셋은 종료 코드 0, `grep` 은 아무것도 내지 않는다. 마지막 줄은 백엔드와 웹 검사를 모두 돌린 뒤 새 spec 만 브라우저로 돌린다. 반복 안정성은 `cd web && pnpm test:browser agent-memory.spec.ts --repeat-each=3 --retries=0` 으로 본다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/agent-memory.ts` | 신규 |
| `web/src/lib/agent-memory-api.ts` | 신규 |
| `web/src/app/api/admin/agents/[code]/memory-collections/route.ts` | 신규 |
| `web/src/components/agent/agent-memory-section.tsx` | 신규 |
| `web/src/components/agent/agent-detail-body.tsx` | 수정 |
| `test/unit/agent-memory.test.ts` | 신규 |
| `test/browser/agent-memory.spec.ts` | 신규 |
