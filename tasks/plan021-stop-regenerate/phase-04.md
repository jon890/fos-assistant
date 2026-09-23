# Phase 04. 화면에서 마지막 답을 다시 받고 마지막 질문을 고치고 판을 넘긴다

**Execution profile**: standard

## 목표

마지막 답 아래에 다시 생성을, 마지막 사용자 메시지에 수정을 둔다.
메시지 목록을 판으로 접는 순수 함수를 만들고, 답 한 줄의 판과 turn 의 판을 `‹ 2/3 ›` 로 넘긴다.

**범위 외**: Control Plane 은 고치지 않는다. phase 02 가 끝냈다.
마지막 turn 밖의 메시지에는 두 단추를 그리지 않는다. ADR-022 가 막았다.

## 컨텍스트

phase 03 이 끝나 있어야 한다. `web/src/components/chat/message-actions.tsx` 와 `web/src/components/ui/copy-button.tsx` 가 있다.
`message-actions.tsx` 가 없으면 `PHASE_BLOCKED: 메시지 동작 줄이 아직 없다` 를 출력하고 끝낸다.

phase 02 가 만든 것이다.

| 무엇 | 모양 |
| --- | --- |
| 다시 생성 | `POST /api/chat/conversations/{id}/regenerate`. 본문이 없다. 응답은 `/api/chat/stream` 과 같은 사건 스트림이다 |
| 수정 | `POST /api/chat/stream` 에 `editOfMessageId` 를 더해 보낸다 |
| 메시지 한 줄 | `replacesMessageId` 가 이 메시지가 대신하는 이전 메시지다. 없으면 null |
| 오류 | `MESSAGE_NOT_LATEST`, `CONVERSATION_BUSY`, 첨부가 달린 메시지 수정은 `VALIDATION_FAILED` |

메시지 조회는 이전 판까지 모두 `id` 순서로 준다. 화면이 접어야 한다.
사진 첨부가 머지되어 `Turn` 에 첨부 칸이 있다. **구현 전에 `message-bubble.tsx` 의 `Turn` 과 첨부를 그리는 자리를 읽는다.**

**근거 문서**: `docs/adr/ADR-022-다시-생성과-수정은-같은-session-에-판으로-쌓는다.md`, `docs/flow.md` 의 「다시 생성과 수정」 「메시지 동작」 「실행이 실패할 때」, `docs/code-architecture.md` 의 「대화」 절 아래 「메시지 한 줄」, `docs/data-schema.md` 의 「chat_message」

## 의도 메모

- 판을 접는 규칙을 컴포넌트 안에 두지 않는다. `web/src/lib/message-versions.ts` 의 순수 함수로 두고 따로 검사한다.
  판이 둘 겹치는 경우(고친 turn 안에서 다시 생성)가 컴포넌트 안에 있으면 검사할 방법이 브라우저 테스트뿐이다.
- 그 파일은 `@/` 별칭을 쓰지 않고 상대 경로만 쓴다. `node --test` 가 별칭을 풀지 못한다.
  `test/e2e/scenarios/streaming.ts` 가 `web/src/lib/stream.ts` 를 상대 경로로 읽는 것과 같은 방식이다.
- 다시 생성과 수정은 **마지막 turn 의 가장 최근 판을 보고 있을 때만** 그린다. 이전 판을 보는 중에 누르면 무엇을 다시 만드는지 헷갈린다.
- 판을 고른 상태는 대화를 바꾸면 버린다. 저장하지 않는다.
- 새 판이 생기면 그 판을 보인다. 다시 읽은 목록에서 가장 최근 판이 기본이다.

## 작업 항목

### 1. `web/src/lib/message-versions.ts` 를 만든다

```ts
export type VersionedMessage = {
  id: number;
  role: "USER" | "ASSISTANT";
  replacesMessageId: number | null;
};

/** 판 하나의 자리. `slotId` 는 그 판 사슬의 첫 메시지 번호다 */
export type VersionSlot = { slotId: number; index: number; count: number };

export type FoldedTurn<T extends VersionedMessage> = {
  user: T;
  userVersion: VersionSlot;
  answers: { message: T; version: VersionSlot }[];
};

/**
 * 이전 판까지 담긴 메시지 목록을 화면에 그릴 turn 들로 접는다.
 *
 * @param messages `id` 순서의 메시지
 * @param selected 판 자리마다 고른 순번. 없으면 가장 최근 판이다
 */
export function foldVersions<T extends VersionedMessage>(
  messages: T[],
  selected: Record<number, number>,
): FoldedTurn<T>[];
```

규칙이다.

1. `replacesMessageId` 로 사슬을 잇는다. 아무것도 가리키지 않는 메시지가 사슬의 첫 판이고 그 번호가 `slotId` 다.
2. 사용자 메시지 사슬 하나가 turn 자리 하나다. turn 자리는 `slotId` 순서로 그린다.
3. 사용자 메시지 한 판의 답은 `id` 순서에서 그 판 뒤에 오고 다음 사용자 메시지 앞에 오는 답들이다.
   고친 판은 목록 뒤쪽에 있으므로 「다음 사용자 메시지」 는 판을 가리지 않고 `id` 순서의 다음 `USER` 다.
4. 한 판의 답들도 사슬로 잇는다. 답 사슬 하나가 답 자리 하나다.
5. `selected` 에 없는 자리는 가장 최근 판이다. 범위를 넘는 순번은 가장 최근 판으로 읽는다.
6. 사슬이 끊겨 가리키는 메시지가 목록에 없으면 그 메시지를 첫 판으로 읽는다. 예외를 던지지 않는다.

`isLatestView(folded)` 도 둔다. 마지막 turn 과 그 마지막 답이 모두 가장 최근 판이면 참이다. 마지막 turn 에 답이 없으면 turn 의 판만 본다.

답이 없는 turn 은 `answers` 가 빈 배열이다. 그 turn 을 다시 시도해 생긴 답은 아무것도 가리키지 않으므로 그 turn 의 첫 답 자리가 된다.

### 2. `web/src/components/chat/version-switcher.tsx` 를 만든다

`VersionSwitcher({ slot, onChange })` 가 `‹ {index + 1}/{count} ›` 를 그린다. `count` 가 1이면 아무것도 그리지 않는다.
두 단추의 `aria-label` 은 「이전 판」 과 「다음 판」 이고 끝에서는 `disabled` 다. 가운데 글은 `data-testid="version-label"` 이다.

### 3. `message-actions.tsx` 에 다시 생성과 판을 더한다

`Props` 에 더한다.

```ts
/** 이 답의 판 자리 */
version: VersionSlot;
onVersionChange(index: number): void;
/** 다시 생성을 그린다. 마지막 turn 의 가장 최근 판을 보고 있고 답을 만드는 중이 아닐 때 참이다 */
canRegenerate: boolean;
onRegenerate(): void;
```

다시 생성 단추의 `aria-label` 은 「다시 생성」 이다. 판 넘기기는 복사 단추 왼쪽에 둔다.

### 4. 사용자 말풍선에 수정을 둔다

`message-bubble.tsx` 의 사용자 줄 아래에 수정 단추(`aria-label="수정"`)와 그 turn 의 `VersionSwitcher` 를 둔다.
수정 단추는 마지막 turn 의 가장 최근 판이고, 그 메시지에 첨부가 없고, 답을 만드는 중이 아닐 때만 그린다.

누르면 그 말풍선 자리가 입력칸이 된다. 부품은 `web/src/components/chat/message-editor.tsx` 로 만든다.

- 처음 값은 원래 글이다. 입력칸은 `Composer` 처럼 한글 조합 중에는 Enter 를 무시한다.
- 「보내기」 와 「취소」 두 단추. `Esc` 는 취소다. 빈 글이면 보내기가 `disabled` 다.
- 원래 글과 같으면 보내기가 `disabled` 다. 같은 글로 새 판을 만들 이유가 없고 그것은 다시 생성이 한다.

### 5. `chat-panel.tsx` 가 판을 접어 그리고 두 요청을 보낸다

- 메시지 목록을 `foldVersions(turns, selected)` 로 접어 그린다. `selected` 는 `useState<Record<number, number>>({})` 이고 대화를 바꾸면 비운다.
  `MessageList` 가 `Turn[]` 을 받는 모양이면 접은 결과를 받도록 고친다.
- 다시 생성은 `/api/chat/conversations/{id}/regenerate` 를 연다. 사건 처리는 `send` 와 같다. 공통 부분을 한 함수로 모은다.
  기다리는 동안 이전 답 자리에 흘러오는 새 답을 그린다. 실패하면 흘러온 것을 지우고 이전 판을 다시 보인다.
- 수정은 `/api/chat/stream` 에 `{ conversationId, text, agentCode, editOfMessageId }` 를 보낸다.
  기다리는 동안 새 사용자 말풍선과 흘러오는 답을 그 turn 자리에 그린다. 실패하면 편집하던 글을 편집칸에 되돌린다.
- 끝나면 `done` 이나 `stopped` 로 이력을 다시 읽고 `selected` 에서 그 자리를 지운다. 가장 최근 판이 보인다.
- `MESSAGE_NOT_LATEST` 를 받으면 오류를 보이고 이력을 다시 읽는다.
- phase 03 이 답 없는 사용자 메시지 아래에 그린 「다시 시도」 의 `onRetry` 에 다시 생성 요청을 넘긴다. 같은 경로, 같은 사건 처리다.
  서버는 마지막이 답 없는 사용자 메시지이면 그 질문으로 새 답을 만든다. 사용자 메시지를 다시 보내지 않으므로 같은 질문이 둘 남지 않는다.
- `web/src/app/api/chat/stream/route.ts` 가 `editOfMessageId` 를 넘기는지 phase 02 에서 이미 했다. 하지 않았으면 여기서 더한다.

### 6. `docs/code-architecture.md` 의 「아직 만들지 않은 것」 을 고친다

「대화 화면 개선」 아래 목록에서 `중지와 \`stopped\` 사건, 다시 생성과 수정, 메시지 동작` 줄을 뺀다.
그 목록이 비면 「대화 화면 개선」 항목도 뺀다. 다른 줄은 건드리지 않는다.

### 7. 새 테스트 위치를 확인 목록에 올린다

`test/unit/` 은 이 저장소에 처음 생기는 테스트 위치다. 확인 목록에 없으면 머지 전 검사가 이것을 돌리지 않는다.

- `AGENTS.md` 의 「확인」 절 명령 목록에 `node --test 'test/unit/**/*.test.ts'` 를 `node test/e2e/run.ts` 바로 뒤에 더한다.
  디렉터리 인자만 주면 `.ts` 를 찾지 못할 수 있어 파일 패턴을 따옴표로 준다.
  **먼저 저장소 root 에서 그 명령을 돌려 테스트가 실제로 도는지(`# tests` 가 0 이 아닌지) 확인한다.**
  Node 22.18 에서 `.ts` 를 읽지 못하면 `node --experimental-strip-types --test 'test/unit/**/*.test.ts'` 로 바꾸고 AGENTS.md 에도 그 줄을 적는다.
  같은 절의 「네 검사」 와 「네 명령」 이라는 말을 실제 줄 수에 맞춰 고친다
- `docs/code-architecture.md` 의 「화면을 검증하는 방법」 표에 한 줄을 더한다.
  위치 `test/unit/`, 확인하는 것 「화면이 쓰는 순수 함수」, 띄우는 것 「없다」

### 8. 이 phase 를 검증하는 테스트

`test/unit/message-versions.test.ts` 를 만든다. `node:test` 와 `node:assert/strict` 를 쓰고 `../../web/src/lib/message-versions.ts` 를 읽는다.

| 입력 | 기대 |
| --- | --- |
| 판이 없는 두 turn | turn 둘, 모든 자리의 `count` 1 |
| 마지막 답을 두 번 다시 생성 | 마지막 turn 의 답 자리 하나, `count` 3, 기본 `index` 2 |
| 마지막 질문을 고침 | turn 자리 하나에 `count` 2. 고르지 않으면 고친 판과 그 답이 보인다. `selected` 로 0을 고르면 고치기 전 질문과 그 답이 보인다 |
| 고친 turn 에서 다시 생성 | 고친 판의 답 자리가 `count` 2. 고치기 전 판의 답은 섞이지 않는다 |
| 이전 판을 고름 | `isLatestView` 가 거짓 |
| 가리키는 메시지가 없음 | 예외 없이 그 메시지를 첫 판으로 읽는다 |
| 마지막 turn 에 답이 없음 | 그 turn 의 `answers` 가 빈 배열. `isLatestView` 가 참 |
| 답 없던 turn 을 다시 시도함 | 그 turn 의 답 자리 하나, `count` 1 |

`test/browser/regenerate.spec.ts` 를 새로 만든다.

| 경우 | 기대 |
| --- | --- |
| 마지막 답에서 다시 생성을 누른다 | `version-label` 이 `2/2`. 「이전 판」 을 누르면 `1/2` 와 첫 답이 보인다. 그때 다시 생성 단추가 없다 |
| 마지막 질문을 고쳐 보낸다 | 사용자 말풍선 아래 `version-label` 이 `2/2`. 새로 고쳐도 같다 |
| 앞의 사용자 메시지 | 수정 단추가 없다 |
| 원래 글과 같은 글 | 편집칸의 보내기가 `disabled` |
| 답 없는 사용자 메시지에서 「다시 시도」 를 누른다. 대역 입력 `중지 빈 답 검사` 로 보내고 첫 조각 전에 멈춘 뒤 누른다 | 그 말풍선 아래에 새 답이 생기고 `no-answer` 가 사라진다. 사용자 말풍선은 하나뿐이다. 새로 고쳐도 같다 |

## 검증

```bash
# cwd: 저장소 root
node --test 'test/unit/**/*.test.ts'
grep -n '중지와' docs/code-architecture.md
scripts/check-public-safe.sh
```

`grep` 은 아무것도 내지 않아야 한다.

```bash
# cwd: web/
pnpm typecheck
pnpm test:browser regenerate.spec.ts stop.spec.ts chat.spec.ts
pnpm test:browser
```

AGENTS.md 의 「확인」 절 명령을 적힌 순서대로 모두 돌린다. 이 plan 의 마지막 phase 다.

**배포한 뒤 실제 중지를 한 번 왕복시켜 확인한다.** 실제 Hermes 에서 취소된 run 의 사건 스트림이 닫히는지, 상태 조회의 `output` 이 채워지는지 두 가지를 본다.
결과를 `docs/hermes-integration.md` 의 「취소가 아래로 내려가지 않는다」 절 가까이에 측정한 사실로 적는다. 실행 방법은 적지 않는다. 배포와 확인 절차는 `fos-home-infra` 가 소유한다.
스트림이 닫히지 않는다면 10초 뒤 끊는 자리가 실제로 쓰인다는 뜻이므로 그 사실도 함께 적는다.
이 확인은 머지 조건이 아니다. 머지한 뒤 배포할 때 한다. 하지 못했으면 PR 본문에 확인하지 못했다고 적는다.

끝나면 `tasks/plan021-stop-regenerate/index.json` 의 이 phase 를 `completed` 로, plan 의 `status` 를 `completed` 로 바꾼다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `web/src/lib/message-versions.ts` | 신규 |
| `web/src/components/chat/version-switcher.tsx` | 신규 |
| `web/src/components/chat/message-editor.tsx` | 신규 |
| `web/src/components/chat/message-actions.tsx` | 수정 |
| `web/src/components/chat/message-bubble.tsx` | 수정 |
| `web/src/components/chat/message-list.tsx` | 수정 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `docs/code-architecture.md` | 수정 |
| `test/unit/message-versions.test.ts` | 신규 |
| `AGENTS.md` | 수정 |
| `test/browser/regenerate.spec.ts` | 신규 |
