# Phase 03. 입력창에서 사진을 고르고 함께 보낸다

**Execution profile**: standard

## 목표

대화 화면의 입력창에서 사진을 골라 올리고, 텍스트와 함께 보낸다.
지난 대화를 열면 그때 올린 사진이 보이고, 보관 기간이 지난 것은 그 사실을 알린다.

**범위 외**:
사진을 고치거나 자르는 것은 하지 않는다. 올린 그대로 쓴다.
사진만 보내는 길을 만들지 않는다. 언제나 텍스트와 함께 간다.

## 컨텍스트

phase-01 이 받아 두는 경로를, phase-02 가 에이전트에게 알리는 길을 만들었다.
이 phase 가 화면을 붙인다.

**근거 문서**:
`docs/code-architecture.md` 의 「사진 첨부」 절과 「web 화면 구조」 절,
`docs/flow.md` 의 「사진을 올려 보낼 때」 절,
`docs/adr/ADR-020-사진은-공유-디렉터리에-두고-에이전트가-파일로-읽는다.md`.

### 고르면 그 자리에서 올라간다

보내기를 누를 때 한꺼번에 올리지 않는다.
열 장에 33MB 였던 실측이 있고, 그것을 보내는 순간에 올리면 그동안 화면이 멈춘다.

**고르면 올라가고 미리보기가 입력창 위에 붙는다.**
보내기를 누를 때는 그 첨부 번호들만 간다.

## 의도 메모

- 사진만 보내는 길을 두지 않았다. 에이전트가 무엇을 하라는지 모르는 상태를 만들지 않는다.
- 올리는 중에 보내기를 막는다. 아직 번호가 없는 사진이 빠진 채 나간다.
- 미리보기를 원본으로 그리지 않는다. 열 장이 33MB 이고 그것을 한 화면에 그리면 느리다.

## 작업 항목

### 1. 서버 라우트

**`web/src/app/api/chat/conversations/[conversationId]/` 가 이미 있다.** 그 아래에 둔다.
경로 변수 이름이 `conversationId` 다. `id` 가 아니다.
브라우저가 Control Plane 을 직접 부르지 않는다. `web/AGENTS.md` 가 그렇게 정한다.

| 파일 | 부르는 것 |
| --- | --- |
| `attachments/route.ts` | `POST /api/v1/chat/conversations/{id}/attachments` |
| `attachments/[attachmentId]/route.ts` | `GET` 과 `DELETE` |

`callControlPlane` 은 쓸 수 없다. 본문을 언제나 `JSON.stringify` 하고 응답을 `JSON.parse` 한다.

**`web/src/lib/control-plane.ts` 에 `forwardControlPlane` 을 더한다.**
`requestControlPlane` 과 같이 세션에서 토큰을 만들고, 본문과 `Content-Type` 을 받은 그대로 넘긴다.

```ts
forwardControlPlane(path: string, init: {
  method: string;
  body?: ReadableStream<Uint8Array> | null;
  contentType?: string | null;
}): Promise<ControlPlaneResponse>
```

본문이 스트림이면 Node 의 `fetch` 에 `duplex: "half"` 를 함께 준다. 없으면 요청이 거절된다.

**본보기는 `web/src/app/api/chat/stream/route.ts` 다.**
받은 응답 본문을 그대로 넘기는 방식과 실패 응답을 옮기는 방식이 거기 있다.
경로 변수를 받는 방식은 `web/src/app/api/chat/conversations/[conversationId]/messages/route.ts` 를 따른다.

`POST` 는 multipart 를 그대로 흘려보낸다. 본문을 읽어 다시 만들지 마라.
`GET` 은 이미지 본문을 그대로 흘려보내고 `Content-Type` 과 `Cache-Control` 을 함께 넘긴다.

### 1-1. 메시지를 보내는 두 라우트가 첨부 번호를 넘긴다

`web/src/app/api/chat/route.ts` 와 `web/src/app/api/chat/stream/route.ts` 는 본문을
`conversationId`, `text`, `agentCode` 셋으로 다시 만들어 보낸다. 그대로 두면 첨부 번호가 Control Plane 에 닿지 않는다.
둘 다 `attachmentIds` 를 받아 넘긴다. 없으면 빈 목록을 보낸다.

### 2. 입력창에 사진 단추를 더한다

`web/src/components/chat/composer.tsx` 다.
지금 `value` 와 `disabled` 와 `onChange` 와 `onSend` 넷을 받는다.

더할 것이다.

| 무엇 | 어떻게 |
| --- | --- |
| 사진 단추 | 보내기 단추 옆에 둔다. 누르면 파일 고르기가 열린다 |
| 받는 형식 | `image/jpeg`, `image/png`, `image/gif`, `image/webp` |
| 여러 장 | 한 번에 고를 수 있게 한다 |
| 미리보기 | 입력창 위에 가로로 늘어놓는다. 각각에 지우는 단추를 둔다 |
| 올리는 중 | 그 자리에 도는 표시를 두고 **보내기를 잠근다** |
| 올리기 실패 | 그 미리보기에 `describeError` 문장을 보이고 지우는 단추를 둔다. 지울 때까지 보내기를 잠근다 |
| 새 대화 | 대화 번호가 없으면 사진 단추를 잠그고 「첫 메시지를 보낸 뒤 사진을 올릴 수 있습니다」 를 보인다 |
| 흐름이 붙은 에이전트 | 사진 단추를 두지 않는다. `AgentView.acceptsAttachments` 가 거짓이면 그렇다 |

상한은 한 번에 10장이고 한 장 10MB 다.
**넘게 고르면 넘는 것을 올리지 않고 그 사실을 알린다.** 조용히 버리지 않는다.

**미리보기를 원본으로 그리지 않는다.** 고른 파일을 `createImageBitmap` 으로 읽어
긴 변 192px 의 canvas 에 줄여 그리고, 그 `toBlob` 결과의 object URL 을 쓴다.
미리보기를 지우거나 보낸 뒤에는 `URL.revokeObjectURL` 로 놓는다.

`chat-panel.tsx` 가 첨부 번호 목록을 갖고, 보낼 때 본문에 `attachmentIds` 로 싣는다.
보낸 뒤 미리보기를 비운다.

### 3. 지난 대화의 사진을 보인다

`web/src/components/chat/message-bubble.tsx` 가 메시지 하나를 그린다.
그 메시지에 첨부가 있으면 본문 아래에 사진을 보인다.

| 상태 | 무엇을 보이나 |
| --- | --- |
| `visible` 이 참 | 사진. 누르면 큰 화면으로 본다 |
| `visible` 이 거짓 | 자리를 남기고 `describeError("ATTACHMENT_GONE", ...)` 의 문장 |

`Turn` 타입에 `attachments` 칸을 더한다. 대화 이력의 `MessageView.attachments` 를 그대로 받는다.
사진 주소는 `/api/chat/conversations/{대화 번호}/attachments/{첨부 번호}` 다.
`Turn` 에 대화 번호가 없으므로 `MessageBubble` 이 대화 번호를 prop 으로 받는다.

말풍선 안의 사진은 `loading="lazy"` 로 두고 크기를 클래스로 제한한다.

**거짓일 때 그 자리를 없애지 마라.** 무엇이 있었는지가 남아야
지난 대화를 읽는 사람이 에이전트가 무엇을 보고 답했는지 안다.

### 4. 오류 문장

`web/src/components/error-message.ts` 의 `MESSAGES` 에 더한다.

| 코드 | 문장 |
| --- | --- |
| `ATTACHMENT_GONE` | 보관 기간이 지나 볼 수 없습니다. |

화면 안에 문장을 따로 적지 않는다. 두 벌이 되면 한쪽만 고쳐진다.
말풍선도 `visible` 이 거짓일 때 이 문장을 `describeError` 로 가져온다.
문장은 `docs/` 의 ADR-020 과 `flow.md` 가 적은 그대로다.

길이와 형식과 장수가 상한을 넘는 것은 모두 `VALIDATION_FAILED` 로 온다.
**그 코드 하나로는 무엇이 잘못됐는지 알 수 없으므로 화면이 고르기 전에 막는다.**

### 5. 이 phase 를 검증하는 테스트

`test/browser` 에 화면 검사를 더한다.
기존 검사 파일의 짜임을 따른다. `playwright.config.ts` 의 projects 가 `mobile` 과 `desktop`
두 폭으로 돌리므로 검사 파일이 폭을 따로 지정하지 않는다.

| 무엇 | 기대 |
| --- | --- |
| 사진을 고른다 | 미리보기가 입력창 위에 붙는다 |
| 올리는 중이다 | 보내기 단추가 잠긴다 |
| 미리보기의 지우는 단추 | 그 사진만 빠진다 |
| 사진을 붙여 보낸다 | 보낸 메시지 아래에 그 사진이 보인다 |
| 11장을 고른다 | 10장만 올라가고 넘은 것을 알린다 |
| 보관 기간이 지난 첨부가 달린 대화 | 「보관 기간이 지나 볼 수 없습니다」 가 보이고 그 자리가 남는다 |
| 이미지가 아닌 파일 | 고르기에서 걸린다 |
| 새 대화 | 사진 단추가 잠겨 있다 |
| 흐름이 붙은 에이전트의 대화 | 사진 단추가 없다. `FLOW_AGENT_CODE` 로 연다 |
| 10MB 를 넘는 파일 | 올라가지 않고 그 사실을 알린다 |

**보관 기간이 지난 첨부는 운영 API 로 만든다.** 사진을 붙여 보낸 뒤 검사가
`page.request` 로 웹의 `DELETE` 라우트를 불러 지우고 다시 연다. `visible` 이 거짓인 상태가 만료와 같다.
운영 코드에 시험용 문을 두지 않고, `src/test` 의 지원 컨트롤러도 더하지 않는다.

파일을 고르는 것은 Playwright 의 파일 고르기 기능을 쓴다.
**실제 이미지 파일을 검사 안에서 만든다.** 저장소에 이미지를 넣지 마라.
작은 PNG 를 바이트로 만들어 쓰면 된다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
scripts/check-public-safe.sh
```

`pnpm build` 가 요구하는 환경 변수는 `web/AGENTS.md` 의 「검사」 절이 갖는다.
빠뜨리면 `Failed to collect page data` 로 끝난다.

아래가 아무것도 내지 않아야 한다. 인라인 스타일을 쓰지 않는다.

```bash
# cwd: 저장소 root
grep -rn 'style={{' web/src/
```

## Critical Files

| 파일 | 변경 |
|---|---|
| `web/src/app/api/chat/conversations/[conversationId]/attachments/route.ts` | 신규 |
| `web/src/app/api/chat/conversations/[conversationId]/attachments/[attachmentId]/route.ts` | 신규 |
| `web/src/components/chat/composer.tsx` | 수정 |
| `web/src/components/chat/message-bubble.tsx` | 수정 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `web/src/components/error-message.ts` | 수정 |
| `web/src/lib/control-plane.ts` | 수정. `forwardControlPlane` |
| `web/src/lib/agent.ts` | 수정. `AgentView` 타입에 `acceptsAttachments` |
| `web/src/app/api/chat/route.ts` | 수정. `attachmentIds` |
| `web/src/app/api/chat/stream/route.ts` | 수정. `attachmentIds` |
| `test/browser/` | 추가 |

## 끝낸 뒤

`tasks/plan018-chat-photo-upload/index.json` 의 이 phase 를 `completed` 로,
plan 의 `status` 를 `completed` 로 바꾼다.

## 배포한 뒤 실제로 한 번 돌린다

**테스트가 모두 통과해도 운영에서 안 될 수 있다.**
Hermes 대역이 실제와 다른 응답을 내도록 쓰여 있으면 테스트는 통과한다.
실제로 그렇게 스트리밍이 통째로 안 된 채 배포된 적이 있다.

선행이 하나 있다. 비공개 저장소 `fos-home-infra` 가 소유한다.

- 호스트의 사진 디렉터리를 Control Plane 컨테이너에 쓰기로, Hermes 컨테이너에 읽기로 붙이는 것
- Control Plane 쪽 경로와 에이전트 쪽 경로를 각각 설정으로 주는 것

**배포 요청에 그것을 함께 적는다.** 없으면 기동이 실패한다. 두 설정 모두 비면 멈추게 되어 있다.

배포한 뒤 아래를 확인한다.

- 사진을 올리면 미리보기가 붙는다
- 보내면 에이전트가 그 사진의 내용을 말한다. **이것이 진짜 판정이다**
- 그 대화를 다시 열면 사진이 그대로 보인다
- 홈서버의 그 디렉터리에 파일이 실제로 있다
