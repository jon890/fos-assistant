# Phase 01. 커넥터 보관 파일을 암호화해 쓰고, 옛 평문 파일은 다음 쓰기 때 바꾼다

**Execution profile**: standard

## 목표

연결마다 하나인 커넥터 보관 파일(`<Hermes 루트>/connector-vault/c<연결 번호>.json`)을 AES-256-GCM 으로 암호화해 쓴다.
key 는 대시보드 프로세스의 환경 변수가 가리키는 파일에서 읽는다. key 가 없으면 지금처럼 평문으로 쓴다.

**범위 외**: 바인딩 설치가 profile `.env` 에 쓰는 평문 값. 아래 「의도 메모」 의 까닭으로 이 계획에서 바꾸지 않는다. Control Plane 은 바뀌지 않는다.

## 컨텍스트

**근거 문서**: `backend/docs/adr/ADR-20261008-data-encryption.md` 의 「위협 모델」, `docs/connectors.md` 의 토큰 저장, `hermes/README.md` 의 운영 값 표와 「커넥터」 절, `docs/privacy.md`

- 보관 파일 모듈은 `hermes/plugins/dashboard-profile-api/connector_vault.py` 다
  - `_vault_dir()`, `_vault_path(vault)`(이름은 `VAULT_ID_RE`, 링크 거절), `_read_vault(vault)`(없으면 None, 모양 `{"v": 1, "connector", "values"}` 을 엄격히 검사), `_write_vault(vault, connector, values)`(디렉터리 0700, 파일 0600, `_atomic_private_write`)
  - 요청 처리는 `_connector_vault_request` 이고 PUT, DELETE, import 를 맡는다
- `_read_vault` 를 부르는 다른 곳은 `hermes/plugins/dashboard-profile-api/connector_install.py`(바인딩 설치)와 `hermes/plugins/dashboard-profile-api/connector_run.py`(보관 값으로 도구 호출)다. 둘 다 `values` dict 만 쓴다. 이 계획은 `_read_vault` 의 반환 모양을 바꾸지 않는다
- 운영 값은 대시보드 프로세스의 환경 변수로 받는다. 이름은 `FOS_ASSISTANT_` 로 시작한다(`connector_schema.py` 의 `CONNECTOR_ROOTS_ENV`, `sandbox.py` 의 `SANDBOX_ENV`)
- Hermes 이미지(`v2026.9.24`)는 `cryptography==50.0.0` 을 핵심 의존으로 갖는다. `cryptography.hazmat.primitives.ciphers.aead.AESGCM` 을 쓴다. 새 의존을 더하지 않는다
- 시험은 `hermes/tests/test_dashboard_profile_api_connector_vault.py` 와 `hermes/tests/dashboard_profile_api_support.py` 의 `vault()` 도우미를 따른다. 보관 파일을 직접 읽는 시험이 `hermes/tests/test_connector_call.py`, `hermes/tests/test_dashboard_profile_api_env.py` 에도 있다

## 의도 메모

- key 파일 모양은 Control Plane 의 KEK 파일과 같다. 한 줄에 `<id>:<base64 32바이트>`, 빈 줄과 `#` 줄은 건너뛴다. 활성 id 는 따로 받는다. 같은 파서를 두 언어로 두지만 모양이 같아야 운영이 한 절차로 만든다. 값은 Control Plane KEK 와 다른 key 를 쓴다. Hermes 컨테이너가 Control Plane KEK 를 갖지 않게 하기 위해서다
- 암호화한 파일은 `{"v": 2, "connector": "<id>", "key_id": "<id>", "sealed": "v1.<IV>.<암호문과 태그>"}` 다. `sealed` 를 풀면 지금의 `values` JSON 이다. AAD 는 `connector-vault:<vault>:<connector>` 라 파일을 다른 연결 이름으로 옮기면 풀리지 않는다
- 읽기는 `v` 가 1 이면 평문으로, 2 면 풀어서 읽는다. 쓰기는 key 가 있으면 늘 2 로 쓴다. 따로 옮기는 배치는 두지 않는다. 운영이 key 를 넣은 뒤 연결을 한 번씩 다시 저장하거나 import 하면 바뀐다. 남은 v1 파일 수를 기동 로그에 남긴다
- key 가 없는데 v2 파일을 만나면 그 연결의 값을 읽지 못한 것으로 보고 지금의 「모양이 틀림」 과 같은 예외를 낸다. 값과 key 를 예외와 로그에 싣지 않는다
- **profile `.env` 는 평문으로 남는다.** Hermes 는 커넥터 MCP 서버를 profile 의 `.env` 를 읽어 띄운다. 실행할 때 복호화하는 감싸는 명령을 두면 그 명령이 key 를 같은 컨테이너에서 읽어야 하므로, `.env` 를 읽을 수 있는 사람은 key 도 읽는다. 얻는 것은 profile 디렉터리만 담은 백업을 막는 것뿐인데, 그 백업은 운영이 `.env` 를 빼는 것으로 막을 수 있다. 그래서 바꾸지 않고 `docs/privacy.md` 와 ADR 의 「못 막는 것」 에 적는다

## 작업 항목

### 1. `hermes/plugins/dashboard-profile-api/connector_vault.py`

- 상수 `VAULT_KEY_FILE_ENV = "FOS_ASSISTANT_VAULT_KEY_FILE"`, `VAULT_ACTIVE_KEY_ENV = "FOS_ASSISTANT_VAULT_ACTIVE_KEY_ID"` 를 둔다
- `_vault_keys()` 는 두 환경 변수를 읽어 `(active_id, {id: bytes})` 를 돌려준다. 둘 다 비면 `(None, {})` 다. 하나만 있거나 모양이 틀리면 `ValueError` 다. 프로세스 안에서 한 번만 읽어 둔다
- `_read_vault` 는 v1 과 v2 를 모두 받는다. 반환 값은 지금처럼 `{"v": 1, "connector", "values"}` 모양의 dict 로 맞춘다. 부르는 쪽을 바꾸지 않기 위해서다
- `_write_vault` 는 key 가 있으면 v2 로 쓴다

### 2. 운영 값 문서 `hermes/README.md`

운영 값 표에 두 환경 변수를 더한다. 비우면 평문으로 쓴다는 것, key 를 잃으면 연결을 다시 등록해야 한다는 것, Control Plane KEK 와 다른 key 를 쓴다는 것을 적는다.

### 3. 시험 `hermes/tests/test_dashboard_profile_api_connector_vault.py`

- key 를 준 채 PUT 하면 파일의 `v` 가 2 이고 파일 글에 칸 값이 없다. 같은 연결로 도구를 부르는 경로(`_read_vault`)는 원래 값을 받는다
- v1 평문 파일이 있으면 그대로 읽히고, 다음 PUT 뒤 v2 가 된다
- 파일을 다른 연결 이름으로 복사하면 읽기가 실패하고 예외 메시지에 값이 없다
- key 없이 v2 파일을 읽으면 실패하고 503 을 낸다
- key 가 없으면 지금처럼 v1 로 쓴다(기존 시험이 그대로 통과한다)

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_dashboard_profile_api_connector_vault.py'
python3 -m unittest discover -s hermes/tests
node scripts/check-file-length.mjs
```

셋 다 실패 없이 끝난다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/connector_vault.py` | 수정 |
| `hermes/tests/test_dashboard_profile_api_connector_vault.py` | 수정 |
| `hermes/README.md` | 수정 |
| `docs/privacy.md` | 수정 |
| `backend/docs/adr/ADR-20261008-data-encryption.md` | 수정 |
