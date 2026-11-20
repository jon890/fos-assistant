## ADR-20261120 / no-local-shell: 셸 계열 도구는 local 로 켜지 않고, 정책 밖 profile 의 셸은 끈다

- **status**: `accepted`
- Date: 2026-11-20
- **decision-type**: `architecture`
- **areas**: `security`
- **현재 규칙의 원본**: 스펙 [`docs/features/agent-tools/spec.md`](../features/agent-tools/spec.md) 의 REQ1, DECISION1, DECISION17(사용자 확인 2026-11-20), 설계 [`design.md`](../features/agent-tools/design.md) 의 「PR1」, [`hermes/README.md`](../../hermes/README.md) 의 「셸 실행 공간」
- [ADR-086](ADR-086-셸과-파일-도구는-사용자별-docker-실행-공간에서만-돈다.md) 의 「정책에 없는 profile 은 도구 저장을 허용하고 local 로 둔다」 를 대체한다. [ADR-20261008 / default-toolsets](ADR-20261008-default-toolsets.md) 의 「사람의 저장은 local 로 켠다」 전제와 [ADR-20261008 / execute-code-unattended](../../hermes/docs/adr/ADR-20261008-execute-code-unattended.md) 의 「local 로 되돌릴 때 승인 키를 지운다」 경로도 없어진다. 실행 공간의 모양, 정책이 없을 때의 거절, 비밀 요청 스킬 검사는 그대로다

### 결정

**대시보드 plugin 은 정책에 없는 profile 이 셸, 파일, 코드 실행, 사진 도구를 켜는 저장을 언제나 409 로 거절한다. 거절 본문에 `reason: unlisted` 를 넣는다.**
`require_sandbox` 는 옛 Control Plane 과의 호환으로 계속 받지만 도구 저장의 판정을 바꾸지 않는다.

**Control Plane 은 기동할 때마다 실행 공간 도구가 켜진 에이전트의 도구를 지금 목록 그대로 다시 저장한다.**
`unlisted` 로 거절되면 실행 공간 도구를 빼고 다시 쓴다. `reason` 이 없는 거절(정책 없음, 정책 틀림, 디렉터리 실패)이나 Hermes 무응답은 그 에이전트를 건드리지 않고 메모리에 두었다가, 기다리는 기본 도구를 다시 켜는 주기에 끝날 때까지 다시 본다.

이 결정은 Control Plane 이 쓰는 토큰 경로에 적용한다. 운영자가 Hermes 대시보드에서 직접 설정을 바꾸는 경로는 plugin 이 검사하지 않으며 운영 규칙이 갖는다.

### 맥락

ADR-086 은 기존 실행기를 깨지 않으려고 정책에 없는 profile 의 셸을 local 로 허용했다. 기본 비서의 기본 도구는 `require_sandbox` 로 이 길을 피했지만([ADR-20261008 / default-toolsets](ADR-20261008-default-toolsets.md)), 관리자 화면의 도구 저장은 그 필드를 보내지 않아 local 로 켰다.
local 셸은 Hermes 프로세스의 사용자로 돌아 다른 profile 의 `.env`, 플러그인 값 파일, 전체 첨부에 닿는다.
사용자는 셸을 모든 에이전트의 기본으로 켜기로 하고, 그 전에 이 길부터 닫기로 했다(2026-11-20).
이미 local 로 켜진 profile 은 새 저장을 막아도 다음 셸 저장 전까지 local 로 돈다. Control Plane 은 어느 profile 이 local 인지 따로 모른다.

### 대안 기각

- **새 저장만 막는다.** 이미 열린 local 셸이 남는다(스펙 DECISION17).
- **plugin 이 기동할 때 local profile 의 셸을 끈다.** plugin 은 `sandbox_owner` 를 모른다. 정책에 있는데 local 로 남은 profile 은 주인 키가 있어야 docker 로 바꿀 수 있다.
- **한 번만 도는 표지를 두고 기동 작업을 한 번만 돌린다.** 표지를 둘 자리를 새로 만들어야 한다. 정책이 그대로면 다시 저장은 결과가 같아 매 기동 돌아도 된다. 에이전트 수는 가족 단위다.
- **기동 때 한 번만 보고 실패는 로그로 둔다.** Control Plane 과 Hermes 는 함께 재시작되는 일이 많아 첫 시도가 실패하기 쉽고, 실패한 에이전트는 다음 기동까지 local 셸로 남는다.
- **409 를 그대로 두고 정책 밖으로 본다.** 정책이 잠깐 틀린 채 기동하면 모든 에이전트의 셸을 끈다. 이유를 나눠 정책 밖일 때만 끈다.

### 결과

- 얻는 것:
  - 어떤 경로의 도구 저장도 셸을 local 로 켜지 않는다.
  - 배포 뒤 첫 기동에서 local 로 남은 셸이 docker 로 바뀌거나 꺼진다.
- 감당할 것:
  - **정책 밖 profile 의 에이전트는 셸, 파일, 코드 실행이 꺼진다.** 정책의 기본 줄이 운영에 들어올 때까지 다시 켤 수 없다(스펙 DECISION3, 설계 「PR2」).
  - 관리자가 정책 밖 profile 에 셸을 켜려 하면 「격리된 실행 공간이 준비되지 않아 이 도구를 켤 수 없어요.」 를 본다.
  - 기동할 때마다 셸이 켜진 에이전트 수만큼 Hermes 설정 쓰기가 생긴다.
  - 운영이 정책을 바꾼 뒤의 기동은 셸 에이전트의 실행 공간 설정을 새 정책으로 다시 써 컨테이너가 바뀐다. 운영의 일괄 반영을 대신한다.
  - plugin 을 Control Plane 보다 먼저, 붙여서 배포한다. 반대면 기동 작업이 `reason` 을 받지 못해 아무것도 끄지 않는다. 둘 사이에는 정책 밖에서 셸이 켜진 에이전트의 사진 대화, 스킬 첫 게시, 다른 도구 저장도 409 로 실패한다.
  - 정책에서 빠진 docker profile 은 셸이 꺼진 뒤에도 docker `terminal:` 과 `approvals.unattended_mode: approve` 가 남는다. 셸 계열이 꺼져 있어 쓰이지 않는다.
- **확인 방법**: plugin 단위 테스트가 정책 밖 저장의 409 와 `reason`, `terminal:` 불변을 본다. Control Plane 단위 테스트가 기동 작업이 `unlisted` 에서만 셸을 끄는 것을 본다. 배포 뒤 셸이 켜진 에이전트의 backend 확인은 운영 저장소가 한다.
