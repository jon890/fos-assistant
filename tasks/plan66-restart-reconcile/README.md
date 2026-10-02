# plan66. 재기동 때 남은 실행을 Hermes 에 물어 정한다

이슈 #98 을 닫는다. 결정은 `docs/adr/ADR-060-재기동-때-남은-실행은-hermes-에-물어-정하고-도는-실행에는-다시-붙는다.md`, 동작 계약은 `docs/backend/turn-control.md` 의 「기동할 때 남은 실행 정리」 에 있다.
docs 는 이 계획과 같은 브랜치에서 이미 고쳤다. phase 는 docs 를 다시 쓰지 않고, 구현이 docs 와 달라질 때만 그 절을 같은 커밋에서 고친다.

| phase | 만드는 것 |
| --- | --- |
| 01 | Hermes 실행 조회 한 번(`HermesRunsClient.lookupRun`) |
| 02 | Hermes 의 답 하나를 실행 줄과 대화에 적는 `RecoveredRunRecorder` |
| 03 | 기동 때 잡고 묻는 `RestartReconciler`. `OrphanedExecutionSweeper` 를 대신한다. 이미 있는 e2e 재시작 시나리오를 맞춘다 |
| 04 | 가짜 Hermes 의 실행 조회와 e2e 시나리오, 사용량 화면의 문구 |

순서대로 한다. 뒤 phase 가 앞 phase 의 타입을 쓴다.
phase 검증에는 포맷 검사를 넣지 않는다. Spotless 는 처음 고치는 파일 전체를 검사하므로, 통합 검증 전에 `./gradlew spotlessApply` 결과를 기능 변경과 다른 커밋으로 올린다.

## 배포 뒤 확인할 것

e2e 는 Control Plane 을 강제로 죽여 다시 띄운다. 정상 종료 신호로 내릴 때 도는 turn 스레드가 내려가는 도중 실행 줄을 `FAILED` 로 적는지는 검사하지 않는다. 그렇게 적힌 줄은 다시 정해지지 않는다. 배포 뒤 도는 실행을 둔 채 Control Plane 만 다시 띄워 그 실행이 끝까지 가는지 한 번 왕복시켜 본다. 실행 방법은 운영 저장소가 갖는다.
