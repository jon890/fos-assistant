## ADR-003: Memory 권한은 주입으로 강제한다

- Status: Accepted
- Date: 2026-09-17

### 맥락

개인 Memory 와 가족 공용 Shared Memory 가 함께 있어야 한다.
접근 권한은 LLM 의 판단이 아니라 애플리케이션 계층이 정해야 한다.

프롬프트로 "남의 memory 는 읽지 마라"라고 적는 방식은 검사할 수 없는 약속이다.
에이전트에게 memory 조회 도구를 주면 그 도구가 무엇을 돌려주느냐가 경계가 된다.

### 결정

Memory 의 단일 소스는 Control Plane 데이터베이스다.
Hermes 의 내장 memory 는 개인 Memory 로 쓰지 않는다.

실행할 때 Control Plane 이 요청자가 볼 수 있는 항목만 골라
Runs API 의 `instructions` 로 넣는다.
`instructions` 는 에이전트의 기본 프롬프트를 지우지 않고 그 위에 얹힌다.

Hermes 내장 memory 조회 도구는 주지 않는다.
제목만 주입한 항목은 [ADR-015](ADR-015-memory-는-층을-나눠-싣는다.md)에서 정한
Control Plane MCP 도구로만 읽는다.
Control Plane 이 토큰의 사용자를 먼저 정하고 그 사용자가 볼 수 있는 본문만 응답한다.

### 거절한 대안

- Hermes 내장 memory 를 개인 Memory 로 쓰는 방식은
  개인과 공용의 저장 위치가 갈라지고 화면에서 개인 Memory 를 보여주려면 Hermes 파일을 따로 읽어야 하므로 쓰지 않는다.
- Hermes 내장 memory 조회 도구에서 권한을 검사하는 방식은
  MVP 에서 필요하지 않은 왕복을 더하므로 지금은 쓰지 않는다.
  나중에 memory 가 커져 전부 주입하기 어려워지면 그때 도입한다.
  그때도 권한 검사는 Hermes 도구가 아니라 Control Plane 이 맡는다.

### 결과

권한 경계가 한 곳에 모인다.
항상 싣는 본문과 제목 색인은 주입 문자열로 검사하고,
색인 본문은 Control Plane MCP 응답으로 검사한다.

대신 memory 가 많아지면 주입할 양이 실행마다 늘어난다.
그 시점에 도구 방식으로 옮긴다.
