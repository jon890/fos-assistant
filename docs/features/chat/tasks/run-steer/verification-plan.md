# steer 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ1 | 설정 켬, 도구를 두 번 부르는 가짜 실행이 도는 대화, 빈 대기 줄 | 입력창 글로 `POST .../pending`(`steer: true`), 대기 줄과 이력, 대화 단위 SSE 를 읽는다 | steer 요청 본문이 그 글, 대기 줄 비어 있음, 답 앞 `USER` 행 `DELIVERED`, 대화 단위 SSE(다른 창이 받는 길)로 `steer` 사건 | 백엔드 통합 시험(H2, `StubHermesRunsClient`) | worker | 없음 | PR 전 |
| REQ2 | 같은 대화, 가짜가 409·500 또는 응답 지연을 돌려줌 | 입력창 글을 보낸다 | 409·500: 말풍선 없음, 글은 대기 줄, turn 이 끝나면 다음 turn. 지연: `UNCONFIRMED` 말풍선과 멈춘 줄 | 백엔드 통합 시험 | worker | 없음 | PR 전 |
| REQ3 | `DELIVERED` 말풍선이 있는 turn, 가짜가 `pending_steer` 로 그 글을 돌려줌 | turn 을 끝낸다 | 그 행 `CARRIED_OVER`, 다음 turn 이 바로 열림, 새 `USER` 행 없음, 다음 실행의 질문 번호가 그 행 | 백엔드 통합 시험 | worker | 없음 | PR 전 |
| REQ4 | `DELIVERED` 말풍선이 있는 turn | 중지한다. 이어 「보내기」 와 「취소」 를 각각 해 본다 | `UNCONFIRMED`, 멈춘 줄에 같은 글. 보내기: 새 행 없이 `CARRIED_OVER`. 취소: 행은 `UNCONFIRMED`, 글은 입력창 | 백엔드 통합 시험 | worker | 없음 | PR 전 |
| REQ5 | 하위 에이전트 사건이 열린 turn, 또는 그 대화에 도는 맡긴 실행 | 입력창 글을 보낸다 | 행 `child` 가 `SUBAGENT`/`DELEGATION`. 안내 줄은 PR2 에서 본다 | 백엔드 통합 시험 | worker | 없음 | PR 전 |
| REQ6 | 도는 turn | `steer` 표시 없는 글(카드 답), 스킬 커맨드, `@` 맡김 turn 중 글, 남의 대화로 보낸다 | 표시 없음·스킬 커맨드·맡김 turn: steer 호출 없음. 남의 대화: 404. 화면의 표시 싣기는 PR2 에서 본다 | 백엔드 컨트롤러·서비스 시험 | worker | 없음 | PR 전 |
| REQ7 | steer 말풍선이 낀 답 | 다시 생성 API 를 부르고 화면을 본다 | `MESSAGE_NOT_LATEST`. 단추 숨김은 PR2 에서 본다 | 백엔드 통합 시험 | worker | 없음 | PR 전 |
| REQ8 | 설정 끔(기본) | 기존 대기열·중지 시험과 steer 시험의 꺼짐 사례를 돌린다 | steer 호출 없음, 기존 시험 통과 | 백엔드 시험 전체(Draft CI) | worker | 없음 | PR 전 |
| REQ9 | 없음 | 계약 문서 steer 절을 읽고 `HermesSteerRequestTest`·`StubHermesRunsClientTest` 를 돌린다 | 문서가 v2026.9.24 기준 동작과 「부른다」 를 적음, 시험 통과 | 문서 검토와 Java 가짜 Hermes 시험. E2E 가짜는 PR2 | worker | 없음 | PR 전 |
| REQ1 | 같은 대화 두 창 | 두 요청으로 잇달아 글을 보낸다 | 첫 글만 끼우고 둘째는 대기 칸에 남아 순서대로 다음 turn 으로 감 | 백엔드 통합 시험 | worker | 없음 | PR 전 |
| REQ3 | steer 응답을 기다리는 동안 turn 이 끝나게 한 가짜 | 글을 보낸다 | 받은 말풍선도 그 turn 의 정리에 들어가고, 정리 뒤 온 글은 호출 없이 대기 칸 | 백엔드 통합 시험 | worker | 없음 | PR 전 |
| REQ4 | `steering` 글과 끝난 실행의 `DELIVERED` 행이 남은 DB | 서버 기동 정리를 돌린다 | `steering` 글은 `UNCONFIRMED` 와 멈춘 줄, 끝난 실행의 `DELIVERED` 는 그대로(이미 따른 지시를 다시 보내지 않음) | 백엔드 통합 시험 | worker | 없음 | PR 전 |
| REQ8 | 설정 끔 | 대기 줄을 저장해 JSON 을 읽는다. 버전 2 값을 decode 하고, 버전 3 을 넣어 본다 | 꺼짐 동안 버전 1, 버전 2 왕복, 모르는 버전은 오류(보내지 않음) | `PendingMessageStoreTest` | worker | 없음 | PR 전 |
