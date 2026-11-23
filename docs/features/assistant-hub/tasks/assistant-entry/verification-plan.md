# 입구를 비서로 모으기 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ4 | 기본 비서와 쇼핑 담당이 있는 사용자 | 빈 코드, 비서 코드, 쇼핑 담당 코드로 새 대화와 빈 대화를 만든다(`ConversationEntryTest`) | 앞 둘은 비서 대화가 생기고 쇼핑 담당은 `VALIDATION_FAILED` | `cd backend && ./gradlew test --tests '*ConversationEntryTest'` | worker | 없음 | PR 전 |
| REQ4 | 같은 사용자, 비서를 끈 사용자 | `/new` 와 `/new?agent=<쇼핑 담당>` 을 연다(`web/test/browser/start-screen.spec.ts`) | 두 주소 모두 비서만 보이고 고르기가 없다. 비서가 꺼졌으면 입력창이 막히고 안내가 보인다 | `cd web && pnpm test:browser test/browser/start-screen.spec.ts` | worker | 없음 | PR 전 |
| REQ4 | 에이전트 상세와 연결 화면 | 「대화하기」 단추와 `/new?agent=` 링크를 찾는다(`web/test/browser/agent-lifecycle.spec.ts`, `web/test/browser/connector-onboarding.spec.ts`) | 없다 | `cd web && pnpm test:browser test/browser/agent-lifecycle.spec.ts test/browser/connector-onboarding.spec.ts` | worker | 없음 | PR 전 |
| REQ6 | 비서 대화 | `delegateTo` 를 넣어 보낸다(`MentionDelegationTest`) | `VALIDATION_FAILED`, 실행이 생기지 않는다 | `cd backend && ./gradlew test --tests '*MentionDelegationTest'` | worker | 없음 | PR 전 |
| REQ6 | 비서 대화 화면, 지난 맡김이 있는 대화 | 입력창에 `@` 를 치고, 지난 맡김이 있는 대화를 연다(`web/test/browser/agent-team-mention.spec.ts`) | 고르기 목록과 맡김 칩이 없고, 지난 맡김은 맡긴 줄과 맡은 에이전트 이름표의 답으로 보인다 | `cd web && pnpm test:browser test/browser/agent-team-mention.spec.ts` | worker | 없음 | PR 전 |
| REQ6, REQ8 | 평가 세트와 운영 묶음 | 데이터, 채점, 묶음 대조 테스트를 돌린다 | 이름을 부른 부탁과 바꾸기 부탁이 `agent_delegate` 를 기대하고 `/new?agent=` 링크를 실패로 센다 | `node --test test/model-eval/concepts/concepts-eval-data.test.ts test/model-eval/concepts/concepts-eval-score.test.ts test/repo/quality-bundles.test.ts` | worker | 없음 | PR 전 |
| REQ6, REQ8 | 평가 계정 | 운영 모델로 서비스 개념 평가 묶음을 돌린다 | 두 질문이 통과한다 | 배포한 운영 환경의 평가 계정(절차는 `fos-home-infra`) | 코디네이터 | 배포 | PR 뒤 |
| REQ4, REQ6 | e2e 시나리오 | 전체 e2e 를 돌린다 | 모든 시나리오가 통과한다. 새 대화는 기본 비서로 열리고 전문 에이전트 실행은 지난 대화로 돈다 | `node test/e2e/run.ts` | worker | 없음 | PR 전 |
