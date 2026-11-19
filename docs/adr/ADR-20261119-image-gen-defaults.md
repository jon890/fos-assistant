## ADR-20261119 / image-gen-defaults: 기본 비서의 「그림 만들기」 는 Control Plane 이 켜고 이미지 서비스를 함께 넣으며 사람이 없는 실행에서는 막는다

- **status**: `accepted`
- Date: 2026-11-19
- **decision-type**: `domain-policy`
- **areas**: `security, operations`
- **현재 규칙의 원본**: 스펙 [`spec.md`](../features/generated-image/spec.md) 의 REQ11~REQ14 와 DECISION4, DECISION5, DECISION6, DECISION14, DECISION23, DECISION31(사용자 확인 2026-11-19), 설계 [`design.md`](../features/generated-image/design.md) 의 「PR2」, [`agent-skill.md`](../features/agent-skill.md) 의 「에이전트 도구」, [`users.md`](../features/users.md) 의 「첫 에이전트의 기본 도구」
- [ADR-20261008 / default-toolsets](ADR-20261008-default-toolsets.md) 의 기본 도구에 `image_gen` 을 더하고, 그 ADR 이 기각한 「기존 기본 profile 에 한 번 켜기」 를 이 도구에 한해 받아들인다. [ADR-029](../../backend/docs/adr/ADR-029-에이전트-도구는-control-plane-이-등급으로-판정하고-hermes-설정-api-로-쓴다.md) 의 등급 표는 바꾸지 않는다

### 결정

**모든 기본 비서에 「그림 만들기」(`image_gen`)를 Control Plane 이 켠다. 켜는 저장이 그 profile 에 이미지 서비스 설정을 함께 넣는다. 사람이 없는 실행의 `image_generate` 는 도구 판정이 막는다.**

| 항목 | 정한 것 |
| --- | --- |
| 새 기본 비서 | 기본 도구(`assistant.people.default-toolsets`)에 `image_gen` 을 더한다 |
| 이미 있는 기본 비서 | 마이그레이션이 한 번 기다리는 목록(`agent.pending_default_toolsets`)에 넣고, 요청 밖의 주기 작업이 켠다 |
| 이미지 서비스 | Control Plane 이 `image_gen` 이 든 도구 저장에 `ensure_image_service: true` 를 넣어 보내고, 대시보드 plugin 이 같은 쓰기에서 `image_gen.provider` 를 넣는다. 서비스는 ChatGPT 구독의 gpt-image-2 하나이고 값은 plugin 이 갖는다. 이미 있는 설정은 덮어쓰지 않는다 |
| 넣지 못하면 | plugin 이 설정을 먼저 쓰고 처리기가 실패하면 되돌리므로 도구도 켜지지 않는다. 이 칸을 모르는 옛 plugin 은 400 으로 거절한다 |
| 실행 공간이 준비되지 않았다 | 켜지 않고 기다리는 목록에 둔 채 5분마다 다시 본다. 주인 그룹의 관리자에게 사용자와 까닭을 알림 한 건으로 남긴다. 운영이 그 profile 을 실행 공간 정책에 등록한 뒤의 차례에 스스로 켜고 알림을 읽음으로 바꾼다 |
| 관리자가 끈 비서 | 사람의 도구 저장이 그 도구를 켰거나 켜져 있던 것을 끄면 기다리는 목록에서 뺀다. 목록에 다시 넣는 길은 마이그레이션 한 번과 첫 로그인뿐이라 배포와 재시작이 다시 켜지 않는다 |
| 사람이 없는 실행 | 도구 판정(`/internal/hermes/tool-guard`)이 그 호출의 실행을 찾고 트리 루트의 `unattended` 가 참이면 `image_generate` 를 막는다. 모델은 그림을 만들 수 없다는 글을 받는다 |
| 사용자가 따로 만든 비서 | 기본으로 켜지 않는다. 관리자가 켜면 이미지 서비스가 같은 길로 들어간다 |

### 맥락

「그림 만들기」 toolset 을 켜도 그 profile 에 이미지 서비스 설정이 없으면 Hermes 가 `image_generate` 를 모델에 보이지 않는다([`hermes-contract.md`](../../hermes/docs/hermes-contract.md) 의 「`image_generate` 의 결과와 그림 파일」). 2026-11-19 까지는 관리자 profile 하나에만 운영에서 설정을 넣어 두었다.
`image_gen` 은 사진 파일을 읽는 도구라 실행 공간 정책에 등록된 profile 에서만 켜진다([ADR-091](ADR-091-사진-첨부는-사용자별로-저장하고-실행-공간에는-그-사용자만-붙인다.md)).

**실행 공간 준비 가운데 Control Plane 이 스스로 할 수 있는 범위를 확인했다.**

| 단계 | 누가 하나 |
| --- | --- |
| 주인의 첨부 디렉터리 만들기 | Control Plane. 도구 저장 전에 이미 만든다 |
| 사용자 작업 디렉터리와 플러그인 출력 디렉터리 만들기, profile 의 `terminal:` 을 실행 공간 설정으로 쓰기 | 대시보드 plugin. 도구 저장 때 이미 한다 |
| profile 을 실행 공간 정책에 등록하기 | 운영. 정책은 대시보드 프로세스의 환경 값이라 Hermes 를 다시 만들어야 바뀐다. 절차는 운영 저장소가 갖는다 |

마지막 단계만 Control Plane 이 하지 못한다. 그래서 그 단계는 관리자 알림으로 남기고, 끝났는지는 다시 켜 보는 것으로 안다.

구독 한도를 지키는 장수 상한은 두지 않기로 했다(스펙 DECISION6). 대신 사람이 직접 부탁하지 않은 실행은 그림을 만들지 않는다(DECISION14).

### 대안 기각

- **Control Plane 이 운영의 정책 적용 작업을 직접 일으킨다.** 사용자가 기다릴 것이 없어진다. 그러나 Control Plane 이 Hermes 를 다시 만드는 권한을 갖게 되고, 그 작업은 도는 대화를 끊는다. 운영 저장소의 승인 절차도 건너뛴다.
- **plugin 이 실행 중에 관리 표식이 있는 profile 을 정책 대상으로 본다.** 운영 작업 없이 새 profile 이 들어간다. 그러나 정책 계약과 운영의 Docker 요청 검사를 함께 바꾸고 두 저장소의 배포 순서를 맞춰야 한다. 운영 저장소가 같은 까닭으로 기각했다.
- **기동할 때마다 모든 기본 비서에 켠다.** 상태 칸이 필요 없다. 그러나 관리자가 끈 비서가 재시작마다 다시 켜진다(DECISION23).
- **기다리는 상태를 메모리나 새 표에 둔다.** 메모리는 재시작에 사라져 준비가 끝나도 켜지 않는다. 새 표는 만들지 않는다(사용자 결정 2026-11-01). `agent` 의 칸 하나로 충분하다.
- **이미지 서비스 값을 Control Plane 이 보낸다.** 서비스를 바꿀 때 plugin 을 배포하지 않아도 된다. 그러나 plugin 이 받을 값을 다시 검사해야 해 값이 두 곳에 생긴다. 서비스는 하나다(DECISION5).
- **이미지 서비스를 profile 틀에 넣는다.** 새 profile 은 처음부터 설정을 갖는다. 그러나 이미 있는 profile 에는 닿지 않고, 틀은 도구를 켜지 않은 profile 에도 설정을 남긴다.
- **사람이 없는 실행의 지시에만 「그림을 만들지 않는다」 고 적는다.** 코드가 없다. 그러나 모델이 따르지 않으면 한도를 쓴다. 도구 판정은 이미 호출마다 묻고 있어 한 줄로 막는다.
- **사람이 없는 실행에서 toolset 을 끈다.** Hermes 는 실행마다 toolset 을 줄이지 못한다([ADR-20261105 / event-watch](ADR-20261105-event-watch.md)).

### 결과

- 얻는 것:
  - 가족 모두가 각자의 기본 비서에서 따로 설정하지 않고 그림을 만든다.
  - 도구가 켜졌는데 이미지 서비스가 없어 「도구가 없다」 고 답하는 상태가 생기지 않는다.
  - 실행 공간이 늦게 준비돼도 관리자가 도구 화면에서 다시 켜지 않아도 된다.
- 감당할 것:
  - **대시보드 plugin 을 Control Plane 보다 먼저 배포한다.** 옛 plugin 은 `ensure_image_service` 를 400 으로 거절한다. 그동안 `image_gen` 이 켜진 에이전트의 도구 저장이 실패하고, 기다리는 기본 비서는 실패가 세 번 이어지면 관리자에게 알린다. plugin 을 올리면 다음 차례에 켜진다.
  - 실행 공간 정책 등록은 여전히 운영의 일이다. 새 사용자가 등록 전에 첫 로그인하면 그림 만들기는 등록 뒤 5분 안에 켜진다.
  - 이미지 서비스는 그 profile 의 ChatGPT 구독 로그인을 쓴다. 로그인이 없는 profile 은 설정이 있어도 `image_generate` 가 보이지 않는다. Control Plane 은 이것을 보지 못하므로 배포 뒤 가족 비서에서 한 장을 만들어 확인한다.
  - 기다리는 도구를 켜는 쓰기는 지금 켜진 도구를 다시 저장한다. 실행 공간 정책이 바뀌어 있었으면 그 profile 의 실행 공간이 새로 만들어진다. 사람의 도구 저장과 같다.
  - Control Plane 밖에서 연 session(실행을 찾지 못한 호출)의 그림 만들기는 이 판정이 막지 않는다. Hermes 의 예약 작업 toolset 은 꺼져 있다.
  - 장수 상한이 없다. 한도가 소진되면 비서의 답 자체가 멈출 수 있다(스펙 DECISION27).

- **적용 범위**: `agent/application/AgentPendingToolsets`, `AgentDefaultToolsets.enablePending`, `AgentToolService` 의 저장 뒤 정리, `people/application/FirstAgentCreator`, `HttpHermesToolsetClient` 의 `ensure_image_service`, `orchestration/application/EventToolGuard`, 대시보드 plugin 의 `image_service.py` 와 `PUT /api/config`.
