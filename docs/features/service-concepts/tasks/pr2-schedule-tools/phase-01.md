# PR2 예약 작업 도구 phase 1

스펙 REQ7-REQ10, 설계 [`design.md`](../../design.md) 의 「PR2 예약 작업 도구」 를 구현한다. PR 하나다.

## 할 일

1. `connector.approval`: `ControlPlaneApprovals`(줄 만들기, `owns`, 승인, 표시 정책)와 port `ControlPlaneActionRunner`. `ConnectorAction` 에 커넥터 연결 없이 만드는 생성 메서드를 둔다. `ConnectorActionService.approve` 와 `ConnectorActionDetails` 의 표시, 결과 전달, 알림 줄 글을 고친다.
2. `mcp`: port `TaskRequests`, `McpTaskTools`(정의 다섯과 처리), `McpToolCatalog`, `McpController` 의 처리와 먼저 살펴보기 조건, `McpMemoryRemember` 의 내부 도구 목록.
3. `task`: `TaskAgentRequests`(목록, 만들기, 고치기, 멈추기·다시 켜기, 지우기 요청)와 `TaskDeleteRunner`. `TaskService` 에 대화를 미리 적는 만들기를 더한다.
4. `context`: 개념 목록의 예약 작업 줄을 직접 처리의 말로 바꾼다.
5. 평가 세트: 만들기 문항과 `instructionIncludes`, 평가가 만든 작업을 지우는 정리.
6. 문서: `mcp.md` 도구 표와 살펴보기 조건, `schedule.md`, `connector-policy.md`, `code-architecture.md` 의 port 설명, 기능 파일.

## 검증

- `./gradlew test --tests '*TaskAgentRequests*' --tests '*ControlPlaneApprovals*' --tests '*McpTask*' --tests '*McpToolCatalog*' --tests '*ServiceConcepts*' --tests '*McpAgentTools*' --tests '*McpCallerInvariant*' --tests '*ArchitectureRules*'`
- `node --test test/model-eval/concepts/*.test.ts test/repo/service-concepts.test.ts`
- `scripts/dev/check-local.sh --fast`

이 계획은 구현 PR 에서 지운다.
