# Phase 02. 답 아래 결과물을 누르면 옆 패널에 스크립트 없이 띄운다

**Execution profile**: standard

## 목표

답의 `artifacts` 를 답 아래에 파일 이름으로 보이고, 누르면 작업 과정 패널 자리에 그 HTML 을 iframe 으로 띄운다.
iframe 은 스크립트를 막고, 서버 라우트는 Control Plane 이 준 보안 머리글을 그대로 옮긴다. 이 plan 의 마지막 phase 다.

**범위 외**: Control Plane(phase-01 이 끝냈다). 결과물을 고치거나 지우는 화면은 만들지 않는다.

## 컨텍스트

**근거 문서**:
- `docs/adr/ADR-027-에이전트가-만든-html-은-대화별-폴더에-두고-스크립트-없이-보인다.md`
- `docs/flow.md` 「결과물 파일을 볼 때」 의 흐름도와 상황 표
- `docs/code-architecture.md` 「결과물 파일」 의 「경로」, 머리글 표, 「메시지 한 줄의 `artifacts`」

phase-01 이 `GET /api/v1/chat/conversations/{conversationId}/files/{*path}` 와 메시지의 `artifacts: [{ path, byteSize, deleted }]` 를 더했다.
`test/e2e/fake-hermes.ts` 는 `결과물 파일 검사` 를 받으면 결과물 폴더에 `초안/index.html` 과 `초안/photo.png` 를 쓴다.

계획을 쓸 때의 코드다. 시작할 때 다시 연다.

| 위치 | 지금 모양 |
| --- | --- |
| `web/src/app/api/chat/conversations/[conversationId]/attachments/[attachmentId]/route.ts` | `forwardControlPlane` 으로 받은 본문을 `new Response(upstream.body, ...)` 로 흘려보낸다. `Content-Type` 과 `Cache-Control` 만 옮긴다 |
| `web/src/lib/conversation-id.ts` | 대화 공개 식별자 계획이 더한 `isConversationId` |
| `web/src/components/chat/message-bubble.tsx` | `Turn` 타입에 `attachments?`, `activity?`. `AttachmentGallery` 가 답 본문 아래에 사진을 그린다 |
| `web/src/components/chat/activity/activity-panel.tsx` | `useMediaQuery("(min-width: 1024px)")` 로 넓으면 `<aside>`, 좁으면 `Sheet`(`side="right"`). 머리에 제목과 `TooltipButton` 닫기. `onOpenAutoFocus` 에서 `focusWithoutTooltip` |
| `web/src/components/chat-panel.tsx` | `panelTarget`(`ActivityPanelTarget \| null`) 상태 하나가 옆 패널을 연다. 대화를 바꾸거나 새 대화를 시작할 때 `setPanelTarget(null)`. turn 이 끝날 때 `settleFinishedActivity` 가 함수형 갱신으로 `live` 패널을 바꾸고, 렌더에서 `live` 대상을 바꿔 끼우는 자리가 있다 |

## 의도 메모

- **옆 패널은 한 번에 하나다.** `chat-panel.tsx` 의 패널 상태를 작업 과정과 결과물을 함께 담는 합 타입으로 넓힌다. 둘을 따로 두면 둘이 같이 열린다
- **iframe `sandbox` 값은 `allow-same-origin allow-popups allow-popups-to-escape-sandbox` 다.** `allow-scripts` 를 넣지 않는다. 까닭은 ADR-027 이 갖는다
- 결과물 패널은 작업 과정 패널보다 넓다. 넓은 화면에서 `w-[min(48rem,50vw)]` 쯤으로 둔다. 좁은 화면은 전체 폭 `Sheet` 다
- 파일 이름은 `path` 의 마지막 조각이다. 같은 이름이 둘이면 앞 폴더를 붙인다
- 서버 라우트의 머리글 옮기기는 목록으로 둔다. 목록 밖의 머리글은 옮기지 않는다

## Blocked 조건

- phase-01 이 끝나지 않았다. `grep -rn 'conversations/{conversationId}/files' backend/src/main/java` 가 아무것도 내지 않는다 → `PHASE_BLOCKED: phase-01 이 끝나지 않았다`

## 작업 항목

### 1. 서버 라우트 `web/src/app/api/chat/conversations/[conversationId]/files/[...path]/route.ts`

- `GET` 만 둔다. `isConversationId` 가 아니면 400 `VALIDATION_FAILED`
- 경로 조각을 `encodeURIComponent` 로 하나씩 싸서 `/` 로 잇고 `forwardControlPlane` 으로 넘긴다. 조각에 `..` 이나 빈 조각이 있으면 400. 폴더 밖 판정은 Control Plane 이 한 번 더 한다
- 성공이면 본문을 흘려보내고 `Content-Type`, `Content-Security-Policy`, `X-Content-Type-Options`, `Cache-Control` 을 옮긴다. 실패는 첨부 라우트처럼 JSON 으로 옮긴다

### 2. 메시지 타입과 파일 목록

- `Turn` 에 `artifacts?: { path: string; byteSize: number; deleted: boolean }[]`
- 답 본문 아래(사진 아래)에 파일마다 한 줄. 파일 아이콘(lucide `FileText`)과 이름. 누르면 `onOpenArtifact(turn.id, path)`
- `deleted` 면 흐리게 두고 누르지 못한다. 옆에 「보관 기간이 지나 볼 수 없습니다」
- 각 줄에 `data-testid="message-artifact"`

### 3. `web/src/components/chat/artifact/artifact-panel.tsx`

- `activity-panel.tsx` 와 같은 넓은 화면, 좁은 화면 분기와 머리 모양. 제목은 파일 이름, 머리에 「새 탭으로 열기」(`target="_blank"` 링크)와 닫기
- 본문은 `<iframe title={파일 이름} sandbox="allow-same-origin allow-popups allow-popups-to-escape-sandbox" src={...}>`. `data-testid="artifact-frame"`
- iframe 주소를 먼저 `fetch(..., { method: "GET", cache: "no-store" })` 로 확인하지 않는다. 두 번 받게 된다. 410 은 목록의 `deleted` 가 먼저 막는다. 패널을 연 사이 지워진 경우는 iframe 안에 서버의 오류 응답이 그대로 보인다. `docs/flow.md` 의 상황 표가 그렇게 적는다
- 초점이 iframe 안에 있으면 Esc 가 부모 창까지 오지 않는다. 닫기 단추로 닫는다
- 좁은 화면의 초점 처리는 작업 과정 패널을 따른다

### 4. `chat-panel.tsx`

- 옆 패널 상태를 `{ kind: "activity"; target: ActivityPanelTarget } | { kind: "artifact"; messageId: number; path: string } | null` 로 넓힌다
- 작업 과정을 열던 곳은 `kind: "activity"` 로, 결과물 줄은 `kind: "artifact"` 로 연다. 지금 `setPanelTarget(null)` 하는 곳은 그대로 둘 다 닫는다
- turn 이 끝날 때 작업 과정 패널을 바꾸는 갱신(`settleFinishedActivity` 와 렌더의 `live` 바꿔 끼우기)은 `kind: "activity"` 일 때만 건드린다. **결과물 패널은 turn 이 끝나도 그대로 둔다**
- `messageId` 는 `Turn.id` 가 `number | string` 이므로 `Turn.id` 의 타입을 그대로 쓴다. 결과물 줄의 콜백은 `onOpenArtifact(messageId, path)` 다

### 5. 이 phase 를 검증하는 테스트

`test/browser/` 에 `artifact.spec.ts`:

| 경우 | 기대 |
| --- | --- |
| `결과물 파일 검사` 를 보낸다 | 답 아래 `message-artifact` 가 `index.html` 하나 |
| 그 줄을 누른다 | `artifact-frame` 이 보이고 `sandbox` 에 `allow-scripts` 가 없다 |
| iframe 안 | `img` 의 `naturalWidth` 가 0 보다 크다. iframe 문서의 `document.title` 이 `초안` 이다. 대역 HTML 의 스크립트가 `스크립트가 돌았다` 로 바꾸려 한다 |
| iframe 주소를 직접 요청한다 | 응답 머리글에 `Content-Security-Policy` 의 `sandbox` 와 `nosniff` |
| 작업 과정 패널을 연다 | 결과물 패널이 닫힌다 |
| 다른 대화로 옮긴다 | 결과물 패널이 닫힌다 |

새 spec 은 모바일과 데스크톱 폭에서 모두 통과해야 한다.

## 검증

```bash
# cwd: 저장소 root
grep -rn 'allow-scripts' web/src
```

아무것도 내지 않아야 한다.

AGENTS.md 의 「확인」 절 명령을 적힌 순서대로 모두 돌린다. 이 plan 의 마지막 phase 다.
`pnpm build` 는 `web/AGENTS.md` 의 자리표시자 환경 변수가 필요하다.

끝나면 `tasks/plan027-html-artifact-preview/index.json` 의 이 phase 를 `completed` 로, plan 의 `status` 를 `completed` 로 바꾼다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `web/src/app/api/chat/conversations/[conversationId]/files/[...path]/route.ts` | 신규 |
| `web/src/components/chat/artifact/artifact-panel.tsx` | 신규 |
| `web/src/components/chat/message-bubble.tsx` | 수정 |
| `web/src/components/chat/message-list.tsx` | `onOpenArtifact` 를 넘기면 수정 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `test/browser/artifact.spec.ts` | 신규 |
