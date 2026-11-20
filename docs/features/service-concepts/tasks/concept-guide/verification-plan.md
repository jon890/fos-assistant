# 서비스 개념 PR1 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ1 | 가짜 Hermes 테스트 지원 | `ServiceConceptsTurnTest` 와 `ServiceConceptsInstructionsTest` 를 돌린다 | 대화·예약·살펴보기·맡긴 실행의 `instructions` 에 일곱 개념 이름과 다섯 주소가 있고 「사용량」, `/usage` 가 없다. `FileSpaceTurnTest` 가 그대로 통과한다 | 로컬 Gradle 테스트(H2) | worker | 없음 | PR 전 |
| REQ2 | 같다 | `ServiceConceptsInstructionsTest` 의 모델 단계 줄 검사와 `test/repo/service-concepts.test.ts` 의 도구 이름 검사를 돌린다 | 줄에 바꾸지 않는다는 말과 입력창 단추 안내가 있고 그런 도구가 없다 | 로컬 Gradle 테스트(H2) | worker | 없음 | PR 전 |
| REQ2, REQ3, REQ4, REQ5 | 평가 계정, 그 계정이 주인인 비공개 평가 에이전트, 그 계정이 쓸 수 있는 다른 에이전트, 연결하지 않은 플러그인 하나 | 이 브랜치의 `test/model-eval/concepts/run.ts send` 와 `score` 를 바뀌기 전 운영에서 돌린다 | 기준선 보고서(문항별 통과 여부)가 PR 본문에 있다 | 운영 Control Plane 과 실제 모델 | 코디네이터 | push 한 브랜치의 평가 세트 | PR 전 |
| REQ2, REQ3, REQ4, REQ5 | 위와 같다 | 배포 뒤 같은 명령을 다시 돌린다 | 세트 통과(돌린 문항의 85% 이상). 결과가 기능 완료 증거와 PR 댓글에 있다 | 운영 Control Plane 과 실제 모델 | 코디네이터 | 배포 | PR 뒤 |
| REQ4 | 평가 계정이 쓸 수 있는 다른 에이전트 | 배포 뒤 평가의 에이전트 바꾸기 문항 답에 붙은 `/new?agent=<code>` 링크를 운영 화면에서 연다 | 새 대화 화면에 그 에이전트가 골라져 있다(이미 있는 동작, `web/src/components/chat-panel.tsx`) | 운영 웹 화면 | 코디네이터 | 배포 | PR 뒤 |
| REQ5 | 가짜 카탈로그 | `ConnectorListingsTest` 와 MCP 웹 테스트를 돌린다 | 연결 상태, `thisAgent`, 링크의 `?agent=` 규칙, 남의 연결 비노출, 살펴보기에서 받음 | 로컬 Gradle 테스트(H2) | worker | 없음 | PR 전 |
| REQ6 | 없음 | 판정·문항 단위 테스트와 저장소 테스트를 돌린다 | 모두 통과. 개념마다 문항 둘 이상 | 로컬 node --test 와 CI | worker | 없음 | PR 전 |
