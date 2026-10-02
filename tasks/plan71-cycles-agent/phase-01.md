# Phase 01. agent 가 people 을 쓰지 않게 한다

**Execution profile**: deep

## 목표

`agent.application.AgentLifecycleService` 가 `people` 의 타입 셋을 쓰는 간선을 끊는다.
profile 만들기와 지우기, 허용 목록이 쥔 profile 이름 확인은 port 로 받고, 첫 에이전트의 과금 기본값 설정은 `agent` 로 옮긴다.

**범위 외**: profile 을 만드는 순서와 MCP 토큰 발급(`HermesProfileProvisioner` 의 본문). 에이전트를 만들고 지우는 흐름의 순서와 예외. `agent` 가 `skill` 과 `orchestration` 을 쓰는 간선은 다음 phase 가 맡는다.

## 컨텍스트

- 규칙은 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 의 `TOP_LEVEL_PACKAGES_FREE_OF_CYCLES` 다. 최상위 패키지 간선 하나가 위반 하나다. `B` 에서 `A` 로 돌아올 수 있으면 간선 `A -> B` 가 위반이다. `shared` 는 그래프 밖이다. 규칙은 컴파일한 클래스를 읽는다.
- 기준 파일은 `backend/config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948` 다. 손으로 고치지 않고 아래 명령으로 줄인다. 절차는 `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」 에 있다.
- 결정의 근거는 `docs/adr/ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md` 다. 층 순서에서 `agent` 는 `skill`, `orchestration`, `people` 보다 아래다.
- `LAYER_DIRECTION` 은 패키지를 넘어서도 건다. `infra` 는 `application` 만 접근할 수 있다. port 를 구현하는 클래스는 위 패키지의 `application` 에 둔다. `infra` 의 클래스가 port 를 직접 구현하지 않는다.
- `application` 은 타입 하나에 파일 하나다(`backend/AGENTS.md`). 서비스 안에 공개 중첩 타입을 두지 않는다.
- **동작을 바꾸지 않는다.** 호출 순서, 예외, 트랜잭션이 열리는 자리가 그대로여야 한다. `ArchitectureRules.java` 를 고치지 않는다.
- 포맷은 이 phase 에서 돌리지 않는다. `spotlessApply` 결과는 team-lead 가 따로 커밋한다. import 순서를 손으로 정렬하지 않는다.
- 주석과 Javadoc 은 한국어로 쓴다. 테스트 메서드는 영문 camelCase 이름과 한국어 `@DisplayName` 을 갖는다. `gradlew` 는 `backend/` 안에 있다.
- `backend/src/main/java/com/bifos/assistant/agent/application/AgentLifecycleService.java` 가 쓰는 `people` 의 타입이다.
  - `people.application.HermesProfileProvisioner` 의 `provision(String profileName)` 과 `deprovision(String profileName)`. 호출 자리는 셋이다
  - `people.infra.AllowedPersonRepository` 의 `existsByHermesProfile(profileName)`. 호출 자리는 하나다
  - `people.application.PeopleProperties` 의 `defaultCostMode()`. 호출 자리는 하나다
- `backend/src/main/java/com/bifos/assistant/people/application/PeopleProperties.java` 는 `@ConfigurationProperties(prefix = "assistant.people")` record 이고 칸 둘이 모두 `agent.domain.type` 의 enum 이다. `people.application.FirstAgentCreator` 도 이 record 를 쓴다.
- `HermesProfileProvisioner` 는 `hermes` 와 `mcp` 의 서비스를 쓴다. `agent` 로 옮길 수 없다.

**근거 문서**: 위 ADR-068 의 C5, `docs/backend/people.md`, `docs/backend/agent.md`, `docs/adr/ADR-033-사용자가-에이전트를-만들고-공개해도-만든-사람이-관리한다.md`

## 의도 메모

- 설정 prefix `assistant.people` 을 바꾸지 않는다. 바꾸면 운영 설정이 읽히지 않는다.
- record 이름 `PeopleProperties` 는 그대로 둔다. 이름을 바꾸면 import 밖의 줄이 바뀌어 검토 범위가 는다. Javadoc 에 「설정 이름이 `assistant.people` 인 까닭은 처음에 `people` 이 갖던 설정이기 때문이다」 를 적는다.
- port 메서드는 지금 부르는 메서드와 인자, 반환, 던지는 예외가 같아야 한다.

## 작업 항목

### 1. `agent/application/ProfileProvisioning.java` 신규

```java
public interface ProfileProvisioning {
    void provision(String profileName);

    void deprovision(String profileName);
}
```

Javadoc 에 「에이전트가 쓸 Hermes profile 을 만들고 거둔다. 순서와 토큰 발급을 아는 `people` 이 구현한다」 를 적는다.
`people/application/HermesProfileProvisioner.java` 에 `implements ProfileProvisioning` 과 `@Override` 둘을 더한다. 본문은 바꾸지 않는다.

### 2. `agent/application/ReservedProfileNames.java` 신규와 구현

```java
public interface ReservedProfileNames {
    boolean reservedByPerson(String profileName);
}
```

`people/application/AllowedPersonProfileNames.java` 를 새로 만든다. `@Service`, `@RequiredArgsConstructor`, `implements ReservedProfileNames`.
`AllowedPersonRepository` 를 받고 `reservedByPerson` 은 `existsByHermesProfile(profileName)` 의 값을 그대로 돌려준다. `@Transactional` 을 붙이지 않는다.

### 3. `PeopleProperties` 를 `agent.application` 으로 옮긴다

`git mv` 로 `backend/src/main/java/com/bifos/assistant/people/application/PeopleProperties.java` 를 `backend/src/main/java/com/bifos/assistant/agent/application/PeopleProperties.java` 로 옮기고 `package` 줄을 고친다.
`backend/src/main/java` 와 `backend/src/test/java` 의 import 를 새 패키지로 고친다. `git grep -n "people\.application\.PeopleProperties" -- backend/src docs` 가 0 건이어야 한다.

### 4. `AgentLifecycleService` 의 변경

필드 셋의 타입을 바꾼다. `HermesProfileProvisioner provisioner` 를 `ProfileProvisioning provisioner` 로, `AllowedPersonRepository allowedPeople` 을 `ReservedProfileNames reservedProfileNames` 로 바꾸고, `PeopleProperties` 는 같은 패키지가 되어 import 만 지운다.
`allowedPeople.existsByHermesProfile(profileName)` 호출을 `reservedProfileNames.reservedByPerson(profileName)` 으로 바꾼다. 그 밖의 줄은 바꾸지 않는다.

### 5. 문서를 고친다

- `docs/backend/agent.md` 의 표에서 「profile 을 만들고 거두기」 줄의 「`people/application/HermesProfileProvisioner` 를 쓴다」 를 「`agent/application/ProfileProvisioning` port 로 부른다. 구현은 `people/application/HermesProfileProvisioner` 다」 로 고친다. 같은 문서의 「`people.application.HermesProfileProvisioner` 와 같은 규칙」 은 위치가 그대로라 고치지 않는다. `PeopleProperties` 의 위치를 적은 문서는 없다
- `docs/backend/packages.md` 의 `connector` 절에 있는 「`agent` 가 `people` 을 거쳐 `mcp` 를 쓰므로 `mcp` 가 `connector` 를 부르면 순환이 된다」 문장을 「`people` 이 `mcp` 를 쓰고 `connector` 는 그 둘을 쓰므로 `mcp` 가 `connector` 를 부르면 순환이 된다」 로 고친다

고친 문서에 `bash /Users/nhn/personal/fos-skills/content-preview/scripts/style-check.sh <파일>` 을 돌려 종료 코드 0 인지 본다.

### 6. 기준 파일을 줄인다

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
```

`agent -> people` 줄이 빠진다. 다른 줄이 함께 빠질 수 있다. 줄이 늘거나 새 위반으로 실패하면 다시 얼리지 말고 보고한다. 끝난 뒤의 줄 수와 빠진 줄을 회신에 적는다.

### 7. 이 phase 를 검증하는 테스트

- `AgentLifecycleService` 를 `new` 로 만드는 테스트의 생성자 인자를 맞춘다. `git grep -n "new AgentLifecycleService(" -- backend/src/test` 로 찾는다. 대역은 port 타입으로 바꾼다. 단언은 바꾸지 않는다
- `backend/src/test/java/com/bifos/assistant/people/AgentPortsWiringTest.java` 를 새로 만든다. `@SpringBootTest`, `@ActiveProfiles("test")`
  - 정상: 주입받은 `ProfileProvisioning` 빈의 실제 클래스가 `HermesProfileProvisioner` 다
  - 정상: 허용 목록에 profile 이름 하나를 저장하면 `ReservedProfileNames.reservedByPerson` 이 그 이름에 true 다
  - 실패: 허용 목록에 없는 이름에는 false 다

## 검증

```bash
# cwd: backend/
./gradlew test
./gradlew checkstyleMain checkstyleTest
! grep -n "^agent -> people " config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948
! grep -rnE "^import (static )?com\.bifos\.assistant\.people\." src/main/java/com/bifos/assistant/agent
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/agent/application/ProfileProvisioning.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/ReservedProfileNames.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/people/application/AllowedPersonProfileNames.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/people/application/PeopleProperties.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/agent/application/PeopleProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/people/application/HermesProfileProvisioner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/people/application/FirstAgentCreator.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentLifecycleService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/people/AgentPortsWiringTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/**/*.java` | 수정 |
| `docs/backend/packages.md` | 수정 |
| `docs/backend/agent.md` | 수정 |
| `backend/config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948` | 수정 |
