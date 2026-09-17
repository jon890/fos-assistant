# Phase 02. 화면에서 대화를 이어간다

**Execution profile**: standard

## 목표

대화 목록을 보이고 고른 대화의 메시지를 불러와, 브라우저를 새로 고쳐도 이어서 말할 수 있게 한다.
보낸 사람 이름을 함께 보인다.

지금은 대화가 React 상태로만 남아 있어 새로 고치면 사라진다.
데이터는 이미 데이터베이스에 있고 화면이 읽지 않는 것이 문제다.

**범위 외**

- 대화 삭제와 이름 바꾸기는 하지 않는다.
- 토큰 스트리밍은 다른 plan 이 한다.
- 여러 사람이 같은 대화를 읽고 쓰는 것은 하지 않는다.

## 컨텍스트

웹은 Next.js 16 과 React 19 를 쓰고 Tailwind v4 로 꾸민다.
브라우저는 Control Plane 을 직접 부르지 않는다.
Next.js 서버 라우트가 세션에서 메일 주소를 꺼내 짧은 수명의 토큰을 만들어 대신 부른다.

따를 패턴을 경로로 짚는다.

| 무엇 | 경로 |
| --- | --- |
| 대화 화면 | `web/src/components/chat-panel.tsx` |
| 서버 라우트 | `web/src/app/api/chat/route.ts` |
| Control Plane 호출 | `web/src/lib/control-plane.ts` |
| 오류 문구 | `web/src/components/error-message.ts` |
| 서버에서 직접 읽는 화면 | `web/src/app/usage/page.tsx` |

백엔드는 이미 아래를 준다. 새로 만들지 않는다.

| 경로 | 주는 것 |
| --- | --- |
| `GET /api/v1/chat/conversations` | `id`, `title`, `workspaceCode`, `updatedAt` |
| `GET /api/v1/chat/conversations/{id}/messages` | `id`, `role`, `content`, `senderName`, `executionId`, `createdAt` |

`senderName` 은 phase-01 이 더한다.

**근거 문서**: `docs/flow.md` 의 「대화 이력」 절과 「갈리는 지점」 표

## 의도 메모

- 대화 목록을 서버 컴포넌트에서 미리 읽지 않는다.
  메시지를 보낸 뒤 목록을 다시 읽어야 하므로 클라이언트에서 다루는 편이 단순하다.
- 목록에 쪽 나눔을 넣지 않는다. 가족 규모에서 대화 수가 많지 않다.
- 보낸 사람 이름이 없으면 `나` 로 되돌리지 않는다. 이름 자리를 비운다.
  `나` 는 여러 사람이 보는 화면에서 누구인지 알려주지 못한다.
- 비서 줄의 이름은 화면이 정한다. 서버가 주지 않는다.

## 작업 항목

### 1. 서버 라우트를 더한다

`web/src/app/api/chat/conversations/route.ts` 를 만든다.

- `GET` 이 `callControlPlane` 으로 `/api/v1/chat/conversations` 를 부른다.
- `web/src/app/api/usage/route.ts` 와 같은 형태를 따른다. 오류는 코드와 문구를 그대로 넘긴다.

`web/src/app/api/chat/conversations/[conversationId]/messages/route.ts` 를 만든다.

- `GET` 이 `/api/v1/chat/conversations/{id}/messages` 를 부른다.
- 경로 인자는 숫자만 받는다. 숫자가 아니면 부르지 않고 400 으로 답한다.

### 2. 대화 목록을 화면에 더한다

`web/src/components/conversation-list.tsx` 를 만든다.

- 대화 배열과 지금 고른 번호, 고를 때 부르는 함수를 받는다.
- 제목과 마지막 시각을 보인다. 작업 영역이 있으면 함께 보인다.
- 고른 대화를 눈에 띄게 한다.
- 대화가 없으면 빈 상태 문구를 보인다.
- `새 대화` 버튼을 둔다.

### 3. `chat-panel.tsx` 가 이력을 읽는다

- 처음 뜰 때 대화 목록을 읽는다. 있으면 가장 최근 것을 고르고 그 메시지를 읽는다.
- 대화를 고르면 그 메시지를 읽어 화면을 바꾼다.
- `새 대화` 는 고른 번호와 메시지를 비운다. 이때 서버를 부르지 않는다.
  다음 메시지를 보낼 때 서버가 대화를 만든다.
- 메시지를 보낸 뒤 목록을 다시 읽는다. 제목과 순서가 바뀐다.
- **보내다 실패하면 쓴 문장을 입력창에 되돌린다.** 지금은 보내는 순간 비워서 실패하면 사라진다.
- 같은 대화에 두 번 눌러도 앞의 요청이 끝나기 전에는 두 번째를 보내지 않는다.
- 메시지를 읽지 못하면 그 대화만 오류를 보이고 목록은 남긴다.

### 4. 보낸 사람 이름을 보인다

- `USER` 줄은 `senderName` 을 보인다. 없으면 이름 자리를 비운다.
- `ASSISTANT` 줄은 `비서` 로 보인다.
- `나` 라는 문구를 쓰지 않는다.

### 5. 오류 문구를 더한다

`web/src/components/error-message.ts` 에 `CONVERSATION_NOT_FOUND` 를 더한다.
남의 대화 번호를 넣었을 때 목록으로 되돌리도록 안내한다.

### 6. 이 phase 를 검증하는 e2e 테스트

`test/e2e/scenarios/` 아래에 더한다.
기존 시나리오 파일의 형태를 따르고 `test/e2e/run.ts` 에 등록한다.

- 대화를 두 번 만들면 목록이 둘을 최근 순으로 준다.
- 대화의 메시지를 읽으면 보낸 순서대로 오고 사용자 줄에 보낸 사람 이름이 있다.
- 다른 사용자가 그 대화의 메시지를 읽으면 `CONVERSATION_NOT_FOUND` 다.

## 검증

```bash
# cwd: 저장소 root
cd web && pnpm typecheck
cd web && pnpm build
```

빌드는 자리표시자 환경 변수가 필요하다. `web/Dockerfile` 이 쓰는 것과 같다.

```bash
# cwd: 저장소 root
node test/e2e/run.ts
```

새 시나리오를 포함해 전부 통과해야 한다.

```bash
# cwd: 저장소 root
grep -rn '"나"' web/src/ && echo "실패: 나 라는 문구가 남아 있다" || echo "통과"
```

## Critical Files

| 파일 | 변경 |
| --- | --- |
| `web/src/app/api/chat/conversations/route.ts` | 신규 |
| `web/src/app/api/chat/conversations/[conversationId]/messages/route.ts` | 신규 |
| `web/src/components/conversation-list.tsx` | 신규 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `web/src/components/error-message.ts` | 수정 |
| `test/e2e/scenarios/` | 신규 |
| `test/e2e/run.ts` | 수정 |

## 끝낸 뒤

`tasks/plan002-conversation-history/index.json` 의 이 phase 를 `completed` 로 바꾸고
plan 의 `status` 를 `completed` 로 바꾼다.
