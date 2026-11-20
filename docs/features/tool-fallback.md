# 도구 실패 뒤 다른 방법

비서가 도구 한 번이 실패했을 때 바로 사용자에게 넘기지 않고, 다른 방법을 정해진 횟수까지 해 본 뒤에만 사용자에게 묻게 하는 기능이다.

covers: `backend/src/main/java/com/bifos/assistant/context/application/ContextAssembler.java`, `backend/src/main/java/com/bifos/assistant/context/application/ToolFailureInstructions.java`, `backend/src/main/java/com/bifos/assistant/context/application/ApprovalCardInstructions.java`, `backend/src/main/java/com/bifos/assistant/mcp/`, `backend/src/main/java/com/bifos/assistant/connector/approval/application/`, `backend/src/main/java/com/bifos/assistant/orchestration/application/EventToolGuard.java`, `backend/src/main/java/com/bifos/assistant/shared/util/`, `backend/src/main/java/com/bifos/assistant/chat/artifact/infra/`, `hermes/plugins/fos-ctx/`

요구와 결정은 스펙 [`tool-fallback/spec.md`](tool-fallback/spec.md), 구조는 설계 [`tool-fallback/design.md`](tool-fallback/design.md) 가 갖는다.
PR1 로 들어온 것은 아래다. 링크 사진 후보를 넓히는 PR2 는 아직이다.

- 모든 실행의 공통 지침에 「도구가 실패했을 때」 절이 든다. 원본은 `ToolFailureInstructions` 다
- Control Plane MCP 도구와 판정 글, fos-ctx 의 실패 글이 「다음 방법: …」 이나 「사용자에게 바로 알린다.」 한 줄로 끝난다. Java 는 `ToolFailureText`, fos-ctx 는 `next_step.py` 가 그 줄을 만든다
- JSON 으로 실패를 돌려주는 도구(원고, 사진 관찰, 위임과 그 상태 조회, 예약 작업, 금융 상한 변경)는 같은 줄을 `next` 칸에 둔다
- 그림의 참고 사진을 받지 못하면 설명만으로 비슷하게 그려 넣지 않고 먼저 그 사실과 사진이나 링크를 보내 달라는 대안을 알린다(스펙 REQ7). 답변 품질 평가에 이 사례 문항(`photo-request`)이 있다

## 배포 뒤 확인

스펙 REQ6 의 PR1 몫이다. 배포하고 fos-ctx 를 profile 에 다시 설치한 뒤 없는 기억을 고치게 해, 비서가 묻기 전에 `memory_id` 없이 새로 남기는 호출을 하고 답에 새로 남겼다고 알리는지 본다. 실행 사건에 실패와 다른 방법 호출이 차례로 남아야 한다.
확인한 사람이 날짜와 결과를 이 절에 적는다.
