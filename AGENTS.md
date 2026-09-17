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
- 테스트는 엔티티로 스키마를 만들고 운영은 Flyway 가 만든 스키마를 검증한다.
  그래서 둘이 어긋나도 테스트는 통과한다.
  엔티티를 바꾸면 마이그레이션도 함께 바꾸고, 배포 로그에서 `Schema validation` 을 확인한다.
  실제로 `@Lob` 이 붙은 문자열이 MySQL 에서 `tinytext` 로 기대돼 기동에 실패한 적이 있다.

## 확인

```bash
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
node test/e2e/run.ts
```

`test/e2e` 는 Hermes 대역을 같은 프로세스에 띄워 홈서버 없이 전체 흐름을 검사한다.
시나리오는 `test/e2e/scenarios/` 에 하나씩 나뉘어 있고 `run.ts` 가 차례로 돌린다.
Node 의 TypeScript 실행을 쓰므로 설치할 의존성이 없다. Node 22.18 이상이 필요하다.

## 커밋

한국어로 쓴다.
제목은 `<type>(<범위>): <메시지>` 형식을 쓰고 범위는 `backend`, `web`, `docs`, `infra` 중 하나다.
변경 대상과 달라진 동작을 함께 적는다.

## 코드 주석은 한국어로 쓴다

이 저장소를 읽는 사람이 한국어 사용자다.

- 주석과 Javadoc 을 한국어로 쓴다.
- 코드 식별자, 타입, 라이브러리 이름, 명령, 경로는 원문 그대로 둔다.
- 커밋 메시지도 한국어로 쓴다.

영어로 남아 있던 주석은 그 파일을 고칠 때 함께 옮긴다.
한 번에 전부 옮기려고 별도 커밋을 만들지 않는다. 읽는 사람이 diff 에서 무엇이 바뀌었는지 놓친다.
