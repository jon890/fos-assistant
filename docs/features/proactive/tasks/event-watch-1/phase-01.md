# Phase 01. 모델 없는 확인과 EVENT 살펴보기

**Execution profile**: standard

## 목표

켠 바인딩마다 15분에 한 번 새 항목을 모델 없이 확인하고, 있을 때만 읽기 경계의 `EVENT` 살펴보기를 연다.

**범위 외**: 알림, 조용한 시간, 다시 연결 알림, 켜고 끄는 API 와 화면(PR2).

## 컨텍스트

**요구사항**: REQ1, REQ2, REQ3, REQ4, REQ7, REQ8, REQ9, REQ10, REQ11, REQ12

**근거 문서**: `docs/features/proactive/spec.md`, `docs/features/proactive/design.md`, `docs/adr/ADR-20261105-event-watch.md`

층 순서상 바인딩과 살펴보기를 함께 쓰는 조정자는 `task` 에 둔다. 예약 작업 발화기(`TaskFiring`)는 발화마다 `task_run` 줄을 만들어 쓰지 않는다.

## 의도 메모

- 확인 지점은 Control Plane 의 조회 시작 시각이다. 메일의 `Date` 머리를 쓰지 않는다
- 바인딩 엔티티는 두 컬럼을 넣기와 고치기에서 빼고 native update 로만 쓴다(rule-68)

## 작업 항목

### 1. 대시보드 plugin

`connector_policy._connector_event_watch` 로 선언을 검증하고, `connector_events._connector_events_request` 가 `POST /api/connectors/<id>/events` 를 처리한다. Gmail `assistant.json` 에 `event_watch` 를 더한다.

### 2. Control Plane

`HermesEventFeedClient`, `ConnectorEventWatch`, 마이그레이션과 `ConnectorBinding` 두 컬럼, `EventWatchState`, `ConnectorEventWatches`, `EventWatchPoller`, `ProactiveCheckService.startEvent`, `ProactiveCheckReadiness.checkEvent`, 입력의 「새 사건」 절과 `EVENT_RULES`.

### 3. 검증

`hermes/tests/test_connector_events.py`, `EventWatchPollerTest`, `EventWatchStateTest`, `HttpHermesEventFeedClientTest`.

## 실행 명령

```bash
# cwd: 저장소 root
python3 scripts/checks/run-hermes-python-tests.py test_connector_events.py
# cwd: backend/
./gradlew test --tests '*EventWatch*' --tests '*HttpHermesEventFeedClientTest'
```
