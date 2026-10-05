# Phase 01. 에이전트 목록에 runsTasks 를 싣고 작업 화면이 거른다

**Execution profile**: standard

## 목표

`GET /api/v1/agents` 의 각 줄에 `runsTasks`(boolean)를 더하고, 작업 만들기와 고치기 화면이 `runsTasks` 가 거짓인 에이전트를 목록에서 뺀다.
흐름 이름은 응답에 싣지 않는다.

**범위 외**: 새 대화 화면의 목록(그대로 모든 에이전트를 보인다). 서버의 `TASK_AGENT_NOT_SUPPORTED` 검사(그대로 둔다).

## 컨텍스트

- 응답 모양: `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentDtos.java` 의 `record AgentView(String code, String name, String visibility, boolean acceptsAttachments, boolean editable, boolean ownedByMe, boolean connectorManaged)` 와 `static AgentView from(Agent agent, boolean editable, boolean ownedByMe)`. Javadoc 이 「흐름 이름은 내보내지 않는다」 고 적는다
- 컨트롤러: `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentController.java` 의 `private AgentView view(CurrentUser user, Agent agent)` 가 `readable`, `create`, `changeVisibility` 모두의 응답을 만든다. 필드는 `AgentService agents`, `CurrentUserProvider currentUser`, `AgentLifecycleService lifecycle`(`@RequiredArgsConstructor`)
- 서버 판정: `TaskService.requireAgent` 와 `TaskRunStarter.agentUsable` 이 `flows.known(agent.flow())` 면 거절한다. `flows` 는 `com.bifos.assistant.agent.application.KnownFlows`. `runsTasks` 는 `!flows.known(agent.flow())` 로 같은 값을 쓴다. `acceptsAttachments` 는 커넥터 에이전트에서도 거짓이라 대신 쓸 수 없다
- 화면: `web/src/components/task/task-form.tsx`. `type AgentOption = { code: string; name: string }`, `fetchChatAgents()`(`web/src/lib/chat-api.ts`, `fetch("/api/agents")`) 결과를 그대로 `setAgents(data)` 하고 첫 줄을 기본값으로 고른다. 고치는 작업의 지금 에이전트가 목록에 없으면 `options` 앞에 붙인다(그대로 둔다)
- 웹의 에이전트 타입: `web/src/lib/agent.ts` 의 에이전트 type(`acceptsAttachments` 등 칸마다 주석이 있다)
- 브라우저 검사: `test/browser/tasks.spec.ts`. 검사 사용자의 에이전트 `browser` 로 작업을 만든다. 응답을 바꿔 끼울 때는 같은 파일처럼 `page.route` 를 쓴다

**근거 문서**: `docs/backend/task.md` 의 「화면」 의 에이전트 고르기 문단(이 plan 이 이미 고쳤다)

## 의도 메모

- 흐름 이름을 싣고 화면이 판정하는 안은 기각한다. 등록된 흐름인지(`KnownFlows`)는 서버만 안다. 응답이 흐름 이름을 내보내지 않는 기존 방침도 지킨다
- 작업용 에이전트 목록 API 를 따로 만드는 안은 기각한다. 같은 목록에 칸 하나면 된다

## 작업 항목

### 1. `AgentView.runsTasks`

- `AgentView` 끝에 `boolean runsTasks` 를 더하고 `from(Agent agent, boolean editable, boolean ownedByMe, boolean runsTasks)` 로 받는다. Javadoc `@param runsTasks` 에 「예약 작업을 돌릴 수 있다. 흐름이 붙은 에이전트는 거짓이다. 화면이 작업의 에이전트 목록을 이 값으로 거른다」 를 적는다
- `AgentController` 에 `KnownFlows flows` 를 주입하고 `view` 가 `!flows.known(agent.flow())` 를 넘긴다
- `AgentView.from` 을 부르는 다른 곳이 있으면 함께 고친다(`git grep "AgentView.from"`)

### 2. 화면

- `web/src/lib/agent.ts` 의 에이전트 type 에 `runsTasks: boolean` 과 한국어 주석을 더한다
- `task-form.tsx` 의 `AgentOption` 에 `runsTasks?: boolean` 을 더하고, `setAgents(data.filter((agent) => agent.runsTasks !== false))` 로 거른다. 기본값도 거른 목록의 첫 줄이다. 칸이 없는 옛 응답은 지금처럼 모두 보인다

### 3. 이 phase 를 검증하는 검사

- 백엔드: `backend/src/test/java/com/bifos/assistant/agent/AgentControllerLifecycleTest.java` 나 같은 디렉터리의 에이전트 목록 검사에 더한다. 흐름(`KnownFlows` 가 아는 이름)이 붙은 합성 에이전트와 보통 합성 에이전트를 두고 `GET /api/v1/agents` 를 읽으면 앞의 것은 `runsTasks=false`, 뒤의 것은 `true` 다. 응답에 `flow` 칸이 없다
- 브라우저: `test/browser/tasks.spec.ts` 에 「작업 만들기의 에이전트 목록은 예약 작업을 돌릴 수 없는 에이전트를 뺀다」 를 더한다. `page.route("**/api/agents", ...)` 로 `runsTasks` 가 참인 줄 하나와 거짓인 줄 하나를 돌려주고, 작업 만들기 화면의 에이전트 고르기에 앞의 이름만 보이고 뒤의 이름은 없다. 모바일과 데스크톱 폭 모두 돈다(같은 파일의 다른 검사처럼)

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.agent.*' --tests 'com.bifos.assistant.task.*'
pnpm --dir web lint
scripts/check-local.sh tasks
```

- 첫 줄: 새 검사와 기존 에이전트, 작업 검사가 통과한다
- 둘째 줄: 웹 lint 가 통과한다(스크립트 이름은 `web/package.json` 을 보고 맞춘다)
- 셋째 줄: 전체 로컬 검사. 브라우저 검사는 `test/browser/tasks.spec.ts` 만 돌고 새 검사를 포함한다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentController.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentControllerLifecycleTest.java` | 수정 |
| `web/src/lib/agent.ts` | 수정 |
| `web/src/components/task/task-form.tsx` | 수정 |
| `test/browser/tasks.spec.ts` | 수정 |
| `docs/backend/task.md` | 수정 |
