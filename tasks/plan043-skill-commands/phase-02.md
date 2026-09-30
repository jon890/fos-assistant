# Phase 02. 입력창의 `/` 자동완성과 말풍선의 스킬 표시

**Execution profile**: standard

## 목표

입력창 맨 앞에 `/` 를 치면 고른 에이전트의 켜진 스킬 목록이 뜨고, 고르면 `/이름 ` 이 들어간다. 커맨드로 보낸 말풍선에 스킬 표시를 붙이고, 없는 이름은 입력창 아래에 알린다.

**범위 외**: backend(phase 01). 이력 이름 규칙(phase 03).

## 컨텍스트

**근거 문서**: `docs/flow.md` 의 「스킬 커맨드로 보낼 때」, 「새 대화 화면」 절, `docs/code-architecture.md` 의 「스킬 커맨드」 절

- `web/src/components/chat/composer.tsx`: `@` 멘션 목록(placeholder 「@로 에이전트를 불러요」)이 이미 있다. 같은 팝업 방식과 키보드 처리를 따른다. `Composer` 는 첨부 상태를 직접 갖고 `onSend(attachmentIds): Promise<boolean>` 이다. `false` 를 돌려받으면 입력한 글을 남긴다
- `web/src/components/chat-panel.tsx`: `<Composer key={composerGeneration}>` 을 넘긴다. 이 key 는 대화를 바꿀 때만 올린다. `Composer` 가 unmount 될 때 올려 둔 첨부를 서버에서 지우므로 입력창을 다른 자리로 옮기지 않는다
- 스킬 목록: 웹 서버 라우트 `GET /api/agents/{code}/skills` 가 `SkillListView`(`skills[].name`, `skills[].enabled`, `skillsToolsetEnabled`)를 준다. 타입과 `SKILL_NAME_PATTERN`(`^[a-z0-9][a-z0-9-]{0,63}$`)은 `web/src/lib/skill.ts` 에 있다. 목록에는 Hermes 기본 스킬(`note_taking.v2` 처럼 점과 밑줄이 든 이름)도 켜진 채 온다
- 흐름이 붙은 에이전트는 `AgentView.acceptsAttachments === false` 로 안다(사진 단추를 숨기는 기준과 같다)
- 보내기는 `/api/chat/stream` 이 먼저다. 없는 이름은 세 경로로 온다
  - 스트림: HTTP 200 과 `error` 사건(`code: "SKILL_COMMAND_UNKNOWN"`), `started` 전이다. `consumeTurnStream` 의 `onError` 가 받는다
  - 스트림이 HTTP 오류로 끝나면 `!response.ok` 분기
  - 비스트림으로 되돌아가면 `sendWithoutStream` 이 HTTP 400 과 `code` 를 받는다
- 지금 오류 문단(`error` 상태)은 입력창 **위**(1024행 부근)에 있다. 없는 이름 문구는 docs 대로 입력창 **아래**에 따로 둔다
- 말풍선: `web/src/components/chat/message-bubble.tsx`(`turn.role === "USER"`)
- 브라우저 검사는 `test/browser/fixtures.ts` 와 같은 가짜 Hermes 를 쓴다. 가짜 Hermes 는 모든 profile 에 기본 스킬 둘을 켠 채 주므로 스킬 없는 에이전트는 `page.route("**/api/agents/*/skills", ...)` 로 빈 목록이나 `skillsToolsetEnabled: false` 를 돌려줘 만든다. 스킬을 올리는 방법은 `test/browser/skills.spec.ts` 가 쓰는 방식을 따른다

## 의도 메모

- 목록은 에이전트를 고를 때 한 번 읽고, `/` 뒤 글자로 거른다. 켜진 스킬 가운데 `SKILL_NAME_PATTERN` 에 맞는 이름만 보인다. 점이나 밑줄이 든 이름은 backend 가 커맨드로 보지 않으므로 목록에서 뺀다
- Tab 이나 Enter 로 고르면 `/이름 ` 을 넣고 목록을 닫는다. Esc 는 닫기만 한다
- 흐름 에이전트(`acceptsAttachments === false`)에서는 `/` 목록을 띄우지 않는다
- 스킬 목록이 비었거나(거른 뒤 0개) `skillsToolsetEnabled === false` 이면 `/` 목록 자리에 「이 에이전트에는 스킬이 없어요」 를 보인다
- 없는 이름 문구는 「`/foo` 스킬이 이 에이전트에 없어요」 다. `foo` 는 보낸 글의 이름이다. 입력창 아래에 보이고 입력한 글은 지우지 않는다. 다음 보내기를 시작하면 지운다. 위 세 경로 모두 같은 문구다
- 말풍선 표시는 사용자 말풍선 내용이 `^/[a-z0-9][a-z0-9-]*(\s|$)` 로 시작하면 이름을 작은 칩으로 앞에 보이고 나머지 글을 그린다. 흐름 에이전트의 대화는 커맨드를 해석하지 않으므로 칩을 붙이지 않는다
- `docs/flow.md` 「스킬 커맨드로 보낼 때」 의 갈리는 지점 표에서 「이 에이전트에는 스킬이 없습니다」 를 「이 에이전트에는 스킬이 없어요」 로 고친다. `web/AGENTS.md` 의 화면 문구 해요체 규칙을 따르기 위해서다. 같은 표의 다른 화면 문구도 해요체인지 본다

## 작업 항목

### 1. 입력창

- `web/src/components/chat/skill-command-menu.tsx` 신규: 목록 팝업과 빈 문구
- `web/src/components/chat/composer.tsx`: 맨 앞 `/` 에서 위 팝업을 연다
- `web/src/components/chat-panel.tsx`: 고른 에이전트의 스킬 목록을 읽어 입력창에 넘긴다. 흐름 에이전트면 넘기지 않는다. 보내기가 위 세 경로로 `SKILL_COMMAND_UNKNOWN` 을 받으면 입력창 아래 문구

### 2. 말풍선

- `web/src/components/chat/message-bubble.tsx`: 사용자 말풍선의 스킬 칩

### 3. docs

- `docs/flow.md`: 위 의도 메모의 문구 수정

### 4. 이 phase 를 검증하는 브라우저 검사

- `test/browser/skill-command.spec.ts` 신규
  - 스킬을 올린 에이전트에서 `/` 를 치면 목록이 뜨고 글자로 걸러지며 Enter 로 `/이름 ` 이 들어간다. Esc 는 목록만 닫는다
  - 목록에 점이나 밑줄이 든 기본 스킬 이름(`note_taking.v2`)이 없다
  - 보낸 말풍선에 스킬 칩이 보이고 답이 끝난다
  - `/nope 해 줘` 를 보내면 입력창 아래에 「`/nope` 스킬이 이 에이전트에 없어요」 가 보이고 입력창의 글이 남는다
  - `page.route` 로 빈 목록을 준 에이전트와 `skillsToolsetEnabled: false` 를 준 에이전트에서 「이 에이전트에는 스킬이 없어요」 가 보인다
  - `/usr/bin 은 뭐야` 는 칩 없이 보내진다

## 검증

```bash
# cwd: 저장소 root
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/components/chat/skill-command-menu.tsx` | 신규 |
| `web/src/components/chat/composer.tsx` | 수정 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `web/src/components/chat/message-bubble.tsx` | 수정 |
| `docs/flow.md` | 수정 |
| `test/browser/skill-command.spec.ts` | 신규 |
