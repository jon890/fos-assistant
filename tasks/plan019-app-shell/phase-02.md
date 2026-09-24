# Phase 02. 사이드바 화면 틀과 대화 주소

**Execution profile**: deep

## 목표

위쪽 가로 메뉴를 없애고 모든 화면을 왼쪽 사이드바 하나로 감싼다.
대화 목록을 대화 화면에서 떼어 화면 틀로 옮기고, 대화마다 `/c/{id}` 주소를 준다.
`/` 는 새 대화 화면이 되고 지난 대화를 저절로 열지 않는다.

**범위 외**:
이름 바꾸기와 지우기, 제목 검색, 사이드바 접기, 단축키는 phase-03 이 한다.
에이전트 카드와 추천 질문이 있는 새 대화 화면, 작업 과정 블록, 중지 단추는 이 plan 의 일이 아니다.
`/` 의 대화 화면은 지금처럼 에이전트 `select` 와 빈 메시지 영역을 그대로 쓴다.

## 컨텍스트

**근거 문서**:
`docs/flow.md` 의 「대화 이력」 절과 「대화 목록」 절의 날짜 묶음 표, 「화면 틀」 절,
`docs/code-architecture.md` 의 「web 화면 구조」 경로 표와 「디렉터리」, 「우리 화면의 정체성」 의 요소 표,
`web/AGENTS.md` 의 「색과 간격은 테마 토큰이 소유한다」.

### 먼저 지금 모양을 읽는다

이 plan 은 사진 첨부가 main 에 들어간 뒤에 돈다.
그 변경이 `web/src/components/chat/composer.tsx` 에 첨부 단추를,
`web/src/components/chat-panel.tsx` 와 `message-bubble.tsx` 에 첨부 상태와 미리보기를 더한다.
**구현 전에 `composer.tsx` 와 `chat-panel.tsx` 의 지금 모양을 읽고 첨부 흐름을 깨지 않는다.**
특히 대화 번호가 없는 새 대화에서 첨부를 어떻게 올리는지 확인한다.
첨부 경로가 대화 번호를 요구하므로, 그 변경이 새 대화에서 첨부를 막거나 대화를 먼저 만들 수 있다.
그 동작을 그대로 둔다. `/` 에서도 `/c/{id}` 에서도 첨부가 지금과 같이 돌아야 한다.
첨부 쪽 식별자는 코드에서 읽은 것만 쓴다.

**입력창을 새로 만드는 것은 사용자가 대화를 바꿀 때뿐이다.** 사진 첨부 구현이 정한 규칙이고 지금 코드에 있다.

- `chat-panel.tsx` 가 `<Composer key={composerGeneration}>` 로 넘긴다. `composerGeneration` 은 사용자가 다른 대화를 고르거나 새 대화를 시작할 때만 올라간다
- `conversationId` 를 `key` 로 쓰지 않는다. 새 대화에서 첫 사진을 올리는 중에 대화 번호가 생기면 입력창이 새로 만들어져 사진이 사라진다
- `Composer` 가 첨부 상태(미리보기, 올리기, 첨부 번호)를 직접 갖는다. unmount 될 때 올려 두고 보내지 않은 첨부를 서버에서 지우고, 보내는 중인 것은 남긴다
- `onSend(attachmentIds)` 는 `Promise<boolean>` 이다. 참일 때만 미리보기를 비운다
- 새 대화에서 첫 사진을 올리면 `Composer` 가 빈 대화를 만들고 `onConversationCreated` 로 알린다

**배치를 바꾸려고 `Composer` 를 두 번 그리거나 부모를 갈아 끼우지 않는다.** 한 자리에 그대로 두고 감싸는 요소의 클래스만 바꾼다.
React 는 부모가 바뀌면 자식을 새로 만든다. 그러면 unmount 정리가 돌아 올린 사진이 지워진다.
정리가 빠지거나 입력창이 너무 자주 새로 만들어지면 올린 첨부가 쌓이거나 사라진다. 한 번에 10장인 서버 상한이 찰 수 있다.


### 지금 구조

- `web/src/app/layout.tsx` 가 `SiteHeader` 와 `<main>` 을 그린다. `readMe()` 로 `isAdmin` 과 `displayName` 을 넘긴다
- `web/src/components/ui/site-header.tsx` 는 `/signin` 에서 메뉴를 숨긴다. 경로를 `usePathname` 으로 안다
- `web/src/components/ui/site-nav.tsx` 가 링크 목록과 기억 제안 수를 갖는다.
  `/api/memories` 를 읽어 `PROPOSED` 수를 세고, `window` 의 `memory-proposal-count` 사건으로도 갱신한다.
  뱃지는 `data-testid="memory-proposal-count"` 다
- `web/src/components/chat-panel.tsx` 가 대화 목록과 서랍(`ConversationDrawer`, `ConversationList`)을 함께 갖고,
  처음 열릴 때 가장 최근 대화를 저절로 연다(`loadInitialConversation`)

## 의도 메모

- 경로를 옮겨 첫 메시지 뒤 주소를 바꾸는 안을 버렸다. `router.replace` 는 대화 화면을 새로 만들어
  흘러오던 답과 사건 스트림을 잃는다. `window.history.replaceState` 로 주소만 바꾼다.
  Next.js 의 `usePathname` 이 이것을 따라간다
- 목록을 대화 화면이 계속 갖는 안을 버렸다. 사이드바는 `/usage` 에서도 보여야 한다
- 목록 context 가 첫 조회를 한 번만 한다. 대화 화면은 보낸 뒤 `refresh()` 만 부른다
- 가장 최근 대화를 저절로 여는 동작을 지운다. `/` 는 늘 새 대화다
- **`replaceState` 로 주소만 바꾼 화면은 여전히 `/` 페이지의 트리다.** 그 뒤 `href="/"` 로 가면 같은 세그먼트라
  `ChatPanel` 이 새로 만들어지지 않을 수 있다. 그래서 `ChatPanel` 이 `usePathname()` 을 보고 스스로 비운다(작업 항목 7)
- `started` 뒤에 실패하면 사용자 메시지는 서버에 이미 저장됐다. 입력창에 되돌리면 다시 보낼 때 같은 질문이 둘 남는다.
  「다시 시도」 단추는 뒤에 올 다시 생성 작업이 만든다. 이 phase 는 오류를 그 메시지 아래에 보이는 것까지 한다
- `Esc` 를 전역에서 해석하는 자리는 `chat-panel.tsx` 의 처리기 하나다. `docs/flow.md` 「화면 틀」 의 차례를 따른다.
  이 phase 는 그 처리기의 뼈대와 1, 2 단계(조합 중이면 무시, 이미 처리된 사건이면 무시)만 만든다.
  패널 닫기와 중지는 뒤에 올 작업이 같은 처리기에 더한다. 안쪽 요소의 `Esc` 는 처리하면 전파를 막는다

## 작업 항목

### 1. `web/src/components/shell/conversations-provider.tsx` 신규

`"use client"`. 대화 목록을 한 곳에서 갖는다.

```ts
export type Conversation = {
  id: number;
  title: string;
  agentCode: string;
  agentName: string;
  updatedAt: string;
};

type ConversationsValue = {
  conversations: Conversation[];
  loading: boolean;
  error: string | null;
  refresh(): Promise<void>;
};

export function ConversationsProvider(props: { enabled: boolean; children: React.ReactNode }): JSX.Element
export function useConversations(): ConversationsValue
```

`enabled` 가 거짓이면(로그인 화면) 부르지 않는다. 오류 문구는 `components/error-message.ts` 의 `describeError` 를 쓴다.
`Conversation` 타입은 지금 `components/chat/conversation-list.tsx` 에 있다. 여기로 옮기고 옛 파일은 지운다.
**옛 파일이 가진 동작을 함께 옮긴다.** 사진 첨부 구현이 제목이 빈 대화를 「새 대화」 로 보이게 그 파일을 고쳤다.
`conversation-nav.tsx` 가 같은 규칙으로 그린다. 제목 검색은 빈 제목을 「새 대화」 로 보고 거른다.

### 2. `web/src/components/shell/group-by-date.ts` 신규

```ts
export type ConversationGroup = { label: "오늘" | "어제" | "지난 7일" | "지난 30일" | "그 이전"; items: Conversation[] };
export function groupByDate(conversations: Conversation[], now: Date): ConversationGroup[]
```

기준은 `docs/flow.md` 의 「대화 목록」 표다. 브라우저 시간의 날짜로 자른다. 빈 묶음은 돌려주지 않는다.
받은 순서(최근 순)를 묶음 안에서 지킨다.

### 3. `web/src/components/shell/sidebar.tsx`, `conversation-nav.tsx`, `main-nav.tsx` 신규

접근성 이름을 아래로 정한다. phase-03 과 브라우저 테스트가 이 이름으로 찾는다.

| 요소 | 역할과 이름 |
| --- | --- |
| 사이드바 | `aside`, `aria-label="사이드바"` |
| 홈 | 링크 `우리집 비서 홈`. 지금 `site-header.tsx` 의 것과 같은 이름 |
| 새 대화 | 링크 `새 대화`, `href="/"` |
| 대화 목록 | `nav`, `aria-label="대화 목록"`. 묶음마다 제목, 그 아래 대화마다 링크 |
| 대화 한 줄 | 링크. 이름은 제목. `href="/c/{id}"`. 지금 주소의 대화면 `aria-current="page"` |
| 주요 화면 | `nav`, `aria-label="주요 화면"`. 에이전트, 기억, 사용량, 그리고 `ADMIN` 이면 에이전트 관리, 사람 관리 |
| 아래쪽 | 로그인한 사람 이름과 `ThemeToggle` |

- `main-nav.tsx` 는 `site-nav.tsx` 의 링크와 기억 제안 수 로직을 그대로 옮긴다. `대화` 링크는 뺀다. 새 대화 링크가 그 자리다
- `conversation-nav.tsx` 는 `useConversations()` 와 `groupByDate` 로 그린다.
  읽는 중에는 `components/ui/skeleton.tsx` 로 세 줄, 없으면 「아직 대화가 없다」, 오류면 그 문구
- 색과 간격은 `globals.css` 의 토큰 클래스만 쓴다. 인라인 `style` 을 쓰지 않는다

### 4. `web/src/components/shell/app-shell.tsx` 신규

```ts
export function AppShell(props: { isAdmin: boolean; displayName?: string; children: React.ReactNode }): JSX.Element
```

- `usePathname()` 이 `/signin` 이면 사이드바와 위쪽 막대 없이 `<main>` 안에 `children` 만 그린다.
  `ConversationsProvider` 의 `enabled` 도 거짓이다
- 그 밖에는 `ConversationsProvider` 로 감싸고 `[사이드바][main]` 가로 배치.
  `main` 은 지금 `layout.tsx` 의 `<main>` 클래스 `mx-auto min-h-0 w-full flex-1 overflow-y-auto px-4 py-5` 를 옮긴다
- `md` 미만: 사이드바는 서랍. 위쪽 막대 `header` 에 단추 `사이드바 열기`, 가운데 제목, 링크 `새 대화`.
  열리면 뒤를 덮는 단추 `사이드바 닫기`. `Esc` 로도 닫힌다. 경로가 바뀌면 닫힌다.
  서랍의 몸체 잠금과 `Esc` 처리는 지금 `components/chat/conversation-drawer.tsx` 의 것을 옮긴다.
  **서랍이 `Esc` 로 닫을 때는 `event.preventDefault()` 와 `event.stopPropagation()` 을 한다.**
  지금 코드는 `window` 에 붙여 전파를 막지 않는다. 서랍의 `keydown` 을 서랍 요소에 붙이거나, `window` 에 붙이되
  캡처 단계(`addEventListener("keydown", fn, true)`)로 먼저 받아 전파를 막는다. 대화 화면의 `Esc` 처리기가
  `event.defaultPrevented` 를 보고 무시한다
- 위쪽 막대의 가운데 제목은 화면이 정한다. `useShellTitle(title: string | null)` 훅을 같은 파일에서 내보낸다.
  대화 화면이 에이전트 이름을 넘기고, 넘기지 않은 화면은 「우리집 비서」 다
- `md` 이상: 사이드바가 늘 보인다. 폭은 `w-64`

### 5. `web/src/app/layout.tsx`

`SiteHeader` 와 `<main>` 을 `<AppShell isAdmin={...} displayName={...}>{children}</AppShell>` 로 바꾼다.
`body` 의 `flex h-dvh flex-col overflow-hidden` 에서 `flex-col` 은 화면 틀이 가로 배치를 갖게 되므로 고친다.
`web/src/components/ui/site-header.tsx`, `site-nav.tsx`, `components/chat/conversation-drawer.tsx`,
`components/chat/conversation-list.tsx` 를 지운다. 다른 곳이 import 하지 않는지 `grep -rn` 으로 확인한다.

### 6. 대화 주소

- `web/src/app/c/[conversationId]/page.tsx` 신규. `app/page.tsx` 처럼 세션이 없으면 `/signin` 으로 보낸다.
  `params` 는 `Promise<{ conversationId: string }>` 다(`app/executions/[id]/page.tsx` 를 본다).
  숫자가 아니면 `app/executions/[id]/page.tsx` 처럼 `redirect("/")`. 숫자면 `<ChatPanel initialConversationId={Number(id)} />`
- `web/src/app/page.tsx` 는 `<ChatPanel initialConversationId={null} />`

### 7. `web/src/components/chat-panel.tsx`

- props `{ initialConversationId: number | null }` 를 받는다
- 대화 목록, 서랍, `loadInitialConversation`, `selectConversation`, `startNewConversation`, `refreshConversations` 를 지운다.
  목록 갱신은 `useConversations().refresh()` 다
- `initialConversationId` 가 있으면 그 대화의 메시지를 읽는다. 에이전트는 목록 context 의 그 대화의 `agentCode` 로 잠근다.
  목록에 아직 없으면 메시지를 읽는 동안 잠금만 걸고 이름은 비워 둔다
- **이어지는 대화는 `agentCode` 없이도 보낸다.** 지금 `send` 는 `agentCode.length === 0` 이면 돌아간다.
  목록 조회가 실패하면 `/c/{id}` 에서 `agentCode` 를 얻을 곳이 없어 보내지 못한다.
  Control Plane 은 이어지는 대화에서 요청의 `agentCode` 를 무시하고 대화에 적힌 에이전트를 쓴다
  (`ChatService.resolveConversation` 이 `conversationId` 가 있으면 `requireOwnConversation` 만 부른다).
  그래서 그 조건을 `conversationId === null && agentCode.length === 0` 으로 바꾸고, 이어지는 대화에서는
  알면 그 값을, 모르면 빈 문자열을 보낸다. 서버 라우트 `web/src/app/api/chat/stream/route.ts` 와 `api/chat/route.ts` 가
  빈 `agentCode` 를 거르지 않는지 확인한다
- 읽다가 `CONVERSATION_NOT_FOUND` 면 메시지 영역 대신 `data-testid="conversation-not-found"` 안내
  「대화를 찾을 수 없다」 와 링크 `새 대화`(`href="/"`) 를 그린다. 입력창을 그리지 않는다
- `ChatEvent` 의 `type` 에 `"started"` 를 더한다. 받으면
  - 이 보내기가 시작된 뒤 사용자가 다른 대화나 `/` 로 옮겼으면 아무것도 하지 않는다.
    보내기마다 `selectionVersion` 같은 번호를 잡아 두고 사건을 받을 때 같은지 본다. 지금 코드의 `selectionVersion` ref 를 살린다
  - `conversationId` 가 아직 null 이면 그것을 적고 `window.history.replaceState(null, "", \`/c/${id}\`)`
  - 받은 `executionId` 를 ref 에 둔다. 여러 번 오면 마지막 것이 남는다
  - `refresh()` 를 부른다
- **대화 번호를 얻는 자리는 둘이다.** `started` 와, 사진 첨부가 새 대화에서 첫 사진을 올리기 전에 부르는
  빈 대화 만들기(`POST /api/v1/chat/conversations`, 화면 쪽 호출은 사진 첨부 구현이 만든 것을 코드에서 찾는다)다.
  **두 자리 모두 번호를 받은 같은 처리 안에서 `replaceState` 로 주소를 먼저 바꾸고** 그다음 `conversationId` 를 적는다.
  빈 대화를 만든 쪽은 `Composer` 이고 `onConversationCreated` 로 알린다. 그 콜백에서 주소를 바꾼다.
  빈 대화를 만든 뒤에도 `refresh()` 를 불러 목록에 「새 대화」 로 넣는다
- **주소가 `/` 로 바뀌었을 때만 상태를 비운다.** `usePathname()` 의 앞 값을 ref 에 두고,
  앞 값이 `/` 가 아니었는데 지금 `/` 이거나, 사이드바의 `새 대화` 와 뒤에 올 단축키가 부르는 context 의
  `startNew()` 가 불렸을 때 `conversationId`, 메시지, 입력 중 상태, 오류를 비우고 `selectionVersion` 과 `composerGeneration` 을 올린다.
  첨부는 `Composer` 가 갖고 있어 `composerGeneration` 을 올리면 unmount 정리가 지운다. 대화 번호를 얻었을 때는 `composerGeneration` 을 올리지 않는다.
  `pathname === "/" && conversationId !== null` 만으로 판정하지 않는다.
  `/` 에서 사진을 먼저 올려 빈 대화가 생기는 순간에도 그 조건이 참이 되어 올린 사진과 번호가 지워진다.
  `replaceState` 뒤에도 Next 의 `usePathname` 이 바로 따라오는지 브라우저 검사로 확인한다
- 실패 처리: `started` 를 받기 전의 실패는 지금처럼 `restoreFailedMessage` 로 입력창에 되돌린다.
  `started` 를 받은 뒤의 실패(`error` 사건, 스트림 끊김)는 되돌리지 않는다. 입력창을 비운 채로 두고
  메시지를 다시 읽은 뒤, 마지막 사용자 메시지 아래에 `data-testid="turn-error"` 로 오류 문구를 보인다
- `done` 을 받으면 지금처럼 메시지를 다시 읽고 `refresh()` 를 부른다. 한 번에 받는 경로(`sendWithoutStream`)도 같다
- `useShellTitle` 에 지금 에이전트 이름을 넘긴다
- 에이전트 줄(`agents.length > 1` 이면 `select`)은 그대로 둔다. `md` 미만에서는 고를 수 있을 때만 그린다.
  잠긴 이름은 위쪽 막대 제목이 대신 보인다
- `IconButton` 의 `☰` 는 지운다. 사이드바 열기는 화면 틀이 갖는다

### 8. 이 phase 를 검증하는 브라우저 테스트

`test/browser/` 의 기존 검사 가운데 아래를 새 구조에 맞게 고치고, 새 검사는 `test/browser/shell.spec.ts` 에 둔다.
픽스처는 `test/browser/fixtures.ts` 의 `test`, `expect` 를 쓴다.

고칠 곳:

| 파일 | 지금 가정 | 고친 뒤 |
| --- | --- | --- |
| `chat.spec.ts` 「mobile에서 입력창을 유지하고 대화 목록을 서랍으로 쓴다」 | `대화 목록 열기`, `complementary` 이름 `대화 목록`, 대화 단추 | `사이드바 열기`, `complementary` 이름 `사이드바`, 대화 링크. 고르면 주소가 `/c/{id}` 이고 서랍이 닫힌다 |
| `chat.spec.ts` 「desktop에서 대화 목록을 고정 칸으로 보인다」 | 같은 이름들 | `사이드바` 가 보이고 `사이드바 열기` 가 숨는다 |
| `chat.spec.ts` 「막혀서 넘어가면...」 | `/` 에서 서랍을 열고 대화 단추를 누른다 | 대화 목록의 링크 `/넘김 화면 검사/` 를 누른다 |
| `flow-progress.spec.ts` 의 `sendWithTheFlowAgent` 와 「흐름이 아닌 대화에는...」 | 서랍을 열고 `새 대화` 단추 | `/` 가 이미 새 대화라 그 두 줄을 지운다 |
| `nav.spec.ts` 「역할에 맞는 메뉴와...」 | 머리에 링크와 이름이 보인다 | `mobile` 이면 먼저 `사이드바 열기`. 그 뒤 같은 검사 |
| `nav.spec.ts` 「머리의 이름을 한 번만 읽고...」 | `header` 가 `nowrap`, 높이 56px 이하 | 홈 링크 하나, `mobile` 에서 위쪽 막대 `header` 높이 56px 이하와 가로 넘침 없음 |
| `theme.spec.ts` 「밝기 단추로...」 | 밝기 단추가 바로 보인다 | `mobile` 이면 먼저 `사이드바 열기` |
| `memory.spec.ts` 의 `memory-proposal-count` 검사 | 머리의 뱃지 | 사이드바의 같은 `data-testid`. `mobile` 에서 보이는지를 보는 곳은 서랍을 연다 |

`chat.spec.ts` 의 나머지(`/` 에서 보내는 검사)는 `/` 가 새 대화라 그대로 통과해야 한다.
`goto("/")` 뒤 지난 대화의 메시지가 보인다고 가정하는 검사가 있으면 그 대화의 `/c/{id}` 로 가게 고친다.

`shell.spec.ts` 에 넣을 것:

- **`/` 에서 보냄 → `started` 를 받아 주소가 `/c/{숫자}` 가 됨 → 곧바로 사이드바의 `새 대화` → 메시지 영역이 비고 입력창이 비어 있으며
  주소가 `/`. 거기서 다시 보내면 새 대화가 하나 더 생긴다.** 이 순서를 지킨다. `/c/{id}` 로 곧바로 들어가서 시작하면
  이 결함을 잡지 못한다
- `started` 뒤에 실패하면 입력창이 비어 있고 `turn-error` 가 보이며, 새로 고쳐도 그 사용자 메시지가 한 번만 있다.
  `page.route` 로 스트림 응답을 `started` 한 줄 뒤에 `error` 사건으로 끝나게 바꾼다
- 서랍을 연 채 `Esc` 를 누르면 서랍만 닫힌다

- `/` 에서 보내면 주소가 `/c/{숫자}` 로 바뀌고 흘러오던 답이 끊기지 않으며 사이드바 `오늘` 묶음 맨 위에 그 대화가 생긴다
- 새로 고치면 같은 대화가 열린다
- 사이드바의 `새 대화` 를 누르면 `/` 로 가고 메시지 영역이 빈다
- `/c/999999` 는 `conversation-not-found` 를 보이고 `새 대화` 링크가 `/` 로 간다
- `/usage` 에서도 사이드바가 보인다. `/signin` 에서는 `사이드바` 가 없다
- `desktop` 과 `mobile` 두 폭에서 `document.documentElement.scrollWidth` 가 창 폭을 넘지 않는다


**사진을 먼저 올리는 새 대화 검사**를 `shell.spec.ts` 에 더한다.
`/` 에서 사진을 고르면 주소가 `/c/{id}` 로 바뀌고 올린 사진이 그대로 있으며 사이드바에 「새 대화」 한 줄이 생긴다.
그 뒤 보내면 같은 대화에 붙고 제목이 첫 메시지로 바뀐다.
사진 올리기의 셀렉터와 픽스처는 사진 첨부 구현이 만든 `test/browser/` 의 검사에서 가져온다.

**입력창 보존 검사**를 `shell.spec.ts` 에 더한다.
`/` 에서 사진을 고른 뒤 주소가 `/c/{id}` 로 바뀌어도 미리보기가 남는다.
사이드바에서 다른 대화를 고르면 미리보기가 사라지고, 그 첨부가 서버에서 지워졌는지 첨부 조회로 확인한다.

## 검증

```bash
# cwd: web/
pnpm typecheck
AUTH_SECRET=build-time-placeholder \
ASSISTANT_JWT_SECRET=build-time-placeholder \
CONTROL_PLANE_BASE_URL=http://build-time-placeholder \
AUTH_GOOGLE_ID=build-time-placeholder \
AUTH_GOOGLE_SECRET=build-time-placeholder \
pnpm build
pnpm test:browser
```

모두 종료 코드 0 이어야 한다.

```bash
# cwd: 저장소 root
grep -rn 'style={{' web/src/
grep -rn 'site-header\|site-nav\|conversation-drawer\|conversation-list' web/src/
```

둘 다 아무것도 나오지 않아야 한다. 앞의 것은 `web/AGENTS.md` 대로 값이 이어지는 수라서 클래스로 만들 수 없고, 그 이유를 주석으로 남긴 예외만 허용한다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `web/src/components/shell/conversations-provider.tsx` | 신규 |
| `web/src/components/shell/group-by-date.ts` | 신규 |
| `web/src/components/shell/sidebar.tsx` | 신규 |
| `web/src/components/shell/conversation-nav.tsx` | 신규 |
| `web/src/components/shell/main-nav.tsx` | 신규 |
| `web/src/components/shell/app-shell.tsx` | 신규 |
| `web/src/app/layout.tsx` | 수정 |
| `web/src/app/page.tsx` | 수정 |
| `web/src/app/c/[conversationId]/page.tsx` | 신규 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `web/src/components/ui/site-header.tsx` | 삭제 |
| `web/src/components/ui/site-nav.tsx` | 삭제 |
| `web/src/components/chat/conversation-drawer.tsx` | 삭제 |
| `web/src/components/chat/conversation-list.tsx` | 삭제 |
| `test/browser/shell.spec.ts` | 신규 |
| `test/browser/chat.spec.ts` | 수정 |
| `test/browser/flow-progress.spec.ts` | 수정 |
| `test/browser/nav.spec.ts` | 수정 |
| `test/browser/theme.spec.ts` | 수정 |
| `test/browser/memory.spec.ts` | 수정 |

끝나면 `tasks/plan019-app-shell/index.json` 의 이 phase 를 `completed` 로 바꾸고 `current_phase` 를 3으로 올린다.
