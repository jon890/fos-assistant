# Phase 02. 셸 조건과 이름 겹침을 판정해 에이전트용 스킬을 설치하고 어긋남을 본다

**Execution profile**: standard

## 목표

판정 함수 하나로 바인딩 설치와 설치 상태 판정이 같은 기대 집합을 쓰게 하고, 붙이기 응답에 빠진 스킬을 낸다. 계약 문서를 같은 커밋에서 맞춘다.

**범위 외**: Control Plane 의 저장과 화면 안내(다음 계획). 운영 정책과 셸 설정 쓰기(`_sandbox_terminal`)는 고치지 않는다.

## 컨텍스트

**요구사항**: REQ2, REQ3, REQ4, REQ5, REQ6, REQ7

**근거 문서**: `docs/features/agent-skill/spec.md`, `docs/features/agent-skill/design.md` 의 「판정 함수」, 「설치 응답」, 「소유 기록」, 「설치 상태 판정」

- 바인딩 설치는 `hermes/plugins/dashboard-profile-api/connector_binding.py` 의 `_connector_bind_config` 다. 붙이기 분기에서 `skills = sorted(manifest["skills"])` 로 모두 복사하고, `(skills_dir / skill).exists() and skill not in previous_skills` 이면 `FileExistsError`(409)다. 소유 기록 항목 `state[plugin]["skills"]` 에 설치한 이름을 적는다. 「바뀐 것 없음」 은 두 곳에서 일찍 돌아간다(떼기에서 기록 없음, 모든 파일이 같음).
- 응답은 `connector_install.py` 의 붙이기 처리에서 `{**response, **result}` 로 나간다.
- 설치 상태 판정은 `connector_status.py` 의 `_bind_skills_installed(profile_dir, manifest, entry)` 이고 호출부는 `connector_install.py` 의 상태 조회(이미 `config` 를 읽었다)다.
- 셸 설정 모양은 `sandbox.py` 의 `_sandbox_terminal` 이 쓰는 `terminal` 이다. `backend` 가 `docker` 이고, 정책 env 가 있으면 `docker_env` 에 들어간다. API 도구 목록은 `platform_toolsets.api_server` 다.
- 올린 스킬 경로는 `skills.external_dirs`(0개나 1개, 그 아래 스킬마다 디렉터리)다(`toolconfig.py` 의 `_operator_skill_dirs` 참고).
- 소유 기록 허용 키는 `connector_state.py` 의 `_connector_state` 가 갖는다. 새 키를 더하지 않는다(design DECISION2).

## 의도 메모

- 판정은 운영 정책을 읽지 않는다(spec DECISION17, design DECISION3). 정책이 없는 개발·CI 에서 `_sandbox_policy()` 의 오류 로그가 늘지 않는다.
- 플러그인 스킬(에이전트용이 아닌 스킬)의 겹침은 지금 규칙 그대로 409 다(spec DECISION16).
- 설치 상태 판정이 판정 함수를 쓰게 해야 정의 어긋남 점검이 조건 변화를 맞춘다(spec DECISION7). 다시 설치가 같은 함수로 기록을 쓰므로 같은 어긋남을 되풀이하지 않는다.

## 작업 항목

### 1. 판정 함수 `_agent_skill_plan`

새 파일 `hermes/plugins/dashboard-profile-api/connector_agent_skills.py` 에 두고, 테스트가 `self.plugin._agent_skill_plan` 으로 부르도록 `__init__.py` 의 재노출 목록에 더한다.

```python
def _agent_skill_plan(profile_dir: pathlib.Path, config: dict, manifest: dict,
                      owned_skills: list) -> tuple[set, dict]:
    """(설치할 에이전트용 스킬 이름, {빠진 이름: [까닭]})."""
```

- 대상은 `manifest["agent_skills"]` 의 이름뿐이다.
- 까닭 순서: `terminal_off`(api_server 목록에 `terminal` 없음, 목록이 아니어도 같음), `sandbox_unregistered`(`config["terminal"]["backend"] != "docker"`), `sandbox_env_missing`(`docker_env` 키에 요구 이름이 하나라도 없음). 세 까닭은 요구 env 가 비지 않은 스킬만 본다.
- `name_taken`: profile `skills/<이름>` 이 있고 그 이름이 `owned_skills` 에 없다. 또는 소유와 상관없이 `skills.external_dirs` 의 각 경로 아래 `<이름>` 디렉터리가 있다(플러그인은 `external_dirs` 에 설치하지 않는다). `external_dirs` 가 문자열 하나면 그 하나를 담은 목록으로, 목록도 문자열도 아니면 빈 목록으로 읽는다(`toolconfig.py` 의 `_operator_skill_dirs` 와 같은 읽기). 항목이 문자열이 아니거나 읽지 못하면 그 항목을 건너뛴다. 모든 에이전트용 스킬을 본다.
- 예외를 내지 않는다. config 의 칸이 모양이 틀리면 조건을 못 채운 것으로 본다.

### 2. `connector_binding.py` 의 붙이기

- 붙이기 분기에서 `plan, skipped = _agent_skill_plan(profile_dir, saved, manifest, previous_skills)` 를 부른다.
- 처음 붙이기(`owned is None`)에 `name_taken` 이 하나라도 있으면 지금처럼 `FileExistsError("이미 있는 스킬 디렉터리와 이름이 겹친다")`.
- 설치할 스킬 = 플러그인 스킬(`manifest["skills"]` 에서 `manifest["agent_skills"]` 를 뺀 것) ∪ `plan`. 기존 겹침 검사는 플러그인 스킬에만 한다. 소유 기록 `skills` 와 `desired` 는 이 집합으로 만든다.
- 붙이기의 반환값(일찍 돌아가는 「바뀐 것 없음」 포함)에 `"skipped_skills": [{"name": n, "reasons": r} for n, r in sorted(skipped.items())]` 를 넣는다. 판정을 그 반환보다 앞에서 한다. 떼기의 반환값에는 넣지 않는다. 떼기 응답 본문 전체를 비교하는 기존 테스트(`test_dashboard_profile_api_connector_install_security.py`)가 그대로 통과해야 한다.
- docstring 에 에이전트용 스킬과 판정을 한 줄로 더한다.

### 3. `connector_status.py` 의 `_bind_skills_installed`

시그니처를 `_bind_skills_installed(profile_dir, manifest, entry, config)` 로 바꾸고 호출부(`connector_install.py`)를 고친다. 기대 집합 = 플러그인 스킬 ∪ `_agent_skill_plan(profile_dir, config, manifest, entry["skills"])[0]` 이고, `sorted(entry["skills"]) != sorted(기대 집합)` 이면 거짓이다. 파일 비교는 기록의 스킬마다 한다.

### 4. 계약 문서

- `hermes/connectors/README.md` 의 「스킬」: `agent_skills` 칸 모양, 스킬마다 요구 env, 설치 조건과 까닭, 자동 맞춤, 붙인 뒤 이름 겹침, `scripts/` 를 복사하지 않음을 적는다. ADR-20261120 / plugin-agent-skills 를 링크한다.
- `hermes/plugins/dashboard-profile-api/README.md`: 카탈로그 응답의 `skills` 뜻과 `agent_skills` 칸, `PUT /api/connectors` 응답의 `skipped_skills` 를 적는다. 옛 대시보드는 두 칸을 내지 않는다는 문장도 둔다.
- `docs/features/connector.md` 의 「바인딩 설치」 스킬 줄: 에이전트용 스킬과 조건을 한 줄로 더하고 `agent-skill/spec.md` 를 가리킨다.
- `docs/features/agent-skill.md` 의 「스크립트와 실행 공간」 끝에 플러그인 에이전트용 스킬은 `agent-skill/spec.md` 가 갖는다는 한 줄.

### 5. 테스트

- 새 `hermes/tests/test_connector_agent_skills.py`: 판정 함수의 까닭 네 가지와 순서, 요구 env 없는 스킬은 셸 조건을 보지 않음, 소유한 이름은 `name_taken` 이 아님, `external_dirs` 겹침, config 모양이 틀려도 예외 없음.
- `hermes/tests/test_dashboard_profile_api_connector_binding_install.py`(기존 부품 `support.ProfileApiRouteTest` 의 `bind_fixture` 를 쓴다). `dashboard_profile_api_support.py` 에 `agent_skill_fixture(self)` 를 더한다: `bind_fixture` 가 복사한 demo 플러그인에 `agent-skills/demo-report/SKILL.md`(요구 없음)와 `agent-skills/demo-runner/SKILL.md`(요구 env `CLAUDE_PLUGIN_ROOT`)를 만들고 그 `connector.json` 에 `agent_skills` 를 더한다. 셸 조건은 profile `config.yaml` 의 `platform_toolsets.api_server` 와 `terminal` 을 직접 써서 만든다:
  - 터미널 꺼진 profile 에 붙이면 200, 요구 env 없는 스킬만 설치, 응답 `skipped_skills` 에 `terminal_off`(REQ2, REQ3). 바로 뒤 상태 조회의 `configured` 가 참이다(조건이 그대로면 점검이 다시 설치하지 않는다, REQ4)
  - `skills.disabled` 에 `demo-report` 를 넣고 다시 설치해도 그 이름이 남는다(REQ2)
  - `terminal` 과 docker 셸 설정, `docker_env` 를 갖춘 profile 에 붙이면 모두 설치, 빈 `skipped_skills`(REQ3)
  - 같은 요청을 다시 보내면 「바뀐 것 없음」 이고 응답에 같은 `skipped_skills`
  - 터미널 꺼진 채 붙인 뒤 셸 조건을 갖추면 상태 조회의 `configured` 가 거짓, 다시 설치 뒤 `demo-runner` 가 깔리고 참. 그 뒤 터미널을 끄면 다시 거짓, 다시 설치 뒤 `demo-runner` 디렉터리가 지워지고 참(REQ4)
  - 떼면 설치한 에이전트용 스킬 디렉터리가 없음(REQ5)
  - 처음 붙일 때 같은 이름의 profile 스킬 디렉터리가 있으면 409, 파일 그대로. `demo-report` 를 설치한 채로 `skills.external_dirs` 경로 아래 `demo-report` 디렉터리를 만들면 다시 설치는 200, profile 의 `demo-report` 는 지워지고 응답에 `name_taken`, 올린 스킬 디렉터리 그대로, 바로 뒤 상태 조회의 `configured` 가 참(REQ6)
  - 붙이기 전후 `.env` 와 `terminal` 설정이 같음(REQ7)
- 기존 바인딩·상태 테스트가 `_bind_skills_installed` 의 새 인자로 통과하는지 함께 돌린다.

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_connector_agent_skills.py'
python3 -m unittest discover -s hermes/tests -p 'test_dashboard_profile_api_connector_binding*.py'
python3 -m unittest discover -s hermes/tests -p 'test_connector_manifest.py'
python3 -m unittest discover -s hermes/tests
node --test test/repo/doc-links.test.ts test/repo/doc-files.test.ts
scripts/dev/check-local.sh --fast
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/connector_agent_skills.py` | 신규 (판정 함수) |
| `hermes/plugins/dashboard-profile-api/connector_binding.py` | 수정 (판정으로 설치, 응답 칸) |
| `hermes/plugins/dashboard-profile-api/connector_status.py` | 수정 (기대 집합) |
| `hermes/plugins/dashboard-profile-api/connector_install.py` | 수정 (호출 인자) |
| `hermes/plugins/dashboard-profile-api/__init__.py` | 수정 (`_agent_skill_plan` 재노출) |
| `hermes/tests/test_connector_agent_skills.py` | 신규 |
| `hermes/tests/test_dashboard_profile_api_connector_binding_install.py` | 수정 |
| `hermes/tests/dashboard_profile_api_support.py` | 수정 (`agent_skill_fixture`) |
| `hermes/connectors/README.md` | 수정 (계약) |
| `hermes/plugins/dashboard-profile-api/README.md` | 수정 (계약) |
| `docs/features/connector.md` | 수정 |
| `docs/features/agent-skill.md` | 수정 |
