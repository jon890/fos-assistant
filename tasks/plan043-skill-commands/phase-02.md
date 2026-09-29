# Phase 02. 입력창의 `/` 자동완성과 말풍선의 스킬 표시

**Execution profile**: standard

## 목표

입력창 맨 앞에 `/` 를 치면 고른 에이전트의 켜진 스킬 목록이 뜨고, 고르면 `/이름 ` 이 들어간다. 커맨드로 보낸 말풍선에 스킬 표시를 붙이고, 없는 이름은 입력창 아래에 알린다.

**범위 외**: backend(phase 01).

## 컨텍스트

**근거 문서**: `docs/flow.md` 의 「스킬 커맨드로 보낼 때」, 「새 대화 화면」 절, `docs/code-architecture.md` 의 「스킬 커맨드」 절

- `web/src/components/chat/composer.tsx`: `@` 멘션 목록(placeholder 「@로 에이전트를 불러요」)이 이미 있다. 같은 팝업 방식과 키보드 처리를 따른다
- 스킬 목록: `GET /api/agents/{code}/skills`(스킬 계획이 만든 서버 라우트), 타입은 `web/src/lib/skill.ts`
- 말풍선: `web/src/components/chat/message-bubble.tsx`(`turn.role === "USER"`)
- 없는 이름이면 보내기가 400 `SKILL_COMMAND_UNKNOWN` 으로 온다
- 흐름이 붙은 에이전트는 `AgentView` 로 알 수 있다(사진 단추를 숨기는 기준과 같다)

## 의도 메모

- 목록은 에이전트를 고를 때 한 번 읽고, `/` 뒤 글자로 거른다. 켜진 스킬만 보인다
- Tab 이나 Enter 로 고르면 `/이름 ` 을 넣고 목록을 닫는다. Esc 는 닫기만 한다
- 스킬이 없거나 흐름 에이전트면 `/` 목록에 「이 에이전트에는 스킬이 없습니다」(흐름 에이전트는 목록을 띄우지 않는다)
- 없는 이름 문구는 「`/foo` 스킬이 이 에이전트에 없어요」. 입력한 글은 지우지 않는다
- 말풍선 표시는 내용이 `^/[a-z0-9][a-z0-9-]*(\s|$)` 로 시작하면 이름을 작은 칩으로 앞에 보이고 나머지 글을 그린다

## 작업 항목

### 1. 입력창

- `web/src/components/chat/skill-command-menu.tsx` 신규: 목록 팝업
- `web/src/components/chat/composer.tsx`: 맨 앞 `/` 에서 위 팝업을 연다
- `web/src/components/chat-panel.tsx`: 고른 에이전트의 스킬 목록을 읽어 입력창에 넘긴다. 보내기가 `SKILL_COMMAND_UNKNOWN` 이면 입력창 아래 문구

### 2. 말풍선

- `web/src/components/chat/message-bubble.tsx`: 사용자 말풍선의 스킬 칩

### 3. 이 phase 를 검증하는 브라우저 검사

- `test/browser/skill-command.spec.ts` 신규
  - 스킬이 있는 에이전트에서 `/` 를 치면 목록이 뜨고 글자로 걸러지며 Enter 로 `/이름 ` 이 들어간다
  - 보낸 말풍선에 스킬 칩이 보이고 답이 끝난다
  - `/nope 해 줘` 를 보내면 입력창 아래 문구가 보이고 글이 남는다
  - 스킬이 없는 에이전트에서는 「이 에이전트에는 스킬이 없습니다」
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
| `test/browser/skill-command.spec.ts` | 신규 |
| `tasks/plan043-skill-commands/index.json` | 수정 |

마지막 phase 다. 검증이 통과하면 `index.json` 의 `status` 를 `completed` 로 바꿔 이 커밋에 담는다. 그 뒤 PR 의 마지막 커밋으로 `tasks/plan043-skill-commands/` 를 지우고, `docs/code-architecture.md` 「아직 만들지 않은 것」 의 스킬 줄을 뺀다.
