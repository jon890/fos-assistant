# 실행 port 정리 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ1 | 구현이 끝난 브랜치 | `cd backend && ./gradlew archTest` 로 구조 테스트를 돌린다 | `runtime` 패키지가 `hermes` 를 기대지 않고, `hermes` 밖의 운영 코드가 `HermesRunsClient`, `HermesRunEventStream` 과 Hermes 실행 타입을 기대지 않으며, mcp 가 `AgentRuntime` 과 `AgentRuntimes` 를 기대지 않는다는 규칙이 통과한다. 새 규칙의 기준 파일에 위반이 없다 | 로컬 Gradle | worker | 없음 | PR 전 |
| REQ1 | 구현이 끝난 브랜치 | `git grep -nE "apiBaseUrl\|profileName\|hermes" -- backend/src/main/java/com/bifos/assistant/runtime` 으로 중립 port 와 타입의 글자를 본다 | 주석을 뺀 선언에 Hermes 의 주소, profile 이름이 없다. `RunEvent` 에 사건 이름 문자열 칸이 없고 `RunEventKind` 가 있다 | 로컬 저장소 | worker | 없음 | PR 전 |
| REQ1 | 구현이 끝난 브랜치 | `cd backend && ./gradlew test` 로 backend 테스트 전체를 돌리고 `git diff origin/main -- backend/src/test` 를 읽는다 | 전체 통과. 지금 있던 테스트에서 바뀐 것이 옮긴 타입의 import 와 이름, 부르는 쪽 클래스의 생성자 인자, `RuntimeTarget` 으로 바뀐 인자뿐이다. Hermes 사건 이름과 상태 문자열로 준비한 값, 단언하는 값(실행 기록의 오류 코드, 「provider 계정이 막힘」, 취소 기록)은 그대로다 | 로컬 Gradle | worker | 없음 | PR 전 |
| REQ1 | 에이전트의 지금 profile 과 실행 기록에 저장된 profile 이 다른 상황(테스트가 만든다) | 재기동 복구의 실행 조회, 대화 삭제, 위임 중지 테스트 사례를 돌린다 | 세 경우 모두 저장된 profile 로 Hermes 를 부른다 | 로컬 Gradle | worker | 없음 | PR 전 |
| REQ1 | 구현이 끝난 브랜치 | `HermesRunStatusMappingTest`, `HermesRunEventKindTest`, `HermesAgentRuntimeContractTest` 를 돌린다 | Hermes 의 종료 상태마다 저장에 쓰는 상태 글자가 받은 그대로이고 취소·성공·실패 판정과 「provider 계정이 막힘」 판정이 옮기기 전과 같다. 끝 사건 넷이 모두 끝 종류로 옮겨진다. port 계약(가린 사건, 모르는 실행, 글 끼우기)이 통과한다 | 로컬 Gradle | worker | 없음 | PR 전 |
| REQ1 | 이 PR 이 배포된 운영 | Hermes 에이전트와 대화 한 번을 왕복한다. 답 흐름, 도구 표시, 중지, 답 중 글 끼우기를 본다. 절차는 운영 저장소가 갖는다 | 네 가지가 배포 전과 같다 | 운영 | 코디네이터 | 배포 | PR 뒤 |
| REQ2 | 계획 커밋 | `docs/features/runtime-abstraction.md` 의 「하네스 설계 원칙 비교」 를 읽는다 | Hermes, OpenClaw, ZeroClaw 의 지침 구조, 도구 호출, 기억과 맥락, 다시 시도와 실패 처리, 하위 에이전트, 안전 장치, 일상생활 비서에 맞는지가 있고 읽은 날짜와 「확인 못 함」 이 표시돼 있다 | 로컬 저장소 | worker | 없음 | PR 전 |
