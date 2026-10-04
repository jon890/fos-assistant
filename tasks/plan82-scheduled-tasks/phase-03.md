# Phase 03. 예약 작업 화면과 대화 목록의 작업 묶음

**Execution profile**: standard

## 목표

사용자가 화면에서 예약 작업을 만들고 고치고 멈추고 지우며 최근 실행을 본다. 대화 목록은 작업이 만든 대화를 작업 이름 아래로 묶는다. 작업 알림을 누르면 그 작업이나 대화로 간다.

**범위 외**: Control Plane 의 표와 API 와 발화(phase 01, 02). 웹 푸시. 에이전트가 제안한 작업을 받아들이는 카드(다음 단계).

## 컨텍스트

**근거 문서**: `docs/backend/task.md` 의 「작업」, 「시각」, 「API」, 「화면」, 「알림」, `docs/adr/ADR-078-예약-작업의-결과는-실행마다-새-대화가-기본이고-목록은-작업으로-묶는다.md`, `docs/frontend/shell.md` 의 「대화 목록」, `docs/frontend/structure.md` 의 화면 표, `web/AGENTS.md` 전체.

Control Plane API 는 phase 01 이 만들었다. 구현 전에 `backend/src/main/java/com/bifos/assistant/task/presentation/TaskDtos.java` 와 `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` 의 `ConversationView` 를 읽어 칸 이름을 맞춘다.

따를 기존 패턴(경로는 `web/src/` 기준):

- 서버 라우트: `lib/control-plane.ts` 의 `callControlPlane<T>(path, {method, body})`, 본문은 `lib/json-body.ts` 의 `readJsonBody(request)`, 오류는 `lib/api-response.ts` 의 `errorResponse`. 경로의 UUID 검사는 `lib/conversation-id.ts` 의 `isPublicId` 를 쓴다(알림 라우트 `app/api/notifications/[notificationId]/read/route.ts` 가 같은 방식이다).
- 화면은 `fetch` 를 직접 부르지 않는다. `lib/task-api.ts` 에 호출 함수를 둔다.
- 알림 화면 `app/notifications/page.tsx` 와 `components/notification/notification-list.tsx` 처럼 page 는 세션만 확인하고 목록은 브라우저가 읽는다. 그래서 `loading.tsx` 를 두지 않는다. `test/unit/loading-routes.test.ts` 의 `ROUTE_FRAMES` 에 없는 경로에 `loading.tsx` 가 있으면 실패한다.
- 메뉴: `components/shell/main-nav.tsx` 의 `LINKS`.
- 대화 목록: `components/shell/conversation-nav.tsx`, 날짜 묶음은 `components/shell/group-by-date.ts` 의 `groupByDate(conversations, now)`, 상태는 `components/shell/conversations-provider.tsx` 의 `Conversation` 타입.
- 알림의 갈 곳: `lib/notification.ts` 의 `notificationHref`.
- 부품: `components/ui/` 의 `Button`, `Input`, `Textarea`, `Label`, `NativeSelect`, `Badge`, `EmptyState`, `Notice`, `AlertDialog*`, `Card*`. 고르는 칸은 `NativeSelect` 다(ADR-023).
- 오류 문구: `components/error-message.ts`.
- 화면 문구는 해요체다(`web/AGENTS.md` 의 「화면 문구」). 오류 코드와 `reason` 원문은 그리지 않는다.

## 의도 메모

- 시각 고르기는 화면이 cron 으로 바꾼다. 서버는 cron 만 안다. 「직접 입력」 은 cron 을 그대로 보낸다.
- 작업 묶음은 목록 맨 위에 둔다. 날짜 묶음 사이에 끼우면 작업 대화가 날마다 흩어진다.
- 작업 묶음은 지금 읽은 쪽의 대화로만 만든다. 작업 대화만 따로 읽는 API 는 두지 않는다. 오래된 작업 대화는 목록을 더 읽거나 작업 화면의 실행 목록에서 찾는다.

## 작업 항목

### 1. 순수 함수

| 파일 | 함수 |
| --- | --- |
| `web/src/lib/task.ts` | 타입 `TaskView`, `TaskRunView`, `ScheduleView`, `TaskRequest`. `ScheduleChoice` 는 `{kind:"daily", time}`, `{kind:"weekly", weekday, time}`, `{kind:"monthly", day, time}`, `{kind:"once", date, time}`, `{kind:"cron", cron}` 다. `scheduleRequestOf(choice, timeZone)` 가 `daily` 를 `"{m} {h} * * *"`, `weekly` 를 `"{m} {h} * * {0-6}"`, `monthly` 를 `"{m} {h} {d} * *"`, `once` 를 `{type:"ONCE", fireAt:"{date}T{time}"}` 로 바꾼다. `choiceOf(schedule)` 가 그 반대이고 위 넷 모양이 아닌 cron 은 `cron` 이다. `describeSchedule(schedule)` 는 「매일 09:00」, 「매주 월요일 09:00」, 「매달 1일 09:00」, 「한 번, 2026-11-01 09:00」, 「직접 입력: 0 9 1 * *」 을 돌려준다. `runReasonText(reason)` 은 `docs/backend/task.md` 의 「알림」 의 까닭 표와 같은 문구이고 `MISSED` 는 「서버가 꺼져 있던 동안의 실행이라 건너뛰었어요」, `PAUSED` 는 「작업을 멈춰 건너뛰었어요」, `OWNER_REVOKED` 는 null 이다 |
| `web/src/components/shell/group-by-task.ts` | `groupByTask(conversations)` 가 `{ tasks: {taskId, title, conversations}[], others: Conversation[] }` 를 돌려준다. `taskId` 가 있는 대화를 작업마다 모으고 작업은 가장 최근 대화의 `updatedAt` 순, 대화도 최근 순이다. 나머지는 `others` 다 |

`lib/notification.ts` 의 `NotificationKind` 유니온에 `TASK_SUCCEEDED`, `TASK_FAILED`, `TASK_SKIPPED` 를, `NotificationTargetType` 에 `TASK` 를 더하고, `notificationHref` 가 `TASK` 면 `/tasks/{targetId}` 를 돌려준다.
위 파일들은 `test/unit` 이 읽는다. 런타임 import 가 있으면 상대 경로로 쓰고 `web/eslint.config.mjs` 의 `NODE_TEST_READ_FILES` 에 넣는다.

### 2. 호출 함수와 서버 라우트

`web/src/lib/task-api.ts`: `fetchTasks()`, `fetchTask(id)`, `createTask(body)`, `updateTask(id, body)`, `pauseTask(id)`, `resumeTask(id)`, `deleteTask(id)`, `fetchTaskRuns(id, limit)`.

| 파일 | 경로 |
| --- | --- |
| `web/src/app/api/tasks/route.ts` | GET, POST |
| `web/src/app/api/tasks/[taskId]/route.ts` | GET, PUT, DELETE |
| `web/src/app/api/tasks/[taskId]/pause/route.ts` | POST |
| `web/src/app/api/tasks/[taskId]/resume/route.ts` | POST |
| `web/src/app/api/tasks/[taskId]/runs/route.ts` | GET. `limit` 만 넘긴다 |

`taskId` 가 UUID 가 아니면 Control Plane 을 부르지 않고 400 `VALIDATION_FAILED` 다.

### 3. 화면

| 파일 | 내용 |
| --- | --- |
| `web/src/app/tasks/page.tsx` | 세션 확인 뒤 `TaskList`. `metadata` 제목 「예약 작업」 |
| `web/src/app/tasks/new/page.tsx` | 세션 확인 뒤 `TaskForm`(새 작업). 제목 「새 예약 작업」 |
| `web/src/app/tasks/[taskId]/page.tsx` | 세션 확인 뒤 `TaskDetail`. 제목 「예약 작업」 |
| `web/src/components/task/task-list.tsx` | 브라우저에서 목록을 읽는다. 줄마다 이름, 에이전트 이름, `describeSchedule`, 다음 실행(브라우저 시간으로, 없으면 「다음 실행 없음」), 상태 배지(`ACTIVE` 「켜짐」, `PAUSED` 「멈춤」). 위에 「새 작업」 링크. 빈 목록은 `EmptyState` 「아직 예약 작업이 없어요.」. 줄의 testid `task-item` |
| `web/src/components/task/task-form.tsx` | 이름, 에이전트(새 대화 화면이 쓰는 `lib/chat-api.ts` 의 `fetchChatAgents()` 로 읽고, 흐름이 붙은 것(`flow` 가 null 이 아닌 것)과 꺼진 것은 고르기 목록에서 뺀다. 구현 전에 그 응답의 칸 이름을 읽어 맞춘다), 지시(`Textarea`, 8000자), 시각(`ScheduleChoice` 의 다섯 종류와 그에 맞는 칸), 시간대(기본 `Asia/Seoul` 을 보이고 고칠 수 있는 `Input`), 대화 방식(「실행마다 새 대화」, 「대화 하나에 이어서」), 놓친 실행(「한 번만 실행」, 「건너뛰기」), 알림(「늘 알림」, 「실패만 알림」, 「알리지 않음」). 저장하면 `/tasks/{id}` 로 간다. 실패하면 `Notice` 로 오류 문구 |
| `web/src/components/task/task-detail.tsx` | 같은 폼으로 고치기, 「멈추기」 또는 「다시 켜기」, 「지우기」(`AlertDialog` 확인 뒤 `/tasks` 로), 최근 실행 목록(예정 시각, 상태 문구 「기다리는 중」, 「도는 중」, 「마쳤어요」, 「실패했어요」, 「멈췄어요」, 「건너뛰었어요」, `runReasonText`, 대화가 있으면 「대화 보기」 링크 `/chat/{conversationId}`). 실행 줄의 testid `task-run` |

`components/error-message.ts` 에 `TASK_NOT_FOUND: "이미 지워졌거나 없는 작업이에요."`, `TASK_LIMIT_REACHED: "예약 작업은 10개까지 만들 수 있어요. 쓰지 않는 작업을 지워 주세요."`, `TASK_SCHEDULE_INVALID: "실행 시각을 다시 확인해 주세요. 반복 간격은 15분보다 짧을 수 없어요."`, `TASK_AGENT_NOT_SUPPORTED: "이 에이전트로는 예약 작업을 만들 수 없어요."` 를 더한다.

`components/shell/main-nav.tsx` 의 `LINKS` 에서 「연결」 뒤에 `{ href: "/tasks", label: "예약 작업" }` 을 더한다. `test/browser/shell.spec.ts` 가 메뉴 링크를 `[/^에이전트/, /^연결/, /^기억/, /^사용량/]` 로 정확히 단언하므로 `[/^에이전트/, /^연결/, /^예약 작업/, /^기억/, /^사용량/]` 로 고친다.

### 4. 대화 목록의 작업 묶음

- `components/shell/conversations-provider.tsx` 의 `Conversation` 타입에 `taskId: string | null`, `taskTitle: string | null` 을 더한다
- `components/shell/conversation-nav.tsx`: 검색으로 거른 뒤 `groupByTask` 로 나눈다. `tasks` 가 있으면 날짜 묶음 위에 제목 「예약 작업」 묶음을 그린다. 작업마다 이름과 대화 수가 있는 접힌 단추 하나(`aria-expanded`, testid `task-group`)이고, 누르면 그 작업의 대화가 기존 줄 모양 그대로 펼쳐진다. 지금 연 대화가 그 작업의 대화이거나 검색어가 있으면 펼친 채로 그린다. `others` 는 지금처럼 `groupByDate` 로 그린다

### 5. 문서

`docs/frontend/structure.md` 의 화면 표(`/tasks`, `/tasks/new`, `/tasks/{id}`)와 `docs/frontend/shell.md` 의 「대화 목록」 과 「화면 틀」 그림은 계획 커밋이 이미 적었다. 구현이 이 문서들과 다르면 같은 커밋에서 고친다. 고치기 전에 계획 담당에게 알린다.

### 6. 이 phase 를 검증하는 테스트

| 파일 | 확인하는 것 |
| --- | --- |
| `test/unit/task-schedule.test.ts` | `scheduleRequestOf` 가 매일 09:30 을 `30 9 * * *`, 매주 월요일 09:00 을 `0 9 * * 1`, 매달 1일 09:00 을 `0 9 1 * *` 로, 한 번을 `ONCE` 와 `2026-11-01T09:00` 으로 바꾼다. `choiceOf` 가 그 반대이고 `*/15 * * * *` 는 `cron` 이다. `describeSchedule` 문구. `runReasonText("OWNER_REVOKED")` 는 null |
| `test/unit/group-by-task.test.ts` | 작업 둘과 보통 대화 셋이 섞인 목록에서 작업이 최근 대화 순으로 나오고 보통 대화가 `others` 에 남는다. 작업 대화가 없으면 `tasks` 가 빈다 |
| `test/unit/notification.test.ts` | 기존 검사에 `TASK` 대상이 `/tasks/{id}` 인 경우를 더한다 |
| `test/browser/tasks.spec.ts` | 아래 |

브라우저 spec 은 실제 Control Plane 에 붙는다(`test/browser/fixtures.ts`). 에이전트는 `seedAgents` 가 만든 `browser` 를 쓴다.

- 메뉴의 「예약 작업」 으로 `/tasks` 에 가면 빈 목록 문구가 보인다(검사 사용자의 작업을 테스트 앞에서 API 로 모두 지운다)
- 「새 작업」 에서 이름, 에이전트, 지시, 「매달」 1일 09:00 을 넣고 저장하면 상세로 가고, 목록에 「매달 1일 09:00」 줄이 하나 보인다
- 「멈추기」 를 누르면 배지가 「멈춤」, 「다시 켜기」 를 누르면 「켜짐」 이다
- 「지우기」 를 확인하면 목록에서 사라진다
- `page.route("**/api/chat/conversations?**", ...)` 로 작업 대화 둘(같은 작업)과 보통 대화 하나를 돌려주면 사이드바에 「예약 작업」 묶음과 접힌 작업 줄 하나가 보이고, 누르면 대화 둘이 펼쳐진다. 좁은 폭은 먼저 「사이드바 열기」 를 누른다(`test/browser/nav.spec.ts` 방식)
- `**/api/notifications/events` 는 빈 SSE 응답으로 끝낸다. `test.afterEach` 에서 `page.unrouteAll({ behavior: "ignoreErrors" })`

## 검증

```bash
# cwd: web/
pnpm lint
pnpm format:check
pnpm typecheck
pnpm build
pnpm test:browser tasks.spec.ts notifications.spec.ts nav.spec.ts shell.spec.ts conversation-requests.spec.ts
```

```bash
# cwd: 저장소 root
node --test test/unit/task-schedule.test.ts test/unit/group-by-task.test.ts test/unit/notification.test.ts
node --test 'test/unit/**/*.test.ts'
grep -rn 'style={{' web/src/components/task web/src/app/tasks web/src/components/shell
scripts/check-public-safe.sh
```

기대값: `grep` 은 아무것도 내지 않는다. 나머지는 모두 종료 코드 0.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/task.ts` | 신규 |
| `web/src/lib/task-api.ts` | 신규 |
| `web/src/lib/notification.ts` | 수정 |
| `web/src/app/api/tasks/route.ts` | 신규 |
| `web/src/app/api/tasks/[taskId]/route.ts` | 신규 |
| `web/src/app/api/tasks/[taskId]/pause/route.ts` | 신규 |
| `web/src/app/api/tasks/[taskId]/resume/route.ts` | 신규 |
| `web/src/app/api/tasks/[taskId]/runs/route.ts` | 신규 |
| `web/src/app/tasks/page.tsx` | 신규 |
| `web/src/app/tasks/new/page.tsx` | 신규 |
| `web/src/app/tasks/[taskId]/page.tsx` | 신규 |
| `web/src/components/task/task-list.tsx` | 신규 |
| `web/src/components/task/task-form.tsx` | 신규 |
| `web/src/components/task/task-detail.tsx` | 신규 |
| `web/src/components/shell/group-by-task.ts` | 신규 |
| `web/src/components/shell/conversation-nav.tsx` | 수정 |
| `web/src/components/shell/conversations-provider.tsx` | 수정 |
| `web/src/components/shell/main-nav.tsx` | 수정 |
| `web/src/components/error-message.ts` | 수정 |
| `web/eslint.config.mjs` | 수정 |
| `test/unit/task-schedule.test.ts` | 신규 |
| `test/unit/group-by-task.test.ts` | 신규 |
| `test/unit/notification.test.ts` | 수정 |
| `test/browser/tasks.spec.ts` | 신규 |
| `test/browser/shell.spec.ts` | 수정 |
| `docs/frontend/structure.md` | 수정 |
| `docs/frontend/shell.md` | 수정 |
