# 실행 추적 PR1 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ1 | `duration` 0, `tool_call_id`, `timestamp` 가 붙은 v0.23.2 모양의 도구 사건 | `HermesRunEventStreamTest` 로 1.2초 도구를 흘리고 대화 사건과 저장 사건의 `durationMs` 를 본다. 웹 단위 테스트로 null 시간 줄을 그린다 | 둘 다 약 1200. 짝 없는 줄은 null 이고 실행 트리에 「모름」 | 로컬 Gradle 테스트(H2), 웹 단위 테스트 | worker | 없음 | PR 전 |
| REQ2 | 합성 사용자와 도는 실행, 비밀값 모양 글과 UUID 가 든 오류 글 | `RunTraceEndpointTest` 로 서명한 기록을 보내고 저장 줄을 조회한다 | 가린 요약이 200자 안, 오류 종류 그대로. 플러그인 도구는 종류만 | 로컬 MockMvc 와 H2 | worker | 없음 | PR 전 |
| REQ3 | 오류 요약이 있는 실행 | `ExecutionTreeServiceTest` 로 `MEMBER` 와 `ADMIN` 응답을 비교한다 | `MEMBER` 는 두 칸 null, 실패와 시간은 있음 | 로컬 Gradle 테스트 | worker | 없음 | PR 전 |
| REQ6 | 인자·결과 자리에 표식 글을 넣은 hook 인자 | plugin 단위 테스트로 본문을 보고, 엔드포인트 테스트로 저장값과 로그를 본다 | 본문, 저장값, 로그 어디에도 표식 글이 없다. 경계에 걸친 비밀값 조각도 없다 | 로컬 unittest, MockMvc | worker | 없음 | PR 전 |
| REQ7 | 5초 늦는 가짜 서버, 500 을 주는 가짜 서버 | `test_fos_ctx_run_trace` 로 hook 을 부르고 돌아오는 시간을 잰다 | 0.1초 안에 돌아오고 예외 없음. 로그에는 실패 종류만 | 로컬 unittest | worker | 없음 | PR 전 |
| REQ12 | 플러그인 호출 뒤 빈 내용과 실패한 일반 도구 | `ToolDetailRedactorTest` 와 웹 단위 테스트 | 빈 줄에 「0자」 없음, 실패 줄에 오류 종류와 시간(관리자) | 로컬 Gradle, 웹 단위 테스트 | worker | 없음 | PR 전 |
| REQ1, REQ2 | 운영 배포(plugin 과 Control Plane) | 실제 실행 하나에서 도구를 부르고 실패하는 도구를 한 번 섞은 뒤 관리자 실행 상세를 연다 | 도구 줄에 실제 걸린 시간, 실패 줄에 가린 오류 요약 | 운영 | 코디네이터 | 배포(PR 머지 뒤 plugin 과 Control Plane) | PR 뒤 |
