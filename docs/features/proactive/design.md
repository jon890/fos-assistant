# 사건 감지형 살펴보기 기술 설계

스펙은 [spec.md](spec.md), 지금 동작은 [기능 파일](../proactive.md)이 갖는다.
새 신뢰 경계(실행 밖의 메일 목록 조회)의 결정과 근거는 [ADR-20261105 / event-watch](../../adr/ADR-20261105-event-watch.md) 가 갖는다.

## 지금 구조

| 무엇 | 지금 |
| --- | --- |
| 살펴보기 실행 | `ProactiveCheckService.start` 가 점검 대화를 찾거나 만들고, turn 자리를 잡고, `proactive_check` 줄을 저장한 뒤 가상 스레드에서 `ProactiveCheckRun` 을 돌린다. 계기는 `MANUAL`, `SCHEDULED` 다 |
| 매일 깨우기 | `task` 의 `CHECK` 작업과 `TaskFiring` 이 발화마다 `task_run` 줄을 하나 만든다. 조용한 시간(`ProactiveQuietHours`)은 승인 알림만 생략한다 |
| 플러그인 선언 | 대시보드 plugin 이 `assistant.json` 을 검증해 카탈로그로 내고, backend 는 `ConnectorManifest` 로 읽는다. 둘 다 서비스 이름을 모른다(rule-65). backend 에는 `gmail` 이 한 번도 나오지 않는다 |
| 실행 밖 도구 호출 | `POST /api/connectors/<id>/call` 은 확인 도구와 선택지 도구만 인자 없이 부른다. `execute` 는 승인한 호출을 그 profile 의 값으로 한 번 실행한다 |
| 층 순서 | `proactive` 는 `connector` 보다 아래이고 `task` 는 둘보다 위다(`TopLevelPackageOrder`). 그래서 바인딩과 살펴보기를 함께 쓰는 조정자는 `task` 에 둔다 |

## PR 나눔

운영 코드가 PR 하나의 상한을 넘어 둘로 나눈다. 두 PR 모두 이 설계를 쓴다.

| PR | 담는 것 | 요구사항 |
| --- | --- | --- |
| PR1 | 대시보드 plugin 의 `events` 경로와 Gmail 선언, Control Plane 의 조회 클라이언트, 바인딩 줄 두 컬럼과 상태, `EventWatchPoller`, 계기 `EVENT` 살펴보기, 하루 상한, 연결 실패 세기. 켜기는 `ConnectorEventWatches.enable` 만 있고 API 와 화면이 없어 배포해도 켜진 바인딩이 없다 | REQ1, REQ2, REQ3, REQ4, REQ7, REQ8, REQ9(깨우기 쪽), REQ10(켜기 조건), REQ11(세기), REQ12 |
| PR2 | 알림 두 종류와 갈 곳, 조용한 시간 미루기와 아침 묶음, 「다시 연결 필요」 알림, 켜고 끄는 API 와 에이전트 상세 소절 | REQ5, REQ6, REQ9·REQ10(화면), REQ11(알림), REQ7(화면 표시) |

## 요구사항 연결

| ID | 해결 방법 |
| --- | --- |
| REQ1 | `task` 의 `EventWatchPoller` 가 `assistant.event-watch.check-cron`(기본 15분)마다 켜진 바인딩을 차례로 본다. 목록 조회는 대시보드 plugin 의 새 경로 `POST /api/connectors/<id>/events` 이고 모델을 부르지 않는다. 결과는 바인딩 줄의 `event_watch_state` 컬럼 하나에 덮어쓴다. 새 항목이 없으면 `proactive_check` 줄도 실행 줄도 만들지 않고, 하루 실행 수도 세지 않는다 |
| REQ2 | 확인 지점은 마지막으로 결과를 다 쓴 조회를 시작한 Control Plane 의 시각이다. 다음 조회는 지점보다 `overlap`(기본 30분) 앞부터 받고, 그 겹치는 구간에서 이미 본 식별자(`seen`)를 뺀다. 보낸 사람이 쓰는 메일의 `Date` 머리는 지점으로 쓰지 않는다. 미래 날짜 하나가 지점을 밀어 뒤의 메일을 모두 놓치게 하기 때문이다. 상태는 바인딩 줄에 있어 서버를 다시 띄워도 같은 값을 읽는다 |
| REQ3 | 플러그인 선언 `event_watch.query` 가 분류를 빼는 검색식(`-category:promotions -category:social`)을 갖고, `skip_labels` 가 같은 분류의 라벨을 갖는다. plugin 이 결과에서 그 라벨이 붙은 항목을 한 번 더 뺀다. 남은 항목이 없으면 REQ1 의 「새 항목 없음」 이다 |
| REQ4 | 새 항목이 있으면 `ProactiveCheckService.startEvent` 가 계기 `EVENT` 로 살펴보기를 연다. 입력에 「새 사건」 절(원천 이름, 새 항목 수, 식별자와 원문 링크 20개까지)을 `<external-data>` 로 감싸 싣고, 지시에 사건 실행 규칙(`EVENT_RULES`)을 더한다. 분야 지침 스킬은 읽게 하지 않고 준비 판정에서도 보지 않는다. 지점과 `seen` 은 `startEvent` 의 `beforeRun` 으로 살펴보기 줄과 같은 트랜잭션에서 옮긴다. 모델은 붙은 플러그인의 읽기 도구로 내용을 읽는다. 도구 판정과 읽기 경계는 지금의 살펴보기 트리 그대로다 |
| REQ5 | (PR2) 결과 블록에 새 칸을 두지 않는다. 알릴 메일은 「새로 알릴 것」 으로 판정된 `findings` 가운데 `topicKey` 가 입력의 식별자인 것이다. 사건 실행에서는 입력 밖 식별자의 발견을 새 까닭 `NOT_IN_EVENT` 의 「참고」 로 내린다(`FindingJudgement.withinEvent`). 그래서 답에 보이는 「새로 알릴 것」 과 알림의 수가 같고, 침묵 판정도 같은 수로 한다. 질문이나 출처 장애만 있는 실행은 답을 남기되 알림은 없다. 발견 상한이 5개라 한 실행이 알리는 메일도 5통까지이고 나머지 수는 `summary` 에 쓴다. 하나 이상이면 실행이 끝난 뒤 `EventCheckEnded` 사건을 받은 `EventWatchNotices` 가 알림 `EVENT_CHECK_REPORTED` 한 건(「새 메일 N건 중 M건 확인이 필요해요」, 제목 없음)을 점검 대화로 만든다. 답에 보일 메일 목록과 까닭, 원문 링크는 지시가 `findings` 한 칸씩 쓰게 하고 지금의 `CheckAnswerRenderer` 가 그린다. 제안은 지금의 `suggestions` 저장과 상한을 그대로 쓴다 |
| REQ6 | (PR2) 켤 때 받은 시간대(없으면 `Asia/Seoul`)를 상태에 둔다. 실행이 끝난 시각이 그 시간대의 22시~07시면 알림을 만들지 않고 상태의 `deferred` 에 실행 수, 새 항목 수, 알릴 수를 더한다. 알릴 것이 없던 실행도 새 항목 수는 더한다. 07시 뒤 첫 확인에서 알릴 수가 1 이상이면 「밤사이 새 메일 N건 중 M건 확인이 필요해요」 한 건을 만들고 비운다. 다시 연결 알림은 조용한 시간에 내지 않고, 07시 뒤에도 실패가 이어지면 그 확인에서 낸다(결정 DECISION10) |
| REQ7 | 상태의 `day`(그 시간대의 날짜)와 `runs` 로 바인딩마다 하루 사건 실행 수를 센다. 상한 `max-runs-per-day`(기본 12)에 닿으면 그날은 목록을 조회하지 않고 지점을 옮기지 않으며 마지막 결과를 `DAILY_LIMIT` 으로 적는다. 다음 날 첫 확인은 옮기지 않은 지점에서 받아 쌓인 메일을 함께 본다. 사용자의 하루 발화 상한(`assistant.task.max-runs-per-day`, ADR-079)도 예약 작업의 발화와 모델을 부른 사건 실행을 함께 센다. `TaskFiring` 도 같은 합으로 본다. 화면의 「오늘 상한 도달」 은 PR2 다 |
| REQ8 | `EVENT` 살펴보기는 시작할 때 `writes_allowed` 를 에이전트 값과 상관없이 거짓으로 적는다. 그래서 지금의 읽기 경계가 플러그인 쓰기 도구를 거절하고, `follow_up_propose` 는 지금처럼 받는다 |
| REQ9 | 사건 실행의 준비 판정은 `ProactiveCheckReadiness.checkEvent` 다. 에이전트의 쓰기 허용과 상관없이 읽기 경계의 toolset 목록만 받는다. 쓰기 허용 에이전트의 넓은 목록(browser, 외부 메시지 등)을 받으면 메일 본문이 그 도구를 부를 수 있어서다. 셸·파일·코드 실행 toolset 은 `isolated-execution-enabled` 가 참일 때만 받고, 거짓이면 `ISOLATED_EXECUTION_REQUIRED` 로 막는다. `delegation` 은 목록 밖이라 막힌다. `web` 은 사용자 결정(스펙 DECISION24)으로 받는다 |
| REQ10 | 기본은 꺼짐(`event_watch_enabled = false`)이다. 켜려면 그 에이전트에 붙은 연결의 바인딩과 연결이 `READY` 이고, manifest 가 `event_watch` 를 선언했고, 그 조회 도구의 선언이 `READ` 이고 승인 `none` 이어야 한다(살펴보기 트리가 승인 없이 부를 수 있는 도구). 같은 연결의 바인딩을 모두 잠근 뒤 다른 바인딩이 켜져 있으면 거절한다 |
| REQ11 | 조회가 실패하면 지점을 둔다. `credential_rejected` 와 `forbidden` 만 `failures` 를 늘린다. 동시 호출 한도나 Hermes 장애의 `unavailable` 은 다시 연결할 일이 아니라서다. (PR2) `failure-threshold`(기본 4, 한 시간)에 처음 닿을 때 알림 `EVENT_WATCH_FAILED`(「Gmail 다시 연결이 필요해요」) 한 건을 외부 서비스 연결 화면으로 낸다. 알림 표시는 상태의 `failureNotified` 다. 성공하면 `failures` 와 알림 표시를 비운다. 상태 응답의 `failing` 으로 화면이 다시 연결하라고 보인다 |
| REQ12 | `startEvent` 가 `USER_BUSY` 나 `CONVERSATION_BUSY` 로 거절되면 지점과 `seen` 을 옮기지 않고 결과를 `BUSY` 로 적는다. 다음 15분에 같은 메일을 다시 받는다. 읽지 않은 보고의 건너뛰기는 `SCHEDULED` 만 하므로 `EVENT` 는 받지 않는다. 거꾸로 사건 실행이 남긴 보고도 매일 깨우기의 건너뛰기 판정에서 뺀다(매일 깨우기를 바꾸지 않는다). 점검 대화는 `CheckConversations.findOrCreate` 가 없으면 새로 만든다 |

## 책임과 경계

| 모듈 | 맡는 것 |
| --- | --- |
| 대시보드 plugin | `event_watch` 선언 검증과 카탈로그 노출, `events` 경로. 그 profile 에 설치한 연결의 값으로 조회 도구를 부르고 식별자, 받은 시각, 라벨만 돌려준다. 서비스 이름을 모른다 |
| Gmail 플러그인 | `assistant.json` 의 `event_watch` 선언 하나. 도구 코드는 바꾸지 않는다 |
| `hermes`(backend) | 새 port `HermesEventFeedClient` 와 HTTP 구현 `HttpHermesEventFeedClient`, 카탈로그의 `event_watch` 해석(`ConnectorEventWatch`) |
| `connector.connection` | 바인딩 줄의 두 컬럼을 갖는다. `ConnectorEventWatches` 가 켜기 조건과 같은 연결의 하나뿐 규칙, 켜진 바인딩 목록, 잠금 아래 상태 갱신을 맡는다. 다른 패키지는 그 컬럼을 직접 쓰지 않는다 |
| `proactive.check` | 계기 `EVENT`, `startEvent`, `checkEvent`, 입력의 「새 사건」 절, 지시의 사건 실행 규칙. (PR2) 끝날 때 알릴 메일 수를 실은 사건. 바인딩과 알림을 모른다 |
| `task` | `EventWatchPoller`(주기 확인과 깨우기, 가상 스레드 하나에서 돈다), (PR2) `EventWatchNotices`(알림, 조용한 시간 미루기), `EventWatchService` 와 `EventWatchController`(에이전트 상세의 조회와 켜고 끄기. 켜기는 요청자 자신의 연결만 다룬다). 막는 까닭은 `checkEvent` 의 까닭에 연결 쪽 까닭을 더하고, Hermes 장애를 `READINESS_UNKNOWN` 으로 바꾸는 것만 매일 깨우기와 같다 |
| `notification` | (PR2) 종류 `EVENT_CHECK_REPORTED`, `EVENT_WATCH_FAILED` 와 갈 곳 `CONNECTIONS`(목록 화면). 목록 갈 곳은 `ADMIN_CONNECTIONS` 처럼 공개 식별자 없이 둔다 |
| web | 먼저 살펴보기 절 끝의 「새 항목 살펴보기」 소절(`agent-event-watch-section.tsx`, 매일 깨우기 소절 아래)과 그 도우미(`lib/event-watch.ts`, 까닭 문장 포함), 서버 라우트 `app/api/agents/[code]/event-watches/**`, 알림 종류와 갈 곳(`lib/notification.ts`) |

`task` 가 `connector` 와 `proactive` 를 쓰는 방향은 지금 층 순서 그대로다. 새 최상위 패키지와 새 하위 기능은 없다.

## 인터페이스와 데이터

### 플러그인 선언 `event_watch`

`assistant.json` 의 새 칸이다. 칸의 계약은 [`hermes/plugins/dashboard-profile-api/README.md`](../../../hermes/plugins/dashboard-profile-api/README.md) 의 선언 표가 갖는다.

| 칸 | 뜻 |
| --- | --- |
| `tool` | 조회 도구. `tools` 에 `READ` 로 선언한 도구여야 한다 |
| `args` | 도구 인자. 글 값의 `{since}` 를 조회 시작 시각(초 단위 epoch)으로 바꾼다 |
| `items` | 결과에서 항목 배열의 칸 이름 |
| `page_token` | 다음 쪽 식별자의 결과 칸과 인자 이름. 선택 |
| `id`, `received_at`, `labels` | 항목에서 읽을 칸 이름. 이 셋만 돌려준다 |
| `skip_labels` | 이 라벨이 붙은 항목은 돌려주지 않는다 |
| `noun` | 화면과 알림이 쓰는 항목 이름(「메일」) |
| `link` | 원문 링크 틀. `{id}` 를 식별자로 바꾼다. `https` 만 받는다 |

### 대시보드 plugin 경로

`POST /api/connectors/<id>/events`, 서비스 토큰. 본문은 `{"profile", "since"}` 만 받는다. `since` 는 0 이상의 정수 초다.
`execute` 와 같은 확인(관리 표식, 설치 기록, 그 profile 의 값)을 거친 뒤 `tool` 을 쪽마다 부르고, 쪽 상한(4쪽)에 닿으면 멈춘다.
답은 `{"ok": true, "items": [{"id", "received_at", "labels"}], "truncated"}` 이거나 `call` 과 같은 `{"ok": false, "error"}` 다.
`received_at` 은 읽을 수 있으면 UTC ISO-8601, 아니면 null 이다. 식별자는 영문, 숫자, `_`, `-` 만 받고 128자까지다. 제목, 보낸 사람, 요약은 응답과 로그에 싣지 않는다.

### 바인딩 줄의 두 컬럼

`agent_connector_binding` 에 컬럼 둘을 더한다. 새 테이블은 없다.

| 컬럼 | 타입 | 뜻 |
| --- | --- | --- |
| `event_watch_enabled` | `BOOLEAN NOT NULL DEFAULT FALSE` | 사건 감지를 켰는가 |
| `event_watch_state` | `TEXT NULL` | 켤 때 만들고 확인마다 덮어쓰는 JSON 글(`EventWatchState`). 시간대, 지점, `seen`, 마지막 확인 시각과 결과, 새 항목 수, 연속 실패 수, 하루 실행 수와 날짜. PR2 가 알림 표시와 미룬 알림을 더한다 |

엔티티는 두 컬럼을 넣기와 고치기에서 뺀다(`insertable = false, updatable = false`). 다른 흐름이 바인딩 엔티티를 저장할 때 상태를 옛 값으로 덮지 않게 하기 위해서다(rule-68). 쓰기는 `ConnectorEventWatches` 의 조건부 update 뿐이다. `updated_at` 은 승인의 바인딩 revision 이라 바꾸지 않는다.
JSON 컬럼이 아니라 `TEXT` 인 까닭은 H2 의 `JSON` 컬럼이 글 인자를 JSON 문자열 하나로 저장해 두 DB 의 동작이 갈리기 때문이다. DB 가 이 컬럼을 조회하지 않는다.

### 계기와 결과 블록

- `CheckTrigger.EVENT`. `unattended()` 가 참이고, 예약 실행처럼 알릴 것이 없으면 대화와 보고를 남기지 않는다
- 결과 블록은 바꾸지 않는다. 알릴 메일은 `findings` 의 `topicKey` 로 받는다(REQ5)
- `EventBatch(sourceId, sourceTitle, noun, link, newCount, ids)`. `sourceId` 는 바인딩 번호이고 `proactive` 는 그 뜻을 모른다
- (PR2) 끝날 때 `EventCheckEnded(userId, conversationId, batch, notifyCount, endedAt)` 를 낸다. turn 이 답을 저장하고 돌아왔을 때만 낸다. 실패하거나 멈춘 실행은 점검 대화의 알림 줄이 알린다. `notifyCount` 는 「새로 알릴 것」 으로 남은 발견 가운데 `topicKey` 가 `batch.ids` 안에 있는 것의 서로 다른 수다. 침묵한 실행은 0 이다

### Control Plane API(PR2)

| 경로 | 뜻 |
| --- | --- |
| `GET /api/v1/agents/{code}/event-watches` | 그 에이전트에 붙은 요청자 연결 가운데 `event_watch` 를 선언한 것마다 켜짐, 켤 수 있는지와 까닭, 시간대, 마지막 확인과 결과, `dailyLimitReached`, `failing` |
| `PUT /api/v1/agents/{code}/event-watches/{connectorId}` | 본문 `{enabled, timezone}`. 켜기는 대화를 시작할 수 있어야 하고(`requireStartable`), 끄기는 읽기 권한만 본다 |

막는 까닭은 `{code, toolsets}` 꼴이다. 사건 실행의 준비 판정(`checkEvent`)이 낸 까닭(`DISABLED`, `TOOLSETS_NOT_ALLOWED`, `ISOLATED_EXECUTION_REQUIRED`)에 연결 쪽 까닭 `EVENT_TOOL_NOT_ALLOWED`, `CONNECTION_NOT_READY`, `EVENT_WATCH_ELSEWHERE` 를 더한다. 연결 쪽 까닭은 `proactive` 가 모르는 뜻이라 `CheckBlockerCode` 에 넣지 않고 `task` 의 값(`EventWatchBlocker`)으로 둔다. 분야 지침 스킬(`SKILL_MISSING`)은 보지 않는다. 매일 깨우기의 `ProactiveScheduleService.blockers` 를 쓰지 않는 까닭은 그것이 `readiness.check`(스킬과 쓰기 허용 목록)를 거치기 때문이다. 조회에서 Hermes 를 읽지 못하면 매일 깨우기 조회처럼 `READINESS_UNKNOWN` 하나로 바꿔 켤 수 없다고 보이고, 끄기는 그대로 받는다. 켜기는 Hermes 실패를 그대로 올린다. 준비 판정은 트랜잭션 밖에서 하고 그 뒤에 `ConnectorEventWatches.enable` 의 트랜잭션을 연다. 잠그는 동안 Hermes 를 부르지 않는다.
켜기를 거절하면 `PROACTIVE_CHECK_UNAVAILABLE`, 그 에이전트에 요청자의 그 연결이 붙어 있지 않으면 `CONNECTOR_NOT_FOUND` 다. `timezone` 이 비면 `assistant.task.default-time-zone`(Asia/Seoul) 이고, 읽지 못하면 매일 깨우기와 같은 `TASK_SCHEDULE_INVALID` 다.
`dailyLimitReached` 는 그 시간대의 오늘 사건 실행 수가 바인딩 상한에 닿았거나 오늘 마지막 확인이 `DAILY_LIMIT` 이면 참이다. 사용자 하루 상한에 닿은 날도 보이기 위해서다. `failing` 은 연속 실패 수가 `failure-threshold` 이상이면 참이다.

### 알림과 상태(PR2)

| 알림 | 언제 | 제목과 본문 | 누르면 |
| --- | --- | --- | --- |
| `EVENT_CHECK_REPORTED` | 조용한 시간 밖에서 끝난 사건 실행의 `notifyCount` 가 1 이상 | 「새 메일 N건 중 M건 확인이 필요해요」, 본문은 원천 이름(플러그인 제목). `메일` 은 선언의 `noun` 이다 | 그 점검 대화 |
| `EVENT_CHECK_REPORTED`(아침 묶음) | 조용한 시간이 끝난 뒤 첫 확인에서 미룬 알릴 수가 1 이상 | 「밤사이 새 메일 N건 중 M건 확인이 필요해요」 | 마지막으로 미룬 실행의 점검 대화. 지워졌으면 갈 곳 없음 |
| `EVENT_WATCH_FAILED` | 연속 실패 수가 `failure-threshold` 이상인 확인 가운데 조용한 시간 밖의 첫 확인 | 「Gmail 다시 연결이 필요해요」(원천 이름), 본문은 외부 서비스 연결에서 다시 연결하라는 한 줄 | 외부 서비스 연결(`/connections`). 목록이라 공개 식별자가 없다 |

`EventWatchState` 에 두 칸을 더한다. 옛 JSON 은 칸이 없어 기본값으로 읽는다. 상태를 바꾸는 메서드와 다시 켜기(시간대만 바꾼다)는 두 칸을 그대로 옮긴다.

| 칸 | 뜻 |
| --- | --- |
| `failureNotified` | 이번 연속 실패에 다시 연결 알림을 냈는가. 목록을 받으면(`reachable`) 실패 수와 함께 비운다 |
| `deferred` | 조용한 시간에 미룬 실행 수, 새 항목 수, 알릴 수와 마지막 실행의 점검 대화 번호, 원천 이름과 항목 이름. 이름을 두는 까닭은 아침에 카탈로그를 읽지 못해도 묶음을 내기 위해서다. 아침 묶음을 내면 비운다. 비었으면 null |

`task` 의 `EventWatchNotices` 가 셋을 모두 만든다. 조용한 시간 판정은 `ProactiveQuietHours` 의 22시~07시를 같은 값으로 쓰고, 시간대는 상태의 `timeZone` 이다.

- `EventCheckEnded` 를 받으면 새 트랜잭션에서 바인딩 줄을 잠그고 끝난 시각이 조용한 시간이면 `deferred` 에 더한다. 아니면 `notifyCount` 가 1 이상일 때 알림을 만든다. 상태 갱신과 알림은 한 트랜잭션이다
- 확인(`EventWatchPoller.check`)의 맨 앞에서 조용한 시간 밖이고 `deferred` 가 있으면 비우고, 알릴 수가 1 이상이면 아침 묶음 한 건을 만든다. 막혔거나 상한에 닿은 확인에서도 먼저 한다
- 끄기는 상태를 지우므로, 끄는 트랜잭션에서 지우기 전에 `deferred` 를 조용한 시간과 상관없이 아침 묶음 한 건으로 낸다(리뷰 반영)
- 조회가 실패해 `failed` 를 적는 트랜잭션에서, 실패 수가 정한 횟수 이상이고 `failureNotified` 가 거짓이며 조용한 시간 밖이면 참으로 적고 알림을 만든다

## 실패와 일관성

- **상태 갱신은 바인딩 줄을 잠그고 다시 읽은 뒤 쓴다.** 확인 스레드와 실행이 끝난 스레드가 같은 줄을 고칠 수 있어서다. 잠그는 동안 Hermes 를 부르지 않는다. 조회는 잠금 밖에서 하고, 결과를 쓸 때 다시 잠가 그 사이 꺼졌으면 버린다
- **지점은 깨우기를 시작하는 트랜잭션에서 옮긴다.** `startEvent` 의 `beforeRun` 이 살펴보기 줄 저장과 같은 트랜잭션에서 상태를 쓴다. 그래서 서버가 그 사이에 내려가도 둘이 어긋나지 않는다. 시작이 거절되면(바쁨, 막는 까닭, 상한) 옮기지 않는다. 시작한 실행이 뒤에 실패해도 같은 메일로 다시 깨우지 않는다. 실패는 점검 대화의 알림 줄과 실행 기록이 남긴다(REQ2 가 앞선다)
- **쪽 상한을 넘으면 최근 것만 본다.** `truncated` 면 받은 항목으로 깨우고 지점을 옮긴다. 4쪽(Gmail 은 100통)을 넘는 메일이 15분 안에 오면 오래된 쪽은 깨우지 않는다. 입력에는 새 항목을 20개까지 싣고 수는 모두 센다
- **서버 한 대 전제다.** 확인은 `BackgroundTasks` 의 가상 스레드 하나가 차례로 돈다. 일정 스레드를 붙잡지 않고, 앞 확인이 안 끝났으면 다음 일정은 건너뛴다. 기동 정리(`TaskRunRecovery`)가 끝나기 전에는 돌지 않는다
- **매 확인 앞에서 값싼 조건을 다시 본다.** 바인딩과 연결이 `READY` 인지, 주인이 허용 목록에 있고 그 에이전트로 대화를 시작할 수 있는지, 조회 도구가 아직 승인 없이 불리는지를 본다. 하나라도 아니면 조회하지 않고 `BLOCKED` 다. toolset 판정은 Hermes 를 불러 깨울 때만 한다
- **(PR2) 알림은 그 원인의 상태 갱신과 한 트랜잭션이다.** 미루기와 아침 묶음, 다시 연결 알림 모두 바인딩 줄을 잠근 트랜잭션 안에서 알림을 만든다. 둘 중 하나만 남지 않는다. 실행이 끝난 사건은 turn 스레드에서 동기로 받아 새 트랜잭션을 연다. 실패해도 살펴보기의 끝 정리는 계속된다(`recordQuietly`). 그 사이 바인딩을 껐으면 미룰 자리와 시간대가 없어 알릴 항목이 있으면 바로 알린다. 이미 사용자가 볼 답이 대화에 있기 때문이다
- **(PR2) 켜기의 조건은 두 번 본다.** `EventWatchService` 가 준비 판정과 연결·도구·하나뿐 조건으로 까닭을 모아 거절하고, `ConnectorEventWatches.enable` 이 같은 연결의 바인딩을 잠근 뒤 연결·도구·하나뿐을 다시 본다. 두 요청이 같은 연결의 다른 에이전트를 함께 켜도 하나만 남는다
- **(PR2) 답을 저장한 뒤 사건을 내기 전에 서버가 내려가면 그 실행의 알림은 없다.** 기동 복구(`ProactiveCheckRecovery`)는 알림을 다시 만들지 않는다. 답은 점검 대화와 지금 화면의 보고에 남아 사용자가 볼 수 있어 받아들인다
- **배포 순서는 plugin 먼저다.** 옛 plugin 은 `events` 경로가 없어 404 이고 Control Plane 은 실패로 센다. 옛 Control Plane 은 카탈로그의 새 칸을 읽지 않는다(rule-66)
- **session 교체가 빨라진다.** 사건 실행도 `session-max-checks`(14회)를 센다. 하루 상한 12회를 다 쓰면 하루에 한 번쯤 점검 대화의 session 이 바뀐다. 이전 맥락은 Memory 와 입력의 최근 제안·보고가 잇는다

## 검증 전략

| ID | 경계 | 확인 |
| --- | --- | --- |
| REQ1 | backend 통합(가짜 Hermes 클라이언트) | 새 항목 없는 확인 뒤 `proactive_check`, `agent_execution` 줄 수가 그대로이고 상태만 바뀐다 |
| REQ2 | backend 통합 | 같은 항목을 두 번 돌려줘도 깨우기는 한 번. 저장소에서 다시 읽은 상태로 새 poller 를 만들어도 같다. 지점보다 앞이지만 겹침 안의 새 식별자는 깨운다 |
| REQ3 | hermes 단위 | `skip_labels` 항목을 빼고, 검색식에 분류 제외가 들어간다 |
| REQ4 | backend 단위와 통합 | 입력에 「새 사건」 절과 식별자가 `<external-data>` 안에 있고 계기가 `EVENT` 다 |
| REQ5(PR2) | backend 단위와 통합 | 입력 밖 식별자와 참고로 내려간 발견을 센 수에서 뺀다. 알림이 한 건이고 갈 곳이 점검 대화이며 제목과 본문에 메일 제목이 없다. 알릴 것이 없으면 알림이 없다 |
| REQ6(PR2) | backend 통합(고정 시계) | 그 시간대의 23시에 끝난 실행은 알림이 없고, 07시 뒤 첫 확인에서 「밤사이」 한 건이 생긴다. 시간대가 다르면(UTC) 같은 시각이 조용한 시간이 아니다 |
| REQ7 | backend 통합 | 상한에 닿은 뒤 조회를 부르지 않고 지점이 그대로다. 날이 바뀌면 다시 깨운다 |
| REQ7(PR2 화면) | backend 통합과 브라우저 | 상한에 닿은 바인딩의 조회 응답이 `dailyLimitReached` 이고 화면이 「오늘 상한 도달」 을 보인다 |
| REQ8 | 기존 계약 테스트 | `writes_allowed` 가 거짓으로 적힌다. 쓰기 거절은 `WakeWriteBoundaryContractTest` 의 경계를 그대로 쓴다 |
| REQ9, REQ10(PR2) | backend 통합 | 켜기 API 가 셸 toolset 과 격리 꺼짐, 조회 도구 승인 필요, 다른 에이전트에서 켜짐, 연결이 준비되지 않음을 각각 거절하고 조회가 그 까닭을 싣는다. 기본은 꺼짐이다 |
| REQ9, REQ10 | backend 통합 | 셸 toolset 과 격리 꺼짐, 쓰기 허용 에이전트의 browser toolset 이면 깨우지 않는다. 조회 도구 승인 필요, 다른 에이전트에서 켜짐이면 켜기가 거절된다 |
| REQ11 | backend 통합 | 연결 문제만 실패 수를 늘리고 지점은 그대로이며, 성공하면 비운다. (PR2) 네 번째 연속 실패에서만 알림 한 건이고 다섯째에는 없으며, 성공 뒤 다시 네 번이면 한 건 더 |
| REQ12 | backend 통합 | 대화가 바쁘면 지점이 그대로이고 다음 확인에서 깨운다 |

화면은 PR2 의 브라우저 테스트 하나가 소절의 켜기와 상한·실패 표시를 본다. 실제 Gmail 왕복은 배포 뒤 운영 확인이 맡는다.

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | 상태 자리 | 상태는 바인딩 줄의 두 컬럼에 둔다. 예약 작업 줄이나 `task_run` 을 쓰지 않는다 | 작성자 | 「같은 연결에 하나뿐」 과 도구 허용이 바인딩 단위이고, 떼면 상태도 함께 사라진다. `TaskFiring` 은 발화마다 `task_run` 줄을 만들어 REQ1 의 「행을 쌓지 않는다」 와 맞지 않는다 |
| DECISION2 | 발화기 | 발화기는 따로 둔다(`EventWatchPoller`). 예약 작업 발화기를 다시 쓰지 않는다 | 작성자 | 위와 같은 까닭이고, 확인은 모델 없이 끝나 turn 자리와 시작 단계가 필요 없다 |
| DECISION3 | 설정 값 | 하루 상한 12회, 연속 실패 4회(PR2), 겹침 30분, 쪽 상한 4쪽, 입력 식별자 20개 | 작성자 | 깨어 있는 시간에 한 시간 남짓마다 한 번 살펴볼 수 있는 수다. 실패 4회는 한 시간이라 잠깐의 장애로 알리지 않는다. 값은 설정(`assistant.event-watch`)이 갖는다 |
| DECISION4 | 확인 지점 | 지점은 Control Plane 의 조회 시작 시각이다 | 작성자 | 메일의 `Date` 는 보낸 사람이 쓰는 값이다(REQ2) |
| DECISION5 | 알릴 메일과 답의 목록 | 메일 목록과 까닭은 결과 블록의 `findings` 로 그리고, 알릴 메일 수도 그 `topicKey` 로 센다 | 작성자 | 블록 밖의 글은 버리는 계약(ADR-081)을 지키고 새 그리기 틀을 만들지 않는다. 별도 칸(`notifyItems`)을 두면 답에 없는 메일로 알리거나 답에만 있고 알림이 없는 경우가 생긴다(독립 검토) |
| DECISION6 | 원천 일반화 | 플러그인 선언으로 원천을 일반화한다 | 작성자 | rule-65. 다음 원천(가계부, 토스, 커리어)은 선언만 더한다 |
| DECISION7 | 조회 클라이언트 | 사건 실행의 조회 클라이언트는 새 port `HermesEventFeedClient` 로 둔다 | 작성자 | `HttpHermesConnectorClient` 는 파일 길이 상한 가까이 있고, 목록 조회는 다른 읽기 제한(40초)을 쓴다 |
| DECISION8 | 연속 실패로 세는 오류 | 연속 실패는 `credential_rejected` 와 `forbidden` 만 센다 | 작성자 | `unavailable` 은 동시 호출 한도나 장애로도 나와 다시 연결할 일이 아니다(독립 검토) |
| DECISION9 | (PR2) 끝난 실행을 알림으로 잇는 길 | 실행이 끝났다는 사건(`EventCheckEnded`)을 `proactive` 가 내고 `task` 가 받아 알린다 | 작성자 | `proactive` 는 바인딩과 알림을 모른다(층 순서). 살펴보기 끝에 `orchestration` 이 받는 `ProactiveCheckEnded` 처럼 아래 층이 사건을 내고 위 층이 받는다 |
| DECISION10 | (PR2) 다시 연결 알림과 조용한 시간 | 조용한 시간에는 내지 않고, 07시 뒤 첫 실패 확인에서 낸다. 아침 묶음에 섞지 않는다 | 작성자 | 스펙 REQ6 이 조용한 시간에 생긴 알림을 미룬다. 실패가 07시 전에 풀리면 다시 연결할 일이 없어 알리지 않는다. 묶음은 「새 메일 N건」 꼴이라 종류가 다른 알림을 섞지 않는다(독립 검토) |
| DECISION11 | (PR2) 알릴 것 없는 아침 묶음 | 아침 묶음은 알릴 수가 0 이면 만들지 않는다 | 작성자 | 「새 메일 N건 중 0건」 은 할 일이 없는 알림이다. 스펙 REQ5 의 「알릴 것이 없으면 알림이 없다」 와 같다 |
| DECISION12 | (PR2) 화면의 시간대 입력 | 화면은 시간대를 매일 깨우기처럼 글 칸으로 받고 기본값을 `Asia/Seoul` 로 채운다 | 작성자 | 스펙 DECISION7. 브라우저 시간대를 몰래 쓰지 않아 저장된 값이 화면에 보인다 |
| DECISION13 | (PR2) 입력 밖 발견 | 사건 실행에서 `topicKey` 가 입력 식별자가 아닌 발견을 `NOT_IN_EVENT` 의 「참고」 로 내린다 | 작성자 | 답의 「새로 알릴 것」 과 알림 수가 갈리지 않게 한다. 입력 밖 식별자는 알릴 항목으로 받지 않는다는 ADR-20261105 / event-watch 의 결정을 답에도 적용한다(독립 검토) |
