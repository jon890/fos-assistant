## ADR-20261121 / acp-runtime: 실행 port 를 런타임에 묶이지 않게 두고 두 번째 런타임은 ACP 구현 하나로 붙인다

- **status**: `accepted`
- Date: 2026-11-21
- **decision-type**: `architecture`
- **areas**: `security, operations`
- **현재 규칙의 원본**: 스펙 [`runtime-abstraction/spec.md`](../features/runtime-abstraction/spec.md), 설계 [`runtime-abstraction/design.md`](../features/runtime-abstraction/design.md), 조사 [`runtime-abstraction.md`](../features/runtime-abstraction.md)
- [ADR-001](ADR-001-hermes를-런타임으로-두고-core를-고치지-않는다.md) 은 그대로다. Hermes 가 기본 런타임이고 core 를 고치지 않는다. 이 ADR 은 그 옆에 다른 런타임을 붙이는 경계를 정한다.
- [ADR-086](ADR-086-셸과-파일-도구는-사용자별-docker-실행-공간에서만-돈다.md) 과 [ADR-20261120 / no-local-shell](ADR-20261120-no-local-shell.md) 은 Hermes profile 에 그대로 적용한다. Hermes 가 아닌 런타임의 명령 실행은 이 ADR 의 「명령 실행」 행을 따른다.

### 결정

**Control Plane 의 실행 port 를 런타임 이름이 없는 계약으로 바꾸고, Hermes 가 아닌 런타임은 Agent Client Protocol(ACP) 구현 하나로 붙인다. 런타임은 관리자만 바꾸는 에이전트별 내부 설정이고 기본은 Hermes 다.**

| 무엇 | 값 |
| --- | --- |
| 중립으로 바꾸는 것 | 실행 port 하나. turn 제출, 사건, 중지, 글 끼우기, 조회, 잃은 실행 정리, 세션 삭제, 사용량 읽기 |
| Hermes 전용으로 두는 것 | profile, 플러그인, 스킬, toolset, gateway 를 부르는 나머지 port |
| 두 번째 구현 | ACP 구현 하나. 런타임을 더할 때는 띄우는 명령과 설정만 더한다 |
| 첫 대상 | OpenClaw 와 ZeroClaw. 비교가 목적이고 Hermes 를 내리지 않는다 |
| 하지 못하는 동작 | 예외가 아니라 port 의 값으로 답하고 부르는 쪽이 지금의 대체 흐름을 쓴다. 도는 turn 에 글 끼우기는 「끼우지 않았다」 로 답해 다음 turn 으로 넘어간다 |
| 런타임이 가진 기억, 예약, 채널, 스킬, 하위 에이전트 | 끄거나 쓰지 않는다. 기억과 예약과 위임은 Control Plane 도구로만 한다 |
| 격리 | 사용자마다 런타임의 디렉터리와 프로세스를 따로 둔다 |
| 로그인 | 런타임 디렉터리의 파일에 둔다. Control Plane 은 토큰을 보지 않는다 |
| 명령 실행 | 명령 실행, 파일 쓰기, 작업 디렉터리 밖 읽기는 늘 승인 카드로 묻고 승인받은 것만 런타임이 도는 곳에서 돈다. 늘 묻게 설정하지 못하는 런타임은 그 도구를 끈다 |

### 맥락

Control Plane 은 Hermes 하나에 맞춰져 있고 Hermes 는 마이너 버전마다 계약이 크게 바뀐다.
사용자는 「최대한 열린 제품으로 개발하고 쉽게 갈아탈 수 있는 환경」 과, 일상 비서로 쓸 만한 다른 하네스를 Hermes 와 품질로 비교하기를 원했다(2026-11-21).
OpenClaw, ZeroClaw, goose, Hermes 가 모두 ACP 로 뜬다. ACP 는 세션 만들기, 불러오기, turn 보내기, 취소, 사건 알림, 권한 요청을 표준으로 둔다.

### 대안 기각

- **런타임마다 전용 구현을 만든다(Codex app-server 의 자체 JSON-RPC 등)**: 런타임을 더할 때마다 프로토콜 구현이 늘어 「쉽게 갈아탄다」 는 목표와 어긋난다. ACP 로 되지 않는 것이 확인되면 그때 사용자에게 다시 묻는다.
- **OpenAI 호환 HTTP 로 붙인다**: 여러 하네스가 지원하지만 세션, 중지, 권한 요청이 표준에 없다. OpenClaw 는 이 연결의 토큰이 Gateway 전체 권한이다.
- **port 를 모두 중립으로 바꾼다**: profile, 플러그인, 스킬, toolset 은 Hermes 대시보드에 기대고 다른 런타임에 짝이 없다. 교체가 정해지기 전에 바꾸면 쓰지 않을 추상화가 생긴다.
- **새 런타임의 셸을 사용자별 Docker 실행 공간에 넣는다**: 런타임마다 실행 공간을 붙이는 방법이 달라 ACP 구현 하나로 끝나지 않는다. 쓰는 사람이 관리자와 평가 계정뿐이라 승인 카드로 막는다.

### 결과

- 얻는 것: 실행을 부르는 코드가 런타임을 모른다. 런타임 하나를 더하는 일이 띄우는 명령과 설정으로 줄어든다. 같은 질문을 세 런타임으로 돌려 숫자로 비교한다.
- 감당할 것:
  - ACP 에는 도는 turn 에 글을 끼우는 표준이 없어 새 런타임에서는 다음 turn 으로 넘긴다.
  - 승인받은 명령이 Docker 실행 공간이 아니라 런타임이 도는 곳에서 돈다. 그곳에 로그인 파일과 그 사용자의 세션이 있다.
  - 새 런타임의 에이전트는 Hermes 에 설치하는 플러그인, 스킬, toolset 을 쓰지 못한다.
  - 구독 로그인, 지침 넣기, Control Plane 도구 붙이기, 웹 검색이 ACP 로 되는지는 2026-11-21 에 문서로만 확인했다. ACP 구현을 넣는 PR 의 설계가 실제로 띄워 확인하고, 되지 않으면 이 ADR 을 고친다.

### 확인 방법

중립 port 의 패키지가 Hermes 패키지를 기대지 않는지와 Hermes 밖의 코드가 Hermes 실행 타입을 기대지 않는지를 구조 테스트가 본다. 사건 이름과 끝난 상태는 enum 과 이름 표의 단위 테스트가 본다. 구현별 계약은 port 계약 테스트 하나를 Hermes 구현과 ACP 구현에 함께 돌린다.
