# Phase 03. 에이전트 상세의 「새 항목 살펴보기」 소절과 알림 갈 곳

**Execution profile**: standard

## 목표

먼저 살펴보기 절 끝에서 붙인 연결마다 사건 감지를 켜고 끄며, 막는 까닭과 오늘 상한 도달, 다시 연결 필요를 보인다. 새 알림 종류를 누르면 점검 대화나 외부 서비스 연결로 간다.

**범위 외**: backend(phase 01, 02).

## 컨텍스트

- 같은 모양의 소절: `web/src/components/agent/agent-proactive-schedule-section.tsx`(읽기, 저장, 까닭 목록, 마지막 결과). 그 소절을 그리는 `agent-proactive-check-section.tsx` 의 끝, 매일 깨우기 소절 아래에 새 소절을 둔다
- API 도우미: `web/src/lib/proactive-check.ts`. 서버 라우트: `web/src/app/api/agents/[code]/proactive-check/schedule/route.ts`(rule-55: 브라우저는 서버 라우트와 lib 도우미로만 부른다)
- 알림 갈 곳: `web/src/lib/notification.ts` 의 `notificationHref`
- 문구는 해요체와 제품 용어(rule-58). 「플러그인」, 「연결」, 「외부 서비스 연결」 을 쓴다. 내부 값(`DAILY_LIMIT`)을 보이지 않는다

**요구사항**: REQ5(알림 갈 곳), REQ7(「오늘 상한 도달」), REQ9·REQ10(켜기와 까닭), REQ11(다시 연결 필요)

**근거 문서**: `docs/features/proactive/spec.md`, `docs/features/proactive/design.md`

## 의도 메모

- 시간대는 매일 깨우기처럼 글 칸으로 받고 저장된 값(꺼져 있으면 `Asia/Seoul`)을 채운다(설계 DECISION12)
- 붙은 연결 가운데 지원하는 것이 없으면 소절 제목과 한 줄 안내만 보인다
- 조회가 실패해도 먼저 살펴보기 절의 다른 소절을 막지 않는다. 소절 안에 실패 문구만 그린다

## 작업 항목

### 1. `web/src/lib/event-watch.ts`(신규)

- 타입 `EventWatch`(phase 02 응답 칸 그대로), `EventWatchBlocker`(code 는 `ProactiveCheckBlocker` 의 code 와 연결 쪽 세 값의 합집합)
- `fetchEventWatches(code)`, `saveEventWatch(code, connectorId, {enabled, timezone})`: `/api/agents/{code}/event-watches[/{connectorId}]`
- `describeEventWatchBlocker(blocker, title)`: 새 세 값의 문장. `ISOLATED_EXECUTION_REQUIRED` 는 도구 이름과 함께 「격리된 실행 공간이 준비되기 전에는 켤 수 없어요」, 나머지는 `describeBlocker` 를 쓴다
- `describeEventWatchResult(view)`: 마지막 확인 결과 한 구절(새 항목 없음, 살펴봄, 바빠서 다음으로 미룸, 막힘, 오늘 상한, 확인 실패)

### 2. 서버 라우트(신규)

- `web/src/app/api/agents/[code]/event-watches/route.ts`: GET 을 Control Plane `/api/v1/agents/{code}/event-watches` 로
- `web/src/app/api/agents/[code]/event-watches/[connectorId]/route.ts`: PUT. 커넥터 id 는 `web/src/lib/connection.ts` 의 `CONNECTOR_ID_PATTERN` 으로 검사하고 아니면 400 `VALIDATION_FAILED`
- 모양은 `proactive-check/schedule/route.ts` 를 따른다

### 3. `web/src/components/agent/agent-event-watch-section.tsx`(신규)

`<section aria-label="새 항목 살펴보기">`, 제목 「새 항목 살펴보기」, 설명 「붙인 서비스에 새 항목이 생기면 15분 안에 살펴보고 알릴 것만 알려요. 밤 10시부터 아침 7시까지는 아침에 한 번에 알려요.」.
연결마다 한 줄(`data-testid="event-watch"`): 제목 「{title} 새 {noun}」, 스위치(`aria-label="{title} 새 {noun} 살펴보기"`, 꺼져 있고 `available=false` 면 끔), 시간대 칸, 저장 단추, 까닭 목록, 마지막 확인 줄, `dailyLimitReached` 면 「오늘 상한 도달. 내일 첫 확인에서 쌓인 {noun}을 함께 살펴봐요.」, `failing` 이면 경고 「{title} 다시 연결이 필요해요」 와 `/connections` 링크.
`agent-proactive-check-section.tsx` 의 매일 깨우기 소절 아래에서 그린다.

### 4. 알림 갈 곳

`web/src/lib/notification.ts` 의 `NotificationKind` 에 `EVENT_CHECK_REPORTED`, `EVENT_WATCH_FAILED`, `NotificationTargetType` 에 `CONNECTIONS` 를 더하고 `notificationHref` 가 `/connections` 를 돌려준다. `test/unit/notification.test.ts` 에 그 사례를 더한다.

### 5. 이 phase 를 검증하는 브라우저 테스트

`test/browser/event-watch.spec.ts`(신규). `proactive-check.spec.ts` 의 `browser` 에이전트와 준비를 따른다.

- 실제 Control Plane 왕복: 지원하는 연결이 없는 에이전트의 소절이 안내 한 줄을 보인다. 붙지 않은 커넥터로 PUT 하면 404 `CONNECTOR_NOT_FOUND`, 형식이 틀린 id 면 400 `VALIDATION_FAILED` 다
- 응답 대역(`page.route` 로 GET 과 PUT): 꺼진 연결의 스위치를 켜고 저장하면 PUT 본문이 `{enabled: true, timezone: "Asia/Seoul"}` 이고 켜짐이 보인다. `dailyLimitReached` 면 「오늘 상한 도달」, `failing` 이면 「다시 연결이 필요해요」 와 `/connections` 링크, `available=false` 와 `EVENT_WATCH_ELSEWHERE` 면 스위치가 꺼져 있고 까닭이 보인다. `ISOLATED_EXECUTION_REQUIRED`(toolsets `terminal`)면 도구 이름과 격리 문구가 보인다. 저장은 `clickAndWaitForResponse` 로 응답을 받은 뒤 화면을 본다
- 두 폭(프로젝트 설정)에서 가로 넘침이 없다

## 검증

```bash
cd web && pnpm lint && pnpm format:check && pnpm typecheck
cd web && pnpm test:browser event-watch.spec.ts
node --test test/unit/notification.test.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/event-watch.ts` | 신규 |
| `web/src/lib/notification.ts` | 수정 |
| `web/src/app/api/agents/[code]/event-watches/route.ts` | 신규 |
| `web/src/app/api/agents/[code]/event-watches/[connectorId]/route.ts` | 신규 |
| `web/src/components/agent/agent-event-watch-section.tsx` | 신규 |
| `web/src/components/agent/agent-proactive-check-section.tsx` | 수정 |
| `test/browser/event-watch.spec.ts` | 신규 |
| `test/unit/notification.test.ts` | 수정 |
