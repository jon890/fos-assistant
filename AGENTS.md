# AGENTS.md

가족용 AI 비서다.
Hermes Agent 를 Agent Runtime 으로 두고 이 저장소는 Control Plane 과 웹을 맡는다.

## 읽기 순서

| 문서 | 언제 보는지 |
| --- | --- |
| [`docs/code-architecture.md`](docs/code-architecture.md) | 패키지와 경계를 바꿀 때 |
| [`docs/hermes-integration.md`](docs/hermes-integration.md) | Hermes 를 호출하거나 설정을 바꿀 때 |
| [`docs/adr/INDEX.md`](docs/adr/INDEX.md) | 되돌리기 어려운 결정을 할 때 |
| [`tasks/plan001-mvp/index.md`](tasks/plan001-mvp/index.md) | 다음에 무엇을 만들지 정할 때 |

## 지켜야 할 것

- Hermes core 를 고치지 않는다. profile, API server, plugin hook 만 쓴다.
  고쳐야 할 것 같으면 ADR-001 의 검토 순서를 따른다.
- 비밀값을 데이터베이스에 넣지 않는다. profile key 는 홈서버 파일에 둔다.
- 실행할 profile 은 요청자의 바인딩에서만 꺼낸다. 요청 본문이 profile 을 정하지 못한다.
- Memory 접근 권한은 Control Plane 이 주입으로 정한다. 에이전트에게 memory 조회 도구를 주지 않는다.
- 실행 기록은 실패해도 남긴다.

## 기술 주의점

- Spring Boot 4 는 Jackson 3 을 쓴다. `com.fasterxml.jackson` 이 아니라 `tools.jackson` 을 import 한다.
- `RestClient.Builder` 는 자동 구성되지 않는다. `RestClient.builder()` 로 직접 만들고 timeout 을 준다.
- `backend/src/test/resources/application-test.yml` 은 test profile 전용이다.
  `application.yml` 이라는 이름으로 두면 `smokeRun` 이 실제 설정 대신 이 파일을 읽는다.

## 확인

```bash
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
./scripts/e2e-smoke.sh
```

`e2e-smoke.sh` 는 `tools/fake-hermes` 를 써서 홈서버 없이 전체 흐름을 검사한다.

## 커밋

한국어로 쓴다.
제목은 `<type>(<범위>): <메시지>` 형식을 쓰고 범위는 `backend`, `web`, `docs`, `infra` 중 하나다.
변경 대상과 달라진 동작을 함께 적는다.
