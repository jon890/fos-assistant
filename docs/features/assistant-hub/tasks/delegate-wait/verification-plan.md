# 맡긴 일 기다리기 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ1 | 기다림을 켠 통합 테스트, 곧 끝나는 자식 | `McpAgentToolsTest` 의 기다림 사례 | 도구 결과에 결과가 있고 `result_delivered_at` 이 적혔으며, turn 이 닫힌 뒤 결과 알림 줄이 생기지 않는다 | backend 테스트 | worker | 없음 | PR 전 |
| REQ1 | 기다림을 켠 통합 테스트, 루트 turn 중지 | `McpAgentToolsTest` 의 중지 사례 | 기다림이 곧 풀리고 전달 표시가 비어 결과가 지금 깨우기에 남는다 | backend 테스트 | worker | 없음 | PR 전 |
| REQ1 | 먼저 살펴보기 트리와 손자 위임 | `McpAgentToolsTest` 의 대상 밖 사례 | 기다리지 않고 바로 `RUNNING` | backend 테스트 | worker | 없음 | PR 전 |
| REQ1 | 기다리는 동안 보낸 글 | 기다림은 도는 turn 안의 도구 호출이라 대기열과 끼워 넣기는 지금 규칙 그대로다. 기존 `test/e2e/scenarios/chat-queue.ts` 와 `ChatQueue` 관련 backend 테스트가 통과한다 | 대기 글은 답이 끝난 뒤 가고, 끼워 넣기를 켠 환경에서는 도구가 돌아온 뒤 그 답에 끼워진다 | 로컬 e2e(가짜 Hermes, H2), backend 테스트 | worker | 없음 | PR 전 |
| REQ2 | 기다림을 켠 통합 테스트, 상한보다 오래 걸리는 자식 | `McpAgentToolsTest` 의 상한 사례와 기존 `DelegationWakeServiceTest` | `RUNNING` 과 note, 전달 표시가 비어 자식이 끝난 뒤 지금 깨우기가 자동 답을 연다 | backend 테스트 | worker | 없음 | PR 전 |
| REQ2 | 상한 0 | `McpAgentToolsTest` 기존 사례와 `McpToolCatalogTest` | 지금과 같은 결과와 도구 설명 | backend 테스트 | worker | 없음 | PR 전 |
| REQ3 | 자식 run 이 도구 사건을 냄 | 위임 시나리오 「맡긴 실행의 도구 사건」 | 트리의 자식 노드에 도구 사건이 빈틈없는 번호로 있다 | 로컬 e2e(가짜 Hermes, H2) | worker | 없음 | PR 전 |
| REQ3 | `MEMBER` 와 `ADMIN` 구독 | `ToolDetailStreamTest` 의 `delegation_activity` 사례 | `MEMBER` 에게 `detail` 이 비고 `ADMIN` 에게 보인다 | backend 테스트 | worker | 없음 | PR 전 |
| REQ3 | 대역한 SSE | `cd web && pnpm test:browser test/browser/agent-team-delegation.spec.ts` | 도우미 아래 검색 줄, 끝난 답의 「끝나면 이어서 알려 드려요」 | 로컬 브라우저 테스트 | worker | 없음 | PR 전 |
| REQ1, REQ2, REQ3 | 운영 배포 | [`remote-verification.md`](remote-verification.md) 의 행 | 그 행의 기대값 | 운영(평가 계정) | 코디네이터 | 배포 | PR 뒤 |
