# Phase 03. 범용 커넥터의 공통 계약 검사와 소유자

**Execution profile**: standard

## 목표

`hermes/connectors/` 아래의 커넥터가 모두 계약을 지키는지 보는 공통 검사를 만든다.
커넥터를 더하면 고칠 것 없이 검사 대상이 된다. 기여자가 무엇을 빠뜨렸는지 CI 가 알려 준다.

**범위 외**: 위험도 분류가 맞는지의 판단. 그것은 리뷰가 본다.

## Blocked 조건

- `hermes/connectors/gmail/connector.json` 이 없다 → `PHASE_BLOCKED: phase 02 가 끝나지 않았다` 출력 후 종료

## 컨텍스트

- **검사가 보는 항목은 `docs/connector-authoring.md` 의 「공통 검사」 표가 갖는다.** 표의 줄마다 검사 하나를 만든다. 표와 다르게 만들지 않는다. 달라져야 하면 멈추고 알린다
- 결정은 `docs/adr/ADR-059-범용-커넥터는-이-저장소의-hermes-connectors-에-두고-저장소가-유지보수한다.md` 다
- 대시보드 plugin 의 manifest 검증은 `hermes/plugins/dashboard-profile-api/__init__.py` 의 `_load_connector(connector_id, entry)` 다. `entry` 는 `{"root": Path, "command": 실행 파일 경로나 None, "env": {}}` 모양이다. plugin 을 Hermes 없이 읽는 방법은 `hermes/tests/test_connector_manifest.py` 가 갖는다(Hermes 모듈을 가짜로 끼운다). 그 파일의 도움 함수를 `import test_connector_manifest as base` 로 다시 쓴다
- 서버를 자식으로 띄워 도구를 읽는 선례는 `hermes/tests/test_connector_call.py` 다. `mcp.ClientSession`, `mcp.StdioServerParameters`, `mcp.client.stdio.stdio_client` 를 쓴다. 도구의 읽기 전용 표시는 `tool.annotations.read_only_hint` 다
- 검사는 CI 의 `hermes` job(`python -m unittest discover -s hermes/tests -v`)과 `scripts/check-local.sh` 의 `check_hermes` 가 돌린다. 새 job 을 만들지 않는다
- 저장소에 `.github/CODEOWNERS` 가 아직 없다

**근거 문서**: `docs/connector-authoring.md` 의 「공통 검사」, `docs/adr/ADR-059-범용-커넥터는-이-저장소의-hermes-connectors-에-두고-저장소가-유지보수한다.md`

## 의도 메모

- 커넥터 목록을 검사 코드에 적지 않는다. 디렉터리를 찾아 돈다. 커넥터가 하나도 없으면 검사가 실패한다. 경로가 바뀌어 아무것도 보지 않는 검사가 통과하는 것을 막는다
- 서버를 띄울 때 외부를 부르지 않는다. `initialize` 와 `tools/list` 만 한다. env 에는 칸마다 뜻 없는 글을 준다
- import 검사는 실행하지 않고 `ast` 로 읽는다
- 검사가 실패할 때 어느 커넥터의 무엇이 틀렸는지 글로 보인다. `subTest(connector=...)` 를 쓴다

## 작업 항목

### 1. `hermes/tests/test_connectors_contract.py`

`hermes/connectors/` 바로 아래의 디렉터리(이름이 `.` 으로 시작하지 않는 것)를 모두 찾아 커넥터마다 `docs/connector-authoring.md` 「공통 검사」 표의 줄을 본다.

| 표의 줄 | 보는 방법 |
| --- | --- |
| manifest 검증 통과 | `_load_connector(<디렉터리 이름>, {"root": 경로, "command": sys.executable, "env": {}})` 가 예외 없이 끝난다 |
| 이름 셋이 같다 | 디렉터리 이름, `connector.json` 의 `id`, `.claude-plugin/plugin.json` 의 `name` |
| `schema` 가 `2` | `connector.json` |
| 서버의 도구와 선언이 같다 | `.mcp.json` 의 첫 인자가 가리키는 파일을 `sys.executable` 로 띄워 `tools/list` 를 읽는다. 이름의 집합이 `tools` 의 키와 같다. 다르면 빠진 이름과 남는 이름을 글에 싣는다 |
| `READ` 만 읽기 전용 | 위에서 읽은 도구의 `annotations.read_only_hint is True` 인 이름의 집합이 `risk: READ` 인 이름의 집합과 같다 |
| `title` | `risk` 가 `READ` 가 아닌 도구에 비지 않은 `title` 이 있다 |
| 닫힌 위험도 | `risk` 가 `DESTRUCTIVE` 나 `FINANCIAL` 인 선언이 없다 |
| 비밀 칸 | `fields[].env` 를 대문자로 바꾼 글에 `TOKEN`, `SECRET`, `PASSWORD`, `KEY` 가 들었으면 `secret` 이 참이다 |
| 운영 값 | `operator_env` 와 `operator_secrets` 가 없거나 빈 목록이다 |
| 실행과 의존성 | `.mcp.json` 서버의 `command` 가 `python3` 이다. 그 커넥터 디렉터리의 `*.py` 를 모두 `ast.parse` 해 `import` 와 `from ... import` 의 맨 앞 이름이 `sys.stdlib_module_names` 나 `{"mcp", "mcp_types", "anyio"}` 에 있다. 상대 import 는 받는다 |
| 스킬 | `plugin.json` 의 `skills`(없으면 `./skills`) 아래에 `SKILL.md` 가 하나 이상 있다 |
| 검사와 문서와 소유자 | `hermes/tests/connectors/test_<id 의 - 를 _ 로>.py`, `docs/connectors/<id>.md` 가 있다. `.github/CODEOWNERS` 에 `/hermes/connectors/<id>/` 로 시작하고 소유자가 하나 이상 붙은 줄이 있다 |

- 커넥터가 하나도 없으면 실패하는 검사를 하나 둔다
- **검사가 실제로 잡는지 본다.** 임시 디렉터리에 `hermes/connectors/gmail/` 을 복사해 하나씩 망가뜨린 뒤 같은 검사 함수가 위반을 내는지 본다. 검사 함수는 `check_connector(root: Path, repo: Path) -> list[str]` 처럼 위반 글의 목록을 돌려주게 만들어 실제 커넥터와 망가뜨린 복사본에 같이 쓴다. 망가뜨리는 것은 적어도 넷이다: 선언에서 도구 하나를 뺀다, 쓰기 도구의 `title` 을 뺀다, secret 칸의 `secret` 을 거짓으로 바꾼다, 서버에 `import requests` 를 더한다

### 2. `.github/CODEOWNERS`

```
# 범용 커넥터는 디렉터리마다 소유자를 둔다 (docs/adr/ADR-059). 소유자는 서비스의 API 가 바뀌면 고치고 실제 계정으로 확인한다.
/hermes/connectors/gmail/ @jon890
/hermes/tests/connectors/test_gmail.py @jon890
/docs/connectors/gmail.md @jon890
```

다른 경로의 줄은 더하지 않는다. 저장소 전체의 소유자를 정하는 것은 이 계획의 범위가 아니다.

## 검증

```bash
# cwd: 저장소 root
python3 -m unittest discover -s hermes/tests -v 2>&1 | grep -c "test_connectors_contract"
python3 -m unittest discover -s hermes/tests
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
scripts/quality.sh check
```

- 첫 명령은 0 보다 큰 수를 낸다
- 나머지는 종료 코드 0 이다
- `test/unit/` 에 `.github/` 의 파일 목록이나 workflow 를 단언하는 검사가 있다(`review-workflow.test.ts`, `pr-risk-labels.test.ts`). `CODEOWNERS` 를 더해 그 검사가 깨지면 그 검사의 기대값을 이 phase 에서 고치고 변경 파일에 더한다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/tests/test_connectors_contract.py` | 신규 |
| `.github/CODEOWNERS` | 신규 |
