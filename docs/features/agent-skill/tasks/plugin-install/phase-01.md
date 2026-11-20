# Phase 01. manifest 의 에이전트용 스킬 칸을 읽고 카탈로그에 낸다

**Execution profile**: standard

## 목표

`assistant.json` 의 `agent_skills` 를 검증해 manifest 값과 카탈로그 응답에 넣는다. 다음 phase 의 판정과 설치가 이 값을 쓴다.

**범위 외**: 셸 조건 판정, 바인딩 설치, 설치 상태 판정(phase 02). Control Plane 과 웹(다음 계획).

## 컨텍스트

**요구사항**: REQ1

**근거 문서**: `docs/features/agent-skill/spec.md`, `docs/features/agent-skill/design.md` 의 「인터페이스와 데이터」, `hermes/docs/adr/ADR-20261120-plugin-agent-skills.md`

- manifest 는 `hermes/plugins/dashboard-profile-api/connector_manifest.py` 의 `_load_connector` 가 만든다. 플러그인 스킬은 같은 함수에서 `plugin.get("skills", "./skills")` 의 디렉터리를 `_connector_skills`(`connector_skills.py`)로 읽어 `"skills"` 에 넣는다. 에이전트용 스킬도 같은 함수로 읽는다.
- `declared` 는 `_read_assistant_json(root)` 의 결과다. 표준 플러그인(`assistant.json` 없음)은 `_standard_declaration` 이 만들고 `agent_skills` 가 없다.
- 카탈로그 응답은 같은 파일의 `_connector_catalog_response` 가 만든다. 선택 칸은 `**({...} if ... else {})` 관례로 비어 있지 않을 때만 넣는다.
- 운영 정책의 env 허용 이름은 지금 `sandbox.py` 의 `SANDBOX_PATH_ENV` 와 `_sandbox_env_ok` 안의 `"CAREER_BACKEND_URL"` 로 흩어져 있다.

## 의도 메모

- `agent_skills` 를 `plugin.json` 에 두지 않는다. Claude Code 가 그 디렉터리를 스킬로 읽게 되고, assistant 전용 정보는 `assistant.json` 에 둔다(design DECISION1).
- 카탈로그 `skills` 에 에이전트용 스킬 이름을 합친다. Control Plane 의 처음 붙이기 이름 겹침 검사가 그대로 넓어진다(design DECISION4).
- 오류 메시지는 기존처럼 `ValueError` 의 짧은 한국어 사유만 쓰고 파일 내용과 경로를 넣지 않는다.

## 작업 항목

### 1. `hermes/plugins/dashboard-profile-api/sandbox.py` 의 허용 env 상수

`SANDBOX_ALLOWED_ENV = SANDBOX_PATH_ENV | {"CAREER_BACKEND_URL"}` 를 두고 `_sandbox_env_ok` 는 이름이 이 집합 밖이면 거짓을 돌려준다(지금 동작과 같게). `__init__.py` 의 재노출 목록에 더한다.

### 2. `hermes/plugins/dashboard-profile-api/connector_manifest.py` 의 `agent_skills` 읽기

`_load_connector` 에서 플러그인 스킬을 읽은 뒤 `declared.get("agent_skills")` 를 읽는다. 함수 하나(`_agent_skill_declaration(root, declared, plugin_skills) -> tuple[dict, dict]`)로 뺀다. `plugin_skills` 는 이미 읽은 플러그인 스킬 사전이고, 이름 겹침 검사를 이 함수 안에서 한다.

- 칸이 없으면 `({}, {})`.
- 객체이고 키가 `path` 와 선택 `sandbox_env` 뿐이어야 한다.
- `path` 는 플러그인 스킬 경로와 같은 검사(plugin 안, 디렉터리, 링크 없음)를 한다. `_connector_skills([path])` 로 읽는다.
- `sandbox_env` 는 객체이고, 키는 읽은 스킬 이름(`_skill_name` 결과)이어야 하며, 값은 비지 않은 문자열 목록이고 중복이 없으며 모두 `SANDBOX_ALLOWED_ENV` 안이어야 한다.
- 에이전트용 스킬 이름이 플러그인 스킬 이름과 겹치면 `ValueError("스킬 이름이 겹친다")`.
- 돌려주는 값: 파일 사전 `{이름: {상대 경로: 바이트}}` 와 `{이름: frozenset(env)}`(요구 없는 스킬은 빈 frozenset).

manifest 의 `"skills"` 는 두 파일 사전을 합친 것으로, 새 키 `"agent_skills"` 는 두 번째 값으로 둔다.

### 3. 카탈로그 응답

`_connector_catalog_response` 에 `**({"agent_skills": [{"name": n, "sandbox_env": sorted(e)} for n, e in sorted(...)]} if manifest["agent_skills"] else {})` 를 더한다. docstring 의 `skills` 설명을 「붙인 에이전트에 설치할 수 있는 스킬 이름(에이전트용 스킬 포함)」 으로 고친다.
표준 플러그인 경로가 manifest 를 만드는 곳에도 `"agent_skills": {}` 가 들어가는지 확인한다(`_load_connector` 한 곳이면 그대로 된다).

### 4. `hermes/tests/test_connector_manifest.py` 에 테스트

기존 테스트의 임시 plugin 만드는 부품을 쓴다.

- 정상: `agent-skills/` 에 두 스킬(하나는 `sandbox_env` 있음)을 둔 플러그인의 manifest `skills` 에 둘이 다 있고, `agent_skills` 가 이름과 env 집합을 갖는다. 카탈로그 항목에 `agent_skills` 가 이름 순으로 있고 `skills` 에 이름이 합쳐 있다.
- 칸 없는 플러그인의 카탈로그 항목에 `agent_skills` 키가 없다.
- 거절(각각 카탈로그에서 빠짐): `path` 가 plugin 밖, 링크, 없는 디렉터리 / 모르는 키 / `sandbox_env` 키가 없는 스킬 이름 / 허용 밖 env 이름 / 빈 목록 / 플러그인 스킬과 이름 겹침 / 에이전트용 스킬 앞머리에 `required_environment_variables` / 에이전트용 스킬 하나의 파일이 `CONNECTOR_SKILL_MAX_FILES` 를 넘음.
- 테스트 플러그인은 옛 이름 `connector.json` 을 쓴다. 같은 디렉터리에 `assistant.json` 을 함께 두면 거절되므로, 기존 테스트 플러그인의 `connector.json` 에 `agent_skills` 를 더한다.
- `sandbox.py` 정책 검증이 지금처럼 `CAREER_BACKEND_URL` 과 경로 이름을 받고 다른 이름을 거절한다(기존 `test_dashboard_profile_api_sandbox_policy.py` 가 통과하면 된다).

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_connector_manifest.py'
python3 -m unittest discover -s hermes/tests -p 'test_dashboard_profile_api_sandbox_policy.py'
python3 -m unittest discover -s hermes/tests -p 'test_connector_standard_plugin.py'
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/sandbox.py` | 수정 (허용 env 상수) |
| `hermes/plugins/dashboard-profile-api/__init__.py` | 수정 (재노출) |
| `hermes/plugins/dashboard-profile-api/connector_manifest.py` | 수정 (`agent_skills` 읽기, 카탈로그 칸) |
| `hermes/tests/test_connector_manifest.py` | 수정 (테스트) |
