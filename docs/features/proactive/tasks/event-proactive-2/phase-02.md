# Phase 02. 사건 감지를 켜고 끄는 API 와 상태 조회

**Execution profile**: standard

## 목표

에이전트 상세가 붙은 연결마다 사건 감지의 켜짐, 켤 수 있는지와 까닭, 마지막 확인, 오늘 상한 도달, 다시 연결 필요를 읽고, 요청자의 연결만 켜고 끈다.

**범위 외**: 화면(phase 03), 알림(phase 01).

## 컨텍스트

설계 `docs/features/proactive/design.md` 의 「Control Plane API(PR2)」 와 「실패와 일관성」 의 「켜기의 조건은 두 번 본다」 가 원본이다.

- 매일 깨우기의 같은 모양: `task/application/ProactiveScheduleService.java`(`get` 은 `requireReadable`, `update` 는 켜기만 `requireStartable`, Hermes 장애는 `READINESS_UNKNOWN`), `task/presentation/ProactiveScheduleController.java`, `task/presentation/TaskDtos.java`
- 켜기와 끄기의 저장: `connector/connection/application/ConnectorEventWatches.enable`, `disable`(PR1). `enable` 은 같은 연결의 바인딩을 잠근 뒤 다시 본다
- 사건 실행의 준비 판정: `proactive/check/application/ProactiveCheckReadiness.checkEvent(agent, isolatedExecution)`, 격리 설정은 `task/config/ProactiveScheduleProperties.isolatedExecutionEnabled`
- 기본 시간대는 `task/config/TaskProperties.defaultZone()`, 시간대 읽기는 `TaskSchedule.parseZone`(`TASK_SCHEDULE_INVALID`)

**요구사항**: REQ7(화면 표시용 값), REQ9, REQ10, REQ11(상태 표시용 값)

**근거 문서**: `docs/features/proactive/spec.md`, `docs/features/proactive/design.md`

## 의도 메모

- 조회는 그 에이전트에 붙은 **요청자의** 연결 가운데 카탈로그가 `event_watch` 를 선언한 것만 싣는다. 남의 연결은 싣지 않는다(rule-47)
- `connector` 는 `task` 와 `proactive` 를 모른다. 까닭 코드(`CheckBlockerCode`)를 고르는 일은 `task` 의 `EventWatchService` 가 하고, `connector` 는 사실(쓸 수 있는가, 다른 바인딩이 켰는가)만 준다
- 엔티티의 두 사건 감지 칸은 넣기·고치기에서 빠져 있어 같은 영속성 컨텍스트에서 조건부 update 뒤에 읽으면 옛 값이다. 응답은 쓰기 뒤 줄을 다시 읽어 만든다

## 작업 항목

### 1. 까닭 값

`task/application/dto/EventWatchBlocker.java`(신규): `(String code, List<String> toolsets)`. `of(CheckBlocker)` 는 준비 판정의 까닭 이름을 그대로 옮긴다. 연결 쪽 까닭 `EVENT_TOOL_NOT_ALLOWED`(조회 도구가 `READ`·승인 `none` 이 아니다), `CONNECTION_NOT_READY`(연결이나 바인딩이 `READY` 가 아니다), `EVENT_WATCH_ELSEWHERE`(같은 연결의 다른 에이전트가 켰다)는 상수로 둔다. `proactive` 의 `CheckBlockerCode` 는 바꾸지 않는다(설계 「Control Plane API(PR2)」).

### 2. `ConnectorEventWatches` 의 읽기

- 새 값 `connector/connection/domain/EventWatchBinding.java`: `(Long bindingId, String connectorId, boolean usable, boolean enabled, boolean elsewhere, String state)`
- `@Transactional(readOnly = true) List<EventWatchBinding> bindingsOf(Long userId, Long agentId)`: `findByAgentId` 가운데 연결 주인이 `userId` 인 것. `elsewhere` 는 `findByConnectionId` 의 다른 바인딩 가운데 켜진 것이 있는가. 상태 JSON 은 엔티티가 아니라 DB 에서 다시 읽는다(위 의도 메모)
- `enable`, `disable` 은 바꾸지 않는다

### 3. `task/application/EventWatchService.java`(신규)와 값

- 값 `task/application/dto/EventWatchView.java`: `(String connectorId, String title, String noun, boolean enabled, boolean available, List<EventWatchBlocker> blockers, String timeZone, Instant lastCheckedAt, EventWatchResult lastResult, int lastNewCount, boolean dailyLimitReached, boolean failing)`
- `List<EventWatchView> list(CurrentUser, String agentCode)`: `requireReadable`. 선언(`watches.declaring`)이 있는 바인딩만. 까닭은 준비 판정(`checkEvent`, Hermes 장애면 `READINESS_UNKNOWN`) + `CONNECTION_NOT_READY` + `EVENT_TOOL_NOT_ALLOWED`(`ConnectorEventWatches.toolAllowed`) + `EVENT_WATCH_ELSEWHERE`. 켜져 있는 바인딩에는 `EVENT_WATCH_ELSEWHERE` 가 붙지 않는다. 시간대는 켜졌으면 상태 값, 아니면 기본 시간대다
- `dailyLimitReached`: 상태 시간대의 오늘 `runsOn(today) >= maxRunsPerDay` 이거나, 마지막 결과가 `DAILY_LIMIT` 이고 마지막 확인이 오늘이다
- `failing`: `failures >= failureThreshold`
- `EventWatchView update(CurrentUser, String agentCode, String connectorId, boolean enabled, String timeZone)`: 이 메서드는 트랜잭션을 열지 않는다(Hermes 를 잠금 밖에서 부른다). 켜기는 `requireStartable`, 준비 판정을 Hermes 실패 그대로 올리며 모든 까닭이 비어야 하고 아니면 `PROACTIVE_CHECK_UNAVAILABLE`. 그 다음 `watches.enable`. 끄기는 `requireReadable` 과 `watches.disable` 이고 Hermes 를 부르지 않는다. 응답은 `list` 의 그 연결 항목이다. 끈 뒤 응답을 만들 때 Hermes 를 읽지 못해도 실패하지 않는다(`READINESS_UNKNOWN`)
- 그 에이전트에 요청자의 그 연결 바인딩이 없으면 `CONNECTOR_NOT_FOUND`

### 4. `task/presentation/EventWatchController.java`(신규)와 DTO

- `GET /api/v1/agents/{code}/event-watches` → `List<EventWatchResponse>`
- `PUT /api/v1/agents/{code}/event-watches/{connectorId}` 본문 `EventWatchRequest(boolean enabled, String timezone)` → `EventWatchResponse`
- 요청과 응답 record 는 `task/presentation/TaskDtos.java` 에 둔다(rule-15). 응답 칸 이름: `connectorId, title, noun, enabled, available, blockers, timezone, lastCheckedAt, lastResult, lastNewCount, dailyLimitReached, failing`. 까닭은 `{code, toolsets}` 꼴의 `EventWatchBlockerView` 다

### 5. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/proactive/EventWatchControllerTest.java`(신규, `EventWatchTestSupport` 를 이어받는다). HTTP 는 `ProactiveCheckStatusTest` 처럼 `MockMvcBuilders.standaloneSetup` 으로 컨트롤러를 세우고 `CurrentUserProvider` 를 그 사용자로 둔다

- 기본 꺼짐: 붙인 직후 조회가 `enabled=false`, `available=true` 이다
- 켜기 성공 뒤 조회가 `enabled=true`, 시간대가 준 값이다. 시간대를 비우면 `Asia/Seoul` 이다
- 거절 각각(REQ9, REQ10): 셸 toolset 과 격리 꺼짐(`ISOLATED_EXECUTION_REQUIRED`), `delegation` toolset(`TOOLSETS_NOT_ALLOWED`), 조회 도구 승인 `required`(`EVENT_TOOL_NOT_ALLOWED`), 같은 연결의 다른 에이전트가 켬(`EVENT_WATCH_ELSEWHERE`), 바인딩 `PENDING`(`CONNECTION_NOT_READY`). 각 경우 PUT 이 `PROACTIVE_CHECK_UNAVAILABLE` 이고 조회가 그 까닭을 싣는다
- 상한(REQ7): 상태를 오늘 12회로 두면 `dailyLimitReached=true`. 실행 수가 12 미만이어도 마지막 결과가 `DAILY_LIMIT` 이고 마지막 확인이 오늘이면 참, 어제면 거짓
- 다시 켜기는 시간대만 바꾸고 지점을 두며, 틀린 시간대는 `TASK_SCHEDULE_INVALID` 다
- Hermes 장애(`HERMES_UNAVAILABLE`): 조회는 `READINESS_UNKNOWN` 으로 `available=false`, 끄기는 성공, 켜기는 그 오류를 올린다
- 실패(REQ11): 연속 실패 4로 두면 `failing=true`
- 남의 연결: 요청자가 읽을 수 있는 그룹 공개 에이전트에 다른 사용자의 바인딩만 붙어 있으면 조회에 그 연결이 없고 PUT 은 `CONNECTOR_NOT_FOUND`

## 검증

```bash
cd backend && ./gradlew test --tests '*EventWatch*' --tests '*ArchitectureRules*'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/task/application/dto/EventWatchBlocker.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/domain/EventWatchBinding.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorEventWatches.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/task/application/EventWatchService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/application/dto/EventWatchView.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/presentation/EventWatchController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/task/presentation/TaskDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/EventWatchControllerTest.java` | 신규 |
