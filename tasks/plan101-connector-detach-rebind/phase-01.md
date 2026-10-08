# Phase 01. 뗀 서버 이름을 다시 붙이면 재시작을 요구한다

**Execution profile**: standard

## 목표

떼고 곧바로 같은 이름을 붙일 때 gateway 가 옛 연결을 계속 쓰지 않도록 재시작 대기로 둔다.

**범위 외**: Hermes core, backend 상태 전이, 운영 배포와 gateway 접속, 새 의존성.

## 컨텍스트

`hermes/plugins/dashboard-profile-api/connector_binding.py` 의 `_connector_bind_config` 는 뗀 서버를 `detached` 에 기록한다. 붙이기의 `restart` 는 `previous` 가 있을 때만 참이어서 떼기 직후 같은 이름을 붙이면 거짓이다. gateway 는 이름만 비교한다.

**근거 문서**: `docs/backend/connector-install.md` 의 「바인딩의 반영 맞추기」.

## 의도 메모

- 기존 뗀 기록을 재사용한다. 저장 형식과 요청·응답 필드는 바꾸지 않는다.
- 같은 커넥터를 같은 이름으로 붙인 뒤 그 뗀 기록을 지우는 동작은 유지한다.
- 새 이름은 계속 `reload_pending` 이다. 기록에 남은 옛 이름과 다른 이름으로 붙여도 새 이름으로 판정하고 옛 기록을 보존한다.

## 작업 항목

### 1. 재시작 판정과 설명

`hermes/plugins/dashboard-profile-api/connector_binding.py` 의 붙이기 분기에서 `name in detached.values()` 를 기존 `restart` 조건과 OR 한다. 기록을 지우기 전에 판정한다. 함수 설명과 한국어 주석에 gateway 가 옛 연결을 쥐고 있을 수 있음을 적는다.

`docs/backend/connector-install.md` 의 응답 표와 「바인딩의 반영 맞추기」 및 뒤의 요약에서 뗀 이름의 재시작 요구와 probe 의 한계를 일치시킨다. 계획 단계에서 갱신한 반영 맞추기 절도 이 phase 커밋에 담는다.

### 2. 회귀 테스트

`hermes/tests/test_dashboard_profile_api_connector_binding_detach.py` 에 떼기 직후 동일 이름 붙이기를 검사한다. `changed=true`, `restart_required=true`, `reload_pending=false`, 뗀 기록 삭제를 확인한다. 최초 새 이름 붙이기 및 다른 커넥터의 새 이름 붙이기는 `restart_required=false`, `reload_pending=true` 임을 확인한다. 기록이 지워진 뒤 동일 요청의 재시도는 모든 변경 칸이 거짓인지 확인한다.

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_dashboard_profile_api_connector_binding*.py'
scripts/quality.sh check
scripts/check-public-safe.sh
```

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `hermes/plugins/dashboard-profile-api/connector_binding.py` | 수정 |
| `hermes/tests/test_dashboard_profile_api_connector_binding_detach.py` | 수정 |
| `docs/backend/connector-install.md` | 수정 |
