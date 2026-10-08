# Phase 01. 대시보드 plugin 이 두 선언을 읽고 sandbox_required 를 설치에서 판정한다

**Execution profile**: standard

## 목표

`connector.json` 의 선택 boolean `single_binding` 과 `sandbox_required` 를 대시보드 plugin 이 검증한다.
카탈로그는 `single_binding` 을 싣고, 바인딩 설치는 `sandbox_required` 인 커넥터를 실행 공간 정책에 없는 profile 에 붙이지 않는다.

**범위 외**: Control Plane 의 `single_binding` 판정과 오류 코드 옮기기(phase 02), 화면 문구(phase 03).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261008-connector-binding-guards.md`, `docs/connectors.md`, `docs/backend/connector-install.md`

- 계약: `docs/connectors.md` 의 「connector.json」 표, `docs/backend/connector-install.md` 의 「대시보드 plugin 계약」 과 「바인딩 설치」 조건 목록
- manifest 검증은 `hermes/plugins/dashboard-profile-api/connector_manifest.py` 의 `_load_connector` 가 한다. boolean 칸 검증은 같은 함수의 `attachments` 처리(`declared.get("attachments", False)` 와 `isinstance(..., bool)`)를 따른다. 반환 dict 에 `"attachments": attachments` 처럼 칸을 더한다
- 카탈로그 응답은 같은 파일의 `_connector_catalog_response` 가 만든다. `"attachments": manifest["attachments"]` 옆에 `"single_binding": manifest["single_binding"]` 을 더한다. `sandbox_required` 는 카탈로그에 싣지 않는다. Control Plane 이 쓰지 않는다
- 바인딩 설치는 `hermes/plugins/dashboard-profile-api/connector_install.py` 의 `if body["enabled"] and "bind" in body:` 분기다. manifest 를 읽은 직후, `owner_attachments_env` 검사 앞에 둔다. 정책은 `_sandbox_policy()` 로 읽고 등록 profile 은 `sandbox["profiles"]` 다(`owner_output_env` 분기의 `profile in sandbox["profiles"]` 와 같은 판정). 거절은 `_sandbox_unavailable()` 를 그대로 돌려준다(409, 본문 `{"detail", "code": "sandbox_unavailable"}`)
- 이 분기는 처음 붙이기와 다시 설치(연결 확인, 반영 완료)가 함께 지난다. 다시 설치에서 409 를 받은 Control Plane 은 그 바인딩을 `PENDING` 으로 둔다. 이 phase 는 그 동작을 바꾸지 않는다

## 의도 메모

- `sandbox_required` 는 profile 이 정책에 등록됐는지만 본다. 지금 셸이 켜져 있는지는 보지 않는다. 셸은 붙인 뒤에 켤 수 있기 때문이다(ADR 의 「대안 기각」)
- `single_binding` 은 대시보드가 판정하지 않는다. 다른 profile 의 소유 기록을 읽지 않는다
- 두 칸 모두 없으면 거짓이다. 지금 커넥터(`gmail`, `naver-blog`)의 카탈로그는 `single_binding: false` 를 새로 싣는 것 말고 바뀌지 않는다

## 작업 항목

### 1. `hermes/plugins/dashboard-profile-api/connector_schema.py` 와 `connector_manifest.py`

`connector_manifest.py` 는 지금 395줄이고 `scripts/check-file-length.mjs` 의 상한은 400줄이다. 검증 본문은 `connector_schema.py`(170줄)에 둔다.

- `connector_schema.py` 에 `_connector_binding_flags(declared: dict) -> dict` 를 더한다. `declared.get("single_binding", False)` 와 `declared.get("sandbox_required", False)` 를 읽고, 둘 중 하나라도 `bool` 이 아니면 `ValueError("single_binding 과 sandbox_required 는 boolean 이다")` 를 던진다. 돌려주는 값은 `{"single_binding": ..., "sandbox_required": ...}` 다
- `connector_manifest.py` 는 그 함수를 `from .connector_schema import (...)` 목록에 더하고, `_load_connector` 의 반환 dict 에 `**_connector_binding_flags(declared),` 한 줄을 더한다
- `_connector_catalog_response` 의 항목에 `"single_binding": manifest["single_binding"]` 을 더한다. `"attachments": manifest["attachments"],` 와 같은 줄에 이어 쓴다
- 고친 뒤 `connector_manifest.py` 가 400줄을 넘지 않는다

### 2. `hermes/plugins/dashboard-profile-api/connector_install.py`

바인딩 설치 분기에서 `manifest` 가 None 이 아님을 확인한 직후에 더한다.

```python
if manifest["sandbox_required"]:
    sandbox = _sandbox_policy()
    if sandbox is None or profile not in sandbox["profiles"]:
        return _sandbox_unavailable()
```

주석에 ADR-20261008 connector-binding-guards 를 적는다.

### 3. 시험 `hermes/tests/test_connector_manifest.py`

- `test_catalog_lists_the_validated_connector_without_operator_values` 의 `assertEqual(set(entry), {...})` 키 집합에 `"single_binding"` 을 더한다. 더하지 않으면 이 기존 시험이 깨진다
- 두 칸을 선언하지 않은 커넥터의 카탈로그 항목에 `"single_binding": False` 가 있다
- `single_binding: true` 를 선언하면 카탈로그에 참으로 나온다
- 둘 중 하나가 `"true"`(문자열)면 그 커넥터가 카탈로그에서 빠진다. 기존 `test_invalid_manifest_is_left_out_without_raising` 의 방식을 따른다

### 4. 시험 `hermes/tests/test_dashboard_profile_api_connector_binding_install.py`

`hermes/tests/dashboard_profile_api_support.py` 에 `declare_sandbox_required(connector)` 도우미를 더한다. `declare_owner_attachments` 처럼 `connector.json` 사본에 `"sandbox_required": true` 를 쓴다.

- 실행 공간 정책이 없을 때 `sandbox_required` 커넥터의 바인딩 설치는 409 이고 본문 `code` 가 `sandbox_unavailable` 이며, profile 의 `config.yaml` 과 `.env` 와 소유 기록이 바뀌지 않는다
- 정책은 있지만 그 profile 이 `profiles` 에 없을 때도 같은 409 다
- 그 profile 이 정책에 있으면 설치가 성공한다. 정책을 만드는 방법은 같은 파일이나 `test_dashboard_profile_api_connector_binding_output.py` 의 정책 준비 도우미를 따른다
- 선언하지 않은 커넥터는 정책 없이 지금처럼 붙는다

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_connector_manifest.py'
python3 -m unittest discover -s hermes/tests -p 'test_dashboard_profile_api_connector_binding_*.py'
python3 -m unittest discover -s hermes/tests
node scripts/check-file-length.mjs
```

넷 다 실패 없이 끝난다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/connector_schema.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_manifest.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_install.py` | 수정 |
| `hermes/tests/dashboard_profile_api_support.py` | 수정 |
| `hermes/tests/test_connector_manifest.py` | 수정 |
| `hermes/tests/test_dashboard_profile_api_connector_binding_install.py` | 수정 |
