# Phase 02. unattended 승인 결정이 기대는 Hermes 지점을 계약 시험으로 묶는다

**Execution profile**: standard

## 목표

docker profile 의 `approvals.unattended_mode: approve` 가 `execute_code` 만 열고 셸 위험 명령과 plugin 승인 요청은 승인 카드로 남는 데 기대는 Hermes 지점을 `hermes/tests/hermes_contract.py` 에 선언하고, `HermesSourceTest` 가 Hermes 소스에서 그 지점을 확인하게 한다.
Hermes 를 올릴 때 그 지점이 바뀌면 같은 설정이 셸과 커넥터 승인까지 사람 없이 여는 것을 미리 잡기 위해서다.

**범위 외**: plugin 이 값을 쓰는 동작은 phase 01 이 맡는다.

## 컨텍스트

- 계약 선언은 `hermes/tests/hermes_contract.py` 가 갖는다. `LIVE_RELOAD` 처럼 (상대 경로, 함수 이름, …) 튜플을 담은 dict 로 지점을 적는 선례가 있다.
- `hermes/tests/test_hermes_contract.py` 의 `HermesSourceTest` 는 `HERMES_SOURCE` 가 있을 때만 돌고 소스를 import 하지 않고 `ast` 로 읽는다. `self.tree(path)` 와 `self.root` 를 쓴다. `test_live_reload_points` 의 `function` 과 `calls` 내부 helper 가 따를 모양이다.
- `PluginDeclarationTest` 는 Hermes 없이 늘 돈다.
- 계약의 Hermes 버전은 `HERMES_VERSION = "v2026.9.24"` 다. 그 소스에서 확인할 지점은 다음이다.
  - `gateway/run.py` 의 `start_gateway` 안에 `os.environ["HERMES_EXEC_ASK"] = "1"` 할당이 있다.
  - `tools/approval.py` 의 `check_all_command_guards` 와 `_run_approval_gate` 에서 `_unattended_contexts()` 호출이 모두 `is_ask` 이름을 조건에 담은 `if` 안에 있다(`if not is_cli and not is_gateway and not is_ask:`).
  - `tools/approval.py` 의 `check_execute_code_guard` 가 `_should_skip_container_guards` 를 `_unattended_contexts` 보다 먼저 부른다.
  - `tools/approval_context.py` 의 `_get_unattended_approval_mode` 가 문자열 `"unattended_mode"` 를 담는다.
  - `tools/approval_context.py` 의 `_UNATTENDED_APPROVAL_PLATFORMS` 할당이 문자열 `"api_server"` 를 담는다.

**근거 문서**: `docs/adr/ADR-20261008-execute-code-unattended.md`, `docs/hermes/sandbox.md` 의 「`execute_code` 의 승인 판정」, `docs/hermes/upgrades.md`

## 의도 메모

- Hermes 를 import 해 판정 함수를 직접 부르는 시험은 기각했다. 계약 확인은 Hermes 의존성을 설치하지 않는다는 기존 원칙(ADR-088)을 따른다.
- 실패 메시지에는 「ADR-20261008 / execute-code-unattended 를 다시 본다」 를 넣는다. 실패가 곧 plugin 의 `approve` 가 셸과 커넥터 승인까지 열 수 있다는 신호다.

## 작업 항목

### 1. `hermes/tests/hermes_contract.py` 에 선언을 더한다

`LIVE_RELOAD` 아래에 둔다. 주석으로 무엇에 기대는지와 ADR 을 적는다.

```python
UNATTENDED_APPROVAL = {
    # gateway 가 프로세스 환경에 켜는 ask 표식이다
    "exec_ask": ("gateway/run.py", "start_gateway", "HERMES_EXEC_ASK"),
    # ask 문맥에서는 unattended 모드를 보지 않는 판정 함수들이다
    "ask_first": ("tools/approval.py", ("check_all_command_guards", "_run_approval_gate"),
                  "_unattended_contexts", "is_ask"),
    # 컨테이너 예외를 unattended 판정보다 먼저 보는 execute_code 판정이다
    "execute_code": ("tools/approval.py", "check_execute_code_guard",
                     "_should_skip_container_guards", "_unattended_contexts"),
    # plugin 이 쓰는 키 이름과 그 키가 적용되는 플랫폼이다
    "mode_key": ("tools/approval_context.py", "_get_unattended_approval_mode", "unattended_mode"),
    "platform": ("tools/approval_context.py", "_UNATTENDED_APPROVAL_PLATFORMS", "api_server"),
}
```

### 2. `hermes/tests/test_hermes_contract.py` 의 `HermesSourceTest` 에 `test_unattended_approval_points` 를 더한다

- `exec_ask`: `start_gateway` 함수 안에 대상이 `os.environ[<상수 "HERMES_EXEC_ASK">]` 인 `ast.Assign` 이 있고 값이 상수 `"1"` 이다.
- `ask_first`: 두 함수마다 부모를 따라가며, 모든 `_unattended_contexts` 호출이 조건식에 `ast.Name(id="is_ask")` 를 담은 `ast.If` 의 `body` 안에 있다. 호출이 하나도 없으면 실패한다.
- `execute_code`: `check_execute_code_guard` 안에서 `_should_skip_container_guards` 호출의 `lineno` 가 `_unattended_contexts` 호출의 `lineno` 보다 작다.
- `mode_key`: 그 함수 안에 상수 `"unattended_mode"` 가 있다.
- `platform`: 모듈 머리의 `_UNATTENDED_APPROVAL_PLATFORMS` 할당 값 안에 상수 `"api_server"` 가 있다.

### 3. `PluginDeclarationTest` 에 `test_unattended_approval_key_matches_contract` 를 더한다

`PLUGIN_DIR / "sandbox.py"` 를 `ast` 로 읽어 모듈 상수 `UNATTENDED_APPROVAL_KEY` 의 값이 `contract.UNATTENDED_APPROVAL["mode_key"][2]` 와 같은지 본다. 파일에 있는 `_module_constant(tree, name)` helper 를 쓴다.

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_hermes_contract.py' -v
scripts/check-hermes-contract.sh
python3 -m unittest discover -s hermes/tests
scripts/quality.sh check
```

기대값: 네 명령 모두 종료 코드 0. 둘째 명령의 출력에 `test_unattended_approval_points ... ok` 가 있다. 첫 명령은 `HERMES_SOURCE` 가 없어 `HermesSourceTest` 를 건너뛰고 `test_unattended_approval_key_matches_contract ... ok` 를 보인다.
둘째 명령은 상류 저장소에서 계약의 tag 를 받는다. 네트워크가 막혀 받지 못하면 `PHASE_BLOCKED: Hermes 소스를 받지 못했다` 를 출력하고 멈춘다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/tests/hermes_contract.py` | 수정 |
| `hermes/tests/test_hermes_contract.py` | 수정 |
