# Phase 02. 보관 파일 암호화를 문서에 반영한다

**Execution profile**: standard

## 목표

보관 파일이 암호문이 되고 운영 값 셋이 생긴 것을 책임 문서에 적는다. key 를 잃거나 되돌릴 때 연결을 다시 등록한다는 것과 Control Plane KEK 와 다른 key 라는 것만 적는다(REQ5).

**범위 외**: profile `.env` 사본이 사라진다는 서술(PR2). key 파일의 자리, 만드는 명령, 컨테이너 이름은 어디에도 적지 않는다.

## 컨텍스트

**요구사항**: REQ5

**근거 문서**: `docs/features/connector/spec.md`, `docs/features/connector/design.md`, `hermes/docs/adr/ADR-20261103-connector-vault-key.md`

- 운영 값 표는 `hermes/README.md` 의 「운영 값」 이다
- 보관 파일의 경로, 모양, 경로 검사는 `hermes/plugins/dashboard-profile-api/README.md` 의 「플러그인」 이 갖는다
- 기능 흐름은 `docs/features/connector.md` 의 「보관 파일」, 사용자에게 알리는 비밀값 보관은 `docs/privacy.md` 다
- plugin 이 기대는 Hermes 의존은 `hermes/docs/hermes-contract.md` 다
- 모듈 배치는 `hermes/docs/code-architecture.md` 의 모듈 표다

## 의도 메모

- 공개 저장소다. 환경 변수 이름은 적어도 되고, 값과 파일 자리는 `fos-home-infra` 를 가리킨다
- 운영 절차(배포 뒤 로그 확인)는 확인할 것만 적고 명령은 적지 않는다

## 작업 항목

### 1. `hermes/README.md`

「운영 값」 표에 세 환경 변수를 더한다. 비우면 평문으로 쓴다는 것, 「key 필수」 의 뜻, key 를 잃으면 연결을 다시 등록한다는 것, Control Plane KEK 와 다른 key 라는 것.

### 2. `hermes/plugins/dashboard-profile-api/README.md`

경로 표의 `PUT /api/connector-vault` 에 503 을 더하고, 「보관 파일」 단락을 v2 모양과 기동 이관으로 고친다.

### 3. `docs/features/connector.md`, `docs/privacy.md`, `hermes/docs/hermes-contract.md`, `hermes/docs/code-architecture.md`, `hermes/docs/adr/INDEX.md`

- `connector.md` 의 「보관 파일」 에 암호화와 key 를 잃을 때의 처리를 한 단락 더한다
- `privacy.md` 의 보관 파일 서술을 「암호화한 보관 파일」 로 고친다. `.env` 사본은 그대로 남는다고 적는다
- `hermes-contract.md` 에 plugin 이 `cryptography` 에 기댄다는 확인 항목을 더한다
- `code-architecture.md` 모듈 표에 새 모듈 둘을 더한다
- `hermes/docs/adr/INDEX.md` 에 ADR-20261103 / connector-vault-key 를 더한다

## 검증

```bash
node --test test/unit/doc-files.test.ts test/unit/adr-index.test.ts test/unit/doc-code-references.test.ts
scripts/checks/check-public-safe.sh
```

모두 실패 없이 끝난다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/README.md` | 수정 |
| `hermes/plugins/dashboard-profile-api/README.md` | 수정 |
| `docs/features/connector.md` | 수정 |
| `docs/privacy.md` | 수정 |
| `hermes/docs/hermes-contract.md` | 수정 |
| `hermes/docs/code-architecture.md` | 수정 |
| `hermes/docs/adr/INDEX.md` | 수정 |
