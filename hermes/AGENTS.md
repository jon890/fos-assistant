# hermes

Hermes 에 설치하는 plugin 과 profile 틀, 범용 커넥터다. Hermes core 변경의 경계와 검토 순서는 [rule-63](../docs/code-rules/rule-63.md)에 있다.

| 문서 | 언제 보는지 |
| --- | --- |
| [`hermes/docs/code-architecture.md`](docs/code-architecture.md) | plugin 과 커넥터를 어디에 두고 어떤 순서로 배포하는지 |
| [`hermes/docs/hermes-contract.md`](docs/hermes-contract.md) | 우리가 기대는 Hermes 의 동작. Hermes 를 올릴 때 다시 확인할 목록 |
| [`hermes/plugins/fos-ctx/README.md`](plugins/fos-ctx/README.md) | profile plugin 이 MCP 호출에 붙이는 맥락과 session 등록 계약 |
| [`hermes/plugins/dashboard-profile-api/README.md`](plugins/dashboard-profile-api/README.md) | 대시보드 plugin 이 여는 경로와 커넥터 운영 목록 |
| [`hermes/connectors/README.md`](connectors/README.md) | 범용 커넥터를 만들 때 |
| [`hermes/README.md`](README.md) | 설치 묶음과 운영 값, 검사 |
| [`hermes/docs/adr/INDEX.md`](docs/adr/INDEX.md) | 이 모듈에 관한 결정 |

## 변경 전에 고를 규칙

| 변경 대상 | 규칙 |
| --- | --- |
| 새 내부 의존과 fake Hermes | [rule-64](../docs/code-rules/rule-64.md) |
| 서비스별 커넥터와 범용 plugin | [rule-65](../docs/code-rules/rule-65.md) |
| 전환 배포의 호환성 | [rule-66](../docs/code-rules/rule-66.md) |
| 묶음과 환경 값 입력 | [rule-46](../docs/code-rules/rule-46.md), [rule-67](../docs/code-rules/rule-67.md) |

배포 순서는 [`code-architecture.md`](docs/code-architecture.md)의 「디렉터리 배치와 배포 순서」가 갖는다.
