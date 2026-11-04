# 실행기와 사본 제거 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- |
| REQ6 | key 를 준 v2 보관 파일 두 개와 시험 플러그인 둘을 붙인 관리 profile | 바인딩 설치 뒤 `config.yaml`, `.env` 를 읽고, 정의의 command 와 args 를 기본 env 와 정의 env 만으로 실제 자식 프로세스로 띄운다(`hermes/tests/test_connector_launcher.py`) | 정의와 `.env` 에 칸 값과 key 가 없다. 대역 서버가 원래 값을 받고 `FOS_ASSISTANT_CONNECTOR_VAULT_` 이름은 받지 않는다. 활성 key id 없이 풀린다 | 로컬 Python 단위 테스트 | 커넥터 entry 빌드 | PR 전 |
| REQ6 | 같은 profile | 보관 파일을 지우거나, 다른 플러그인 것으로 바꾸거나, 깨거나, key 파일을 비우거나, 인자를 틀리게 하거나, 실제 command 를 지우고 띄운다 | 78 로 끝나고 stderr 는 `connector-launcher: <이유>` 한 줄이며 값, 경로, key 가 없다 | 로컬 Python 단위 테스트 | 없음 | PR 전 |
| REQ6 | 실행기 정의의 바인딩, `.env` 에 칸 값 없음 | 직접 실행(`test_connector_execute.py`), prepare 와 승인 실행(`test_connector_guard_transport.py`), 스냅샷(`test_connector_snapshot.py`)을 부른다 | 자식이 보관 파일 값을 받는다. 보관 파일을 풀지 못하면 자식을 띄우지 않는다. scope 값이 바뀌면 409 다 | 로컬 Python 단위 테스트 | 없음 | PR 전 |
| REQ6 | 관리 profile | `PUT /api/env` 로 칸 key 를 쓴다(`test_dashboard_profile_api_env.py`) | 붙이기 전과 뒤 모두 400 이고 `.env` 가 바뀌지 않는다 | 로컬 Python 단위 테스트 | 없음 | PR 전 |
| REQ6 | 실행기 정의와 옛 정의 | `_server_matches` 와 `_connector_state` 에 넣는다(`test_connector_launcher.py`) | 둘 다 맞고, 경로나 칸 대응이 바뀌거나 칸 env 가 정의에 있으면 맞지 않으며, 다른 보관 파일을 여는 정의는 기록으로 받지 않는다 | 로컬 Python 단위 테스트 | 없음 | PR 전 |
| REQ6 | 옛 사본이 남은 바인딩 | 떼기(`test_dashboard_profile_api_connector_binding_detach.py`) | 실행기 정의와 남은 사본 줄이 지워지고 운영자의 줄은 남는다 | 로컬 Python 단위 테스트 | 없음 | PR 전 |
| REQ7 | 옛 정의와 사본을 가진 바인딩, 풀지 못하는 바인딩, 원격 바인딩, 낡은 경로의 실행기 정의, 운영자가 바꾼 정의 | `_migrate_bindings` 를 두 번 돌린다(`test_connector_binding_migration.py`) | 풀 수 있는 옛 바인딩과 낡은 정의는 실행기 정의가 되고 사본이 없다. 나머지는 그대로다. 로그에 수와 「gateway 재시작이 필요하다」 가 있고 값과 profile 이름이 없다. 두 번째는 파일이 바뀌지 않는다 | 로컬 Python 단위 테스트 | 없음 | PR 전 |
| REQ8 | 실행기 바인딩 | 같은 값과 다른 값으로 PUT 한 뒤 다시 설치한다(`test_connector_launcher.py`) | 같은 값은 파일과 정의가 그대로이고 `restart_required: false`, 다른 값은 `restart_required: true` 이고 실행기가 새 값을 넘긴다 | 로컬 Python 단위 테스트 | 없음 | PR 전 |
| REQ1 | 원격 플러그인 | 바인딩 설치(`test_dashboard_profile_api_connector_binding_install.py`) | 원격은 `.env` 에 쓰고 정의는 주소와 헤더다 | 로컬 Python 단위 테스트 | 없음 | PR 전 |
| REQ6, REQ7 | 운영 배포 | 배포 → 기동 로그의 「바인딩 이관」 줄 → gateway 재시작 → stdio 연결마다 도구 호출 왕복 | 남은 사본 0, 도구 호출 성공 | 운영(`fos-home-infra` 절차) | 코디네이터 | 머지 뒤 |
