# Phase 02. 화면 주소를 /chat/{공개 식별자} 로 바꾸고 옛 주소를 넘겨 준다

**Execution profile**: deep

## 목표

web 이 대화를 공개 식별자(UUID 문자열)로 다루고, 대화 주소를 `/chat/{id}` 로 바꾼다.
옛 주소 `/c/{번호}` 는 주인에게만 새 주소로 넘겨 준다. 이 plan 의 마지막 phase 다.

**범위 외**: Control Plane(phase-01 이 끝냈다). 메시지, 첨부, 실행 번호는 그대로 숫자다.

## 컨텍스트

**근거 문서**:
- `docs/adr/ADR-025-대화는-주소에-공개-식별자를-쓰고-번호는-안에만-둔다.md`
- `docs/flow.md` 「대화 이력」 의 흐름도와 「갈리는 지점」 표
- `docs/code-architecture.md` 「대화」 의 「경로」 표와 그 아래 문단, 「web 화면 구조」 표

phase-01 이 Control Plane 을 바꿨다. 대화 경로의 `{id}` 와 응답의 대화 칸이 모두 UUID 문자열이다.
`GET /api/v1/chat/conversations/by-number/{number}` 가 `{ "id": "<공개 식별자>" }` 를 돌려준다.

계획을 쓸 때의 코드다. 디자인 계획이 끝난 뒤라 줄 번호와 className 은 달라져 있다. 시작할 때 다시 연다.

| 위치 | 지금 모양 |
| --- | --- |
| `web/src/app/api/chat/conversations/[conversationId]/` 아래 `route.ts` 다섯 개 | 번호를 `/^\d+$/` 로 보고, 틀리면 400 `VALIDATION_FAILED` 「대화 번호가 올바르지 않습니다.」. `route.ts` 는 `idOf(context)` 로 `Number` 를 돌려준다 |
| `web/src/app/api/chat/route.ts`, `web/src/app/api/chat/stream/route.ts`, `web/src/app/api/chat/conversations/route.ts` | 요청과 응답 타입에 `conversationId: number` |
| `web/src/lib/chat-event.ts` | `conversationId?: number \| null` |
| `web/src/app/c/[conversationId]/page.tsx` | 로그인을 보고 `/^\d+$/` 가 아니면 `/` 로 보낸 뒤 `<ChatPanel initialConversationId={Number(conversationId)} />` |
| `web/src/app/c/[conversationId]/loading.tsx` | 대화 화면의 뼈대 |
| `web/src/components/chat-panel.tsx` | `initialConversationId: number \| null`, `useState<number \| null>`, `useRef<number \| null>`. `window.history.replaceState(null, "", \`/c/${...}\`)` 세 곳 |
| `web/src/components/shell/conversations-provider.tsx` | `export type Conversation` 에 `id` |
| `web/src/components/shell/conversation-nav.tsx` | `` `/c/${conversation.id}` `` 로 링크와 현재 경로 비교 |
| `web/src/components/ui/page-skeleton.tsx` | 주석에 `/c/{id}` |
| `test/unit/loading-routes.test.ts` | `ROUTE_FRAMES` 에 뼈대를 두는 여덟 경로. `"c/[conversationId]"` 가 있다. `callControlPlane` 을 부르는 `page.tsx` 마다 `loading.tsx` 가 있는지도 본다 |
| `test/browser/` | `shell.spec.ts`, `loading.spec.ts`, `start-screen.spec.ts` 등이 `/c/${id}` 로 주소와 링크를 본다. `shell.spec.ts` 는 `/c/999999` 로 없는 대화를 연다 |

## 의도 메모

- **UUID 모양 검사는 한 함수로 모은다.** 서버 라우트 다섯 개와 두 페이지가 같은 정규식을 쓴다
- **옛 주소는 서버에서 넘긴다.** `/c/[conversationId]/page.tsx` 를 넘겨 주기만 하는 서버 컴포넌트로 바꾼다. 화면을 그리지 않는다
- 옛 주소에서 없는 대화와 남의 대화를 가리지 않는다. 둘 다 `/` 로 보낸다
- `/chat/{id}` 에서 없는 대화는 지금 `/c/{id}` 와 같은 화면(「대화를 찾을 수 없다」)이다
- 서버 라우트의 오류 문구 「대화 번호가 올바르지 않습니다.」 는 「대화 주소가 올바르지 않습니다.」 로 바꾼다

## Blocked 조건

- `tasks/plan023-design-foundation/` 이나 `tasks/plan024-design-screens/` 가 main 에 남아 있다 → `PHASE_BLOCKED: 디자인 계획이 끝나지 않았다`. 두 계획이 `chat-panel.tsx` 와 `conversation-nav.tsx` 를 고친다
- phase-01 이 끝나지 않았다. `grep -n "by-number" backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` 가 아무것도 내지 않는다 → `PHASE_BLOCKED: phase-01 이 끝나지 않았다`

## 작업 항목

### 1. `web/src/lib/conversation-id.ts`

```ts
const CONVERSATION_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export function isConversationId(value: string): boolean { return CONVERSATION_ID.test(value); }
```

한국어 주석으로 대화 표의 번호가 아니라 공개 식별자라는 것을 적는다.

### 2. 서버 라우트

- `conversations/[conversationId]/` 아래 다섯 개가 `/^\d+$/` 대신 `isConversationId` 를 쓴다. `Number(...)` 로 바꾸지 않고 문자열 그대로 넘긴다
- 요청과 응답 타입의 `conversationId: number` 와 `id: number` 를 `string` 으로 바꾼다. `api/chat/route.ts`, `api/chat/stream/route.ts`, `api/chat/conversations/route.ts`, `lib/chat-event.ts`, `conversations-provider.tsx` 의 `Conversation`

### 3. 대화 화면 `web/src/app/chat/[conversationId]/`

- `page.tsx`: 지금 `c/[conversationId]/page.tsx` 와 같은 모양으로 로그인을 보고 `isConversationId` 가 아니면 `/` 로 보낸다. `<ChatPanel initialConversationId={conversationId} />`
- `loading.tsx`: 지금 `c/[conversationId]/loading.tsx` 를 옮긴다

### 4. 옛 주소 `web/src/app/c/[conversationId]/page.tsx`

- 로그인을 보고 `/^\d+$/` 가 아니면 `/` 로 보낸다
- `callControlPlane<{ id: string }>(\`/api/v1/chat/conversations/by-number/${conversationId}\`)` 를 부른다. 서버 컴포넌트에서 부르는 본보기는 `web/src/app/usage/page.tsx` 다
- 성공이면 `redirect(\`/chat/${result.data.id}\`)`, 실패면 `redirect("/")`
- `callControlPlane` 을 부르므로 `loading.tsx` 가 같은 자리에 있어야 한다(`test/unit/loading-routes.test.ts`). 3 에서 옮긴 것과 같은 뼈대를 둔다

### 5. `chat-panel.tsx` 와 `conversation-nav.tsx`

- 대화 식별자 타입을 `string \| null` 로 바꾼다
- `replaceState` 세 곳과 링크, 현재 경로 비교를 `/chat/${id}` 로 바꾼다
- 숫자를 전제한 코드(`Number(...)`, 숫자 비교)가 남지 않게 한다
- `page-skeleton.tsx` 의 주석을 `/chat/{id}` 로 고친다

### 6. 이 phase 를 검증하는 테스트

- `test/unit/loading-routes.test.ts`: `ROUTE_FRAMES` 에 `"chat/[conversationId]"` 를 더하고 `"c/[conversationId]"` 도 남긴다. 「여덟」 을 「아홉」 으로 고친다
- `test/unit/` 에 `isConversationId` 검사: 만든 UUID 는 참, 숫자 `120` 과 빈 문자열과 36자가 아닌 값은 거짓
- `test/browser/` 의 `/c/${id}` 를 `/chat/${id}` 로 고친다. 없는 대화를 여는 검사는 형식이 맞는 임의 UUID 로 연다
- 새 브라우저 검사

| 경우 | 기대 |
| --- | --- |
| 첫 메시지를 보낸다 | 주소가 `/chat/{UUID}` 로 바뀐다 |
| 내 대화의 옛 주소 `/c/{번호}` 로 연다 | `/chat/{그 대화의 UUID}` 로 옮겨지고 메시지가 보인다 |
| 남의 대화 번호로 `/c/{번호}` | `/` 로 옮겨진다 |
| `/chat/abc` | `/` 로 옮겨진다 |

내 대화의 번호는 브라우저 검사에서 얻기 어렵다. `test/browser/fixtures.ts` 의 기존 도구와 test-support 경로를 먼저 본다.
없으면 `backend/src/test/java/com/bifos/assistant/testsupport/` 에 대화 번호를 알려 주는 test-support 경로를 더한다. 운영 코드에 더하지 않는다.

## 검증

```bash
# cwd: 저장소 root
grep -rn '/c/\${' web/src test/browser
grep -rn 'd+\$/' web/src/app/api/chat/conversations
```

둘 다 아무것도 내지 않아야 한다. `/c/[conversationId]/page.tsx` 의 번호 검사는 첫째 grep 에 걸리지 않는다.

AGENTS.md 의 「확인」 절 명령을 적힌 순서대로 모두 돌린다. 이 plan 의 마지막 phase 다.
`pnpm build` 는 `web/AGENTS.md` 의 자리표시자 환경 변수가 필요하다.
새 브라우저 검사는 모바일과 데스크톱 폭에서 모두 통과해야 한다.

끝나면 `tasks/plan028-conversation-public-id/index.json` 의 이 phase 를 `completed` 로, plan 의 `status` 를 `completed` 로 바꾼다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `web/src/lib/conversation-id.ts` | 신규 |
| `web/src/app/api/chat/conversations/[conversationId]/` 아래 `route.ts` 다섯 개 | 수정 |
| `web/src/app/api/chat/route.ts`, `web/src/app/api/chat/stream/route.ts`, `web/src/app/api/chat/conversations/route.ts` | 수정 |
| `web/src/lib/chat-event.ts` | 수정 |
| `web/src/app/chat/[conversationId]/page.tsx`, `loading.tsx` | 신규 |
| `web/src/app/c/[conversationId]/page.tsx` | 수정. 넘겨 주기만 한다 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `web/src/components/shell/conversations-provider.tsx`, `conversation-nav.tsx` | 수정 |
| `web/src/components/ui/page-skeleton.tsx` | 주석 수정 |
| `test/unit/loading-routes.test.ts` | 수정 |
| `test/unit/` 의 `isConversationId` 검사 | 신규 |
| `test/browser/` 의 `/c/` 를 쓰는 spec 과 새 spec | 수정, 신규 |
