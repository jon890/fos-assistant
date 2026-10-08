# Phase 01. 바뀐 실행 정의로 바인딩 재설치

**Execution profile**: standard

## 목표

command, args, env 가 바뀐 커넥터의 바인딩 설치를 거절하지 않고 보관 파일의 값으로 다시 설치한다.

**범위 외**: MCP 서버 이름 변경 자동 이관, 옛 isolated 설치, API 및 DB 형식 변경, 홈서버 접속과 배포.

## 컨텍스트

**근거 문서**: `docs/backend/connector-install.md` 의 「바인딩의 반영 맞추기」.
`_connector_bind_config` 는 `_connector_state(..., plugin)` 로 현재 manifest 를 비교해서 쓰기 전에 ValueError 를 낸다.
`_connector_state(value)` 는 바인딩 기록의 모양만 확인한다. 실제 서버 정의가 소유 기록과 같은지는 설치 함수가 따로 확인한다.
backend 의 `ConnectorBindingInstalls.sendAgain` 은 연결 확인과 관리자 반영 완료에서 같은 PUT 설치를 보낸다.

## 의도 메모

- 실행과 probe 의 manifest 확인을 완화하지 않는다. 설치만 낡은 소유 기록을 받는다.
- 기존 원자적 쓰기와 rollback, 다른 설정의 충돌 확인을 유지한다.
- 비밀값 유출을 막기 위해 로그에는 고정된 칸 이름(command, args, env, mcp_server), 단계, id, 예외 클래스만 남긴다.

## 작업 항목

### 1. 회귀 테스트

새 파일 `hermes/tests/test_dashboard_profile_api_connector_binding_refresh.py` 에 env 추가 후 같은 보관 파일로 다시 붙이는 시험을 먼저 쓴다.
수정 전 503 실패를 확인하고 결과를 보고한다.
command 와 args 교체, 연결 확인과 관리자 반영 완료가 쓰는 반복 PUT 및 probe 확인도 검사한다.
보관 파일, 연결 값, 다른 커넥터 기록, 다른 사용자 profile 이 그대로인지 단언한다.
소유 기록과 실제 서버가 다르면 409 이고 파일이 그대로인지, 같은 정의의 반복 설치가 아무것도 바꾸지 않는지 검사한다.
실패 로그가 단계, id, 고정된 칸 이름을 싣고 토큰, env 값, 예외 본문을 싣지 않는지 검사한다.

### 2. 설치와 로그

`hermes/plugins/dashboard-profile-api/connector_binding.py` 에서 설치가 쓰는 기록 검증을 형식 검증으로 바꾼다.
기존 서버와 소유 기록 비교, env 소유권 검사, restart_required 계산과 rollback 은 유지한다.
`hermes/plugins/dashboard-profile-api/connector_state.py` 에 실행 정의의 어긋난 칸 이름을 반환하는 작은 함수를 두고 설치 로그에서 쓴다.
`hermes/plugins/dashboard-profile-api/connector_install.py` 의 409/503 실패에 단계와 id, 예외 클래스만 기록한다. 예외 본문이나 stack trace 는 찍지 않는다.
단계는 manifest, vault, bind, isolated, status 등 고정된 이름이다.

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_dashboard_profile_api_connector_binding*.py' -v
python3 -m unittest discover -s hermes/tests -p 'test_dashboard_profile_api_connector_install*.py' -v
node scripts/check-file-length.mjs
```

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `hermes/plugins/dashboard-profile-api/connector_binding.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_state.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_install.py` | 수정 |
| `hermes/tests/test_dashboard_profile_api_connector_binding_refresh.py` | 신규 |
| `docs/backend/connector-install.md` | 수정 |
| `hermes/README.md` | 수정 |
