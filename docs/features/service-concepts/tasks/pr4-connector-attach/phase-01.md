# PR4 연결 붙이기 승인 카드 phase 1

스펙 REQ15-REQ16, 설계 [`design.md`](../../design.md) 의 「PR4 연결 붙이기 승인 카드」 를 구현한다. PR 하나다.

## 할 일

1. `mcp`: `McpConnectorAttachTool` 과 port `ConnectorAttachRequests`, `McpToolCatalog`, `McpController` 의 처리와 먼저 살펴보기 조건, `McpMemoryRemember` 의 내부 도구 목록.
2. `connector.action`: `ControlPlaneActions` 의 `connector_attach` 줄, `ConnectorAttachRequester`, `ConnectorAttachRunner`.
3. `connector.action`: `ControlPlaneActionRunner.outsideTransaction()` 과 `ControlPlaneApprovals.approve` 의 세 걸음. 지우기 두 가지는 지금처럼 한 트랜잭션이다.
4. `connector.approval`: 결과를 모르는 Control Plane 줄의 알림 줄.
5. `context`: 개념 목록의 연결 줄에 붙이기를 더한다.
6. `web`: 새 도구의 진행 문장과 종류.
7. 평가 세트: 붙이기 문항.
8. 문서: `mcp.md` 도구 표와 살펴보기 조건, `connector.md` 의 붙이기, `code-architecture.md` 의 port 설명, ADR, 기능 파일.

## 검증

- `./gradlew test --tests '*McpConnectorAttach*' --tests '*McpMemoryForget*' --tests '*McpTaskTools*' --tests '*McpToolCatalog*' --tests '*McpCallerInvariant*' --tests '*McpContentDraftSnapshots*' --tests '*ArchitectureRules*'`
- `node --test test/model-eval/concepts/*.test.ts test/repo/service-concepts.test.ts test/repo/tool-label-coverage.test.ts`
- `scripts/dev/check-local.sh --fast`

## 인수 검증

| 요구사항 | 확인 |
| --- | --- |
| REQ15 | `McpConnectorAttachToolTest` 의 카드와 승인 뒤 바인딩, 사용 시점 알림 줄, 거절 |
| REQ16 | 같은 테스트의 그룹 공개 에이전트, 남의 에이전트, 준비되지 않은 연결의 링크 |
| REQ17, REQ18 | 같은 테스트의 없는 플러그인, 승인하는 때의 재검사, 실패 결과 |

이 계획은 구현 PR 에서 지운다.
