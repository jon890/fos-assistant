## ADR-029: 에이전트 도구는 Control Plane 이 등급으로 판정하고 Hermes 설정 API 로 쓴다

- **status**: `accepted`
- **결정**: 에이전트가 쓸 도구를 화면에서 고른다.
  누가 어느 도구를 켤 수 있는지는 Control Plane 이 도구 등급으로 판정하고, 고른 목록은 Hermes 공식 `PUT /api/config` 로 그 profile 의 `platform_toolsets.api_server` 에 쓴다.
  `agent.disabled_toolsets` 는 다른 platform 에도 적용되므로 쓰지 않고 profile 의 기존 값을 둔다.
  도구 목록의 정본은 계속 profile 의 설정이다. Control Plane 은 도구 선택을 데이터베이스에 따로 두지 않고 쓸 때와 보일 때 Hermes 에서 읽는다.
  대시보드 plugin 은 공유 서비스 토큰 하나를 그대로 쓰고, 요청 본문이 도구 목록만 담는지와 profile 하나만 가리키는지를 강제한다.

### 맥락

[ADR-007](ADR-007-에이전트가-모델과-도구를-함께-정한다.md) 은 도구를 profile 이 갖는다고 정했다. 그동안 도구는 홈서버 스크립트가 profile 설정을 고쳐 바꿨다.
사용자가 자기 에이전트를 만들고 스킬을 올려 쓰게 하려면 도구도 화면에서 골라야 한다.

Hermes 의 `PUT /api/config` 는 쓸 수 있는 키를 제한하지 않는다. 본문의 `profile` 이 query 보다 먼저 선택되고, 요청자가 그 profile 의 주인인지 보지 않는다.
대시보드 토큰에는 profile 신원이 없다.
v0.21.3 에서 plugin 이 본문을 먼저 읽고 같은 요청을 처리기에 넘길 수 있음을 확인했다. 근거는 [`hermes/tools-and-skills.md`](../hermes/tools-and-skills.md#설정-api와-profile-경계) 에 있다.

### 도구 등급

| 등급 | toolset | 켜는 사람 |
| --- | --- | --- |
| 주인 | `web`, `vision`, `todo`, `clarify`, `session_search`, `skills`, `tts`, `delegation` | 그 에이전트의 주인과 `ADMIN` |
| 관리자 | `terminal`, `file`, `code_execution`, `browser`, `computer_use`, `cronjob`, `image_gen`, `video_gen`, `homeassistant`, `spotify`, `discord` | `ADMIN` 만 |
| 항상 끔 | `memory` | 아무도. 기억은 Control Plane 이 갖는다([ADR-003](ADR-003-memory-권한은-주입으로-강제한다.md)) |
| 항상 켬 | 기억 MCP `fos-assistant-memory` | 아무도 끄지 못한다 |

등급 표에 없는 toolset(예: v0.21.3 에 들어온 `connections`, `kanban`)은 화면의 선택 목록에 보이지 않는다.
도구를 쓸 때 plugin 이 허용 목록을 검사해 모르는 이름을 거절한다.
Hermes 를 올릴 때는 `fos-home-infra` 의 기능 검사가 모든 profile 의 켜진 목록을 허용 목록과 대조한다.
Hermes 가 쓰기 없이 toolset 을 자동으로 켤 수도 있어, 도구 조회 응답은 켜진 미분류 이름을 따로 알리고 화면은 관리자에게 알리라고 안내한다.
허락은 켜는 시점에 한 번이다. 쓸 때마다 묻지 않는다. 관리자 등급을 켤 때는 확인 창을 거친다.

**셸과 파일 계열(`terminal`, `file`, `code_execution`, `browser`, `computer_use`)이 켜진 에이전트는 `PRIVATE` 만 된다.**
profile 분리는 이 도구의 파일 접근을 격리하지 않아, 그룹에 공개하면 다른 사용자가 그 에이전트로 홈서버 파일에 닿는다.

### 대안 기각

| 대안 | 기각 이유 |
| --- | --- |
| 홈서버 스크립트로만 바꾼다 | 사용자가 자기 에이전트를 고르지 못한다. 스크립트가 설정 파일을 덮어쓰는 방식을 늘리는 것은 upstream 을 따른다는 원칙과 맞지 않는다 |
| profile 마다 대시보드 토큰을 둔다 | 토큰 발급과 회전이 profile 수만큼 는다. 토큰을 모두 Control Plane 이 쥐므로 Control Plane 이 뚫리면 결과가 같다 |
| 도구 선택을 데이터베이스에 두고 Hermes 에 복사한다 | 정본이 둘이 되어 스크립트나 대시보드에서 바꾼 값과 어긋난다 |
| 허용 키만 쓰는 전용 경로를 API server plugin 에 만든다 | 공유 gateway 재시작이 필요하고 Hermes 의 설정 쓰기를 우리가 다시 구현한다 |

### 결과

- 얻는 것: 주인이 자기 에이전트의 도구를 화면에서 바꾸고, 다음 실행부터 적용된다. 재시작이 필요 없다
- 감당할 것:
  - 공유 토큰이 새면 다른 profile 의 도구 목록을 바꿀 수 있다. 모델, 승인 방식, MCP 서버는 plugin 의 키 검사 때문에 바꾸지 못한다. 이 토큰은 이미 profile 을 만들고 `.env` 를 쓸 수 있다([ADR-018](ADR-018-사람을-더하는-것을-control-plane-이-끝낸다.md))
  - plugin 의 검사와 Control Plane 의 등급 판정이 함께 있어야 경계가 선다. 어느 한쪽을 바꾸면 다른 쪽도 본다
  - Hermes 가 새 toolset 을 더하면 설정을 쓰지 않아도 그 toolset 이 켜질 수 있다. 쓰기 검사와 기동 기능 검사, 화면 경고로 찾으며 실행마다 검사하지 않는다
  - profile 의 공통 비활성화 목록이 막은 toolset 은 API 실행에서도 켤 수 없다. 쓰고 다시 읽어 켜지지 않은 이름을 오류 응답에 담고 관리자에게 알리도록 안내한다
