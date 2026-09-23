# Phase 03. 화면에서 답을 멈추고 답과 코드를 복사한다

**Execution profile**: standard

## 목표

답을 만드는 동안 보내기 단추를 중지 단추로 바꾸고, `Esc` 로도 멈춘다.
`stopped` 사건을 받아 멈춘 답에 「중지됨」 을 붙이고, 남긴 답이 없으면 「답을 받지 못했다」 와 「다시 시도」 를 보인다.
답 아래에 메시지 동작 줄을 두고 복사를 넣는다. 코드 블록에 복사 단추를 넣는다.

**범위 외**: 다시 생성, 수정, 판 넘기기는 phase 04 가 한다. 이 phase 의 메시지 동작 줄에는 복사만 들어간다.
「다시 시도」 단추는 이 phase 에서 자리와 모양만 그리고, 누르면 부를 다시 생성 요청은 phase 04 가 잇는다.
Control Plane 은 고치지 않는다. phase 01 과 02 가 끝냈다.

## 컨텍스트

이 phase 를 시작할 때 아래가 코드에 있어야 한다.

- 화면 틀과 대화 주소. `web/src/components/shell/` 과 `web/src/app/c/[conversationId]/page.tsx`
- 작업 과정 블록과 패널. `web/src/components/chat/activity/`
- 화면이 `started` 사건을 받아 실행 번호를 쥐는 자리
- phase 01 의 `web/src/app/api/chat/executions/[id]/stop/route.ts`

`web/src/components/chat/activity/` 가 없으면 `PHASE_BLOCKED: 작업 과정 화면이 아직 없다` 를 출력하고 끝낸다.

대화 화면의 부품은 앞선 작업이 옮기거나 나눴을 수 있다. **구현 전에 아래 파일의 지금 모양을 읽는다.**
이 문서를 쓸 때의 모양은 이렇다.

| 파일 | 하는 일 |
| --- | --- |
| `web/src/components/chat-panel.tsx` | 스트림 사건을 받아 `turns` 를 고친다. `send` 가 `/api/chat/stream` 을 열고 `readEventStream` 으로 읽는다. `sending` 이 참인 동안 입력을 잠근다 |
| `web/src/components/chat/composer.tsx` | `Composer({ value, disabled, onChange, onSend })`. 한글 조합 중에는 `composing` ref 와 `event.nativeEvent.isComposing` 으로 Enter 를 무시한다. 보내기는 `aria-label="보내기"` 인 원형 `Button` 이다 |
| `web/src/components/chat/message-bubble.tsx` | `Turn` 타입과 `MessageBubble`. 답 줄은 마우스를 올리거나 초점이 가면 `detailsVisible` 로 시각을 보인다 |
| `web/src/components/chat/markdown.tsx` | `CodeBlock({ code, language })` 가 `<pre>` 를 그린다. 복사 단추가 없다 |
| `web/src/components/error-message.ts` | 오류 코드마다 문구. `describeError(code, fallback)` |

**근거 문서**: `docs/flow.md` 의 「중지할 때」 「메시지 동작」 「화면 틀」 의 단축키 표, 「작업 과정」 의 「작업 과정 패널」, `docs/code-architecture.md` 의 「우리 화면의 정체성」 과 「대화」 절 아래 「화면으로 보내는 사건」 「메시지 한 줄」, `docs/adr/ADR-021-중지한-답은-멈춘-자리까지-남긴다.md`

## 의도 메모

- 중지 단추는 보내기 단추와 **같은 자리, 같은 크기**다. 자리가 바뀌면 누르려던 손가락이 다른 것을 누른다.
- `started` 가 오기 전에는 중지 단추를 잠근다. 멈출 실행 번호가 아직 없다.
- 중지를 누른 뒤 `stopped` 가 올 때까지 단추를 잠근다. 오류를 받았을 때만 다시 푼다.
- 중지를 보내다 `HERMES_UNAVAILABLE` 을 받으면 단추를 다시 푼다. 서버는 보내지 못한 run 에만 다시 보내므로 다시 누르는 것이 효과가 있다.
- `EXECUTION_NOT_RUNNING` 은 알리지 않는다. 중지와 끝이 엇갈린 것이고 곧 `done` 이 온다.
- `Esc` 의 차례가 정해져 있다. 작업 과정 패널이 열려 있으면 패널을 닫는 것이 먼저다. 한글 조합 중에는 아무것도 하지 않는다. 조합을 끝내는 `Esc` 가 중지로 읽히면 안 된다.
- 복사는 마크다운 원문이다. 그려진 글을 복사하면 표와 코드 블록이 무너진다.
- 브랜드 색을 본문 글자에 쓰지 않는다. 「중지됨」 과 「복사됨」 은 `text-muted` 다.

## 작업 항목

### 1. `web/src/components/chat/composer.tsx` 에 중지 상태를 더한다

`Props` 에 세 칸을 더한다.

```ts
/** 답을 만드는 중이다. 참이면 보내기 단추 자리에 중지 단추를 그린다 */
running: boolean;
/** 중지할 수 있다. `started` 를 받았고 아직 중지를 누르지 않았을 때 참이다 */
canStop: boolean;
onStop(): void;
```

`running` 이 참이면 보내기 단추 대신 `aria-label="중지"` 인 단추를 같은 크기로 그린다. 안에는 네모 하나를 SVG 로 그린다.
`canStop` 이 거짓이면 `disabled` 다.
`running` 인 동안 입력칸은 잠그지 않는다. 다음 질문을 미리 쓸 수 있게 둔다. Enter 는 보내지 않는다.

### 2. `web/src/components/chat-panel.tsx` 가 중지를 부른다

- 중지는 **마지막으로 받은 `started` 의 번호**로 보낸다. provider 가 막혀 다시 시도하면 `started` 가 새 번호로 다시 온다.
  이미 그 번호를 쥐는 상태가 있으면 그것을 쓰고 새로 만들지 않는다. 새 `started` 가 오면 `stopRequested` 도 되돌리지 않는다. 이미 누른 중지는 서버가 새 시도에도 적용한다.
- `stopRequested` 상태를 둔다. `stop()` 은 `POST /api/chat/executions/{id}/stop` 을 부르고 `stopRequested` 를 참으로 둔다.
  응답이 202 가 아니고 코드가 `EXECUTION_NOT_RUNNING` 이 아니면 `stopRequested` 를 되돌리고 오류를 보인다. `HERMES_UNAVAILABLE` 이 여기 해당한다.
- `Composer` 에 `running={sending}`, `canStop={executionId !== null && !stopRequested}`, `onStop={stop}` 를 넘긴다.
- 스트림 사건 처리에 `stopped` 를 더한다. `done` 처럼 대화 목록과 메시지를 다시 읽는다.
  `messageId` 가 null 이면 흘러온 조각을 지운다. 다시 읽은 이력의 마지막이 답 없는 사용자 메시지가 되고, 작업 항목 4 가 그 아래를 그린다.
  화면에만 두는 임시 줄을 만들지 않는다. 다시 읽으면 곧 사라져 깜빡인다.
- 작업 과정 패널이 `live` 로 열려 있으면 `done` 과 같이 `{ mode: "saved", executionId }` 로 바꾼다. `executionId` 는 `stopped` 가 싣는 번호다.
- 작업 과정 블록의 상태는 `applyChatEvent` 가 `stopped` 를 받아 끝나지 않은 줄을 `stopped` 로 바꾼다. `web/src/components/chat/activity/activity-state.ts` 를 열어 그 갈래가 있는지 보고, 없으면 거기에 더한다.
- `ChatEvent` 타입은 `web/src/lib/chat-event.ts` 에 있고 `type` 합에 `"stopped"` 가 이미 있다. 없을 때만 더한다.

### 3. `Esc` 로 멈춘다

`Esc` 는 `chat-panel.tsx` 의 처리기 하나가 해석한다. 화면 틀을 만든 앞선 작업이 그 처리기를 두었고, 작업 과정 패널 작업이 3단계(패널 닫기)를 더했다.
**여기에 4단계를 더한다. 새 `window` 리스너를 두지 않는다.** 리스너가 둘이면 한 번의 `Esc` 로 패널이 닫히고 답까지 멈춘다.

차례는 `docs/flow.md` 「화면 틀」 을 따른다.

1. `event.isComposing` 이 참이면 아무것도 하지 않는다
2. `event.defaultPrevented` 가 참이면 아무것도 하지 않는다. 이름 입력칸, 확인 창, 서랍, `@` 목록이 이미 처리했다
3. 작업 과정 패널이 열려 있으면 닫고 끝낸다
4. `canStop` 이면 `stop()` 을 부른다

처리기가 2단계를 어떻게 판정하는지는 지금 코드를 읽고 따른다. 앞선 작업이 `stopPropagation` 만 쓰고 있으면 그 판정을 그대로 둔다.

### 4. `Turn` 에 상태를 싣고 「중지됨」 을 그린다

`web/src/components/chat/message-bubble.tsx` 의 `Turn` 에 `status?: "SUCCEEDED" | "FAILED" | "CANCELLED" | "RUNNING" | null` 을 더한다.
메시지 조회가 주는 `status` 가 그대로 들어온다.
답 줄에서 `status === "CANCELLED"` 이면 본문 아래에 `data-testid="stopped-mark"` 인 「중지됨」 을 `text-xs text-muted` 로 그린다.

**대화의 마지막 메시지가 답 없는 사용자 메시지이고 답을 만드는 중이 아니면** 그 말풍선 아래에 `data-testid="no-answer"` 인 줄을 그린다.
글은 「답을 받지 못했다」 이고 옆에 「다시 시도」 단추를 둔다. 앞 turn 이 실패했든 남긴 답 없이 멈췄든 같다.
이 phase 에서 「다시 시도」 는 `onRetry` 속성으로만 받고, 넘기는 쪽이 없으면 단추를 그리지 않는다. phase 04 가 다시 생성 요청을 넘긴다.
`started` 전에 실패해 서버에 아무것도 남지 않은 경우는 지금처럼 입력창에 글을 되돌린다. 이 줄은 그리지 않는다.

### 5. `web/src/components/chat/message-actions.tsx` 를 새로 만든다

답 한 줄 아래의 동작 줄이다. 이 phase 에서는 복사만 넣는다. phase 04 가 다시 생성과 판 넘기기를 더한다.

```ts
type Props = {
  /** 복사할 마크다운 원문 */
  content: string;
  /** 대화의 마지막 답이다. 넓은 화면에서 늘 보일지를 정한다 */
  latest: boolean;
};
```

- 복사 단추는 `aria-label="답 복사"` 다. `navigator.clipboard.writeText(content)` 를 부른다.
  성공하면 2초 동안 「복사됨」, 실패하면 2초 동안 「복사하지 못했다」 로 바뀐다. `aria-live="polite"` 로 알린다.
- 보이는 규칙은 `docs/flow.md` 「메시지 동작」 을 따른다. `md` 이상에서는 `latest` 가 아니면 부모 `li` 의 `group-hover` 와 `group-focus-within` 에서만 보인다. `md` 미만에서는 늘 보인다.
- `MessageBubble` 의 답 줄 본문 아래에 그린다. 흘러나오는 중인 답에는 그리지 않는다.

복사하고 「복사됨」 으로 바꾸는 부분은 작업 항목 6 과 같이 쓴다. `web/src/components/ui/copy-button.tsx` 로 뽑는다.
`CopyButton({ text, label })` 이 단추 하나와 상태 바뀜을 갖는다.

### 6. `web/src/components/chat/markdown.tsx` 의 코드 블록에 복사를 넣는다

`CodeBlock` 이 `<pre>` 를 `relative` 인 감싸개로 두르고 오른쪽 위에 `CopyButton` 을 `label="코드 복사"` 로 둔다.
복사하는 것은 하이라이트 전 `code` 문자열이다.
단추가 코드의 첫 줄을 가리지 않게 `<pre>` 의 위쪽 여백을 넓힌다. 색과 간격은 `globals.css` 의 토큰 클래스로만 쓴다.

### 7. 이 phase 를 검증하는 브라우저 테스트

`test/browser/stop.spec.ts` 를 새로 만든다. `test/browser/chat.spec.ts` 와 `test/browser/fixtures.ts` 의 설정을 따른다.
대역을 붙잡는 방법은 `FakeHermesControl` 이 가진 것을 쓰고, 없으면 `test/e2e/fake-hermes.ts` 의 `holdNextRun` 을 부르는 길을 `fixtures.ts` 에 더한다.

| 경우 | 기대 |
| --- | --- |
| 붙잡아 둔 실행에 보낸다 | `aria-label="보내기"` 가 사라지고 `aria-label="중지"` 가 같은 자리에 보인다 |
| 중지를 누른다 | 답 줄에 `stopped-mark` 가 보이고 보내기 단추가 돌아온다. 새로 고쳐도 `stopped-mark` 가 남는다 |
| 조각이 흐르기 전에 멈춘다. 대역 입력 `중지 빈 답 검사` 에서 첫 조각이 오기 전에 누른다 | 답 줄이 없고 사용자 말풍선 아래에 `no-answer` 가 보인다. 새로 고쳐도 같다 |
| 작업 과정 패널을 연 채 `Esc` 를 누른다 | 패널만 닫히고 중지 단추는 그대로다. 한 번 더 누르면 멈춘다 |
| 입력칸에 초점을 두고 `Esc` 를 누른다 | 위와 같이 멈춘다 |
| 답 복사를 누른다 | 단추 글이 「복사됨」 이 된다. 클립보드 권한은 `context.grantPermissions(["clipboard-read", "clipboard-write"])` 로 준다 |
| 코드 블록이 있는 답에서 코드 복사를 누른다 | 클립보드 글이 코드 원문과 같다 |

`mobile` 과 `desktop` 두 폭에서 모두 돈다. `mobile` 에서는 복사 단추가 마우스를 올리지 않아도 보인다.

## 검증

```bash
# cwd: web/
pnpm typecheck
pnpm test:browser stop.spec.ts chat.spec.ts
pnpm test:browser
```

```bash
# cwd: 저장소 root
grep -rn 'style={{' web/src/
scripts/check-public-safe.sh
```

`grep` 이 내는 줄은 모두 바로 위에 왜 인라인인지 적은 주석이 있어야 한다. `web/AGENTS.md` 의 예외 규칙이다. 주석 없는 줄이 하나라도 있으면 실패다.

끝나면 `tasks/plan021-stop-regenerate/index.json` 의 이 phase 를 `completed` 로 바꾸고 `current_phase` 를 4로 올린다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `web/src/components/chat/composer.tsx` | 수정 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `web/src/components/chat/message-bubble.tsx` | 수정 |
| `web/src/components/chat/message-actions.tsx` | 신규 |
| `web/src/components/ui/copy-button.tsx` | 신규 |
| `web/src/components/chat/markdown.tsx` | 수정 |
| `web/src/components/chat/activity/` | 수정. 멈춘 상태를 받는 자리 |
| `test/browser/stop.spec.ts` | 신규 |
| `test/browser/fixtures.ts` | 수정 |
