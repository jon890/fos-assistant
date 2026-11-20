# 기억 요약과 상세 두 층 PR1 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ1 | KEK 를 켠 테스트 설치의 사용자 기억 | 요약을 쓰고 고치고 지운 뒤 DB 칸과 revision 을 읽는다 | 요약 칸이 암호문이고 revision 에 이전 요약이 남는다. 그룹 기억과 문서에 요약을 적으면 거절된다 | H2 통합 테스트(`MemoryEncryptionTest`, `MemoryServiceTest`) | worker | 없음 | PR 전 |
| REQ1 | 같은 변경 | Draft CI 의 MySQL 검사 | 마이그레이션과 새 칸이 MySQL 에서 통과한다 | GitHub Actions `backend-mysql` | worker | push | PR 전 |
| REQ2 | 요약이 있는 기억, 요약이 없는 짧은 기억, 받지 않는 collection 의 기억, `ALWAYS`·`ARCHIVE` 기억 | 질문 있는 조립과 없는 조립을 만든다 | 요약 줄만 요약 구역에 있고, 질문에 맞는 요약 기억의 상세는 질문 관련 구역에 있다. 나머지는 색인이나 없음 | H2 통합 테스트(`ContextAssemblerTest`) | worker | 없음 | PR 전 |
| REQ3 | 예산을 넘는 요약 기억들 | 조립하고 실행 기록 항목을 읽는다 | 잘린 요약이 없고 넘친 기억은 색인에 `summary-over-budget-v1` 근거로 있다 | H2 통합 테스트 | worker | 없음 | PR 전 |
| REQ4 | `ADMIN` 과 `MEMBER` 사용자 | 관리자 API 로 값을 바꾸고 조립한다 | 바꾼 값이 다음 조립에 쓰이고, 범위 밖과 `MEMBER` 는 거절된다. 줄 없는 그룹은 기본값 | H2 통합 테스트(`MemorySettingAdminTest`) | worker | 없음 | PR 전 |
| REQ5 | 사람이 보낸 질문의 루트 실행과 바깥 글을 읽은 대화 | `memory_remember` 를 요약과 함께 부른다 | 앞은 바로 저장되고 다음 조립의 요약 구역에 있다. 뒤는 제안이고 받아들이기 전 요약 구역에 없다 | H2 통합 테스트(`McpMemoryRememberToolTest`) | worker | 없음 | PR 전 |
| REQ6 | 같은 주인의 옛 기억 셋과 남의 기억 하나 | `replaces` 로 합치고, 되돌리고, 제안 합치기를 받아들인다 | 옛 기억이 실행에서 빠지고 되돌리면 돌아온다. 남의 번호는 건너뛰고 새 기억은 저장된다. 기록 목록에 옛 제목이 있다 | H2 통합 테스트(`MemoryMergeTest`) | worker | 없음 | PR 전 |
| REQ7 | 이직·가계부·여행 합성 사례 | 회수 측정을 돌린다 | 세 사례의 상세가 질문 관련 구역에 들고 악화 금지 게이트를 통과한다 | H2 회수 측정(`MemoryRecallEvalTest`) | worker | 없음 | PR 전 |
| REQ7 | 배포한 운영의 실제 계정 | 이직 상담을 맡기는 질문을 한 번 보낸다 | 답이 상세의 사실을 쓰고, 그 실행에 `MEMORY_SEARCH` 나 `MEMORY_READ` 기록이 있다 | 운영 배포 뒤 실제 대화 | 코디네이터 | 배포 뒤 운영 확인 | PR 뒤 |
| REQ8 | 이관 전 짧은 기억, 긴 기억, 제안, 보관 | 이관을 두 번 돌리고 조립한다 | 대상만 요약 = 본문, 나머지는 빈 글. 이관 전 개인 사실 구역의 번호가 요약 구역에 모두 있다 | H2 통합 테스트(`MemorySummaryBackfillTest`) | worker | 없음 | PR 전 |
| REQ8 | 배포한 운영 | 배포 뒤 기억 표의 요약 칸 상태를 센다 | 요약 칸이 `NULL` 인 줄이 0건(풀지 못한 줄 제외)이다 | 운영 DB 읽기 | 코디네이터 | 배포 뒤 운영 확인 | PR 뒤 |
