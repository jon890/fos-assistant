# 평가 실행기와 서비스 개념 묶음 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ5 | 운영 묶음 파일과 틀린 테스트 묶음 파일 | `EvalBundlesTest` 를 돌린다 | 틀린 파일은 파일 이름과 함께 읽기 실패, 운영 폴더의 묶음이 읽힌다. 실행기가 새 폴더의 묶음을 돌리는지는 다음 PR 의 e2e 가 본다 | 로컬 Gradle 테스트와 가짜 Hermes e2e | worker | 없음 | PR 전 |
| REQ6 | `test/model-eval/concepts/` 의 문항과 판정 테스트 | `node --test test/repo/quality-bundles.test.ts` 와 `EvalJudgeTest` 를 돌린다 | 문항이 같고, 옛 판정 사례가 같은 결과 | 로컬 Node·Gradle 테스트 | worker | 없음 | PR 전 |
| REQ16 | 카드 처리를 적은 문항 | `EvalBundlesTest`(card 칸 읽기), `EvalJudgeTest`(cardStatus 판정) | 차례의 카드 처리가 읽히고, 마지막으로 누른 줄의 상태가 기대와 다르면 `CARD_STATUS` 미달. 카드를 실제로 누르는 것은 다음 PR 의 `EvalRunnerTest` 가 본다 | 가짜 Hermes e2e, 로컬 Gradle 테스트 | worker | 없음 | PR 전 |
| REQ17 | 배포 뒤 확인 대화와 다른 대화의 기억 기록, 실패한 평가 줄 | `DeployCheckCleanerTest`, `DeployCheckApiTest`, `QualityRunTest` 를 돌린다 | 배포 뒤 확인은 자기 대화가 만든 기억만 지우거나 되돌리고, 재기동 처리와 하루 정리가 평가 줄을 건드리지 않는다. 평가 문항 비우기는 다음 PR 이 본다 | 로컬 Gradle 테스트 | worker | 없음 | PR 전 |
