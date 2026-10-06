## ADR-086: 셸과 파일 도구는 사용자별 docker 실행 공간에서만 돈다

- **status**: `accepted`
- **결정**: 운영자가 신뢰한 정책(`FOS_ASSISTANT_SANDBOX`)의 `profiles` 에 등록한 profile 만 Hermes 의 docker terminal backend 로 보낸다.
  대시보드 plugin 이 `terminal`, `file`, `code_execution` 가운데 하나라도 켜는 도구 저장을 받을 때 등록된 profile 의 `terminal:` 설정을 통째로 다시 쓴다.
  정책에 없는 profile 은 도구 저장을 허용하고 local 로 둔다. 이미 docker 였다면 다음 셸 저장에서 docker 설정을 지우고 local 로 돌아간다.
  정책 자체가 없거나 읽지 못하면 모든 profile 의 셸 도구 저장을 거절한다. 정책에 등록됐지만 올바른 실행 공간 설정이 없는 profile 도 거절한다.

  | 칸 | 값 | 까닭 |
  | --- | --- | --- |
  | 컨테이너 | profile 마다 하나. profile, 주인, 설정 지문으로 만든 키로 찾는다. 오래 간다(`container_persistent: true`) | 스킬 마운트가 profile 마다 정확하다. 여러 profile 이 한 키를 함께 쓰면 첫 profile 의 마운트만 붙는다. 주인이나 실행 공간 설정이 바뀌면 옛 컨테이너를 다시 쓰지 않는다 |
  | `/workspace` | 실행 공간 루트 아래 **사용자 디렉터리**를 붙인다 | 한 사용자의 에이전트끼리는 파일을 함께 쓰고, 다른 사용자의 파일은 보이지 않는다 |
  | `/root` | profile 마다 따로(Hermes 기본) | 셸 이력과 사용자 패키지가 profile 사이에 섞이지 않는다 |
  | 망 | 운영이 정한 전용 망. profile 별로 바꿀 수 있다. 인터넷은 열어 둔다 | 내부 서비스 연결은 profile 정책에서만 허용한다. 밖으로 나가는 요청은 막지 않고 기록한다 |
  | 자원 | CPU 와 메모리 상한, pids 256 | 한 사람의 셸이 다른 실행을 굶기지 않는다 |
  | 환경 값 | `docker_forward_env`, `env_passthrough`, `credential_files` 는 비운다. `docker_env` 는 profile 정책의 허용된 경로와 URL 만 쓴다 | profile `.env` 전체를 전달하지 않는다. 비밀값 자체를 env 로 넣지 않는다 |
  | 더 붙이는 경로 | 운영이 정한 읽기 전용 경로만. 모든 실행 공간에 붙일 것과 profile 하나에만 붙일 것을 나눈다 | 사진 첨부와 커리어 실행기처럼 꼭 필요한 것만 연다 |

  실행 공간 루트, 이미지, 망 이름, 자원 값, 더 붙일 경로는 운영 값이다. 대시보드 프로세스의 환경 변수 `FOS_ASSISTANT_SANDBOX` 로 받는다.
  **정책이 없거나 틀리면 셋 가운데 하나라도 켜는 도구 저장을 409 로 거절한다.** 유효한 정책에서 등록하지 않은 profile 만 local 을 유지한다.
  사용자 디렉터리 이름은 Control Plane 이 도구 저장 요청의 `sandbox_owner` 로 준다. 에이전트 주인이 있으면 `u<사용자 번호>`, 없으면 `a<에이전트 번호>` 다.
  정책의 `profiles[profile]` 은 읽기 전용 마운트, env 와 망을 정한다. 일반 API 요청은 이 세 칸을 지정하지 못한다.
  env 는 `CAREER_BACKEND_URL` 과 실행기에 필요한 경로 다섯 개만 받는다. 이름과 검증 계약은 [`hermes/README.md`](../../hermes/README.md)의 「셸 실행 공간」이 갖는다.
  URL 에 인증정보를 넣거나 비밀값 자체를 env 로 넘기지 않는다. token 파일을 읽게 할 때는 그 profile 에만 읽기 전용으로 붙인다.

  커리어 실행기는 Backend 에 직접 연결한다. 해당 실행 공간에만 Backend 가 있는 망을 선택하고 필요한 코드와 근거, token 파일만 읽기 전용으로 붙인다.
  작업본은 `CAREER_WORKSPACE_ROOT=/workspace/career` 에 쓰고 study 실행기의 임시 자료는 `/tmp` 에 둔다.
  읽기 전용 checkout 의 근거 검사는 `--no-fetch` 로 돌린다. 바깥에서 원본을 갱신하는 일과 실제 마운트 범위는 운영 저장소가 갖는다.
  커리어 실행 공간을 먼저 확인하고 다른 profile 로 확대한다. 블로그의 CDP 중계와 사진 경로는 후속 설계가 끝날 때까지 기존 실행을 유지한다.
  **기존 Hermes 예약 작업은 기본 profile(local)에 남으며 아직 격리되지 않았다.** named profile 의 변경은 기본 profile 설정과 예약 작업을 바꾸지 않는다.
  Control Plane 예약 작업과 매일 깨우기는 각 에이전트의 profile 을 쓰는 별도 기능이다.

  Control Plane 은 올린 스킬의 앞머리에 `required_environment_variables`, `required_credential_files`, `setup.collect_secrets`, `prerequisites.env_vars` 가 있으면 저장을 거절한다.
  Hermes 는 스킬을 읽을 때 이 칸의 이름으로 profile 의 값과 파일을 실행 공간에 넣는다. 실측에서 Control Plane MCP 토큰과 커넥터 값 파일이 그렇게 들어왔다.
  이 검사가 생기기 전에 올린 스킬도 막으려고, 스킬 저장은 새 버전에 함께 실리는 기존 스킬에 이 칸이 있으면 거절하고, 도구 저장은 그런 스킬이 올라간 에이전트에서 셸과 파일 도구를 켜거나 켠 채 두는 것을 `AGENT_SKILL_REQUESTS_SECRETS` 로 거절한다.

  Docker 는 컨테이너 생성 요청 본문을 검사하는 socket proxy 로만 다룬다. proxy 는 운영 저장소가 소유한다.
  egress proxy(Hermes 의 iron-proxy)는 지금 켜지 않는다. 동작 계약과 측정은 [`hermes/sandbox.md`](../hermes/sandbox.md) 가 갖는다.

  `terminal`, `file`, `code_execution` 가운데 하나라도 켜진 에이전트의 주인 변경은 거절한다. 실행 공간 디렉터리가 옛 주인의 것으로 남기 때문이다.

- **맥락**: 모든 profile 이 Hermes 컨테이너 하나에서 돈다. 셸과 파일 도구를 켠 에이전트는 다른 profile 의 `.env`, 커넥터 값 파일, 커넥터 코드, Hermes 설정을 읽고 고칠 수 있었다([`hermes/tools-and-skills.md`](../hermes/tools-and-skills.md)).
  사용자는 터미널, 파일, 코드 실행을 최대한 열어 쓰되 사용자마다 격리된 파일 공간에서 읽고 쓰게 하기로 했다(2026-10-05).
  기존 실행기의 환경 값과 내부 서비스 연결이 필요하므로, 2026-10-06 에 profile 마다 켜고 커리어 실행기부터 적용하기로 했다.
  밖으로 나가는 네트워크는 열어 두고 기록만 하며, 허용 목록 방식은 실측 비용을 본 뒤 다시 정하기로 했다.
  운영과 같은 이미지에서 docker backend 를 켜자 셸, 파일 도구, `execute_code` 가 모두 컨테이너 안에서 돌았고 합성 비밀에 하나도 닿지 못했다.

- **대안 기각**:
  - **SSH backend 로 미리 띄운 컨테이너에 붙인다.** Docker 접근 없이 된다. 그러나 profile 마다 sshd 와 key 를 운영해야 하고, 원격 홈으로 스킬과 media cache 를 동기화하는 경로를 따로 검증해야 한다. docker backend 는 Hermes 가 마운트를 읽기 전용으로 붙이고 컨테이너를 label 로 관리한다.
  - **`docker_shared_container_key` 로 사용자마다 컨테이너 하나를 둔다.** 컨테이너 수가 가장 적다. 그러나 여러 profile 이 한 키를 함께 쓰면 첫 profile 의 스킬과 `terminal.*` 만 그 컨테이너에 붙어, 같은 사용자의 다른 에이전트가 자기 스킬 스크립트를 셸에서 찾지 못한다.
  - **profile 마다 `/workspace` 를 따로 둔다.** Control Plane 이 사용자 키를 보낼 필요가 없다. 사용자는 사용자 단위 공간을 골랐다.
  - **egress proxy 로 허용 목록을 켠다.** 기본 목록이 AI provider 뿐이라 `pip`, `npm`, 일반 웹 조회가 모두 403 이다. 목록을 늘리면 요청 하나에 수십 ms 가 더해졌다. proxy 는 환경 변수를 따르는 프로그램만 거르고 소켓을 직접 여는 프로그램은 통과시킨다. Hermes 는 proxy 가 Docker 호스트에 있다고 가정해 우리 구성에서는 연결도 되지 않았다. 사용자 결정대로 열어 두고 기록한다.
  - **경로와 메서드만 거르는 socket proxy.** 실행 공간은 동작하지만 `--privileged -v /:/host` 컨테이너 생성이 통과했다. Hermes 프로세스나 커넥터 MCP 서버가 뚫리면 호스트를 얻는다.
  - **정책이 없으면 지금처럼 로컬 셸로 둔다.** 누락된 정책을 정상 선택으로 오해할 수 있다. 거절한다. 명시적으로 유효한 정책에 등록하지 않은 profile 의 local 실행만 허용한다.
  - **모든 profile 을 한 번에 격리한다.** 기존 실행기의 경로와 서비스 연결이 먼저 바뀌어야 한다. profile 별 적용과 확인을 거쳐 확대한다.

- **결과**:
  - 얻는 것:
    - 격리한 profile 의 셸, 파일 도구, `execute_code` 가 다른 사용자의 파일, 다른 profile 의 `.env`, 커넥터 값 파일과 코드, Hermes 설정에 닿지 않는다. 운영자가 읽기 전용으로 허용한 경로는 예외다.
    - [ADR-083](ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md) 의 「감당할 것」 가운데 둘이 바뀐다. 셸과 파일 도구가 커넥터 비밀에 닿는다는 항목은 실행 공간이 적용된 profile 에서 사라진다. 그 profile 의 `.env` 와 보관 파일은 컨테이너에 없다. 승인 우회 항목도 줄어든다. 셸이 `.env` 의 토큰을 읽거나, 대응 파일을 고치거나, `fos-ctx` 설정을 끄는 길이 없어진다. 커넥터 MCP 서버는 컨테이너 밖에서 돈다.
    - [ADR-082](ADR-082-먼저-살펴보기의-쓰기-도구는-관리자가-에이전트마다-켜고-커넥터-쓰기는-승인-카드로-보낸다.md) 의 위험이 격리한 profile 의 실행 공간 안으로 줄어든다. 웹 결과가 셸 명령으로 이어질 때 닿는 것은 그 사용자의 `/workspace`, 인터넷과 운영 정책이 허용한 경로와 서비스다.
  - 감당할 것:
    - **사진 첨부는 모든 사용자의 것이 보인다.** 첨부 저장소는 공통 루트 아래 대화 번호 폴더라 디스크에 사용자 구분이 없다. 사진을 읽게 하려고 첨부 루트 전체를 읽기 전용으로 붙인다. 쓰기는 막힌다. 첨부를 사용자별 폴더로 옮기고 그 폴더만 붙이는 일을 후속 이슈로 둔다. 우선순위가 높다.
    - **밖으로 나가는 요청은 막지 않는다.** 셸이 사용자 `/workspace` 의 파일을 인터넷으로 보낼 수 있다. 실행 기록의 명령 원문과 운영의 연결 기록으로 사후에 본다.
    - 같은 Hermes 프로세스에서 도는 도구는 실행 공간 밖이다. 남는 구멍은 [`hermes/sandbox.md`](../hermes/sandbox.md) 의 「같은 프로세스에 남는 구멍」 이 갖는다. 커넥터를 에이전트에 붙일 때 설치하는 커넥터 스킬은 대시보드 plugin 의 manifest 검증이 같은 앞머리 칸을 거절한다.
    - 컨테이너는 한 번 뜨면 운영이 멈출 때까지 떠 있고, 도구 시간 초과가 컨테이너 안의 프로세스를 끝내지 않는다. 유휴 컨테이너를 멈추는 일은 운영이 한다. 멈춘 컨테이너는 다음 호출에 Hermes 가 다시 띄운다.
    - 커리어 실행기처럼 운영 비밀 파일을 읽는 스크립트는 그 profile 에 읽기 전용으로 붙여야 돈다. 그 비밀은 그 사용자의 실행 공간에서 셸로 읽힌다. 커넥터(MCP)로 옮기면 컨테이너 밖으로 뺄 수 있다.
    - **정책에 등록되지 않은 profile 과 기존 Hermes 예약 작업은 아직 격리되지 않았다.** 셸이 서버의 다른 파일과 설정에 닿을 수 있다. 화면은 그 profile 에 격리가 적용됐는지 모르므로 두 경우를 함께 알린다.
    - **커리어 셸은 허용한 token 파일을 읽고 Backend 의 모든 API 를 부를 수 있다.** 읽기 전용 마운트는 token 유출과 API 쓰기를 막지 않는다. 해당 망에서 닿는 서비스도 셸에서 접근할 수 있다. 사용자는 실행기 호환성을 위해 직접 연결을 선택했다.
    - 운영 정책 반영이 배포보다 먼저다. 정책이 없으면 셸 저장을 거절한다. 이미 켜진 profile 은 다음 저장이나 운영의 일괄 반영 전까지 이전 backend 를 쓴다. 정책이 유효해도 미등록 profile 은 local 을 쓴다.
    - [ADR-085](ADR-085-매일-깨우기는-예약-작업을-다시-쓰고-다섯-칸-보고를-지금-화면에-올린다.md)의 매일 깨우기 제한은 이 변경만 배포해서 풀리지 않는다. 설정은 전역이므로 일부 profile 만 격리한 상태에서는 기본값 `false` 를 유지한다. 대상 profile 전체의 실행 공간 설정 반영과 기존 스킬 점검, 실제 사용자별 격리 실행 확인 뒤에만 켠다. 확인과 설정 변경 절차는 `fos-home-infra` 가 갖는다.
    - 키가 바뀌면 이전 키의 컨테이너와 `/root` 디렉터리가 남는다. 정리는 운영이 한다.
    - 셸 도구가 이미 켜진 profile 에 남은 옛 스킬만 운영이 점검한다. 두 검사는 새 저장에서만 돌아, 그 스킬은 고치거나 지울 때까지 계속 값을 실행 공간에 넣는다.
    - Hermes 를 올릴 때 terminal backend 계약을 다시 본다. 확인 항목은 [`hermes/upgrades.md`](../hermes/upgrades.md) 가 갖는다.

- **적용 범위**: 대시보드 plugin 의 `PUT /api/config`, `HermesToolsetClient`, `HermesSkillClient`, `AgentToolService`, `AgentAdminService` 와 `AgentLifecycleService` 의 주인 변경 검사, `SkillPublisher`, `Agent` 의 실행 공간 주인 키, 올린 스킬 저장 검사, 에이전트 도구 화면의 확인 문구. 흐름은 [`backend/agent.md`](../backend/agent.md) 의 「에이전트 도구를 고를 때」 가 갖는다.
