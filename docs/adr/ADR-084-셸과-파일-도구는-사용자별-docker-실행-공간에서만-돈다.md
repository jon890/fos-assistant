## ADR-084: 셸과 파일 도구는 사용자별 docker 실행 공간에서만 돈다

- **status**: `accepted`
- **결정**: `terminal`, `file`, `code_execution` toolset 이 켜진 profile 은 Hermes 의 docker terminal backend 로만 돈다.
  대시보드 plugin 이 그 셋 가운데 하나라도 켜는 도구 저장을 받을 때 그 profile 의 `terminal:` 설정을 통째로 다시 쓴다.

  | 칸 | 값 | 까닭 |
  | --- | --- | --- |
  | 컨테이너 | profile 마다 하나, 오래 간다(`container_persistent: true`) | 스킬 마운트가 profile 마다 정확하다. 공유 키는 첫 profile 의 마운트만 붙는다 |
  | `/workspace` | 실행 공간 루트 아래 **사용자 디렉터리**를 붙인다 | 한 사용자의 에이전트끼리는 파일을 함께 쓰고, 다른 사용자의 파일은 보이지 않는다 |
  | `/root` | profile 마다 따로(Hermes 기본) | 셸 이력과 사용자 패키지가 profile 사이에 섞이지 않는다 |
  | 망 | 운영이 정한 전용 망. 인터넷은 열어 둔다 | 같은 bridge 의 내부 서비스에 닿지 않게 한다. 밖으로 나가는 요청은 막지 않고 기록한다 |
  | 자원 | CPU 와 메모리 상한, pids 256 | 한 사람의 셸이 다른 실행을 굶기지 않는다 |
  | 환경 값 | `docker_forward_env`, `docker_env`, `env_passthrough`, `credential_files` 를 빈 목록으로 쓴다 | profile `.env` 의 값이 컨테이너에 들어가는 운영자 경로를 닫는다 |
  | 더 붙이는 경로 | 운영이 정한 읽기 전용 경로만. 모든 실행 공간에 붙일 것과 profile 하나에만 붙일 것을 나눈다 | 사진 첨부와 커리어 실행기처럼 꼭 필요한 것만 연다 |

  실행 공간 루트, 이미지, 망 이름, 자원 값, 더 붙일 경로는 운영 값이다. 대시보드 프로세스의 환경 변수 `FOS_ASSISTANT_SANDBOX` 로 받는다.
  **그 값이 없거나 틀리면 셋 가운데 하나라도 켜는 도구 저장을 409 로 거절한다.** 로컬 셸로 넘어가지 않는다.
  사용자 디렉터리 이름은 Control Plane 이 도구 저장 요청의 `sandbox_owner` 로 준다. 에이전트 주인이 있으면 `u<사용자 번호>`, 없으면 `a<에이전트 번호>` 다.

  Control Plane 은 올린 스킬의 앞머리에 `required_environment_variables`, `required_credential_files`, `setup.collect_secrets`, `prerequisites.env_vars` 가 있으면 저장을 거절한다.
  Hermes 는 스킬을 읽을 때 이 칸의 이름으로 profile 의 값과 파일을 실행 공간에 넣는다. 실측에서 Control Plane MCP 토큰과 커넥터 값 파일이 그렇게 들어왔다.

  Docker 는 컨테이너 생성 요청 본문을 검사하는 socket proxy 로만 다룬다. proxy 는 운영 저장소가 소유한다.
  egress proxy(Hermes 의 iron-proxy)는 지금 켜지 않는다. 동작 계약과 측정은 [`hermes/sandbox.md`](../hermes/sandbox.md) 가 갖는다.

- **맥락**: 모든 profile 이 Hermes 컨테이너 하나에서 돈다. 셸과 파일 도구를 켠 에이전트는 다른 profile 의 `.env`, 커넥터 값 파일, 커넥터 코드, Hermes 설정을 읽고 고칠 수 있었다([`hermes/tools-and-skills.md`](../hermes/tools-and-skills.md)).
  사용자는 터미널, 파일, 코드 실행을 최대한 열어 쓰되 사용자마다 격리된 파일 공간에서만 읽고 쓰게 하기로 했다(2026-10-05).
  밖으로 나가는 네트워크는 열어 두고 기록만 하며, 허용 목록 방식은 실측 비용을 본 뒤 다시 정하기로 했다.
  운영과 같은 이미지에서 docker backend 를 켜자 셸, 파일 도구, `execute_code` 가 모두 컨테이너 안에서 돌았고 합성 비밀에 하나도 닿지 못했다.

- **대안 기각**:
  - **SSH backend 로 미리 띄운 컨테이너에 붙인다.** Docker 접근 없이 된다. 그러나 profile 마다 sshd 와 key 를 운영해야 하고, 원격 홈으로 스킬과 media cache 를 동기화하는 경로를 따로 검증해야 한다. docker backend 는 Hermes 가 마운트를 읽기 전용으로 붙이고 컨테이너를 label 로 관리한다.
  - **`docker_shared_container_key` 로 사용자마다 컨테이너 하나를 둔다.** 컨테이너 수가 가장 적다. 그러나 첫 profile 의 스킬과 `terminal.*` 만 그 컨테이너에 붙어, 같은 사용자의 다른 에이전트가 자기 스킬 스크립트를 셸에서 찾지 못한다.
  - **profile 마다 `/workspace` 를 따로 둔다.** Control Plane 이 사용자 키를 보낼 필요가 없다. 사용자는 사용자 단위 공간을 골랐다.
  - **egress proxy 로 허용 목록을 켠다.** 기본 목록이 AI provider 뿐이라 `pip`, `npm`, 일반 웹 조회가 모두 403 이다. 목록을 늘리면 요청 하나에 수십 ms 가 더해졌다. proxy 는 환경 변수를 따르는 프로그램만 거르고 소켓을 직접 여는 프로그램은 통과시킨다. Hermes 는 proxy 가 Docker 호스트에 있다고 가정해 우리 구성에서는 연결도 되지 않았다. 사용자 결정대로 열어 두고 기록한다.
  - **경로와 메서드만 거르는 socket proxy.** 실행 공간은 동작하지만 `--privileged -v /:/host` 컨테이너 생성이 통과했다. Hermes 프로세스나 커넥터 MCP 서버가 뚫리면 호스트를 얻는다.
  - **설정이 없으면 지금처럼 로컬 셸로 둔다.** 운영 반영 전에도 셸을 켤 수 있다. 그러나 화면이 격리를 약속하는데 실제로는 격리되지 않는 상태가 생긴다. 거절하고 운영 반영을 먼저 한다.

- **결과**:
  - 얻는 것:
    - 셸, 파일 도구, `execute_code` 가 다른 사용자의 파일, 다른 profile 의 `.env`, 커넥터 값 파일과 코드, Hermes 설정에 닿지 않는다.
    - ADR-083(커넥터를 한 번 연결하고 에이전트에 붙인다) 의 「감당할 것」 가운데 둘이 바뀐다. 셸과 파일 도구가 커넥터 비밀에 닿는다는 항목은 실행 공간이 적용된 profile 에서 사라진다. 그 profile 의 `.env` 와 보관 파일은 컨테이너에 없다. 승인 우회 항목도 줄어든다. 셸이 `.env` 의 토큰을 읽거나, 대응 파일을 고치거나, `fos-ctx` 설정을 끄는 길이 없어진다. 커넥터 MCP 서버는 컨테이너 밖에서 돈다.
    - [ADR-082](ADR-082-먼저-살펴보기의-쓰기-도구는-관리자가-에이전트마다-켜고-커넥터-쓰기는-승인-카드로-보낸다.md) 의 위험이 그 사용자의 실행 공간 안으로 줄어든다. 웹 결과의 글이 셸 명령으로 이어져도 닿는 것은 그 사용자의 `/workspace` 와 인터넷이다.
  - 감당할 것:
    - **사진 첨부는 모든 사용자의 것이 보인다.** 첨부 저장소는 공통 루트 아래 대화 번호 폴더라 디스크에 사용자 구분이 없다. 사진을 읽게 하려고 첨부 루트 전체를 읽기 전용으로 붙인다. 쓰기는 막힌다. 첨부를 사용자별 폴더로 옮기고 그 폴더만 붙이는 일을 후속 이슈로 둔다. 우선순위가 높다.
    - **밖으로 나가는 요청은 막지 않는다.** 셸이 사용자 `/workspace` 의 파일을 인터넷으로 보낼 수 있다. 실행 기록의 명령 원문과 운영의 연결 기록으로 사후에 본다.
    - 같은 Hermes 프로세스에서 도는 도구는 실행 공간 밖이다. 남는 구멍은 [`hermes/sandbox.md`](../hermes/sandbox.md) 의 「같은 프로세스에 남는 구멍」 이 갖는다. 커넥터를 에이전트에 붙일 때 설치하는 커넥터 스킬도 올린 스킬과 같은 앞머리 규칙을 따라야 한다.
    - 컨테이너는 한 번 뜨면 운영이 멈출 때까지 떠 있고, 도구 시간 초과가 컨테이너 안의 프로세스를 끝내지 않는다. 유휴 컨테이너를 멈추는 일은 운영이 한다. 멈춘 컨테이너는 다음 호출에 Hermes 가 다시 띄운다.
    - 커리어 실행기처럼 운영 비밀 파일을 읽는 스크립트는 그 profile 에 읽기 전용으로 붙여야 돈다. 그 비밀은 그 사용자의 실행 공간에서 셸로 읽힌다. 커넥터(MCP)로 옮기면 컨테이너 밖으로 뺄 수 있다.
    - 운영 반영이 이 변경의 배포보다 먼저다. 반영 전에 배포하면 셸 계열 도구를 켜는 저장이 모두 거절된다. 이미 켜진 profile 은 다음 저장이나 운영의 일괄 반영 전까지 로컬 셸로 남는다.
    - Hermes 를 올릴 때 terminal backend 계약을 다시 본다. 확인 항목은 [`hermes/upgrades.md`](../hermes/upgrades.md) 가 갖는다.

- **적용 범위**: 대시보드 plugin 의 `PUT /api/config`, `HermesToolsetClient`, `HermesSkillClient`, `AgentToolService`, `SkillPublisher`, `Agent` 의 실행 공간 주인 키, 올린 스킬 저장 검사, 에이전트 도구 화면의 확인 문구. 흐름은 [`backend/agent.md`](../backend/agent.md) 의 「에이전트 도구를 고를 때」 가 갖는다.
