# PR3 할 일과 기억 도구 phase 1

스펙 REQ11-REQ14, 설계 [`design.md`](../../design.md) 의 「PR3 할 일과 기억 도구」 를 구현한다. PR 하나다.

## 할 일

1. `followup`: `FollowUpAgentRequests`(바로 챙기는 줄 만들기와 한 실행의 상한, 챙기는 할 일 목록, 끝낼 대상 찾기와 끝내기). `FollowUp.openedByAgent` 와 결과 `OPENED`.
2. `mcp`: `McpFollowUpTools`(바로 바꾸는 실행의 판정, `follow_up_list`, `follow_up_done`), `follow_up_propose` 의 `requested` 인자, `McpMemoryForgetTool` 과 port `MemoryForgetRequests`, `McpToolCatalog`, `McpController` 의 처리와 먼저 살펴보기 조건, `McpMemoryRemember` 의 내부 도구 목록.
3. `memory`: `MemoryForgetting`(지울 수 있는 항목의 판정, 승인 뒤 지우기).
4. `connector.action`: `ControlPlaneActions` 의 `memory_forget` 줄, `MemoryForgetRequester`, `MemoryForgetRunner`.
5. `hermes`: 할 일 도구의 실행 사건 내용을 남기지 않는 범위를 목록과 끝내기까지 넓힌다.
6. `context`: 개념 목록의 기억 줄과 할 일 줄을 직접 처리의 말로 바꾼다.
7. `web`: 새 도구의 진행 문장과 종류.
8. 평가 세트: 할 일 적기와 끝내기, 기억 지우기 카드 문항.
9. 문서: `attention.md` 의 「제안 도구」, `mcp.md` 도구 표와 살펴보기 조건, `code-architecture.md` 의 port 설명, ADR, 기능 파일.

## 검증

- `./gradlew test --tests '*McpFollowUp*' --tests '*McpMemoryForget*' --tests '*McpToolCatalog*' --tests '*McpToolService*' --tests '*McpCallerInvariant*' --tests '*FollowUp*' --tests '*ArchitectureRules*'`
- `node --test test/model-eval/concepts/*.test.ts test/repo/service-concepts.test.ts test/repo/tool-label-coverage.test.ts`
- `scripts/dev/check-local.sh --fast`

## 인수 검증

| 요구사항 | 확인 |
| --- | --- |
| REQ11 | `McpFollowUpDirectToolTest` 의 사람이 보낸 메시지, 살펴보기 트리, 바깥 글을 읽은 대화, 예약 작업의 실행 |
| REQ12 | 같은 테스트의 끝내기, 후보, 사람이 시키지 않은 실행 |
| REQ13 | `McpMemoryForgetToolTest` 의 카드와 승인, 거절, 그룹 기억의 권한 |
| REQ14 | 개념 목록 지침의 줄과 평가 세트의 `memory-where` |
| REQ17, REQ18 | 두 테스트의 남의 할 일과 기억, 실패 결과 |

이 계획은 구현 PR 에서 지운다.
