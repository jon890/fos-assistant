# Phase 01. 대화별 상태를 `ConversationSession` 으로 나눈다

**Execution profile**: deep

## 목표

`web/src/components/chat-panel.tsx` 의 대화별 상태를 하위 부품 `ConversationSession` 으로 옮기고, `ChatPanel` 이 `key` 로 그 부품을 새로 만들게 한다.
대화를 전환할 때 state 18개와 ref 를 effect 안에서 하나씩 되돌리는 코드가 없어지고, `web/eslint-suppressions.json` 에 남은 마지막 위반(`react-hooks/set-state-in-effect` 1건)이 사라진다.

**사용자에게 보이는 동작은 바뀌지 않는다.** 주소 전환, 첫 메시지 뒤 주소 변경, 대기열, 중지, 다시 생성, 작업 과정 패널이 지금과 같아야 한다.

**범위 외**

- `Esc` 와 풀이의 처리. phase 02 가 한다.
- `selectionVersion` 과 `version` 인자를 다른 방식으로 바꾸는 일. 이 phase 는 그 값이 올라가는 자리만 옮긴다.
- 함수와 타입의 이름 바꾸기, 로직 정리. 옮기는 것과 아래에 적은 변경만 한다.

## 컨텍스트

**근거 문서**: `docs/frontend/shell.md` 의 「대화 이력」 절. 두 부품이 무엇을 갖고 언제 `key` 를 올리는지가 표로 있다.

지금 `ChatPanel` 은 `web/src/app/page.tsx`(`initialConversationId={null}`)와 `web/src/app/chat/[conversationId]/page.tsx` 가 그린다.
경로가 바뀌면 Next.js 가 `ChatPanel` 을 새로 만든다(대화 전환 뒤 입력창의 글이 비는 것으로 확인했다).
그래서 한 `ChatPanel` 안에서 일어나는 전환은 실제로는 아래 둘이다. 셋째는 Next.js 의 동작이 달라져도 맞게 움직이도록 둔다.

1. `/` 에서 첫 메시지를 보내거나 사진을 올려 `window.history.replaceState` 로 주소만 `/chat/{id}` 가 된 뒤, 「새 대화」 를 눌러 주소가 `/` 로 돌아온다.
2. `/` 에서 「새 대화」 를 다시 눌러 `useConversations().newConversationVersion` 이 오른다.
3. `initialConversationId` prop 이 다른 대화로 바뀐다.

지금 코드의 해당 자리다.

- `chat-panel.tsx` 의 `useEffect(..., [initialConversationId])`: 상태를 되돌리고 도는 turn 과 이력을 읽는다. 기준 파일에 든 위반이 여기다.
- `startNewConversation()`: 같은 되돌리기를 한 번 더 적어 둔 함수다. `pathname` effect 와 `newConversationVersion` effect 가 부른다.
- `composerGeneration`: `Composer` 만 `key` 로 새로 만드는 값이다.
- `selectionVersion`: 전환마다 올리는 ref 다. 늦게 온 응답과 스트림 사건이 `selectionVersion.current !== version` 이면 버려진다.

## 의도 메모

- `key` 로 대화 식별자를 쓰지 않는다. 새 대화에서 첫 메시지의 `started` 나 첫 사진 올리기로 식별자가 null 에서 값으로 바뀌는데, 그때 새로 만들면 흘러오던 답과 올리던 사진을 잃는다. `key` 는 `ChatPanel` 이 올리는 번호다.
- 렌더 중에 ref 를 읽거나 쓰지 않는다. `react-hooks/refs` 에 걸린다. `ChatPanel` 의 전환 판정은 ref 가 아니라 state 를 견줘 렌더 중에 한다. 지금 파일의 `seenConversation` 이 같은 방식이다.
- `selectionVersion` 은 `ConversationSession` 에 남긴다. 없어진 부품이 받던 스트림은 계속 읽히므로, 늦게 온 `started` 가 `window.history.replaceState` 로 주소를 바꾸지 않게 막는 장치가 여전히 필요하다. 이제 이 값은 부품이 없어질 때 한 번만 오른다.
- 에이전트 목록은 `ChatPanel` 에 둔다. `ConversationSession` 에 두면 「새 대화」 를 누를 때마다 목록을 다시 읽는다.
- 기준 파일에 위반을 더하지 않는다. 새 lint error 가 나오면 코드를 고친다.

## 작업 항목

### 1. 파일을 옮긴다

```bash
# cwd: 저장소 root
git mv web/src/components/chat-panel.tsx web/src/components/chat/conversation-session.tsx
```

이력이 이어지게 `git mv` 로 옮기고, `web/src/components/chat-panel.tsx` 는 새로 만든다.
옮긴 파일의 import 는 새 위치에 맞게 고친다. `web/eslint.config.mjs` 가 `../` import 를 막으므로 같은 디렉터리는 `./`, 그 밖은 `@/components/...` 와 `@/lib/...` 로 쓴다.

### 2. `web/src/components/chat/conversation-session.tsx` 의 `ConversationSession`

`export function ChatPanel` 을 `export function ConversationSession` 으로 바꾼다. props 는 아래와 같다.

```ts
type ConversationSessionProps = {
  /** 이 부품이 만들어질 때의 대화다. null 이면 새 대화로 시작한 것이다. 부품이 사는 동안 바뀌지 않는다 */
  initialConversationId: string | null;
  /** 지금 대화의 식별자다. 첫 메시지나 첫 사진으로 null 에서 값이 된다 */
  conversationId: string | null;
  onConversationIdChange(id: string): void;
  agents: AgentView[];
  agentsLoading: boolean;
  agentCode: string;
  onAgentCodeChange(code: string): void;
  /** `useConversation(conversationId)` 의 결과다 */
  currentConversation: ReturnType<typeof useConversation>;
};
```

고칠 것이다. 여기 적지 않은 함수와 effect 는 그대로 옮긴다.

| 지금 | 바꾼 뒤 |
| --- | --- |
| `const [conversationId, setConversationId] = useState(...)` | 없앤다. prop `conversationId` 를 읽는다 |
| `conversationIdRef.current = X; setConversationId(X);` 를 나란히 적은 네 자리(`sendWithoutStream`, `send` 의 `onStarted` 와 `onDone`, `Composer` 의 `onConversationCreated`) | 부품 안에 `assignConversationId(id: string)` 를 두고 그것을 부른다. 본문은 아래 「더할 것」 에 있다 |
| `agents`, `agentsLoading`, `agentCode` state 와 `fetchChatAgents` effect | 없앤다. props 를 읽는다. `setAgentCode` 를 넘기던 `StartScreenHeader` 의 `onSelect` 와 `Composer` 의 `mention.onPick` 에는 `onAgentCodeChange` 를 넘긴다 |
| `currentConversation = useConversation(conversationId)` 와 `seenConversation` 맞추기 | 없앤다. prop `currentConversation` 을 읽는다. 맞추기는 `ChatPanel` 로 간다 |
| `const [freshStart, setFreshStart] = useState(initialConversationId === null)` | `const freshStart = initialConversationId === null;` |
| `useEffect(..., [initialConversationId])` 의 동기 되돌리기(`++selectionVersion.current` 부터 `setComposerGeneration` 까지) | 지운다. 새로 만들어진 부품은 초깃값이 이미 그 값이다. `const version = selectionVersion.current;` 로 읽고, `void (async () => { ... })()` 안의 본문은 그대로 둔다. effect 는 `initialConversationId === null` 이면 곧바로 돌아간다 |
| `startNewConversation()`, `pathname` effect, `newConversationVersion` effect, `previousPathname`, `previousNewVersion`, `usePathname()`, `newConversationVersion` | 지운다. `ChatPanel` 이 한다 |
| `composerGeneration` 과 `<Composer key={composerGeneration}` | 지운다. `Composer` 는 `ConversationSession` 과 함께 새로 만들어진다 |

더할 것은 부품이 없어질 때의 정리와 `assignConversationId` 다. 지금 전환 effect 가 하던 것 가운데 없어진 부품에도 필요한 것만 남긴다.

```ts
/** 이 부품이 없어졌다. 그 뒤에 온 사건이 `ChatPanel` 의 대화 식별자를 바꾸지 않게 한다 */
const disposed = useRef(false);

// 이 부품이 없어진 뒤에 온 응답과 스트림 사건을 버린다. 받던 스트림은 서버에서 계속 돌고 계속 읽힌다.
useEffect(() => {
  // 개발 모드는 effect 를 한 번 정리하고 다시 돌린다. 그때 다시 살아 있는 것으로 둔다.
  disposed.current = false;
  return () => {
    disposed.current = true;
    selectionVersion.current += 1;
    sentTurnToken.current = null;
    conversationTasks.current = [];
    autoTurn.current = null;
  };
}, []);

function assignConversationId(id: string) {
  if (disposed.current) return;
  conversationIdRef.current = id;
  onConversationIdChange(id);
}
```

`conversationId` 는 `ChatPanel` 의 state 라 없어진 부품이 불러도 바뀐다. 그러면 새 대화 화면에서 보낸 글이 앞 대화에 저장된다. `disposed` 가 그것을 막는다.

`messagesLoading` 의 초깃값 `initialConversationId !== null` 은 그대로다.
`useShellTitle`, `usePendingQueue`, `useStarterSuggestions`, 스킬 목록 effect, 반환하는 JSX 는 `ConversationSession` 에 남는다.

### 3. `web/src/components/chat-panel.tsx` 의 `ChatPanel`

새 파일이다. `"use client"` 로 시작하고 `ChatPanel({ initialConversationId }: { initialConversationId: string | null })` 을 그대로 내보낸다. 두 `page.tsx` 는 고치지 않는다.

갖는 것이다.

- `agents`, `agentsLoading`, `agentCode` state 와 `fetchChatAgents` effect. 옮기기 전 코드 그대로다.
- `const [session, setSession] = useState({ generation: 0, initialConversationId })`
- `const [conversationId, setConversationId] = useState<string | null>(initialConversationId)`
- `const currentConversation = useConversation(conversationId)` 와 `seenConversation` 맞추기. 옮기기 전 코드 그대로다.
- 전환 판정에 쓰는 state 셋: `seenInitialId`(초깃값 `initialConversationId`), `seenPathname`(초깃값 `usePathname()` 의 값), `seenNewVersion`(초깃값 `newConversationVersion`)

전환 판정은 렌더 중에 한다. effect 를 쓰지 않는다.

```ts
function startNewConversation() {
  setSession((previous) => ({ generation: previous.generation + 1, initialConversationId: null }));
  setConversationId(null);
  setAgentCode(agents[0]?.code ?? "");
}

if (seenInitialId !== initialConversationId) {
  setSeenInitialId(initialConversationId);
  if (initialConversationId !== null) {
    setSession((previous) => ({ generation: previous.generation + 1, initialConversationId }));
    setConversationId(initialConversationId);
  }
}
if (seenPathname !== pathname) {
  setSeenPathname(pathname);
  // 주소만 `/chat/{id}` 로 바꿔 둔 화면에서 「새 대화」 를 눌렀다.
  if (seenPathname !== "/" && pathname === "/" && conversationId !== null) startNewConversation();
}
if (seenNewVersion !== newConversationVersion) {
  setSeenNewVersion(newConversationVersion);
  startNewConversation();
}
```

조건은 옮기기 전의 `pathname` effect 와 `newConversationVersion` effect 와 같다. `conversationIdRef.current !== null` 이던 것만 state `conversationId !== null` 로 읽는다.

그리는 것은 하나다.

```tsx
<ConversationSession
  key={session.generation}
  initialConversationId={session.initialConversationId}
  conversationId={conversationId}
  onConversationIdChange={setConversationId}
  agents={agents}
  agentsLoading={agentsLoading}
  agentCode={agentCode}
  onAgentCodeChange={setAgentCode}
  currentConversation={currentConversation}
/>
```

파일 머리에 두 부품이 무엇을 나눠 갖는지 한국어 주석으로 적는다. 내용은 `docs/frontend/shell.md` 의 표와 같게 한다.

### 4. `web/src/components/ui/page-skeleton.tsx` 의 주석

`ChatSkeleton` 위 주석의 `` `chat-panel.tsx` 의 세로 배치 `` 를 `` `chat/conversation-session.tsx` 의 세로 배치 `` 로 고친다. 코드는 고치지 않는다.

### 5. 기준 파일을 줄인다

```bash
# cwd: web/
pnpm exec eslint --prune-suppressions
```

`web/eslint-suppressions.json` 에서 `src/components/chat-panel.tsx` 줄이 빠져야 한다. 빈 객체가 되거나 eslint 가 파일을 지운다.
파일이 지워졌고 `pnpm lint` 나 `scripts/quality.sh check` 가 그 파일이 없다고 실패하면, 파일을 지운 채 두지 말고 내용이 `{}` 인 파일로 둔다.

### 6. 이 phase 를 검증하는 `test/browser/shell.spec.ts` 의 검사

기존 「시작 사건 뒤 새 대화를 누르면 기존 메시지와 입력이 비고 다음 대화가 생긴다」 아래에 하나를 더한다.
없어진 부품이 받던 스트림의 사건이 새 화면과 주소를 바꾸지 않는지 본다. 이 phase 가 다루는 실패가 이것이다.

```ts
test("답을 만드는 중에 새 대화를 누르면 늦게 온 답이 새 대화 화면과 주소를 바꾸지 않는다", async ({ page, hermes }, testInfo) => {
  await hermes.holdNextRun();
  await page.goto("/");
  await send(page, `늦은 답 ${testInfo.project.name} ${Date.now()}`);
  await hermes.waitForHeldRun();
  await expect(page).toHaveURL(CONVERSATION_URL);
  const firstUrl = page.url();
  await newConversation(page, testInfo);
  if (testInfo.project.name === "mobile") {
    // 서랍이 닫힌 뒤에 입력창을 본다.
    await expect(page.getByRole("complementary", { name: "사이드바" })).toBeHidden();
  }
  await expect(page.getByTestId("user-message")).toHaveCount(0);
  await hermes.releaseHeldRun();
  // 앞 대화의 답이 저장될 때까지 기다린다. 그 뒤에도 새 대화 화면이 그대로여야 한다.
  await expect.poll(async () => {
    const response = await page.request.get(`/api/chat/conversations/${conversationIdOf(firstUrl)}/messages`);
    const messages = (await response.json()) as { role: string }[];
    return messages.some((message) => message.role === "ASSISTANT");
  }, { timeout: 30_000 }).toBe(true);
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByTestId("user-message")).toHaveCount(0);
  await expect(page.getByTestId("assistant-message")).toHaveCount(0);
  await expect(page.getByTestId("composer-shell").getByRole("button", { name: "중지" })).toHaveCount(0);
  // 늦게 온 사건이 대화 식별자를 앞 대화로 되돌렸으면 이 글이 앞 대화에 저장되고 주소가 앞 대화가 된다.
  await send(page, `늦은 답 뒤 새 대화 ${testInfo.project.name} ${Date.now()}`);
  await expect(page).toHaveURL(CONVERSATION_URL);
  expect(page.url()).not.toBe(firstUrl);
});
```

`send`, `newConversation`, `CONVERSATION_URL`, `conversationIdOf` 는 그 파일에 이미 있다.
메시지 조회 경로는 `web/src/lib/chat-api.ts` 의 `fetchConversationMessages` 가 부르는 경로와 같아야 한다. 다르면 그 함수의 경로를 쓴다.
`mobile` 에서 서랍이 스스로 닫히지 않아 `toBeHidden` 이 실패하면 그 줄 대신 `page.getByRole("button", { name: "사이드바 닫기" })` 를 눌러 닫는다.

정상 경로는 기존 검사가 본다. 아래 「검증」 의 spec 이 주소 전환, 첫 메시지 뒤 주소 변경, 대기열, 중지, 다시 생성, 작업 과정 패널을 모두 지난다.

## 검증

```bash
# cwd: web/
pnpm lint
pnpm format:check
pnpm typecheck
```

셋 다 종료 코드 0 이다. `pnpm format:check` 가 실패하면 `pnpm format:changed` 로 고친다.

```bash
# cwd: 저장소 root
grep -c "set-state-in-effect" web/eslint-suppressions.json
grep -n "composerGeneration\|startNewConversation\|previousPathname" web/src/components/chat/conversation-session.tsx
```

첫 명령은 0 을 내거나 파일이 없다고 한다. 둘째 명령은 아무것도 내지 않는다.

브라우저 검사는 돌리기 전에 지시문이 알려 준 대기 스크립트를 먼저 실행한다. 두 폭 모두 돌린다.

```bash
# cwd: web/
pnpm test:browser shell.spec.ts browser/chat stop.spec.ts start-screen starters observe-running regenerate conversation-requests legacy-conversation-url flow-progress activity-panel activity-scroll missing-agent model-choice skill-command ask-card approval-card artifact.spec
```

인자는 파일의 절대 경로에 맞는 정규식이다. `browser/chat` 은 `chat.spec`, `chat-queue`, `chat-attachment`, `chat-delegation-wake`, `chat-outer-scroll` 을 고른다.
`chat` 만 적으면 작업 공간 경로에 같은 글자가 들어 있을 때 모든 spec 을 고른다.
모두 통과해야 한다. `stop.spec.ts` 의 「입력칸에 초점이 있어도 Esc 로 답을 중지한다」 가 30초 초과로 실패하면 phase 02 가 고치는 문제다. 그 검사만 다시 돌려 통과하면 넘어간다.

```bash
# cwd: 저장소 root
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
scripts/quality.sh check
```

`doc-references` 검사가 문서가 가리키는 파일 경로를 확인한다. 모두 종료 코드 0 이다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/components/chat-panel.tsx` | 수정 |
| `web/src/components/chat/conversation-session.tsx` | 신규 |
| `web/src/components/ui/page-skeleton.tsx` | 수정 |
| `web/eslint-suppressions.json` | 수정 |
| `test/browser/shell.spec.ts` | 수정 |
