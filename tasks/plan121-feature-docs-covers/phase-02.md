# Phase 02. 기능 파일 15개에 covers 줄을 적고 저장소 테스트로 지킨다

**Execution profile**: standard

## 목표

`docs/features/` 의 기능 파일 15개 머리에 그 기능을 다루는 코드 경로를 `covers:` 줄로 적는다.
모든 기능 파일에 그 줄이 있고 경로가 저장소에 있는지 저장소 테스트가 확인하게 한다.
ADR 의 「covers 는 뒤 PR 이 넣는다」 문장을 지금 상태로 고친다.

**범위 외**: 검사 스크립트와 CI 단계(phase 01). 기능 파일의 본문.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261009-feature-docs.md` 의 결정 4

- phase 01 이 `scripts/check-feature-covers.mjs` 에 `parseCovers`, `coveredBy`, `staleFeatures` 를 만들었다. 줄의 모양은 phase 01 의 「`covers:` 줄의 모양」 과 같다. 제목(`#`)과 첫 `##` 사이에 둔다.

```markdown
covers: `backend/src/main/java/com/bifos/assistant/connector/`, `web/src/components/connector/`, `hermes/connectors/`
```

- 후보 경로(실제로 있는 디렉터리다. 고르기 전에 그 안을 열어 기능과 맞는지 본다)
  - backend 패키지 `backend/src/main/java/com/bifos/assistant/<패키지>/`: agent, attention, browser, chat, connector, context, crypto, feedback, followup, hermes, mcp, memory, model, notification, orchestration, people, proactive, shared, skill, task, usage, user, workspace
  - web 컴포넌트 `web/src/components/<디렉터리>/`: admin, agent, browser, chat, connector, execution, memory, notification, now, shell, task, usage, workspace
  - web 화면 `web/src/app/<디렉터리>/`: admin, agents, api, browser, c, chat, connections, executions, files, memory, notifications, now, tasks, tool-requests, usage
  - web 화면 중 로그인: `web/src/app/signin/`, `web/src/app/signout/`
  - web 의 기능별 API 클라이언트와 도우미: `web/src/lib/` 의 파일(`chat-api.ts`, `memory-api.ts` 등). 디렉터리 전체가 아니라 파일로 적는다
  - hermes: `hermes/plugins/dashboard-profile-api/`, `hermes/plugins/fos-ctx/`, `hermes/connectors/`
- 계층형 패키지: backend 의 `chat/` 같은 패키지는 하위 패키지가 계층(`application`, `domain`, `infra`, `presentation`)이라 기능으로 나눌 수 없다. `chat/` 에는 첨부(`Artifact*`, `*Attachment*`), 모델(`ModelTier*`, `ModelOption*`, `ModelHidden*`), 기억 사용(`MemoryUse*`, `MemoryCapture*`), 예약 실행(`ScheduledTurn*`), 첫 반응 시간(`*Latency*`) 파일이 섞여 있다. `web/src/components/chat/` 도 같다(`model-picker.tsx`, `memory-use-list.tsx`, `approval-*.tsx`, `use-composer-attachments.ts`, `attention-line.tsx`).

## 의도 메모

- covers 는 「이 경로를 바꾸면 이 문서를 볼 차례」 라는 뜻이다. 그 기능의 흐름과 갈리는 지점을 실제로 가진 코드만 넣는다. `shared/`, `web/src/components/ui/` 처럼 모든 기능이 쓰는 곳은 넣지 않는다. 넣으면 모든 PR 이 경고를 받아 경고가 무시된다.
- **패키지가 한 기능보다 넓으면 파일 이름 glob 으로 나눈다.** 예: attachment 는 `backend/src/main/java/com/bifos/assistant/chat/**/Artifact*`, `backend/src/main/java/com/bifos/assistant/chat/**/*Attachment*` 를 갖는다. chat 기능은 `chat/` 디렉터리 전체를 갖고, 다른 기능의 glob 과 겹쳐도 된다(그 파일을 바꾼 PR 은 두 문서 중 하나를 고치면 각자 판정한다).
- 하나의 기능에도 들지 않는 공용 클래스는 넣지 않는다.
- 한 기능의 covers 는 보통 열 개 안쪽이다. 넘으면 회신에 까닭을 적는다.

## 작업 항목

### 1. 기능 파일 15개의 covers 줄

`docs/features/` 의 chat, execution, model-usage, attachment, workspace, agent-skill, mcp, users, user-browser, connector, connector-policy, memory, attention, schedule, proactive 파일마다 제목 아래 한 문장 뒤에 빈 줄을 두고 `covers:` 줄 하나를 더한다.
파일마다 본문이 가리키는 클래스와 화면, 그 기능의 테스트가 있는 패키지를 보고 경로를 고른다. 파일이 500줄을 넘지 않게 한다.
`docs/features/agent-skill.md` 는 지금 498줄이라 빈 줄과 covers 줄로 정확히 500줄이 된다. covers 는 반드시 한 줄로 적고 다른 줄을 더하지 않는다.

### 2. `docs/adr/ADR-20261009-feature-docs.md`

- 결정 4 의 「`covers:` 줄과 그 검사는 이 ADR 을 받은 뒤의 PR 이 넣는다. 처음 옮길 때는 아직 없다.」 를 지우고, 검사가 `scripts/check-feature-covers.mjs` 이고 CI 의 unit job 에서 PR 에만 돈다는 한 문장으로 바꾼다.
- 「세 검사가 이 자리를 지킨다」 목록에 `scripts/check-feature-covers.mjs`(경고만)와 `test/unit/feature-covers.test.ts`(모든 기능 파일에 covers 가 있다)를 더하고 「세」 를 맞는 수로 고친다.
- 「감당할 것」 의 「처음 옮길 때는 … 복사본이 남아 있다 … 다음 PR 이 한다」 문장은 「코드 값의 복사본은 앞 PR 이 줄였고, 남은 것은 그 기능을 고치는 PR 이 이어 줄인다」 로 바꾼다. 기능 파일에 API 언급과 표가 아직 남아 있어 「다 줄였다」 고 적지 않는다.

### 3. 이 phase 를 검증하는 테스트 `test/unit/feature-covers.test.ts` 에 더한다

- 순수 함수 `coversProblems(docs: Map<string, string>, tracked: string[]): string[]` 를 `scripts/check-feature-covers.mjs` 에 더해 export 한다. covers 가 없는 기능 파일과, 맞는 tracked 파일이 하나도 없는 covers 경로(디렉터리, 파일, glob 모두)를 문제로 낸다.
- 가짜 기능 문서 묶음(covers 가 있는 것 하나, 없는 것 하나, 없는 경로를 가진 것 하나)으로 문제 둘이 나오는 것을 단언한다.
- 저장소의 `docs/features/*.md` 와 `git ls-files` 로 `coversProblems` 가 빈 배열인지 단언한다.

## 검증

```bash
node --test test/unit/feature-covers.test.ts
node --test 'test/unit/**/*.test.ts'
node scripts/check-feature-covers.mjs origin/main
node scripts/check-file-length.mjs
```

- 첫 두 줄은 종료 코드 0 이다.
- 셋째 줄은 종료 코드 0 이다. 이 PR 은 기능 파일을 모두 고치므로 경고가 없어야 한다.
- 넷째 줄에서 기능 파일의 500줄 `알림:` 이 없다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `docs/features/chat.md` | 수정 |
| `docs/features/execution.md` | 수정 |
| `docs/features/model-usage.md` | 수정 |
| `docs/features/attachment.md` | 수정 |
| `docs/features/workspace.md` | 수정 |
| `docs/features/agent-skill.md` | 수정 |
| `docs/features/mcp.md` | 수정 |
| `docs/features/users.md` | 수정 |
| `docs/features/user-browser.md` | 수정 |
| `docs/features/connector.md` | 수정 |
| `docs/features/connector-policy.md` | 수정 |
| `docs/features/memory.md` | 수정 |
| `docs/features/attention.md` | 수정 |
| `docs/features/schedule.md` | 수정 |
| `docs/features/proactive.md` | 수정 |
| `docs/adr/ADR-20261009-feature-docs.md` | 수정 |
| `test/unit/feature-covers.test.ts` | 수정 |
| `scripts/check-feature-covers.mjs` | 수정 |
