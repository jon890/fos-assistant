## ADR-20261011 / content-snapshot-job: 승인한 원고는 불변 참조와 예약 job으로 한 번만 저장한다

- **Date**: 2026-10-11
- **status**: `accepted`
- **결정**: 원고 revision과 compiler의 두 해시를 snapshot으로 고정하고, 기존 ConnectorAction 승인과 서버가 미리 예약한 네이버 worker jobId를 영속 링크로 연결한다. 같은 snapshot은 실패와 UNKNOWN을 포함해 같은 job만 조회한다. snapshot 본문을 별도 표에 복제하지 않는다.
- **맥락**: 기존 네이버 save_draft는 새 UUID로 worker를 띄우며 시작 응답이 유실되면 그 UUID를 Control Plane이 모를 수 있다. ConnectorAction의 도구 성공은 worker 접수 결과여서 실제 임시저장 완료와 다르다. compiler manifest는 업로드 입력을 증명하지만 provider component 대응은 아직 증명하지 못한다.
- **대안 기각**:
  - 모델에게 다섯 DSL 칸을 다시 보내게 하면 승인 revision·원본·경로의 동일성을 서버가 보장할 수 없다.
  - UNKNOWN에서 새 승인을 받아 저장을 재실행하면 이미 저장된 글을 중복 생성할 수 있다.
  - 범용 workflow engine이나 outbox 재전송은 기존 Hermes·worker의 실행 책임을 복제하고 전송 유실을 재시도로 확대한다.
  - 금융 snapshot은 주문 전용 strict 계약이므로 원고 승인에 억지로 넣지 않는다.
- **결과**:
  - 얻는 것: 승인한 사용자·대화·binding·revision·사진 지문과 같은 실제 job을 재접속 후에도 추적한다. 승인 입력과 외부 저장 결과를 분리한다.
  - 감당할 것: 전송 전후의 불확실한 중단은 실제로 실행하지 않았어도 UNKNOWN으로 남을 수 있다. 같은 snapshot의 재저장은 허용하지 않는다. 저장 claim 이후의 철회는 이미 허가한 클릭을 되돌리지 못한다.
- **승인과 철회**: chat 하위 port를 connector가 구현하고 승인 선택·catalog 준비는 TX 밖, 현재 검사와 예약은 coordinator 한 번 안에서 끝낸다. execution·사용자 허용 목록·agent의 실제 writer 잠금과 start·save claim을 같은 트랜잭션에서 직렬화한다. EXECUTING 연결 해제 거절은 보존하고 실제 사용자/agent 철회와 CANCELLED 커밋만 claim 차단의 근거로 삼는다. 원고 승인 줄은 기존 `save_draft` 위험도로 판정하되 승인 방식 `always`로 저장해 상시 허락을 닫는다. 기존 `save_draft` 선언은 바꾸지 않고, 원고 승인 줄의 등록 이름은 서버 도구가 아니어서 일반 승인 경로로는 실행되지 않는다.
- **수명**: 원고 승인 줄은 job이 수명을 소유한다. 일반 실행 정리(5분 정리와 기동 정리)는 이 줄을 UNKNOWN으로 바꾸지 않는다. 대신 job 기한 정리가 승인 뒤 15분 동안 끝나지 않은 job을 한 번 조회하고 닫으며 승인 줄을 같은 결과로 끝낸다. 15분은 ticket 최대 수명과 worker 실행 상한보다 길어 그 뒤에는 새 claim이 없다. 승인 줄은 EXECUTING일 때만 terminal로 바꾼다.
- **조회**: stamped private job은 공개 draft_job에서 거절하고 정확한 CP stamp로만 projection한다. 일반 action 최근 20개와 별도의 bounded 원고 job 링크 페이지로 terminal을 재발견한다.

- **적용 범위**: #396·#398의 새 글 임시저장 연결에만 적용한다. 승인 요청·jobId 예약·worker 허가·시작 전송·private 상태 조회는 구현되어 있고 MCP와 화면 연결은 후속이다. 운영 start·status capability는 false를 유지한다. 원고 MCP 최초 생성의 중복 방지와 진단 정책의 운영 수락, 실제 사진 component 관측을 대신하지 않는다. 상세 계약은 [원고 기능](../features/content-draft.md#승인-snapshot과-저장-job)이 갖는다.
