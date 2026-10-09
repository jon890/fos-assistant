# Phase 02. 대시보드 plugin 이 스킬 디렉터리를 실행 공간에 붙이고 scripts 게시를 판정한다

**Execution profile**: deep

## 목표

실행 공간 정책에 선택 키 `skill_root` 를 더한다.
그 키가 있으면 plugin 이 셸 설정을 쓸 때 그 profile 의 스킬 디렉터리 전체를 Hermes 와 같은 경로에 읽기 전용으로 붙인다.
`require_sandbox` 가 붙은 스킬 게시는 실행 공간 도구, 등록된 profile, `skill_root` 가 모두 있어야 받는다.

**범위 외**: Control Plane 쪽(phase 01, 다음 PR). 운영 정책 값과 socket proxy 설정(이 저장소 밖, `remote-verification.md`).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261009-skill-package.md`, `hermes/README.md` 의 「셸 실행 공간」(정책 표의 `skill_root` 줄, 겹침 규칙, `terminal:` 예시의 스킬 마운트 줄)과 「`PUT /api/config` (스킬 게시)」 줄, `docs/hermes/sandbox.md` 의 「마운트」, `docs/backend/skill.md` 의 「스크립트와 실행 공간」

- 정책 읽기는 `hermes/plugins/dashboard-profile-api/sandbox.py` 의 `_sandbox_policy()` 다. 최상위 키 목록은 `SANDBOX_POLICY_KEYS`, 경로 검사는 `_sandbox_path_ok`, 겹침은 `_sandbox_paths_overlap`, profile 별 설정은 `_sandbox_profiles(...)` 다. `connector_output_root` 가 선택 키의 본보기다(검증, 겹침, 돌려주는 dict 의 칸)
- 셸 설정은 같은 파일의 `_sandbox_terminal(policy, profile, owner, attachment_snapshot)` 이 만든다. 부르는 곳은 `toolconfig.py` 의 `_check_config_update` 와 `connector_install.py` 다. `docker_volumes` 순서는 workspace, 첨부, 커넥터 출력, 공통 마운트, profile 마운트다. 지문은 `docker_shared_container_key` 를 뺀 `terminal` 전체의 sha256 앞 12자다
- Hermes 쪽 스킬 루트는 `hermes/plugins/dashboard-profile-api/toolconfig.py` 의 `SKILL_ROOT_ENV`(`FOS_ASSISTANT_SKILL_AGENT_ROOT`) 와 `_skill_root()` 다. `toolconfig.py` 가 `sandbox.py` 를 import 하므로 `sandbox.py` 는 `toolconfig.py` 를 import 하지 못한다. 둘 다 `common.py` 를 import 한다
- `__init__.py` 는 `from .toolconfig import (SKILL_ROOT_ENV, …)` 로 이름을 가져온다
- 게시 판정은 `toolconfig.py` 의 `_check_config_update` 다. `require_sandbox` 는 지금 도구 목록 분기(`if platform is not None and SANDBOX_TOOLSETS & set(platform["api_server"])`)에서만 쓰인다. 미등록 profile 이면 `_sandbox_unavailable()`(409 `sandbox_unavailable`) 이다
- 시험은 `hermes/tests/` 의 `unittest` 다. `dashboard_profile_api_support.py` 의 `ProfileApiRouteTest` 가 정책 환경 변수, 임시 루트, `expected_terminal(...)` 을 준다. 본보기: `test_dashboard_profile_api_sandbox_policy.py`, `test_dashboard_profile_api_sandbox_terminal.py`, `test_dashboard_profile_api_toolconfig.py`

## 의도 메모

- 지원 파일의 기본 정책에 `skill_root` 를 넣지 않는다. 넣으면 기존 `expected_terminal` 단언이 모두 바뀐다. 새 시험만 정책에 키를 더한다
- 마운트는 Hermes 쪽 `<스킬 루트>/<profile>` 이 링크 없는 디렉터리일 때만 붙인다. 없는 원본을 붙이면 Docker 가 호스트에 빈 디렉터리를 만들거나 socket proxy 가 생성을 거절해 그 profile 의 셸이 멈춘다
- 대상 경로를 `/root/.hermes/…` 가 아니라 Hermes 와 같은 경로로 둔다. `skill_view` 가 모델에게 Hermes 쪽 경로를 준다
- 새 겹침 검사는 정책에 `skill_root` 가 있을 때만 한다. 늘 하면 배포 직후 운영이 키를 넣기 전에도 기존 정책이 무효가 되어 모든 profile 의 셸 저장이 409 가 될 수 있다
- 이 phase 는 커밋 둘로 끝난다. 이동과 동작 변경을 나누는 저장소 규칙(`AGENTS.md` 「머지는 PR 로 한다」) 때문이다. 커밋마다 아래 「커밋 나누기」 의 파일만 담는다

## 작업 항목

### 1. `common.py` 로 스킬 루트를 옮긴다

- `SKILL_ROOT_ENV` 와 `_skill_root()` 를 `toolconfig.py` 에서 `common.py` 로 옮긴다. 내용은 바꾸지 않는다
- `toolconfig.py` 는 `common.py` 에서 두 이름을 import 한다. `__init__.py` 의 `from .toolconfig import (SKILL_ROOT_ENV, …)` 는 그대로 동작한다
- 이 항목만 담은 이동 커밋을 먼저 만든다. 담을 파일은 `hermes/plugins/dashboard-profile-api/common.py`, `hermes/plugins/dashboard-profile-api/toolconfig.py` 둘이고, 커밋 전에 `python3 -m unittest discover -s hermes/tests` 가 종료 코드 0 이어야 한다

### 2. 정책에 `skill_root` 를 더한다(`sandbox.py`)

- `SANDBOX_POLICY_KEYS` 에 `"skill_root"` 를 더한다
- `_sandbox_policy()` 에서 `connector_output_root` 다음에 읽는다. 없으면 `None`. 있으면 `_sandbox_path_ok` 를 통과하고, `workspace_root`, `attachment_root`, `connector_output_root`(있으면) 와 `_sandbox_paths_overlap` 가 거짓이어야 한다. 어기면 `invalid("skill_root")`
- **정책에 `skill_root` 가 있을 때만** 아래를 더 검사한다. 없으면 지금과 같다
  - 공통 `read_only_mounts` 와 `_sandbox_profiles` 의 profile 별 마운트에서, 원본이 `skill_root` 와 겹치거나 대상이 `_skill_root()` 와 겹치면 정책 전체를 거절한다. `_sandbox_profiles` 에 `skill_root` 인자를 더하고 부르는 곳을 함께 고친다
  - `_skill_root()` 가 없으면(환경 변수 없음) `invalid("skill_root")` 다. 마운트 대상을 정할 수 없다
  - `_skill_root()` 도 `_sandbox_path_ok` 를 통과해야 한다. 마운트 문자열에 `:` 이나 제어 문자가 들어가지 않게 한다
  - `_skill_root()` 가 `SANDBOX_RESERVED_PATHS`(`/workspace`, `/root`), `attachment_agent_root`, `connector_output_root`(있으면)와 `_sandbox_paths_overlap` 이면 `invalid("skill_root")` 다. 새 마운트 대상이 예약 경로나 다른 사용자의 자리와 겹치지 않게 한다
- 돌려주는 dict 에 `"skill_root": skill_root` 를 더한다

### 3. 셸 설정에 스킬 마운트를 더한다(`sandbox.py`)

- `_sandbox_terminal` 에서 `policy["skill_root"]` 가 있고 `_skill_root()` 가 있고, `_skill_root() / profile` 이 `os.lstat` 기준 링크가 아닌 디렉터리면 `"%s/%s:%s/%s:ro" % (policy["skill_root"], profile, _skill_root(), profile)` 를 만든다
- `docker_volumes` 에서 커넥터 출력 마운트 다음, 공통 마운트 앞에 둔다. `hermes/README.md` 의 `terminal:` 예시와 같은 순서다
- 지문 계산은 그대로다. 마운트가 들어가면 지문과 키가 바뀐다

### 4. 스킬 게시의 `require_sandbox` 를 판정한다(`toolconfig.py`)

- `_check_config_update` 에서 `skills` 와 `platform` 을 읽은 뒤, `require_sandbox` 이고 `skills is not None` 이면 아래가 모두 참이어야 한다. 하나라도 거짓이면 `_sandbox_unavailable()` 을 돌려준다
  - `platform is not None` 이고 `SANDBOX_TOOLSETS & set(platform["api_server"])` 가 비지 않았다
  - `sandbox is not None`(정책이 유효하고 profile 이 등록됐다. 미등록이면 이미 위 분기에서 409 다)
  - `sandbox["skill_root"]` 가 있다
- 이 판정은 설정 파일을 읽기 전에 한다. `require_sandbox` 가 없는 게시는 지금과 같다

### 5. 시험

- `test_dashboard_profile_api_sandbox_policy.py`: `skill_root` 가 있는 정책을 받고, 상대 경로, `workspace_root` 아래, `attachment_root` 위, `connector_output_root` 와 같은 값이면 정책 전체를 거절한다. 공통 마운트 원본이 `skill_root` 아래이거나 profile 마운트 대상이 Hermes 스킬 루트 아래이면 거절한다. Hermes 스킬 루트 환경 변수가 없거나 `/workspace` 아래, `attachment_agent_root` 아래이면 거절한다. `skill_root` 가 없는 정책은 profile 마운트 대상이 Hermes 스킬 루트 아래여도 지금처럼 받는다
- `test_dashboard_profile_api_sandbox_terminal.py`: 정책에 `skill_root` 가 있고 Hermes 스킬 루트 아래 그 profile 디렉터리가 있으면 셸 저장이 스킬 마운트를 커넥터 출력 다음에 넣고 지문이 키와 맞는다. 디렉터리가 없거나 링크이거나 정책에 키가 없으면 마운트가 없다. 디렉터리가 생긴 뒤 다시 저장하면 키가 바뀐다
- `test_dashboard_profile_api_toolconfig.py`: `require_sandbox: true` 인 스킬 게시가 `skill_root` 없는 정책에서 409 `sandbox_unavailable`, 도구 목록이 없거나 셸 도구가 없을 때 409, 미등록 profile 에서 409 이고 설정 파일이 바뀌지 않는다. 등록된 profile, 셸 도구, `skill_root` 가 있으면 200 이고 `external_dirs` 와 스킬 마운트가 든 `terminal` 이 함께 쓰인다. `require_sandbox` 없는 게시는 기존 시험 그대로 통과한다
- 정책은 `dashboard_profile_api_support.py` 의 기존 `sandbox_policy(skill_root=…)` 와 `set_sandbox_policy` 로 바꾼다. 새 도우미를 만들지 않는다

## 검증

```bash
python3 -m pip install "mcp==2.0.0" "PyYAML==6.0.3"
python3 -m unittest discover -s hermes/tests
```

- 둘째 줄이 종료 코드 0. 첫 줄의 판은 `scripts/check-local.sh` 의 `HERMES_MCP_VERSION`, `HERMES_PYYAML_VERSION` 과 같다. 이미 그 판이면 건너뛴다
- `scripts/check-local.sh` 의 인자는 브라우저 spec 이름이라 이 phase 만 따로 돌리는 데 쓰지 않는다. 전체 검사는 PR 을 열기 전에 인자 없이 돌린다

## 커밋 나누기

| 커밋 | 담을 파일 | 커밋 전 시험 |
|---|---|---|
| 이동 | `hermes/plugins/dashboard-profile-api/common.py`, `hermes/plugins/dashboard-profile-api/toolconfig.py` | `python3 -m unittest discover -s hermes/tests` |
| 동작 | 아래 표의 나머지 파일과 `toolconfig.py` 의 이후 변경 | 「검증」 절 |

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/common.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/toolconfig.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/sandbox.py` | 수정 |
| `hermes/tests/dashboard_profile_api_support.py` | 수정 |
| `hermes/README.md` | 수정 |
| `hermes/tests/test_dashboard_profile_api_sandbox_policy.py` | 수정 |
| `hermes/tests/test_dashboard_profile_api_sandbox_terminal.py` | 수정 |
| `hermes/tests/test_dashboard_profile_api_toolconfig.py` | 수정 |
