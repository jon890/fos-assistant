# Phase 01. 실행기와 사본 제거

**Execution profile**: standard

## 목표

로컬 플러그인의 바인딩이 칸 값을 profile `.env` 에 두지 않고, 실행기가 띄울 때 보관 파일에서 풀어 자식 env 에만 넣게 한다.

**범위 외**: 원격 플러그인의 비밀값(DECISION8), 사용자 브라우저 중계 주소의 접근 표식(DECISION11), Control Plane.

## 컨텍스트

**요구사항**: REQ1, REQ6, REQ7, REQ8

**근거 문서**: `docs/features/connector/spec.md`, `docs/features/connector/design.md` 의 「PR2: 실행기와 사본 제거」

## 의도 메모

- 기동 이관은 바인딩 설치를 다시 돌리지 않고 정의, 소유 기록, 이름 대응, `.env` 줄만 바꾼다. 설치의 다른 검사가 거절해 풀 수 있는 바인딩의 사본이 남지 않게 한다.
- 보관 파일 리비전은 파일 stat 의 해시다. v1 평문의 내용 해시를 정의에 남기지 않는다.

## 작업 항목

### 1. `connector_vault_format.py` 로 보관 파일 모양과 풀기를 옮긴다

이동만 하는 커밋이다.

### 2. `connector_launch.py`, `connector_launcher.py`

실행기 정의를 만들고 알아보고 펼친다. 실행기 스크립트가 보관 파일을 풀어 `execve` 한다.

### 3. 비교, 설치, 직접 실행, `PUT /api/env`

`_server_matches`, `_connector_state`, `_connector_bind_config`, `connector_run.py`, `connector_prepare.py`, `connector_snapshot.py`, `env.py`.

### 4. `connector_binding_migration.py`

기동 이관을 `register` 에서 보관 파일 이관 뒤에 돌린다.

## 검증

```bash
python3 scripts/checks/run-hermes-python-tests.py -j 4
scripts/dev/check-local.sh --fast
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/connector_vault_format.py` | 신규(이동) |
| `hermes/plugins/dashboard-profile-api/connector_launch.py` | 신규 |
| `hermes/plugins/dashboard-profile-api/connector_launcher.py` | 신규 |
| `hermes/plugins/dashboard-profile-api/connector_binding_migration.py` | 신규 |
| `hermes/tests/test_connector_launcher.py` | 신규 |
| `hermes/tests/test_connector_binding_migration.py` | 신규 |
