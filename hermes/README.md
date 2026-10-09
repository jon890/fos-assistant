# Hermes 쪽 코드

Hermes 에 설치하는 plugin 과 profile 틀, 범용 커넥터다. 이 파일은 설치 묶음과 운영 값, 검사를 갖는다.
모듈 구조는 [`hermes/docs/code-architecture.md`](docs/code-architecture.md), Hermes 의 동작은 [`hermes/docs/hermes-contract.md`](docs/hermes-contract.md) 가 갖는다.

## 설치 묶음

```bash
# cwd: 저장소 root
hermes/bundle.sh --out <디렉터리> --mcp-url <Control Plane MCP 주소>
```

`--out` 은 없거나 비어 있는 디렉터리여야 하고, `--mcp-url` 은 `http://` 나 `https://` 로 시작해야 한다.
묶음은 Hermes 의 `plugins/dashboard-profile-api/` 자리에 그대로 들어갈 모양이다.
plugin 디렉터리의 모든 `*.py` 와 `plugin.yaml`, 주소를 채운 `default-config.yaml.template`, 틀의 `plugins.enabled` 가 켜는 profile plugin 을 담은 `profile-plugins/<이름>/` 이다.
틀의 MCP 주소 자리는 `__FOS_ASSISTANT_MCP_URL__` 이다. 주소를 주지 않거나 자리가 남으면 묶음을 만들지 않고 실패한다.
묶음을 Hermes 에 넣고 대시보드를 다시 띄우는 것은 운영 저장소가 한다.
디렉터리 배치와 배포 순서는 [`hermes/docs/code-architecture.md`](docs/code-architecture.md) 의 「디렉터리 배치와 배포 순서」 가 갖는다.

## 운영 값

운영 값은 코드에 두지 않고 설치할 때 받는다.

| 값 | 받는 곳 | 모양과 없을 때의 동작 |
| --- | --- | --- |
| Control Plane MCP 주소 | `bundle.sh --mcp-url` | 묶음을 만들 때 틀에 채운다 |
| 커넥터 목록 | 대시보드 프로세스의 환경 변수 `FOS_ASSISTANT_CONNECTOR_ROOTS` | 값의 모양은 [`hermes/plugins/dashboard-profile-api/README.md`](plugins/dashboard-profile-api/README.md) 의 「커넥터」 에 있다. 이 목록에 있고 `connector.json` 검증을 통과한 것만 카탈로그에 나온다. 비었거나 읽지 못하면 커넥터가 하나도 없는 것으로 본다 |
| 커넥터 실행 파일 기본값 | 대시보드 프로세스의 환경 변수 `FOS_ASSISTANT_CONNECTOR_COMMAND` | 절대 경로다. 목록 항목에 `command` 가 없을 때 쓴다 |
| 대시보드 서비스 토큰 | 환경 변수 `HERMES_DASHBOARD_PROFILE_API_SECRET` | |
| 스킬 루트 | 대시보드 프로세스의 환경 변수 `FOS_ASSISTANT_SKILL_AGENT_ROOT` | |
| 셸 실행 공간 | 대시보드 프로세스의 환경 변수 `FOS_ASSISTANT_SANDBOX` | 값의 모양은 아래 「셸 실행 공간」 에 있다. 없거나 읽지 못하면 `terminal`, `file`, `code_execution`, `vision`, `image_gen`, `video_gen` 을 켜는 도구 저장을 409 로 거절한다 |
| 커넥터 정책을 물을 주소 | gateway 프로세스의 환경 변수 `FOS_CTX_POLICY_URL` | Control Plane 의 `/internal/hermes/connector-policy` 다. 없으면 `fos-ctx` 가 커넥터를 설치한 profile 의 커넥터 도구를 모두 막는다 |
| 자식 session 을 등록할 주소 | gateway 프로세스의 환경 변수 `FOS_CTX_SUBAGENT_URL` | 없으면 등록하지 않는다. 등록이 없는 자식의 호출은 Control Plane 이 거절한다 |

### 셸 실행 공간

`FOS_ASSISTANT_SANDBOX` 는 JSON object 다. 결정은 [ADR-086](../docs/adr/ADR-086-셸과-파일-도구는-사용자별-docker-실행-공간에서만-돈다.md), Hermes 의 동작은 [실행 공간](docs/hermes-contract.md) 이 갖는다.

| 키 | 필수 | 모양 |
| --- | --- | --- |
| `image` | 예 | 실행 공간 이미지. `execute_code` 를 쓰려면 python3 가 있어야 한다 |
| `workspace_root` | 예 | 사용자 디렉터리를 둘 절대 경로. **Docker 호스트와 Hermes 컨테이너에서 같은 경로**여야 한다. 사용자 디렉터리 `<workspace_root>/<sandbox_owner>` 가 `/workspace` 에 붙는다 |
| `attachment_root` | 예 | Docker 호스트에서 첨부를 사용자별로 둔 절대 경로. Hermes에서도 그 경로를 검증할 수 있어야 한다. `<attachment_root>/users/<sha256(sandbox_owner UTF-8)>` 만 읽기 전용으로 붙는다 |
| `attachment_agent_root` | 예 | Hermes와 실행 컨테이너가 함께 보는 첨부 절대 경로. `<attachment_agent_root>/users/<sha256(sandbox_owner UTF-8)>` 가 위 원본의 실행 공간 경로다. Docker 호스트와 Hermes의 경로는 달라도 된다 |
| `connector_output_root` | 아니오 | 커넥터가 계산할 목록을 파일로 쓰는 절대 경로. **Docker 호스트와 Hermes 컨테이너에서 같은 경로**여야 한다. `<connector_output_root>/users/<sha256(sandbox_owner UTF-8)>/<profile>` 이 같은 경로에 읽기 전용으로 붙는다. 없으면 붙이지 않고 커넥터의 `owner_output_env` 는 빈 값이다([ADR-20261008 / connector-output-files](docs/adr/ADR-20261008-connector-output-files.md)) |
| `network` | 아니오 | 실행 공간을 붙일 Docker 망 이름. 없으면 Docker 기본 망이다 |
| `cpu` | 아니오 | 0 보다 크고 8 이하. 기본 1 |
| `memory_mb` | 아니오 | 256 이상 16384 이하의 정수. 기본 1024 |
| `read_only_mounts` | 아니오 | 모든 실행 공간에 붙일 `<원본 절대 경로>:<컨테이너 절대 경로>` 목록. 읽기 전용으로만 붙는다 |
| `profiles` | 예 | 격리할 named profile 이름을 키로 둔 객체. 아래 표의 설정을 값으로 받는다. 빈 객체면 모두 local 로 둔다. `default` 는 등록할 수 없다 |

`profiles[profile]` 은 다음 세 칸만 받는다.

| 키 | 필수 | 모양 |
| --- | --- | --- |
| `read_only_mounts` | 아니오 | 이 profile 에만 붙일 읽기 전용 마운트 목록. 공통 마운트와 같은 형식이다 |
| `env` | 아니오 | 아래 허용 목록의 비밀이 아닌 경로와 URL 만 받는 객체. 기본은 빈 객체다 |
| `network` | 아니오 | 이 profile 에만 적용할 Docker 망 이름. 생략하면 공통 `network` 를 쓴다. `null` 이면 Docker 기본 망이다 |

`env` 에서 `CAREER_BACKEND_URL` 은 HTTP(S) URL 이며 사용자 이름, 비밀번호, query 와 fragment 를 받지 않는다.
`CAREER_BACKEND_TOKEN_FILE`, `CLAUDE_PLUGIN_ROOT`, `CAREER_EVIDENCE_DIR`, `CAREER_WORKSPACE_ROOT`, `CAREER_DART_API_KEY_FILE` 은 절대 경로만 받는다.
token 과 API key 값 자체는 받지 않는다. 파일을 읽게 할 경우 같은 profile 의 읽기 전용 마운트를 운영 정책에 지정한다.
일반 API 요청은 env, 망, 마운트와 profile 정책을 쓰지 못한다.

경로에 `.`, `..`, 빈 조각, 제어 문자, `:` 둘 이상, `/workspace` 나 `/root` 아래의 마운트 대상이 있으면 정책 전체를 읽지 못한 것으로 본다.
`attachment_root` 는 `workspace_root` 와 같거나 그 아래이거나 그 상위일 수 없다.
`attachment_agent_root` 는 `/workspace` 나 `/root` 와 겹칠 수 없다.
`connector_output_root` 는 `workspace_root`, `attachment_root`, `attachment_agent_root`, `/workspace`, `/root` 와 겹칠 수 없다.
공통 또는 profile 별 마운트 원본이 `workspace_root` 또는 `attachment_root` 와 같거나 그 아래이거나 그 상위이면 정책 전체를 읽지 못한 것으로 본다.
마운트 대상이 `attachment_agent_root` 와 같거나 그 아래이거나 그 상위여도 정책 전체를 읽지 못한 것으로 본다.
마운트 원본이나 대상이 `connector_output_root` 와 겹쳐도 같다. 다른 profile 의 출력이 보이기 때문이다.
`connector_output_root` 를 넣거나 빼면 등록된 profile 의 `terminal:` 이 바뀌어 다음 셸 저장에서 컨테이너 키가 바뀐다. 그 저장 전까지는 출력 디렉터리가 붙지 않는다.
셸 저장과 사진 도구 옛 설치는 그 profile 의 출력 디렉터리를 링크 없이 만들지 못하면 409 로 거절한다. 바인딩 설치는 같은 실패를 빈 값으로 넘긴다.
이 검사는 경로 조각 기준으로 한다. 다른 사용자의 workspace나 첨부를 공통 마운트로 보이게 하면 안 되기 때문이다.
표에 없는 최상위 키나 profile 설정 키, 허용 목록에 없는 env 가 있어도 정책 전체를 거절한다.
기존 `profile_mounts` 는 `profiles[profile].read_only_mounts` 로 옮겨야 한다.

첨부 source와 target의 경로는 Hermes에서 모두 검증할 수 있어야 한다. source 루트가 없거나 접근할 수 없으면 409다.
**사용자 디렉터리는 Control Plane 이 이 요청 전에 만든다. plugin 은 만들지 않는다.** Hermes 는 첨부 루트를 읽기 전용으로 볼 수 있다.
양쪽 루트에 `users/<sha256(sandbox_owner UTF-8)>` 가 없으면 409다. 두 루트는 Control Plane 의 `assistant.attachment.root` 와 같은 디렉터리여야 한다.
중간 경로를 포함한 링크를 따라가지 않고 디렉터리를 열며, 실제 경로가 허용 루트 아래의 그 사용자 디렉터리인지 확인한다.
처음 확인한 뒤 mount 문자열 생성 직전, 설정 저장 직전에 경로와 디렉터리 식별자를 다시 대조한다.
검증이 실패하거나 검사 뒤 디렉터리가 바뀌면 409로 중단하고 기존 terminal 설정을 보존한다. 사진 커넥터 설치에도 같은 검사를 적용한다.
API 완료 뒤 Docker 생성까지의 변경은 첨부 루트를 Hermes 에 읽기 전용으로 붙여 막는다. 실제 생성 시점의 검증은 Docker socket proxy가 맡는다.

정책이 유효하고 profile 이 등록돼 있으면 plugin 은 아래 `terminal:` 전체를 쓴다.
등록되지 않은 profile 은 셸 저장을 허용하고 `backend: local` 을 명시한다. 기존 local 옵션은 유지한다.
요청 본문의 `require_sandbox` 가 참이면 local 로 두지 않고 409 `sandbox_unavailable` 로 거절한다. Control Plane 의 기본 도구 적용이 이 값을 보낸다.
이미 docker 였다가 정책에서 빠지면 다음 셸 저장에서 docker 설정을 지우고 local 로 돌아간다.
정책 자체가 없거나 잘못됐으면 등록 여부와 관계없이 셸 저장을 거절한다.

```yaml
terminal:
  backend: docker
  cwd: /workspace
  docker_image: <image>
  container_persistent: true
  docker_persist_across_processes: true
  docker_orphan_reaper: true
  docker_mount_cwd_to_workspace: false
  docker_run_as_host_user: false
  docker_network: true
  # network 가 없으면 망 인자만 뺀다. label 은 정책에 등록된 profile 이름으로 만든다.
  docker_extra_args: ["--network=<profile 의 network>", "--label=fos-sandbox-profile=<profile>"]
  docker_volumes:
    - <workspace_root>/<sandbox_owner>:/workspace
    - <attachment_root>/users/<sha256(sandbox_owner UTF-8)>:<attachment_agent_root>/users/<sha256(sandbox_owner UTF-8)>:ro
    # connector_output_root 가 있을 때만. plugin 이 저장 전에 그 디렉터리를 링크 없이 만든다
    - <connector_output_root>/users/<sha256(sandbox_owner UTF-8)>/<profile>:<같은 경로>:ro
    - <read_only_mounts 의 각 항목>:ro
    - <profiles[profile].read_only_mounts 의 각 항목>:ro
  docker_forward_env: []
  docker_env: <profiles[profile].env>   # env 가 비면 이 칸을 두지 않는다
  env_passthrough: []
  credential_files: []
  container_cpu: <cpu>
  container_memory: <memory_mb>
  docker_shared_container_key: <profile>-<sandbox_owner>-<지문>
```

`docker_shared_container_key` 의 지문은 이 칸을 뺀 나머지 `terminal:` 을 키 정렬 JSON 으로 만든 sha256 앞 12자다. 주인이나 실행 공간 설정이 바뀌면 키가 바뀌어 Hermes 가 새 컨테이너를 만든다. 한 키는 profile 하나만 쓴다.
`profiles[profile].env` 가 비면 `docker_env` 칸을 넣지 않고 지문을 계산한다. Hermes 의 config 저장이 빈 dict 를 기본값과 같다며 지우므로, 칸을 넣고 계산하면 저장된 `terminal:` 로 다시 계산한 지문이 키와 어긋난다.
`fos-sandbox-profile` label 은 proxy 검사와 유휴 정리, 복구에서 정책의 profile 을 식별한다. API 요청으로 label 을 지정하지 못한다.

docker `terminal:` 을 쓰는 같은 설정 쓰기에서 `approvals.unattended_mode: approve` 도 쓴다. `approvals` 의 다른 키는 그대로 둔다.
`backend: local` 을 쓸 때는 `approvals.unattended_mode` 를 지운다. 남은 `approvals` 가 비면 블록째 지운다.
그래서 API 경로의 `execute_code` 는 docker 실행 공간에서만 승인 없이 돈다. 셸 위험 명령과 커넥터 승인은 그대로 승인 카드로 간다.
판정 경로는 [실행 공간](docs/hermes-contract.md) 의 「`execute_code` 의 승인 판정」, 결정은 [ADR-20261008 / execute-code-unattended](docs/adr/ADR-20261008-execute-code-unattended.md) 가 갖는다.

**기존 Hermes 예약 작업은 기본 profile(local)에 남으며 아직 격리되지 않았다.**
named profile 의 도구 저장은 기본 profile 설정과 예약 작업을 바꾸지 않는다.
Control Plane 의 예약 작업과 매일 깨우기는 별도 기능이며 각각의 에이전트 profile 을 쓴다.
적용과 확인, profile 별 실제 값은 `fos-home-infra` 가 갖는다.

## 검사

```bash
# cwd: 저장소 root
python3 -m unittest discover -s hermes/tests
```

Hermes 모듈은 가짜로 끼우므로 Hermes 를 설치하지 않아도 돈다. 실제 Hermes 와 맞는지는 운영 저장소의 live 검사가 본다.

Python 3.13 과 PyYAML 과 `mcp` SDK, 커넥터를 실행할 Bun 이 있어야 한다. Hermes 이미지와 같은 버전이다.
검사는 `mcp==2.0.0` 으로 돌고 plugin 의 지원 범위는 `mcp>=2.0,<3` 이다. SDK 계약은 [커넥터 설치](../docs/backend/connector-install.md) 의 「MCP SDK 계약」 이 갖는다.
커넥터 도구 호출 검사는 `tests/fixtures/demo-connector/` 의 시험 커넥터를 자식 프로세스로 띄운다.
`tests/test_connectors_contract.py` 는 `connectors/` 아래 커넥터를 모두 찾아 계약을 본다. 커넥터 전용 시험은 각 커넥터의 `tests/` 에 있다.
타입 검사와 전용 시험, 묶음 파일 비교는 저장소 루트의 `bash scripts/check-connectors.sh` 로 돌린다.

`dashboard-profile-api` 가 기대는 Hermes 내부 지점은 `tests/hermes_contract.py` 가 한곳에 선언한다([ADR-088](docs/adr/ADR-088-대시보드-plugin-은-감싸는-경로의-바꿔-끼우기를-두고-기대는-hermes-내부-지점을-계약-시험으로-확인한다.md)).
이 절 첫 명령의 시험은 plugin 이 쓰는 Hermes 이름이 모두 선언에 있는지만 본다.
선언한 지점이 실제 Hermes 소스에 그대로인지는 아래 명령이 본다. CI 의 hermes job 이 계약의 판으로 돌린다.
상류 저장소에서 tag 하나를 받으므로 네트워크가 필요하다. 계약의 판이면 tag 가 `HERMES_COMMIT` 을 가리키는지도 본다.

```bash
# cwd: 저장소 root
# 인자가 없으면 hermes_contract.py 의 HERMES_VERSION tag 를 상류에서 받는다
scripts/check-hermes-contract.sh
# 올릴 판의 tag 나, 이미 받아 둔 Hermes 소스 디렉터리를 준다
scripts/check-hermes-contract.sh <tag 또는 디렉터리>
```

소스는 import 하지 않고 구문만 읽으므로 Hermes 의 의존성이 필요 없다. 실패한 항목이 Hermes 를 올리면 깨질 곳이다.
