# plan66. 재기동 때 남은 실행을 Hermes 에 물어 정한다

이슈 #98 을 닫는다. 결정은 `docs/adr/ADR-059-재기동-때-남은-실행은-hermes-에-물어-정하고-도는-실행에는-다시-붙는다.md`, 동작 계약은 `docs/backend/turn-control.md` 의 「기동할 때 남은 실행 정리」 에 있다.
docs 는 이 계획과 같은 브랜치에서 이미 고쳤다. phase 는 docs 를 다시 쓰지 않고, 구현이 docs 와 달라질 때만 그 절을 같은 커밋에서 고친다.

| phase | 만드는 것 |
| --- | --- |
| 01 | Hermes 실행 조회 한 번(`HermesRunsClient.lookupRun`) |
| 02 | Hermes 의 답 하나를 실행 줄과 대화에 적는 `RecoveredRunRecorder` |
| 03 | 기동 때 잡고 묻는 `RestartReconciler`. `OrphanedExecutionSweeper` 를 대신한다 |
| 04 | 가짜 Hermes 의 실행 조회와 e2e 시나리오, 사용량 화면의 문구 |

순서대로 한다. 뒤 phase 가 앞 phase 의 타입을 쓴다.
