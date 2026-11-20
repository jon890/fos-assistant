# 도구 실패 뒤 다른 방법 PR1 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ1 | 모든 `InstructionScope` 의 공통 지침 | `ToolFailureInstructionsTest` 를 실행한다 | 「도구가 실패했을 때」 절이 「도구 호출」 과 「승인 카드」 사이에 있고, 다음 방법부터, 두 번까지, 같은 인자 금지, `mcp__fos_assistant__` 연달아 두 번, 「잠시 뒤 다시」, `<external-data>` 밖의 글만 따르기 문장이 든다 | 로컬 H2 통합 테스트 | worker | 없음 | PR 전 |
| REQ2 | 같은 지침과 판정 글 | `ToolFailureInstructionsTest` 의 바로 알릴 실패 사례, `ToolFailureCoverageTest`, `ConnectorPolicyEndpointTest` 와 `ToolGuardControllerTest` 의 막힘 사례를 실행한다 | 지침에 승인 대기, 사람 손이 필요한 화면, 거절, 결과를 알 수 없는 실행, 결제·구매 상한, 다시 연결, 같은 대상을 다시 노리지 않기가 든다. 결제·구매 상한, 연결 준비 안 됨, 허락 안 된 도구 판정 글은 「사용자에게 바로 알린다.」 로 끝나고 「다시 부르지 않는다」 가 남아 있다. 정책 확인 실패는 다음 방법으로 끝난다. `[승인 대기]` 로 시작하는 글은 그대로다 | 로컬 단위·H2 통합 테스트 | worker | 없음 | PR 전 |
| REQ3 | Control Plane MCP 도구와 판정 글 | `ToolFailureTextTest`, `ToolFailureCoverageTest`, `McpMemoryRememberToolTest`, `McpContentDraftToolTest`, `McpMediaObservationToolTest` 를 실행하고 `McpToolService.result` 가 남지 않았는지 phase 01 의 grep 으로 본다 | 실패 글이 모두 두 끝 줄 가운데 하나로 끝난다. JSON 실패에 `next` 칸이 있고 있던 칸(`code`, `currentRevision`)은 그대로다. `isError` 와 JSON-RPC `error` 모양이 그대로다 | 로컬 단위·H2 통합 테스트 | worker | 없음 | PR 전 |
| REQ3 | fos-ctx 의 실패 글 상수 | `python3 -m unittest discover -s hermes/tests -p 'test_fos_ctx_next_step.py'` 와 hermes 전체 테스트를 실행한다 | fos-ctx 실패 글과 `LINK_REASONS` 가 모두 두 끝 줄 가운데 하나로 끝나고 「올려 달라고 한다」 로만 끝나는 글이 없다. 판정 글 전달은 그대로다 | 로컬 Python unittest | worker | 없음 | PR 전 |
| REQ5 | 모든 범위의 공통 지침 | `ToolFailureInstructionsTest` 의 REQ5 사례를 실행한다 | 다 해 보고도 못 하면 해 본 것을 한 줄 적고 묻는다는 문장과, 결과가 처음 뜻과 달라졌으면 한 줄 알린다는 문장이 든다 | 로컬 H2 통합 테스트 | worker | 없음 | PR 전 |
| REQ1, REQ2, REQ3, REQ5 | push 한 Draft PR | Draft CI 의 backend, hermes, repo 검사와 `검사:전체` 라벨로 돈 E2E 결과를 본다 | 모두 통과한다. E2E 의 연결 바인딩 시나리오와 Memory 되돌려 받기가 새 판정 글과 새 지침 절로 통과한다 | GitHub Actions | worker | push 뒤 Draft PR 의 CI | PR 뒤 |
| REQ6 | 운영에 배포한 backend 와 profile 에 다시 설치한 fos-ctx | 실제 대화에서 없는 기억을 고치게 해 `UPDATE_TARGET_NOT_FOUND` 를 받게 한다. 절차는 운영 저장소가 갖는다 | 비서가 사용자에게 묻기 전에 `memory_id` 없이 새로 남기는 호출을 하고, 답에 새로 남겼다고 한 줄 알린다. 실행 사건에 실패와 다른 방법 호출이 차례로 남는다. 결과를 기능 파일 `docs/features/tool-fallback.md` 의 「배포 뒤 확인」 에 날짜와 함께 적는다 | 운영 | 코디네이터 | 배포 (이 PR 의 머지와 배포, fos-ctx 를 profile 에 다시 설치한 뒤) | PR 뒤 |
