# 사건 감지형 살펴보기 PR2 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- |
| REQ5 | 사건 감지를 켠 바인딩, 서울 낮 시각, 새 항목 둘, 답이 그중 하나와 입력 밖 식별자 하나를 발견으로 쓴다 | 확인을 돌리고 실행이 끝나기를 기다린 뒤 `notification` 을 읽는다 | 알림 한 건, 「새 메모 2건 중 1건 확인이 필요해요」, 갈 곳은 그 점검 대화, 제목·본문에 발견 제목 없음. 알릴 것이 없는 답이면 알림 0건 | backend 통합(H2, 대역 Hermes·목록 조회) `EventWatchNoticesTest` | 없음 | PR 전 |
| REQ6 | 서울 시간대로 켠 바인딩, 서울 23시 | 23시에 깨운 실행이 끝난 뒤 알림과 상태를 읽고, 시계를 다음 날 07시 뒤로 옮겨 확인을 다시 돈다 | 23시에는 알림 0건, 상태에 미룬 수. 07시 뒤 「밤사이 새 메모 N건 중 M건」 한 건, 미룬 수 비움. 같은 순간 UTC 시간대 바인딩은 곧바로 알림 | backend 통합(고정 시계) `EventWatchNoticesTest` | 없음 | PR 전 |
| REQ7 | 오늘 실행 수가 상한인 바인딩 | 조회 API 를 부르고, 화면 소절을 그 응답으로 그린다 | 응답 `dailyLimitReached=true`(실행 수 상한, 또는 오늘 마지막 결과가 상한), 어제의 상한 결과는 거짓, 화면에 「오늘 상한 도달」 | backend 통합 `EventWatchControllerTest`, 브라우저 `event-watch.spec.ts` | 없음 | PR 전 |
| REQ9 | 셸 toolset 이 켜진 에이전트, 격리 설정 꺼짐 / `delegation` 이 켜진 에이전트 | 켜기 PUT 과 조회를 부른다 | PUT `PROACTIVE_CHECK_UNAVAILABLE`, 조회 까닭 `ISOLATED_EXECUTION_REQUIRED` / `TOOLSETS_NOT_ALLOWED`, 화면에 도구 이름과 까닭, 꺼진 스위치 | backend 통합 `EventWatchControllerTest`, 브라우저 `event-watch.spec.ts` | 없음 | PR 전 |
| REQ10 | 붙인 직후 바인딩 / 조회 도구 승인 `required` / 같은 연결의 다른 에이전트가 켬 / 바인딩 `PENDING` | 조회와 켜기 PUT | 기본 꺼짐. 각 거절과 까닭(`EVENT_TOOL_NOT_ALLOWED`, `EVENT_WATCH_ELSEWHERE`, `CONNECTION_NOT_READY`). 남의 연결은 조회에 없고 PUT 은 `CONNECTOR_NOT_FOUND` | backend 통합 `EventWatchControllerTest` | 없음 | PR 전 |
| REQ11 | 목록 조회가 `credential_rejected` 로 실패하는 바인딩 | 확인을 다섯 번, 성공 한 번, 다시 실패 네 번 돈다 | 넷째에만 `EVENT_WATCH_FAILED` 한 건(갈 곳 외부 서비스 연결), 다섯째 없음, 성공 뒤 넷째에 한 건 더. 조회 `failing=true`, 화면에 「다시 연결이 필요해요」 | backend 통합 `EventWatchNoticesTest`, `EventWatchControllerTest`, 브라우저 `event-watch.spec.ts` | 없음 | PR 전 |

운영 왕복(실제 Gmail 로 한 번)은 이번 PR 의 인수 검증이 아니라 배포 뒤 확인이다. index 의 `deferred` 와 PR 본문 「남은 범위」 가 갖는다.
