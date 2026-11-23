# 연결과 스킬 맡기기 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ9 | 기본 비서와 연결을 마친 사용자 | 기본 비서에 붙이기, 떼기, 연결 목록 읽기를 부른다 | 붙이기는 `PRIMARY_AGENT_NO_CONNECTIONS` 이고 바인딩이 생기지 않는다. 떼기는 된다. 목록의 까닭은 `PRIMARY_AGENT` 다 | `cd backend && ./gradlew test --tests '*AgentConnectionControllerTest' --tests '*SpecialistAgentCreatorTest'` | worker | 없음 | PR 전 |
| REQ9 | 연결을 마쳤고 어느 전문 에이전트에도 붙지 않은 사용자 | 비서 대화의 `connector_attach` 를 부르고 카드를 승인한다 | 카드 인자가 새 에이전트 이름(`<서비스> 담당`)이고, 승인하면 그 이름의 비공개 전문 에이전트가 생기고 연결이 붙는다. 비서에는 붙지 않는다 | `cd backend && ./gradlew test --tests '*ConnectorAttach*'` | worker | 없음 | PR 전 |
| REQ9 | 그 연결이 붙은 전문 에이전트가 있는 사용자 | `connector_attach` 를 부른다 | 카드가 없고 `specialist_attached` 와 그 에이전트 코드가 돌아온다 | `cd backend && ./gradlew test --tests '*ConnectorAttach*'` | worker | 없음 | PR 전 |
| REQ9 | 연결 화면과 비서 상세 | 연결 화면의 에이전트 고르기와 비서 상세의 연결 절을 연다 | 고르기에 비서가 없고 비서 상세에 붙이기가 없으며 붙은 연결의 떼기만 있다 | `cd web && pnpm test:node && pnpm test:browser agent-connections` | worker | 없음 | PR 전 |
| REQ10 | 기본 비서, 스킬이 켜진 전문 에이전트, 꺼진 에이전트가 있는 사용자 | 목록 API 를 읽는다 | 켜진 전문 에이전트의 켜진 스킬만 있고 기본 비서와 꺼진 에이전트는 없다 | `cd backend && ./gradlew test --tests '*SkillDelegationTest'` | worker | 없음 | PR 전 |
| REQ10 | 같은 사용자 | 비서 대화에 `/커리어 · position-recommender 공고` 를 보내기 API 로 보낸다 | 202. 질문이 저장되고 부모 없는 커리어 실행이 그 스킬 입력으로 돌며 turn 잠금이 없다. 끝나면 결과 도착 줄과 비서 답이 생긴다. 이력의 질문에 맡김 줄(나 → 커리어)이 있다 | `cd backend && ./gradlew test --tests '*SkillDelegationTest'` | worker | 없음 | PR 전 |
| REQ10 | 같은 사용자 | 비서 답이 도는 동안 보낸다. 남의 대화, 읽기 전용 대화, 기본 비서, 꺼진 에이전트, 꺼진 스킬, 꼴이 아닌 글로 보낸다 | 도는 동안에도 202. 나머지는 각각 `CONVERSATION_NOT_FOUND`, `VALIDATION_FAILED`, `VALIDATION_FAILED`, `AGENT_DISABLED`, `SKILL_COMMAND_UNKNOWN`, `VALIDATION_FAILED` 이고 저장되는 것이 없다 | `cd backend && ./gradlew test --tests '*SkillDelegationTest'` | worker | 없음 | PR 전 |
| REQ10 | 지시가 `/커리어 · position-recommender` 인 예약 작업 | 발화시킨다. 에이전트를 지우거나 스킬을 끈 뒤 다시 발화시킨다 | 커리어 실행이 사람이 없는 실행으로 돌고 발화가 그 실행으로 닫힌다. 없는 에이전트나 꺼진 스킬이면 `FAILED` | `cd backend && ./gradlew test --tests '*TaskRunStarterTest'` | worker | 없음 | PR 전 |
| REQ10 | 비서 대화 | `/` 를 치고 「에이전트 · 스킬」 을 골라 보낸다 | 목록에 그 항목이 있고, 보내면 입력창이 비고 질문 아래 「나 → 그 에이전트에게 맡김」 줄이 생긴다 | `cd web && pnpm test:browser skill-delegation` | worker | 없음 | PR 전 |
| REQ9, REQ10 | e2e | 전체 e2e 를 돌린다 | 모든 시나리오가 통과한다 | `node test/e2e/run.ts` | worker | 없음 | PR 전 |
| REQ10 | 운영 평가 계정 | 스펙 「배포 뒤 왕복 확인」 의 PR3 | 맡긴 줄, 그 스킬로 돈 실행, 결과 도착 줄과 비서 답 | 운영 | 코디네이터 | 배포 | PR 뒤 |
