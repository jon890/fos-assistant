# 읽기 전용 대화와 예약 작업의 에이전트 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ5 | 기본 비서와 쇼핑 담당이 있는 사용자, 쇼핑 담당과의 지난 대화, 점검 대화 | 보내기, 흐르는 보내기, 다시 생성, 결과 다시 전달, 대기 메시지 넣기(끼우기 포함)와 바로 보내기를 부르고 대화 응답을 읽는다(`ReadOnlyConversationTest`) | 쇼핑 담당 대화는 모두 `VALIDATION_FAILED`, 저장 없음, `writable` 거짓. 비서 대화와 점검 대화는 되고 `writable` 참 | `cd backend && ./gradlew test --tests '*ReadOnlyConversationTest'` | worker | 없음 | PR 전 |
| REQ5 | 같은 대화 | 그 대화의 예약 turn 을 돌린다(`ReadOnlyConversationTest`). 결과 전달과 살펴보기 turn 은 보내기 검사(`routeSend`)를 거치지 않아 기존 테스트가 그대로 지킨다 | 막히지 않고 답이 쌓인다 | `cd backend && ./gradlew test --tests '*ReadOnlyConversationTest'` | worker | 없음 | PR 전 |
| REQ5 | 쇼핑 담당과의 지난 대화 | 그 대화를 연다(`web/test/browser/read-only-conversation.spec.ts`) | 이력이 보이고 입력창, 다시 생성 단추가 없으며 안내 줄과 대화방 링크가 있다. 같은 대화로 보내면 400 `VALIDATION_FAILED` 다. 링크를 누르면 입력창이 있는 대화방이 열린다 | `cd web && pnpm test:browser test/browser/read-only-conversation.spec.ts` | worker | 없음 | PR 전 |
| REQ5 | 쇼핑 담당과의 지난 대화에 전달이 `FAILED` 인 결과 알림 줄 | 그 대화를 연다(`web/test/browser/read-only-conversation.spec.ts`) | 전달 상태 문구만 보이고 「결과 다시 전달」 단추가 없다 | `cd web && pnpm test:browser test/browser/read-only-conversation.spec.ts` | worker | 없음 | PR 전 |
| REQ7 | 기본 비서와 쇼핑 담당이 있는 사용자 | 화면 API 와 `task_` 도구로 만들고, 쇼핑 담당 작업을 고치고, 멈췄다 켠다(`TaskPrimaryAgentTest`) | 만들기와 고치기는 기본 비서, 멈추기와 켜기는 그대로. 기본 비서가 없으면 `AGENT_NOT_FOUND` | `cd backend && ./gradlew test --tests '*TaskPrimaryAgentTest'` | worker | 없음 | PR 전 |
| REQ7 | 쇼핑 담당의 `SINGLE` 작업과 그 대화(`TaskRunStarterTest`) | 두 번 발화시킨 뒤 화면 API 로 시각을 고치고 다시 발화시킨다 | 고치기 전 두 발화는 쇼핑 담당 대화에 이어 쌓인다. 고친 뒤 발화는 에이전트가 기본 비서인 새 대화에 쌓이고 작업이 그 대화를 가리키며, 옛 대화의 메시지 수는 그대로다 | `cd backend && ./gradlew test --tests '*TaskRunStarterTest'` | worker | 없음 | PR 전 |
| REQ7 | 작업 화면 | 만들기 화면을 연다(`web/test/browser/tasks.spec.ts`) | 에이전트 칸이 없고 만든 작업이 비서로 보인다 | `cd web && pnpm test:browser test/browser/tasks.spec.ts` | worker | 없음 | PR 전 |
| REQ5, REQ7 | e2e 시나리오 | 전체 e2e 를 돌린다. 다른 에이전트와의 대화를 쓰는 시나리오는 그 에이전트를 잠시 기본 비서로 둔다 | 모든 시나리오가 통과한다 | `node test/e2e/run.ts` | worker | 없음 | PR 전 |
