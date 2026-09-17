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

- **이 저장소는 공개 저장소다. 홈서버의 운영 정보를 적지 않는다.** 아래 「공개 저장소」 를 본다.
- Hermes core 를 고치지 않는다. profile, API server, plugin hook 만 쓴다.
  고쳐야 할 것 같으면 ADR-001 의 검토 순서를 따른다.
- 비밀값을 데이터베이스에 넣지 않는다. profile key 는 홈서버 파일에 둔다.
- 실행할 profile 은 요청자의 바인딩에서만 꺼낸다. 요청 본문이 profile 을 정하지 못한다.
- Memory 접근 권한은 Control Plane 이 주입으로 정한다. 에이전트에게 memory 조회 도구를 주지 않는다.
- 실행 기록은 실패해도 남긴다.

## 공개 저장소

이 저장소는 누구나 읽는다.
Hermes 를 쉽게 쓰는 화면으로 공개하는 것을 검토하고 있어 앞으로도 공개로 둔다.

**아래를 어느 파일에도 적지 않는다.** 코드, 문서, `tasks/`, 커밋 메시지, PR 본문이 모두 해당한다.

| 적지 않는 것 | 예 |
| --- | --- |
| 홈서버의 주소와 계정 | |
| 포트 번호 | `8651`, `8652` |
| 컨테이너 이름 | `hermes`, `bifos-db` |
| 컨테이너 안의 경로 | `/opt/data/...` |
| 데이터베이스 이름과 접속 방법 | |
| **key 나 토큰을 꺼내는 명령** | `grep API_SERVER_KEY ...` |
| 그것들을 조합한 실행 명령 | `docker exec ... curl ...` |

key 값 자체를 적지 않는 것은 당연하고, **그것이 어디 있고 어떻게 꺼내는지도 적지 않는다.**

대신 이렇게 쓴다.

- 무엇을 확인해야 하는지만 적고, 실행 방법은 `fos-home-infra` 를 가리킨다
- 측정한 **결과 수치**는 적어도 된다. 그것을 얻은 명령을 적지 않는 것이다
- 조사 기록은 저장소 밖에 둔다. `.omc/` 는 `.gitignore` 에 있으므로 그 아래도 괜찮다

내보내기 전에 검사한다.

```bash
# cwd: 저장소 root
scripts/check-public-safe.sh
```

## tasks 는 구현 문서만 담는다

`tasks/` 는 이 대화를 보지 못한 구현자가 읽고 실행하는 곳이다.

조사를 phase 로 만들지 않는다.
조사 절차를 phase 에 적으면 구현 문서에 홈서버 접근 명령이 섞이고,
그 phase 가 끝나도 결론이 어디에 남는지 정해지지 않는다.

조사는 `orchestration` 으로 워커에 직접 맡기고, 그 결과를 ADR 이나 `docs/` 에 남긴다.
그 결론이 나온 뒤에 구현 계획을 세운다.

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
cd web && pnpm test:browser
node test/e2e/run.ts
```

`test/browser` 는 그 위에 웹과 Chromium 을 띄워 화면을 검사한다.
`mobile` 과 `desktop` 두 폭에서 돌고 각각 390px 와 1280px 다.

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

## 운영

운영 절차는 이 저장소가 갖지 않는다. 별도의 비공개 저장소 `fos-home-infra` 가 소유한다.

| 무엇 | 어디 |
| --- | --- |
| 배포와 확인 | `services/assistant/README.md` |
| Hermes profile 과 스킬 연결 | `services/hermes-assistant/README.md` |

홈서버는 `ssh homeserver` 로 붙는다. 별칭은 `~/.ssh/config` 에 있다.
**주소와 포트와 계정을 이 저장소에 적지 않는다.** 공개 저장소다.

### 배포했다고 말하기 전에 보는 것

- 컨테이너가 실제로 새로 만들어졌는가. `docker ps` 의 생성 시각으로 본다
- 기동 로그에 `Started Assistant` 가 있는가
- 스키마를 바꿨으면 `Schema validation` 이 실패하지 않았는가

**테스트가 모두 통과해도 운영에서 동작하지 않을 수 있다.**
가짜 Hermes 가 실제와 다른 형태를 보내도록 쓰여 있으면 테스트는 통과한다.
실제로 그렇게 스트리밍이 통째로 동작하지 않은 채 배포된 적이 있다.
Hermes 와 주고받는 것을 바꿨으면 배포한 뒤 실제 실행을 한 번 왕복시켜 본다.
