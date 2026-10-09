# hermes

Hermes 에 설치하는 plugin 과 profile 틀, 범용 커넥터다. Hermes core 는 이 저장소에 없고 고치지 않는다.

| 문서 | 언제 보는지 |
| --- | --- |
| [`hermes/docs/code-architecture.md`](docs/code-architecture.md) | plugin 과 커넥터를 어디에 두고 어떤 순서로 배포하는지 |
| [`hermes/docs/hermes-contract.md`](docs/hermes-contract.md) | 우리가 기대는 Hermes 의 동작. Hermes 를 올릴 때 다시 확인할 목록 |
| [`hermes/plugins/fos-ctx/README.md`](plugins/fos-ctx/README.md) | profile plugin 이 MCP 호출에 붙이는 맥락과 session 등록 계약 |
| [`hermes/plugins/dashboard-profile-api/README.md`](plugins/dashboard-profile-api/README.md) | 대시보드 plugin 이 여는 경로와 커넥터 운영 목록 |
| [`hermes/connectors/README.md`](connectors/README.md) | 범용 커넥터를 만들 때 |
| [`hermes/README.md`](README.md) | 설치 묶음과 운영 값, 검사 |
| [`hermes/docs/adr/INDEX.md`](docs/adr/INDEX.md) | 이 모듈에 관한 결정 |

## 지켜야 할 것

- **Hermes core 를 고치지 않는다.** profile, API server, plugin hook 만 쓴다. 근거와 검토 순서는 [ADR-001](../docs/adr/ADR-001-hermes를-런타임으로-두고-core를-고치지-않는다.md) 에 있다.
- **Hermes 내부 지점에 새로 기대면 같은 커밋에서 `tests/hermes_contract.py` 에 선언한다.** 올릴 판에서 그 지점이 바뀌면 계약 시험이 실패한다.
- **서비스 이름은 `connectors/` 와 그 검사와 문서에만 둔다.** plugin 은 어느 서비스도 모른다. `test/unit/connector-neutral.test.ts` 가 본다.
- **plugin 은 한 배포 동안 옛 Control Plane 의 호출도 받는다.** 새 경로를 더하고 옛것은 다음 배포에서 뺀다. 순서는 [`hermes/docs/code-architecture.md`](docs/code-architecture.md) 의 「디렉터리 배치와 배포 순서」 가 갖는다.
- **운영 값은 코드에 두지 않는다.** 묶음을 만들 때와 프로세스 환경 변수로 받는다. 받는 곳은 [`hermes/README.md`](README.md) 의 「운영 값」 이 갖는다.
