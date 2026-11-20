# 서비스 개념

사용자가 대화에서 서비스의 기능(예약 작업, 기억, 할 일, 외부 서비스 연결, 모델 단계, 에이전트 고르기, 파일 공간)을 평소 말로 부르면 에이전트가 알아듣고 직접 처리하거나 그 화면으로 안내하는 기능이다.

covers: `backend/src/main/java/com/bifos/assistant/context/application/ServiceConceptsInstructions.java`, `backend/src/main/java/com/bifos/assistant/chat/artifact/application/ArtifactService.java`

요구와 결정은 스펙 [`service-concepts/spec.md`](service-concepts/spec.md), 구조는 설계 [`service-concepts/design.md`](service-concepts/design.md) 가 갖는다.
스펙의 PR1 이 들어오면 모든 실행의 공통 지시문에 개념 목록이 들고, 에이전트는 직접 못 하는 기능을 그 화면 링크로 안내한다. 예약 작업, 할 일과 기억, 연결 붙이기의 직접 처리는 뒤 PR 이 더한다.

## 개념 목록

글의 원본은 `ServiceConceptsInstructions` 다. 모든 범위의 공통 지시문에서 답변 형식과 도구 호출 지침 바로 뒤에 든다([`answer-quality.md`](answer-quality.md) 의 「지침」). 대화 turn 입력의 파일 공간 단락(`ArtifactService.FILE_SPACE_RULES`)은 그대로 둔다.
