# Phase 01. 대시보드 plugin 이 mcp SDK 판을 확인하고 실패 원인을 로그에 남긴다

**Execution profile**: standard

## 목표

대시보드 plugin 의 커넥터 도구 호출이 지원 범위(`mcp>=2.0,<3`) 밖의 SDK 에서 조용히 틀리게 돌지 않게 한다.
Hermes 이미지를 올려 SDK 판이 바뀌었을 때 운영 로그 한 줄로 원인을 알 수 있어야 한다.

**범위 외**: 운영 live 검사와 업그레이드 절차의 판 확인은 비공개 운영 저장소가 맡는다. CI 의 `mcp` 판은 그대로 둔다.

## 컨텍스트

- 대상은 `hermes/plugins/dashboard-profile-api/__init__.py` 의 `_run_connector_tool`, `_connector_call_answer`, `_connector_call_request`, `register` 다. 고치기 전에 지금 모양을 읽는다
- 지금 `_connector_call_answer` 는 `getattr(result, "structured_content", None)`, `getattr(result, "is_error", False)` 로 읽는다. 1.x 결과에는 `is_error` 가 없어 도구 오류가 성공으로 읽힌다
- 지금 `_connector_call_request` 의 `except Exception` 은 `type(error).__name__` 만 남긴다. SDK 의 anyio TaskGroup 이 예외를 `ExceptionGroup` 으로 감싸 원인이 안 보인다
- 검사 `hermes/tests/test_connector_call.py` 는 `types.SimpleNamespace` 대역으로 결과를 만든다. 속성 이름이 바뀌어도 통과한다
- `scripts/check-local.sh` 의 hermes 단계는 `import mcp, yaml` 만 되면 설치를 건너뛴다. 다른 판이 깔려 있으면 그 판으로 검사가 돈다
- plugin 은 `mcp` 가 없는 환경에서도 올라와야 한다. `mcp` 와 `mcp_types` 는 함수 안에서 import 한다

**근거 문서**: `docs/connectors.md` 의 「MCP SDK 계약」, `docs/hermes/upgrades.md` 의 「올린 Hermes 이미지의 `mcp` SDK 판을 본다」

## 의도 메모

- 1.x 와 2.x 를 함께 받는 분기는 넣지 않는다. Hermes 가 판을 하나로 고정하고, 쓰이지 않는 판을 검사하려면 CI 판이 둘로 늘어난다
- 로그에 예외 본문, 칸 값, 토큰을 남기지 않는다. 자식의 출력이 본문에 섞일 수 있다
- 판이 범위 밖이어도 plugin 등록은 그대로 한다. profile 관리 경로까지 닫으면 사용자 추가가 멈춘다

## 작업 항목

### 1. `hermes/plugins/dashboard-profile-api/__init__.py` 에 SDK 확인을 더한다

- 상수 `MCP_SDK_MAJOR = 2` 를 둔다
- `_mcp_sdk_version() -> str`: `importlib.metadata.version("mcp")` 를 돌려준다. 읽지 못하면 `"unknown"` 이다
- `_mcp_sdk_problem() -> str | None`: 지원 범위이면 None, 아니면 까닭 한 줄이다. 차례로 본다
  1. `mcp` 판 문자열의 첫 조각이 `MCP_SDK_MAJOR` 가 아니면 `"지원 범위 mcp>=2.0,<3 밖이다"`
  2. `from mcp import ClientSession, StdioServerParameters`, `from mcp.client.stdio import stdio_client` 가 `ImportError` 면 `"mcp SDK 를 읽어 오지 못했다"`
  3. `mcp_types.ToolAnnotations.model_fields` 에 `read_only_hint` 가 없거나 `mcp_types.CallToolResult.model_fields` 에 `structured_content`, `is_error`, `content` 가운데 하나라도 없으면 `"필요한 속성 <이름> 이 없다"`
- `_connector_call_request` 는 자식을 띄우기 전에(동시 실행 수를 세기 전에) `_mcp_sdk_problem()` 을 부른다. 까닭이 있으면 `logger.warning("dashboard-profile-api: mcp SDK %s 로는 커넥터 도구를 부르지 않는다: %s", 판, 까닭)` 한 줄을 남기고 `failed("unavailable")` 로 답한다. 응답 모양은 지금의 `unavailable` 과 같다
- `register` 는 provider 를 등록한 뒤 `_mcp_sdk_problem()` 을 한 번 부른다. 까닭이 있으면 같은 문구의 경고 한 줄을 남긴다. 등록은 그대로 진행한다

### 2. 같은 파일의 결과 해석을 직접 읽기로 바꾼다

- `_connector_call_answer` 에서 `result.structured_content`, `result.is_error`, `result.content`, `item.type`, `item.text` 를 직접 읽는다. `getattr` 기본값을 쓰지 않는다. 텍스트가 아닌 칸은 `item.type != "text"` 로 건너뛴다
- `_connector_call_request` 는 `_connector_call_answer` 호출을 `try` 안으로 옮긴다. 속성이 없어 `AttributeError` 가 나면 아래 로그를 남기고 `unavailable` 이다. `result is None` 일 때의 400 응답은 그대로 둔다

### 3. 같은 파일의 실패 로그에 안쪽 예외 종류와 판을 남긴다

- `_leaf_error_types(error) -> list[str]`: `error.exceptions` 가 있으면 재귀로 풀어 가장 안쪽 예외의 종류 이름을 모은다. 없으면 `[type(error).__name__]` 이다
- `except Exception as error` 의 경고를 `"dashboard-profile-api: 커넥터 %s 의 도구 %s 를 부르지 못했다: %s (mcp SDK %s)"` 로 바꾸고 `", ".join(sorted(set(_leaf_error_types(error))))` 와 `_mcp_sdk_version()` 을 넣는다
- 기존 `ImportError`, `asyncio.TimeoutError` 분기는 그대로 둔다

### 4. 이 phase 를 검증하는 `hermes/tests/test_connector_call.py`

- `types.SimpleNamespace` 로 만든 결과와 내용 칸을 실제 SDK 타입 `mcp_types.CallToolResult`, `mcp_types.TextContent`, `mcp_types.ImageContent` 로 바꾼다. 파일에 `SimpleNamespace` 가 남지 않아야 한다. `("input required", types.SimpleNamespace(), UNAVAILABLE)` 는 속성이 없는 객체(`object()`)로 바꿔 `unavailable` 과 로그 한 줄을 확인한다
- 새 검사 `test_sdk_outside_supported_range_is_unavailable_without_starting_child`: `_mcp_sdk_version` 이 `"1.30.0"` 을 돌려주게 바꾸고 `call` 이 `(200, {"ok": False, "error": "unavailable"})` 이며, 로그에 `1.30.0` 과 `지원 범위` 가 있고, `_run_connector_tool` 이 불리지 않았음을 본다
- 새 검사 `test_missing_sdk_attribute_is_unavailable`: `mcp_types.ToolAnnotations.model_fields` 에서 `read_only_hint` 가 없는 것처럼 `_mcp_sdk_problem` 이 읽는 대상을 바꿔 까닭에 `read_only_hint` 가 들어감을 본다
- 새 검사 `test_failure_log_names_innermost_error_and_sdk_version`: `_run_connector_tool` 이 `ExceptionGroup("outer", [AttributeError("secret-text")])` 를 내게 하고, 로그에 `AttributeError` 와 SDK 판이 있고 `ExceptionGroup` 만 있지 않으며 `secret-text` 가 없음을 본다
- 새 검사 `test_register_logs_sdk_problem_once`: 판이 범위 밖일 때 `register` 가 경고 한 줄을 남기고 provider 는 등록함을 본다. `register` 를 부르는 기존 검사 대역은 `hermes/tests/test_dashboard_profile_api.py` 에 있다. 같은 대역을 쓴다

### 5. `scripts/check-local.sh` 의 hermes 단계

- 조건을 `import mcp, yaml` 에서 판 비교로 바꾼다. `python3 -c 'import importlib.metadata as m, sys; sys.exit(0 if m.version("mcp") == "2.0.0" and m.version("PyYAML") == "6.0.3" else 1)'` 가 실패하면 `python3 -m pip install 'mcp==2.0.0' 'PyYAML==6.0.3'` 을 돌린다
- 고정 판 문자열은 `.github/workflows/ci.yml` 의 hermes job 과 같아야 한다. 그 파일은 고치지 않는다

## 검증

```bash
# cwd: 저장소 root
python3 -m unittest discover -s hermes/tests
! grep -n "SimpleNamespace" hermes/tests/test_connector_call.py
! grep -n 'getattr(result' hermes/plugins/dashboard-profile-api/__init__.py
bash -n scripts/check-local.sh
grep -n "m.version(\"mcp\") == \"2.0.0\"" scripts/check-local.sh
scripts/check-public-safe.sh
```

모두 종료 코드 0 이어야 한다. `unittest` 는 `OK` 로 끝난다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/__init__.py` | 수정 |
| `hermes/tests/test_connector_call.py` | 수정 |
| `scripts/check-local.sh` | 수정 |
