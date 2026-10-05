# 실행 공간(terminal backend)

2026-10-05 에 운영과 같은 이미지(v0.21.5, `v2026.9.24`)를 격리 환경에 띄워 측정했다.
결정은 [ADR-084](../adr/ADR-084-셸과-파일-도구는-사용자별-docker-실행-공간에서만-돈다.md) 가 갖는다.
측정은 도구를 모델 없이 직접 불렀다. gateway 가 profile 하나의 turn 을 묶는 함수(`gateway/run.py` 의 `_profile_runtime_scope`)로 profile 을 묶고 그 안에서 `registry.dispatch` 로 도구를 불렀다.
합성 비밀 파일만 썼다. 운영 Hermes 와 운영 DB 는 건드리지 않았다.

## 무엇이 실행 공간 안에서 도는가

`terminal.backend` 를 `docker` 로 두면 아래가 컨테이너 안에서 돈다.

| 도구 | 어디서 도나 | 근거 |
| --- | --- | --- |
| `terminal`, `process_manage` | 컨테이너. 명령마다 컨테이너 안에서 bash 를 띄운다 | `tools/environments/docker.py` |
| `read_file`, `write_file`, `patch`, `search_files` | 컨테이너. `_get_file_ops` 가 terminal 과 같은 환경을 받아 `ShellFileOperations` 로 읽고 쓴다 | `tools/file_tools.py` 의 `_get_file_ops` |
| `execute_code` | 컨테이너 안의 원격 커널. 스크립트가 부르는 도구는 Hermes 로 돌아와 다시 판정된다 | `tools/code_execution_tool.py` 의 `SANDBOX_ALLOWED_TOOLS` |
| `delegate_task` 의 자식 | 자식 실행은 Hermes 안에서 돌고, 자식의 셸과 파일 도구는 부모와 같은 컨테이너를 쓴다 | `tools/terminal_tool.py` 의 `_resolve_container_task_id` |
| MCP 서버(stdio) | **Hermes 프로세스의 자식.** 컨테이너 밖이다 | `tools/mcp_tool_transport.py` |

`execute_code` 의 RPC 로 다시 Hermes 에서 도는 도구는 `web_search`, `web_extract`, `read_file`, `write_file`, `search_files`, `patch`, `terminal` 이다.
웹 도구는 Hermes 쪽에서 주소 검사를 거치고, 나머지는 다시 컨테이너로 간다.

커넥터 MCP 서버가 컨테이너 밖에서 도는 것이 비밀 격리의 근거다.
커넥터 토큰은 MCP 프로세스의 환경에만 있고, 셸은 그 프로세스와 profile 파일을 보지 못한다.

## profile 마다 다른 설정을 읽는다

공유 listener 는 여러 profile 을 한 프로세스에서 돌린다.
`_profile_runtime_scope` 가 turn 마다 그 profile 의 `terminal.*` 전체를 ContextVar 에 넣는다(`tools/terminal_scope.py`).
그동안 `TERMINAL_*` 읽기는 그 값에서만 찾고 프로세스 환경을 보지 않는다.
설정 파일을 읽지 못하면 거절 scope 가 들어가 셸 실행이 실패한다. 다른 profile 의 설정으로 넘어가지 않는다.

값의 출처 순서는 기본값, profile `.env` 의 `TERMINAL_*`, profile `config.yaml` 의 `terminal:` 이다. 뒤가 이긴다.

| `terminal.*` | 뜻 | 기본값 |
| --- | --- | --- |
| `backend` | `local`, `docker`, `ssh` 등 | `local` |
| `docker_image` | 컨테이너 이미지. `execute_code` 를 쓰려면 python3 가 있어야 한다 | Hermes 기본 이미지 |
| `container_persistent` | 켜면 profile 하나에 오래 가는 컨테이너 하나 | true |
| `docker_shared_container_key` | 같은 값을 둔 profile 들이 컨테이너 하나를 함께 쓴다 | 없음 |
| `docker_volumes` | 더 붙일 `-v` 목록. `:/workspace` 를 주면 기본 workspace 대신 그것을 쓴다 | 없음 |
| `docker_network` | false 면 `--network=none` | true |
| `docker_extra_args` | `docker run` 끝에 붙는다. `--network=<이름>` 으로 망을 고른다 | 없음 |
| `container_cpu`, `container_memory` | `--cpus`, `--memory`(MB) | 1, 5120 |
| `docker_forward_env`, `docker_env` | 컨테이너에 넣을 환경 변수. 운영자가 명시한 값이라 차단 목록을 거치지 않는다 | 없음 |
| `credential_files`, `env_passthrough` | profile 파일과 환경 값을 컨테이너에 넣는 목록 | 없음 |

`cwd` 를 `/workspace` 로 두면 Hermes 가 호스트 쪽에 그 경로가 없다는 경고(`TERMINAL_CWD does not exist`)를 남기지만 컨테이너 안의 작업 디렉터리는 `/workspace` 다.
`TERMINAL_SANDBOX_DIR` 는 프로세스 환경에서 읽는다. 없으면 그 profile 의 `<HERMES_HOME>/sandboxes` 다.

## 컨테이너

### 이름과 재사용

| 칸 | 값 |
| --- | --- |
| 이름 | `hermes-<무작위 8자>` |
| label `hermes-profile` | profile 이름. 공유 키를 쓰면 `<키>-<sha256 12자>` |
| label `hermes-task-id` | `profile_<이름>` 또는 `shared_<키>` |
| label `hermes-egress` | egress proxy 설정의 지문. 바뀌면 새 컨테이너를 만든다 |

**프로세스가 다시 떠도 label 로 같은 컨테이너를 찾아 쓴다.** 멈춘 컨테이너는 `docker start` 로 다시 띄운다.
측정에서 멈춘 컨테이너가 다음 호출에 3.3초 만에 다시 떴고 workspace 파일이 남아 있었다.

**마운트를 바꿔도 같은 label 의 컨테이너를 다시 쓴다.** 프로세스 안의 캐시는 지워진 컨테이너를 옛 run 인자로 다시 만든다(`_find_reusable_container`, `_recreate_container`).
그래서 설정이 바뀌면 키를 바꿔야 새 컨테이너가 생긴다.
plugin 은 `docker_shared_container_key` 를 profile, 주인, 설정 지문으로 만들어 이 키를 바꾼다. 키가 바뀌면 label 과 캐시 키와 `/root` 디렉터리가 모두 새것이 된다.
이전 키의 컨테이너와 `/root` 디렉터리는 남는다. 정리는 운영이 한다.

### 수명

**`container_persistent` 가 켜져 있으면 Hermes 는 컨테이너를 멈추지 않는다.**
유휴 정리(`terminal.lifetime_seconds`, 기본 300초)는 프로세스 안의 연결만 놓는다(`DockerEnvironment.cleanup`).
기동 때 도는 정리기(`reap_orphan_containers`)는 **멈춘** 컨테이너 가운데 `2 × lifetime_seconds` 보다 오래된 것만 지운다.
그래서 한 번 셸을 쓴 profile 의 컨테이너는 사람이 멈출 때까지 떠 있다.

**도구 시간 초과는 컨테이너 안의 프로세스를 끝내지 않았다.**
`search_files` 로 `/` 전체를 찾게 하자 61초에 `search_timeout` 으로 답했지만 `grep` 은 컨테이너 안에서 계속 돌며 CPU 1개를 다 썼다.
`--cpus` 와 `--pids-limit 256` 이 그 상한이다.

### 보안 기본값

측정한 컨테이너의 `docker inspect` 다.

| 칸 | 값 |
| --- | --- |
| capability | `ALL` 을 빼고 `DAC_OVERRIDE`, `CHOWN`, `FOWNER`, `SETUID`, `SETGID` 만 더한다 |
| `security-opt` | `no-new-privileges` |
| `pids-limit` | 256 |
| `privileged` | false |
| 사용자 | root(컨테이너 안). `docker_run_as_host_user` 로 바꿀 수 있다 |
| tmpfs | `/tmp` 512m nosuid, `/var/tmp` 256m noexec, `/run` 64m noexec |
| init | `--init` |

### 마운트

| 컨테이너 경로 | 원본 | 쓰기 |
| --- | --- | --- |
| `/root` | `<sandbox dir>/docker/<task id>/home` | 쓴다 |
| `/workspace` | `<sandbox dir>/docker/<task id>/workspace`. `docker_volumes` 에 `:/workspace` 가 있으면 그것 | 쓴다 |
| `/root/.hermes/skills` | 그 profile 의 `skills/` | 읽기 전용 |
| `/root/.hermes/external_skills/<n>` | `skills.external_dirs` | 읽기 전용 |
| `/root/.hermes/cache/*`, `images`, `attachments` | 그 profile 의 media cache | 읽기 전용 |
| `/root/.hermes/<상대 경로>` | `terminal.credential_files` 와 스킬이 선언한 `required_credential_files` | 읽기 전용 |
| `docker_volumes` 의 나머지 | 운영자가 정한 것 | 적힌 대로 |

`/workspace` 와 `/root` 밖의 파일 시스템은 이미지의 것이고 컨테이너를 지우면 사라진다.

**마운트 원본 경로는 Docker daemon 쪽 경로로 해석된다.**
Hermes 가 컨테이너 안에서 Docker 를 부르면 `-v` 의 원본은 Hermes 컨테이너의 경로가 아니라 Docker 호스트의 경로다.
그래서 sandbox dir, `docker_volumes` 의 원본, 스킬과 media cache 경로가 **Docker 호스트와 Hermes 컨테이너에서 같은 경로**여야 한다.
경로가 다르면 빈 디렉터리가 붙거나 엉뚱한 호스트 경로가 붙는다.

### 공유 키와 사용자별 볼륨

사용자 한 명의 여러 profile 이 파일 공간을 함께 쓰는 길은 둘이다. 둘 다 측정했다.

| 방식 | 컨테이너 | 스킬 마운트 | 다른 사용자 |
| --- | --- | --- | --- |
| `docker_shared_container_key` 를 같게 둔다 | 사용자당 하나 | **컨테이너를 처음 만든 profile 의 것만** 붙는다. 다른 profile 의 스킬은 그 컨테이너에서 보이지 않는다 | 다른 키의 컨테이너라 보이지 않는다 |
| profile 마다 컨테이너, `docker_volumes` 로 사용자 디렉터리를 `/workspace` 에 붙인다 | profile 당 하나 | profile 마다 자기 것 | 다른 디렉터리라 보이지 않는다 |

공유 키는 첫 profile 의 `terminal.*` 와 마운트가 그 컨테이너의 수명 동안 이긴다.
두 번째 방식은 `/root` 가 profile 마다 따로라서 셸 이력과 설치한 사용자 패키지는 나뉘고 `/workspace` 만 함께 쓴다.

## 파일 도구의 쓰기 경로 검사

**`HERMES_WRITE_SAFE_ROOT` 는 docker 백엔드에서도 경로 문자열로 검사한다.**
이미지 기본값이 Hermes 데이터 경로 하나라서, 그대로 두면 `write_file` 이 `/workspace/...` 를 「outside HERMES_WRITE_SAFE_ROOT」 로 거절했다.
값에 `/workspace` 를 더하자 쓰기가 됐다. 이 값은 프로세스 환경이라 모든 profile 에 같이 적용된다.

`read_file` 은 `.env`, `auth.json` 같은 이름을 경로 문자열로 먼저 거절한다(`agent/file_safety.py`).
이 검사는 Hermes 스스로 「방어를 한 겹 더할 뿐 경계가 아니다」 라고 적는다. 경계는 컨테이너다.

## 측정 결과

profile 셋을 썼다. 사용자 A 의 profile 둘, 사용자 B 의 profile 하나다.
모든 profile 의 `.env`, 커넥터 값 파일, 커넥터 코드 디렉터리, 루트 설정과 OAuth 파일에 서로 다른 합성 값을 두었다.

| 시험 | 결과 |
| --- | --- |
| `terminal` 의 호스트 이름 | 사용자 A 의 두 profile 은 같은 컨테이너(공유 키), B 는 다른 컨테이너 |
| `ls`, `cat` 로 Hermes 데이터 경로 | 없는 경로 |
| 컨테이너 환경 변수의 합성 값 | 없음 |
| `read_file` 로 다른 profile 의 `.env`, 루트 `.env`, OAuth 파일 | 이름 검사로 거절 |
| `read_file` 로 커넥터 값 파일, 커넥터 코드, 루트 설정 | 「File not found」 |
| `patch` 로 Hermes 루트 설정 | 읽지 못함 |
| `search_files` 로 `/workspace`, `/root` 의 합성 값 | 0건 |
| `execute_code` 로 다른 profile 의 `.env` 열기 | `FileNotFoundError` |
| `write_file` 로 읽기 전용 스킬 마운트 | 거절 |
| 사용자 A 의 두 profile 이 쓴 `/workspace` 파일 | 서로 보인다 |
| 사용자 B 의 `/workspace` | A 의 파일이 없다 |
| 사용자별 볼륨 방식에서 같은 시험 | 위와 같다. 컨테이너는 profile 마다 하나 |
| 대시보드 plugin 이 쓰는 `terminal:` 블록을 둔 뒤 실제 `PUT /api/config` 처리기로 도구 목록을 저장 | 블록이 남는다. 값이 빈 `docker_env: {}` 만 지워지고 기본값이 같아 동작은 같다 |
| 그 블록으로 셸과 `write_file` | `/workspace` 에서 돈다. 읽기 전용으로 붙인 경로는 읽히고 쓰기는 「Read-only file system」 이다. 전용 망에 붙는다 |
| 한 프로세스에서 `docker_volumes` 의 `/workspace` 원본만 바꾸고 셸을 다시 부른다 | **옛 컨테이너와 옛 원본이 그대로 쓰였다.** 바꾸기 전 디렉터리의 파일이 보였다 |
| 같은 시험에서 `docker_shared_container_key` 도 함께 바꾼다 | 같은 프로세스에서 새 컨테이너가 생기고 새 원본만 보였다 |

## 같은 프로세스에 남는 구멍

docker 백엔드는 셸과 파일 도구만 옮긴다. 아래는 Hermes 프로세스 안에서 돈다.

| 도구나 경로 | 남는 것 | 지금 상태 |
| --- | --- | --- |
| 스킬 앞머리의 `required_environment_variables`(`setup.collect_secrets`, `prerequisites.env_vars` 포함) | 스킬을 읽으면 그 이름의 값을 profile `.env` 에서 꺼내 컨테이너 환경에 넣는다. **측정에서 Control Plane MCP 토큰과 커넥터 토큰이 컨테이너 환경에 들어왔다.** `API_SERVER_KEY` 같은 provider 차단 목록만 막힌다 | 올린 스킬은 Control Plane 이 이 칸을 거절한다 |
| 스킬 앞머리의 `required_credential_files` | profile 디렉터리 안의 파일을 컨테이너 `/root/.hermes/` 아래에 붙인다. `.env` 와 `auth.json` 은 거절하지만 **커넥터 값 파일(`.fos-connectors.json`)은 붙었다** | 위와 같다 |
| `skill_manage` | profile 의 스킬 디렉터리에 쓴다 | `fos-ctx` 의 `pre_tool_call` 이 막는다(ADR-034) |
| `skill_view` 의 `` !`cmd` `` 전처리 | Hermes 호스트에서 명령을 돌린다 | `skills.inline_shell` 기본값 false 로 꺼져 있다 |
| `browser_*` | Hermes 컨테이너의 브라우저가 돈다 | `file://` 은 막혔다(측정). toolset 은 profile 틀에서 꺼져 있다 |
| `vision_analyze`, `image_generate` | 호스트 경로는 그 profile 의 media cache 안만 읽고, 나머지는 컨테이너 안에서 읽는다 | 첨부 경로를 컨테이너에 붙여야 사진을 읽는다 |
| `text_to_speech` 의 `output_path` | 호스트 경로에 쓴다. `HERMES_WRITE_SAFE_ROOT` 가 상한이다 | |
| `MEDIA:` 태그 | OpenAI 호환 경로는 그림 파일을 data URL 로 바꿔 싣는다 | `/v1/runs` 는 이 변환을 하지 않는다 |
| 파일 도구의 존재 확인 | `os.path.exists` 같은 호출 몇 개가 호스트 경로를 본다. 내용은 읽지 않는다 | 남는다 |
| `cronjob` 의 `workdir` | 호스트 경로의 문맥 파일을 읽는다(소스로만 판정) | toolset 은 꺼져 있다 |

## 네트워크

### 닿는 곳

| 망 | 다른 컨테이너 | 인터넷 |
| --- | --- | --- |
| 기본 bridge | 같은 bridge 의 컨테이너에 닿았다 | 닿는다 |
| `docker_extra_args: ["--network=<전용 망>"]` | 다른 망의 컨테이너에 닿지 않았다 | 닿는다 |
| `docker_network: false` | 없음 | 없음 |

**전용 망을 두지 않으면 셸이 같은 bridge 의 내부 서비스에 닿는다.**
Docker 호스트와 같은 LAN 의 주소는 망 종류와 관계없이 기본 route 로 나간다. 막으려면 호스트 방화벽이 필요하다.

### 남길 수 있는 기록

| 수단 | 남는 것 | 비용 |
| --- | --- | --- |
| Control Plane 의 실행 기록 | 셸 명령 원문과 도구 결과(ADR-038 에 따라 관리자에게만) | 이미 있다 |
| 전용 망과 호스트의 연결 추적 | 출발 컨테이너 주소, 목적지 주소와 포트, 시각 | 운영 설정. 내용과 도메인은 남지 않는다 |
| 컨테이너의 DNS 를 기록하는 resolver 로 돌린다 | 질의한 도메인 | 운영 설정 |
| egress proxy(아래) | 요청마다 host, method, path, 상태, 거절 사유 | TLS 를 풀어 보므로 경로까지 남는다 |

### egress proxy(iron-proxy)를 켜면

Hermes 의 `proxy.enabled` 는 `iron-proxy` 를 Hermes 쪽에 띄우고 컨테이너에 `HTTPS_PROXY` 와 CA 를 넣는다.
**허용 목록 방식이다.** 기본 목록은 AI provider 주소 몇 개뿐이다.

| 측정 | 결과 |
| --- | --- |
| 기본 목록에서 `pypi.org`, `files.pythonhosted.org`, `registry.npmjs.org`, `example.com`, `github.com` | 모두 403(`rejected_by: allowlist`) |
| 기본 목록에서 `pip download` | 「No matching distribution」 로 실패, 약 7.7초 |
| 목록에 pypi 와 npm 을 더한 뒤 `pip download`(5회) | 성공. 첫 회 647ms, 나머지 376ms 에서 392ms. proxy 없이 307ms 에서 388ms |
| 목록에 없는 웹 주소 | 403 |
| 목록에 있지만 proxy 토큰이 없는 provider 요청 | 403 |
| 프록시 환경을 무시하고 소켓을 직접 연다 | **연결된다.** proxy 는 환경 변수를 따르는 프로그램만 거른다 |

**설치 위치의 가정이 우리 구성과 다르다.**
Hermes 는 컨테이너에 `--add-host host.docker.internal:host-gateway` 를 넣고 proxy 를 그 주소로 가리킨다. proxy 가 Docker 호스트에서 돈다는 가정이다.
Hermes 가 컨테이너 안에서 돌면 proxy 는 Hermes 컨테이너의 loopback 에 떠서 실행 공간에서 닿지 않았다(Connection refused).
측정은 컨테이너 안에서 proxy 주소를 직접 주어 했다.

**egress 를 켜면 Docker 접근을 TCP 로 두지 못한다.**
Hermes 는 `docker` CLI 를 부를 때도 proxy 환경 변수를 넣는다. `DOCKER_HOST=tcp://...` 이면 CLI 가 그 요청을 proxy 로 보내 「Cannot connect to the Docker daemon」 으로 실패했다. unix socket 은 영향이 없다.

proxy 의 기록은 `iron-proxy.log` 에 요청마다 한 줄이다. 별도 audit 파일 자리는 있지만 v0.39 는 쓰지 않는다.

## Docker 를 다루는 길

지금 운영 Hermes 컨테이너에는 Docker socket 이 없다. 실행 공간을 쓰려면 Hermes 가 컨테이너를 만들 수 있어야 한다.

Hermes 가 쓰는 Docker API 는 `version`, `info`, `ps`, `inspect`, `image inspect`, `create`, `run`, `start`, `exec`, `rm` 이다. volume 과 network API 는 쓰지 않는다.

| 길 | 측정 | 판정 |
| --- | --- | --- |
| socket 을 Hermes 에 직접 붙인다 | 동작한다 | Hermes 프로세스나 그 자식(커넥터 MCP 서버 포함)이 뚫리면 호스트 root 와 같다 |
| 경로와 메서드만 거르는 socket proxy(containers, exec, images, info, version 만 연다) | 실행 공간이 동작한다. volume 과 network 목록은 403 | **`--privileged -v /:/host` 컨테이너 생성이 통과했다.** 호스트 파일 시스템이 보였다 |
| 컨테이너 생성 요청 본문을 검사하는 proxy | 이 저장소에서 측정하지 않았다 | 권장. 아래 조건을 본문에서 강제한다 |

본문 검사 proxy 가 거절할 것은 이렇다.

- `Privileged`, `CapAdd` 의 허용 목록 밖, `Devices`, `PidMode`/`IpcMode`/`NetworkMode` 의 `host`, `SecurityOpt` 의 완화
- 허용한 이미지가 아닌 것
- 실행 공간 루트와 운영자가 정한 읽기 전용 경로 밖의 bind
- 허용한 망이 아닌 것
- `hermes-agent=1` label 이 없는 컨테이너에 대한 `exec`, `start`, `rm`

이 proxy 는 운영 저장소가 소유한다.

## 자원

colima VM(4 CPU, 8 GiB) 에서 측정했다. 이미지는 `python:3.11-slim` 이다.

| 항목 | 측정 |
| --- | --- |
| 첫 셸 호출(컨테이너 생성 포함) | 2.3초에서 3.8초 |
| 이어지는 셸 호출 | 0.3초에서 0.8초 |
| 멈춘 컨테이너를 다시 띄운 호출 | 3.3초 |
| 첫 `execute_code`(원격 커널 기동 포함) | 3.1초에서 4.0초 |
| 유휴 컨테이너 메모리 | 0.6 MiB 에서 15 MiB |
| 컨테이너 수 | profile 마다 하나(사용자별 볼륨 방식) 또는 사용자마다 하나(공유 키). 셸을 한 번도 쓰지 않은 profile 은 만들지 않는다 |

디스크 상한(`container_disk`)은 storage driver 가 XFS pquota 를 지원할 때만 걸린다. 측정 환경에서는 걸리지 않았다.

## 근거

- [tools/terminal_tool.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/terminal_tool.py)
- [tools/terminal_scope.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/terminal_scope.py)
- [tools/environments/docker.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/environments/docker.py)
- [tools/environments/docker_egress.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/environments/docker_egress.py)
- [tools/credential_files.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/credential_files.py)
- [tools/environments/remote_common.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/environments/remote_common.py)
- [tools/skills_tool_setup.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/skills_tool_setup.py)
- [tools/image_source.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/image_source.py)
- [agent/proxy_sources/iron_proxy.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/agent/proxy_sources/iron_proxy.py)
- [agent/file_safety.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/agent/file_safety.py)
- [gateway/run.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/gateway/run.py)
