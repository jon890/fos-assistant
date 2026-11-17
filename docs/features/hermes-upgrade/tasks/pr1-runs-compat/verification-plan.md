# PR1 실행 클라이언트 호환 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ1 | v0.21.6 모양과 v0.23.2 모양의 조회 응답을 내는 HTTP 대역 | `HttpHermesRunsClientCompatTest` 로 `queued` 뒤 `completed`, v0.21.6 의 404·`interrupted` 를 읽는다 | `queued` 는 끝나지 않은 것으로 읽혀 다시 묻고, v0.21.6 결과는 지금과 같다 | 로컬 backend 단위 테스트 | worker | 없음 | PR 전 |
| REQ2 | 도는 실행을 `unknown` 으로 답하는 대역 | 조회 경로(`lookupRun`), turn 경로(`awaitCompletion`), 중지(409 `unknown_execution`), `releaseIfLost` 를 각각 부른다 | 해제 요청이 `admission_id`, `execution_generation` 두 칸으로 한 번 가고, `lookupRun` 은 `NOT_FOUND`, turn 은 `REMOTE_RUN_LOST`, 중지는 정상 반환이다. 해제 409 뒤 다시 조회가 `unknown` 이면 실패로 올린다 | 로컬 backend 단위 테스트 | worker | 없음 | PR 전 |
| REQ2 | 같은 profile 에 연결 실패·시간 초과로 끝난 실행, 다른 오류 코드 실행, 24시간 지난 실행, 다른 profile 실행이 있는 H2 저장소 | `LostRunRelease.beforeSubmit` 을 부른다 | 연결 실패·시간 초과로 24시간 안에 끝난 그 profile 의 run 번호만 `releaseIfLost` 로 넘어간다 | 로컬 backend 통합 테스트(H2) | worker | 없음 | PR 전 |
| REQ2 | v0.23.2 모양으로 띄운 가짜 Hermes | 대화 turn 이 도는 중 재기동 흉내를 보내고, 같은 대화에 다음 글을 보낸다 | 그 turn 은 실패로 보이고 다음 글의 답이 끝까지 온다 | Draft CI 의 E2E(가짜 Hermes) | worker | push 뒤 Draft CI | PR 뒤 |
| REQ3 | 종료 사건을 보관소에 둔 경우, 보관분에서만 얻는 경우, 둘 다 없는 경우 | `toResult` 결과의 `error`, `pendingSteer`, `pendingSteerKnown`, 상태를 본다 | 앞 둘은 종료 사건의 값이 채워지고, 셋째와 `run.interrupted` 는 `pendingSteerKnown=false` 다. `cancelled` 는 설계의 네 경우대로 `cancelled`/`interrupted` 로 나뉜다 | 로컬 backend 단위 테스트 | worker | 없음 | PR 전 |
| REQ3 | 끼운 글이 있는 turn 이 `pendingSteerKnown=false` 결과로 끝남(중지 확인된 turn 포함) | steer 정리를 부른다 | 말풍선이 「전달됐는지 확인 못 함」 이고 같은 글이 멈춘 대기 줄에 있다 | 로컬 backend 테스트 | worker | 없음 | PR 전 |
| REQ4 | v0.23.2 합성 사건 fixture | `HermesRunEventStreamTest` 로 파싱한다 | `tool_call_id` 가 있어도 도구 사건이 지금처럼 저장되고, `duration` 0 은 null, `run.queued` 는 버려진다 | 로컬 backend 단위 테스트 | worker | 없음 | PR 전 |
| REQ9 | PR1 의 문서 변경 | ADR-061 표와 계약 문서 Runs 절을 diff 로 읽고 `node --test test/repo/doc-files.test.ts`, `check-local.sh --fast` 를 돌린다 | `queued`, `unknown`, 해제, 빠진 조회 칸, 오지 않는 사건이 적혀 있고 검사가 통과한다 | 로컬 저장소 검사 | worker | 없음 | PR 전 |

운영 왕복(REQ11)은 이 PR 의 인수 검증이 아니다. 운영 Hermes 를 올린 뒤 스펙의 「배포 뒤 왕복 확인」 으로 본다.
