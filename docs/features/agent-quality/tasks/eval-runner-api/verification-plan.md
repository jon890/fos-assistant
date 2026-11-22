# 평가 실행기와 API 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ6 | 배포된 운영 Control Plane, 준비된 평가 전용 사용자, 옛 스크립트의 마지막 결과 | 관리자 API 로 `service-concepts` 와 `answer-quality` 묶음 평가를 시작해 끝날 때까지 읽고, `answer-quality` 는 `test/model-eval/answer/run.ts score-eval` 로 채점한다 | 두 묶음 결과가 나오고, 옛 결과와 문항별 통과가 크게 다르지 않다 | 운영 | 코디네이터 | 배포 | PR 뒤 |
| REQ9 | 도는 평가 하나, 단계를 고른 요청과 고르지 않은 요청 | `EvalApiTest`, `EvalRunnerTest` 로 두 번째 시작과 모델 단계 적용을 본다 | 두 번째 시작은 200 과 같은 줄, 고른 단계나 풀어 낸 기본 단계가 결과에 적힘, 모르는 단계는 400 | 로컬 Gradle 테스트 | worker | 없음 | PR 전 |
| REQ15 | 평가 사용자와 다른 사용자 둘, 테스트 묶음 | e2e `eval-run` 을 돌린다 | 평가가 평가 사용자의 대화로만 돌고 다른 사용자의 기억·할 일·예약 작업·대화 수가 전후로 같다 | 가짜 Hermes e2e | worker | 없음 | PR 전 |
| REQ15 | 배포된 운영, 준비된 평가 전용 사용자 | 운영 평가 뒤 사용자 계정의 기억·예약 작업 목록이 평가 전후로 같은지 화면에서 본다 | 사용자 계정에 평가가 남긴 것이 없다 | 운영 | 코디네이터 | 배포 | PR 뒤 |
| REQ16 | 승인 카드를 띄우는 테스트 문항 | `EvalRunnerTest` 의 카드 사례 | `approve` 차례 뒤 줄이 승인돼 `cardStatus` 로 판정, `reject` 차례 뒤 거절, 적지 않은 카드는 판정 뒤 거절, 원고 저장 줄은 `CARD_FAILED` | 가짜 Hermes e2e, 로컬 Gradle 테스트 | worker | 없음 | PR 전 |
| REQ17 | 문항 대화로 만든 예약 작업(위임 자식 포함)·기억·할 일·보관본·승인 줄, 함께 도는 배포 뒤 확인의 대화 | `EvalCleanerTest`, `EvalRunnerTest`, `DeployCheckCleanerTest`, `DeployCheckApiTest` 를 돌린다 | 문항의 것만 지워지거나 닫히고, 준비 기억은 되돌아오며, 배포 뒤 확인의 것은 남는다. 「비우기 끝남」 이 없는 줄은 시작, 재기동, 하루 정리가 다시 비운다 | 로컬 Gradle 테스트 | worker | 없음 | PR 전 |
