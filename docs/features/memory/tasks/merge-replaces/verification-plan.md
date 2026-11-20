# 기억 합치기 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ6 | 요청자의 승인된 사용자 기억 둘 | 바로 저장 조건의 대화에서 `replaces` 로 둘을 주고 `memory_remember` 를 부른 뒤 다음 조립, `memory_read`, `memory_search`, `GET /api/v1/memories` 를 본다 | 옛 기억이 조립, 읽기, 검색에서 빠지고 목록에는 남는다. 기록 목록의 `replacedTitles` 가 옛 제목이다 | H2 통합 테스트(`McpMemoryReplacesToolTest`) | worker | 없음 | PR 전 |
| REQ6 | 남의 기억, 그룹 기억, 없는 번호, 받지 않는 collection 의 기억, 제안 상태 기억, 이미 대체된 기억, 풀리지 않는 기억 | 그 번호를 섞어 `replaces` 로 준다 | 새 기억은 저장되고 그 번호만 남아 있으며 결과 글이 까닭 없이 「대신하지 못함」 이다 | H2 통합 테스트(`McpMemoryReplacesToolTest`) | worker | 없음 | PR 전 |
| REQ6 | 바깥 글을 읽은 대화 | `replaces` 로 제안을 남기고 화면 경로(`POST /api/v1/memories/{id}/accept`)와 같은 제안의 바로 저장 재호출(`existingOutcome`)로 각각 받아들인다. 그 사이 옛 기억 하나를 지운 사례도 둔다 | 받아들이기 전에는 옛 기억이 그대로이고, 받아들인 뒤 남은 번호만 대체되며 기록의 번호가 실제로 대신한 것으로 고쳐진다. 재호출의 `replaces` 는 쓰이지 않는다 | H2 통합 테스트(`McpMemoryReplacesToolTest`) | worker | 없음 | PR 전 |
| REQ6 | 합친 새 기억 | 기록을 되돌린 뒤 다음 조립을 본다 | 옛 기억이 다시 조립에 든다 | H2 통합 테스트(`McpMemoryReplacesToolTest`) | worker | 없음 | PR 전 |
| REQ6 | 합친 기록이 있는 대화 | 대화를 연다 | 기록 줄과 제안 카드에 「n개를 합쳤어요」 와 옛 제목이 보인다 | Playwright(`memory-capture.spec.ts`) | worker | 없음 | PR 전 |
| REQ6 | 배포된 운영, 같은 주제의 기억 둘 | 대화에서 기억 정리를 부탁한다 | 기록 줄에 「n개를 합쳤어요」 와 옛 제목이 보이고, 다음 실행의 요약 구역에서 옛 기억이 빠지며, 되돌리면 다시 들어간다 | 운영 | 코디네이터 | 배포 | PR 뒤 |
