# Phase 01. 길이 상한에 닿은 파일에서 코드를 옮겨 자리를 만든다

**Execution profile**: standard

## 목표

뒤 phase 가 껐다 켜기를 더할 자리를 만든다. 동작은 바꾸지 않고 코드만 옮긴다.
`hermes/plugins/dashboard-profile-api/connector_binding.py` 는 400줄(파이썬 상한 400), `backend/src/main/java/com/bifos/assistant/hermes/infra/HttpHermesConnectorClient.java` 는 498줄(Java 상한 500)이라 그대로는 한 줄도 더할 수 없다. 상한은 `scripts/checks/check-file-length.mjs` 가 갖는다.

**범위 외**: 껐다 켜기 동작(phase 02 부터). 이 phase 의 커밋은 이동만 담는다(AGENTS.md 「머지는 PR 로 한다」 의 이동 커밋과 동작 커밋 나누기).

## 컨텍스트

**요구사항**: REQ1 (뒤 phase 의 준비)

**근거 문서**: `docs/features/connector/spec.md`, `docs/features/connector/design.md`

- `_connector_bind_config` 의 끝부분(지금 340줄 `backup = profile_dir / "connector-backups" / ...` 부터 388줄 `raise` 까지)이 백업을 뜨고 `targets` 를 원자적으로 쓰며 실패하면 `_restore_binding_files` 로 되돌린다. 이 덩어리는 `config_path`, `state_path`, `tool_map_path`, `detached_path`, `plugin_values`, `skills_dir`, `profile_dir`, `originals`, `targets` 만 쓴다.
- `HttpHermesConnectorClient` 의 private static `withOwnerBrowser`(133줄), `arguments`(228줄)는 인스턴스 상태와 다른 private 도우미를 쓰지 않는다. `callResult` 는 클래스 안 여러 곳이 쓰는 `requiredBoolean`, `text` 를 불러 옮기지 않는다.

## 의도 메모

- 함수와 메서드의 본문은 글자 그대로 옮긴다. 이름 앞의 `_` 와 주석도 유지한다.
- 옮긴 쪽에서 쓰는 상수와 import 만 새 파일에 둔다. 기존 import 가 쓰이지 않게 되면 지운다.

## 작업 항목

### 1. `hermes/plugins/dashboard-profile-api/connector_binding_write.py` 신규

`_write_binding_files(profile_dir, originals, targets, plugin_values, skills_dir, backup_paths)` 를 만들고 위 덩어리를 그대로 옮긴다. `backup_paths` 는 지금 백업 반복문이 도는 `(config_path, state_path, tool_map_path, detached_path)` 튜플이다. 안쪽 `shared(path)` 함수와 `written`, `created_dirs` 처리, 예외 때 복원과 디렉터리 정리를 함께 옮긴다. 파일 맨 위에 무엇을 옮겨 왔는지 한 줄 docstring 을 둔다.

### 2. `hermes/plugins/dashboard-profile-api/connector_binding.py` 수정

옮긴 자리에서 `_write_binding_files(...)` 를 부른다. 그 뒤의 `removed_outputs` 정리와 빈 스킬 디렉터리 정리, 반환은 그대로 둔다.

### 3. `backend/src/main/java/com/bifos/assistant/hermes/infra/HermesConnectorBodies.java` 신규

package-private `final class HermesConnectorBodies` 에 `withOwnerBrowser`, `arguments` 두 개만 package-private static 으로 옮긴다. `callResult` 는 옮기지 않는다. 두 메서드가 쓰는 상수(`OWNER_BROWSER`, `MAPPER`)는 새 클래스에 자기 상수로 두고, `HttpHermesConnectorClient` 에서 더 쓰지 않게 된 상수만 지운다. 이 이동으로 `HttpHermesConnectorClient` 가 약 20줄 줄어 phase 03 이 더할 자리(7인자 설치와 답 칸 두 줄, 약 15줄)가 생긴다. 클래스 Javadoc 은 한국어 한 줄이다(rule-52).

### 4. `backend/src/main/java/com/bifos/assistant/hermes/infra/HttpHermesConnectorClient.java` 수정

옮긴 메서드를 `HermesConnectorBodies.<이름>(...)` 으로 부른다.

### 5. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/hermes/infra/HermesConnectorBodiesTest.java` 와 기존 테스트

테스트는 `package com.bifos.assistant.hermes.infra` 다. 새 클래스가 package-private 이라 같은 패키지에 둔다.

옮긴 도우미의 경계를 단위 테스트로 고정한다. `withOwnerBrowser` 는 값이 null 이면 키를 넣지 않고 아니면 `owner_browser` 를 더한다. `arguments` 는 JSON 객체가 아니거나 읽지 못하는 글이면 null 을 돌려준다. 그 밖에 이동만 하므로 `hermes/tests/test_dashboard_profile_api_connector_binding_atomic.py`(쓰기 실패와 복원), `hermes/tests/test_dashboard_profile_api_connector_binding_install.py`, `backend/src/test/java/com/bifos/assistant/hermes/HttpHermesConnectorClientTest.java` 가 바뀌지 않고 통과해야 한다.

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_dashboard_profile_api_connector_binding*.py'
python3 -m unittest discover -s hermes/tests -p 'test_connector_*.py'
cd backend && ./gradlew test --tests '*HttpHermesConnectorClientTest' --tests '*HermesConnectorBodiesTest'
cd backend && ./gradlew checkstyleMain spotlessCheck
node scripts/checks/check-file-length.mjs
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/connector_binding_write.py` | 신규 |
| `hermes/plugins/dashboard-profile-api/connector_binding.py` | 수정 (쓰기 덩어리를 옮김) |
| `backend/src/main/java/com/bifos/assistant/hermes/infra/HermesConnectorBodies.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/infra/HttpHermesConnectorClient.java` | 수정 (static 도우미를 옮김) |
| `backend/src/test/java/com/bifos/assistant/hermes/infra/HermesConnectorBodiesTest.java` | 신규 |
