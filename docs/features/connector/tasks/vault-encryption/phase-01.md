# Phase 01. 보관 파일을 암호문으로 쓰고 읽으며, 기동 때 남은 파일을 활성 key 로 다시 쓴다

**Execution profile**: standard

## 목표

대시보드 plugin 이 연결 보관 파일을 별도 key 의 AES-256-GCM 으로 쓰고 읽게 한다. 서버 디스크의 보관 파일에 칸 값의 평문이 남지 않게 하려는 것이다.

**범위 외**: profile `.env` 사본, 실행기, `restart_required`(PR2 의 REQ6 부터 REQ8). Control Plane 과 Hermes core 는 바꾸지 않는다. 문서는 phase 02 가 맡는다.

## 컨텍스트

**요구사항**: REQ1, REQ2, REQ3, REQ4

**근거 문서**: `docs/features/connector/spec.md`, `docs/features/connector/design.md`, `hermes/docs/adr/ADR-20261103-connector-vault-key.md`

- 보관 파일 모듈은 `hermes/plugins/dashboard-profile-api/connector_vault.py` 다. `_vault_dir`, `_vault_path`, `_read_vault`, `_write_vault`, `_connector_vault_request` 가 있다
- `_read_vault` 를 부르는 곳은 `connector_run.py`(`call`, `tools`)와 `connector_install.py`(바인딩 설치)다. 둘 다 반환의 `connector` 와 `values` 만 쓴다
- 쓰기는 `common._atomic_private_write` 로 한다(같은 디렉터리의 `.connector-` 임시 파일을 `os.replace`)
- plugin 진입점은 `__init__.py` 의 `register(ctx)` 다. provider 를 등록한 뒤 열린 경로를 로그로 남긴다. `__init__.py` 는 모듈의 이름을 다시 내보낸다
- 암호문 모양은 Control Plane 의 `backend/src/main/java/com/bifos/assistant/crypto/domain/AesGcm.java` 와 같다. key 파일 모양은 `backend/src/main/java/com/bifos/assistant/crypto/infra/FileKeyEncryptionKeys.java` 와 같다
- `cryptography.hazmat.primitives.ciphers.aead.AESGCM` 을 쓴다. Hermes core 가 고정한 의존이고 `mcp` 가 `pyjwt[crypto]` 로 함께 설치해 CI 에도 있다
- 테스트 기반은 `hermes/tests/dashboard_profile_api_support.py` 의 `ProfileApiRouteTest` 다. `bind_fixture()` 가 `self.hermes_root` 를 만들고 `vault()` 가 `PUT`·`DELETE /api/connector-vault` 를 부른다

## 의도 메모

- key 를 캐시하지 않는다(설계 DECISION2). 테스트는 `mock.patch.dict(os.environ, ...)` 로 상태를 바꾼다
- 쓰기 전 소유 확인은 풀지 않고 `connector` 만 읽는다(설계 DECISION4)
- 예외 메시지와 로그에 칸 값, key, 경로를 넣지 않는다. 로그에는 예외 클래스 이름과 보관 파일 이름(`c<번호>`)만 남긴다
- 이관은 `register` 안에서 동기로 돌고 어떤 예외도 밖으로 내지 않는다

## 작업 항목

### 1. `hermes/plugins/dashboard-profile-api/connector_vault_key.py` 신규

- 상수 `VAULT_KEY_FILE_ENV`, `VAULT_ACTIVE_KEY_ENV`, `VAULT_KEY_REQUIRED_ENV`(설계의 환경 변수 이름)
- `VaultKeys`(frozen dataclass): `state`(`"ready"`, `"absent"`, `"invalid"`), `active_id`, `keys`(id → 32바이트), `required`
- `_vault_keys() -> VaultKeys`: 부를 때마다 환경 변수와 파일을 읽는다
- `_vault_key_file_shared() -> bool`: key 파일을 그룹이나 다른 사용자가 읽을 수 있는가. 기동 이관이 경고에 쓴다
- `_seal(key: bytes, plain: bytes, aad: bytes) -> str`, `_open(key: bytes, sealed: str, aad: bytes) -> bytes`: `v1.<IV>.<암호문과 태그>`, 패딩 없는 base64url. 실패는 `ValueError`

### 2. `hermes/plugins/dashboard-profile-api/connector_vault.py` 수정

- `_vault_aad(vault, connector) -> bytes` 는 `connector-vault:<vault>:<connector>`
- `_parse_vault(vault, raw: bytes) -> dict`: v1 과 v2 의 모양을 엄격히 검사한다. 풀지 않는다
- `_read_vault`: v1 은 그대로, v2 는 `key_id` 의 key 로 풀어 `{"v", "connector", "values"}` 를 돌려준다. key 가 없거나 풀지 못하면 `ValueError`
- `_write_vault`: 소유 확인은 `_parse_vault` 의 `connector` 로 한다. key 가 준비되면 v2, 「key 필수」 면 `VaultKeyUnavailable` 을 낸다, 아니면 v1 과 경고 로그
- `_connector_vault_request`: `VaultKeyUnavailable` 을 503 으로 답하고 key 상태만 로그에 남긴다

### 3. `hermes/plugins/dashboard-profile-api/connector_vault_migration.py` 신규와 `__init__.py` 수정

- `_migrate_vault_files() -> dict`: 설계의 「기동 이관」 표대로 분류하고 다시 쓴다. 다시 쓴 파일은 다시 읽어 값을 대조하고 다르면 원래 바이트로 되돌린다. 수를 담은 dict 를 돌려주고 info 한 줄, 남은 이름 warning 한 줄을 남긴다
- `register` 가 provider 를 등록한 뒤 `_migrate_vault_files()` 를 부른다. 예외는 잡아 클래스 이름만 남긴다
- `__init__.py` 가 새 이름을 다시 내보낸다

### 4. `hermes/tests/hermes_contract.py` 수정

- `THIRD_PARTY_PACKAGES` 에 `cryptography` 를 더하고 Hermes core 가 고정한 의존이라는 주석을 단다

### 5. 테스트 `hermes/tests/test_connector_vault_encryption.py`, `hermes/tests/test_connector_vault_migration.py` 신규

- 인수 검증 계획(`verification-plan.md`)의 PR 전 행을 테스트로 옮긴다
- 실패하는 모든 사례에서 칸 값과 key 의 base64 가 응답과 로그(`assertLogs`)에 없는지 본다

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_connector_vault_*.py'
python3 -m unittest discover -s hermes/tests -p 'test_dashboard_profile_api_connector_*.py'
python3 -m unittest discover -s hermes/tests -p 'test_connector_call.py'
python3 -m unittest discover -s hermes/tests -p 'test_hermes_contract.py'
node scripts/checks/check-file-length.mjs
```

모두 실패 없이 끝난다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/connector_vault_key.py` | 신규 |
| `hermes/plugins/dashboard-profile-api/connector_vault.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_vault_migration.py` | 신규 |
| `hermes/plugins/dashboard-profile-api/__init__.py` | 수정 |
| `hermes/tests/hermes_contract.py` | 수정 |
| `hermes/tests/test_connector_vault_encryption.py` | 신규 |
| `hermes/tests/test_connector_vault_migration.py` | 신규 |
