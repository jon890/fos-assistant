# 기억 옛 칸 삭제 인수 검증 계획

구현 전에 쓴다. 스펙 REQ5 의 「칸이 없다」 와 REQ8 의 「옛 key 줄이 0건일 때만 지운다」 를 닫는 사례다. 전체 기능(REQ1 부터 REQ8)의 앞 PR 증거는 PR1, PR2 본문이 갖고, 여기서는 이 PR 이 바꾸는 부분과 회귀를 본다.

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- |
| REQ5 | 이 PR 의 마이그레이션을 모두 적용한 스키마 | `information_schema` 로 여섯 칸을 찾는다 | 여섯 칸이 모두 없다 | 실제 MySQL(`MysqlMigrationTest` 계열) | 없음 | PR 전 |
| REQ5 | 칸을 지우기 전 스키마에 옛 칸이 있고 줄이 있다 | 마이그레이션을 끝까지 적용한다 | 줄 수와 `title`, `content`, `data_key_id` 가 같고 칸만 사라진다 | H2, 실제 MySQL | 없음 | PR 전 |
| REQ8 | 지우기 전 스키마에서 `memory.content_key_id` 나 `memory_revision.content_key_id` 가 찬 줄 | 확인 마이그레이션까지 적용한다 | 건수가 담긴 예외로 멈추고 어느 칸도 지워지지 않는다 | H2 | 없음 | PR 전 |
| REQ8 | 옛 key 줄이 없다 | 같은 절차 | 확인을 지나 칸이 지워진다 | H2 | 없음 | PR 전 |
| REQ5 | 칸이 없는 스키마 | 화면 경로(`MemoryEncryptionTest`)로 기억을 만들고 읽고 고친다. collection 을 받은 에이전트의 색인과 `memory_read`(`McpMemoryToolTest`, `ContextAssemblerQuestionTest`) | 모두 통과, 옛 칸에 쓰는 SQL 이 없다 | H2 | 없음 | PR 전 |
| REQ5 | 칸이 없는 스키마 | 옛 `SENSITIVE_CHANGED` 변경 기록을 넣고 최근 변경을 읽는다(`AgentMemoryCollectionServiceTest`) | 그 기록은 목록에 나오지 않는다 | H2 | 없음 | PR 전 |
| REQ5 | 사용자 purge 에 쓰는 fixture | `PurgeCoverage` 계열을 돌린다 | 칸 없이도 모든 표의 줄이 지워진다 | H2, 실제 MySQL | 없음 | PR 전 |
| REQ4 | 기억을 읽는 기존 테스트 | `*Memory*`, `ContextAssembler*`, 측정 게이트 테스트를 돌린다 | 모두 통과 | H2 | 없음 | PR 전 |
| REQ5 | 이 PR 을 배포한 운영 | 칸이 사라진 것과 `/memory`, 에이전트 기억 영역 화면 확인 | 칸이 없고 화면이 이전과 같다 | 운영 | 배포: 이 PR 의 배포 | PR 뒤 |
