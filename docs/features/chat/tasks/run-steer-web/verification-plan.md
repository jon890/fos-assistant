# steer 화면 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ1 | 설정 켬, 도구 둘을 부르는 붙잡은 가짜 실행, 빈 대기 줄 | 입력창 글로 대기 경로를 보낸다 | steer 요청 본문이 그 글, `run.steered` 뒤 이력에 `DELIVERED` 행이 질문과 답 사이, 대기 줄 빔 | E2E(`chat-queue`) | worker | 없음 | PR 전 |
| REQ1 | 같은 조건의 브라우저 | 답이 도는 중 입력창에 써서 보낸다 | 답 위에 말풍선과 「전달됨」, 대기 칸에 글 없음 | 브라우저 검사(`chat-steer`) | worker | 없음 | PR 전 |
| REQ1 | `steer` 사건, 임시 질문·흐르는 답이 있는 줄 | `applySteerEvent` | 흐르는 답 바로 위, 저장된 답이 있으면 그 앞, 같은 번호면 상태만 바뀜 | 웹 단위 시험 | worker | 없음 | PR 전 |
| REQ3 | 도구 없이 답만 쓰는 붙잡은 가짜 실행 | 끼운 뒤 실행을 푼다 | 행이 `CARRIED_OVER`, 새 `USER` 행 없이 다음 turn 이 그 글로 열림 | E2E | worker | 없음 | PR 전 |
| REQ4 | 끼운 실행 | 중지한다 | 행이 `UNCONFIRMED`, 멈춘 대기 줄에 말풍선 번호가 붙은 같은 글 | E2E | worker | 없음 | PR 전 |
| REQ4 | 말풍선 번호가 붙은 글이 든 대기 줄 | `visiblePendingItems` | 멈추지 않은 줄에서 숨김, 멈춘 줄에서 보임 | 웹 단위 시험 | worker | 없음 | PR 전 |
| REQ5 | `child` 가 `SUBAGENT`·`DELEGATION`·없음 | 안내 줄 표 | 스펙 DECISION19 의 두 글, 없으면 없음 | 웹 단위 시험 | worker | 없음 | PR 전 |
| REQ6 | 대기 경로 | 입력창 글, 카드 답 | 입력창 글만 `steer: true` | 웹 단위 시험과 코드 읽기 | worker | 없음 | PR 전 |
| REQ7 | 질문, steer, 답의 이력 | 보일 줄을 접는다 | steer 뒤 답은 `afterSteer`, 단추 없음. steer 없는 답은 그대로 | 웹 단위 시험, 브라우저 검사 | worker | 없음 | PR 전 |
| REQ9 | 없음 | 계약 문서의 가짜 Hermes 줄, E2E 가짜 steer 경로 | 「E2E 가짜는 아직」 이 지워지고 시나리오 통과 | 문서 읽기와 E2E | worker | 없음 | PR 전 |
