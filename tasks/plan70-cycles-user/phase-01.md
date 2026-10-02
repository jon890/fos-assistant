# Phase 01. user 가 people 과 agent 를 쓰지 않게 한다

**Execution profile**: deep

## 목표

최상위 패키지 `user` 가 `people` 과 `agent` 와 `hermes` 를 import 하지 않게 한다.
`user` 는 층 순서에서 `hermes` 바로 위의 아래 패키지이고 `people` 과 `agent` 는 그 위다(ADR-068).
`TOP_LEVEL_PACKAGES_FREE_OF_CYCLES` 의 기준이 38 줄에서 32 줄로 준다.

**범위 외**: 첫 로그인의 동작. 사용자를 만드는 순서, 첫 사용자의 역할 판정, 첫 에이전트의 칸 값, 꺼진 사용자 판정. `people` 과 `agent` 사이의 간선은 뒤의 PR 이 맡는다.

## 컨텍스트

- 규칙은 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 의 `TOP_LEVEL_PACKAGES_FREE_OF_CYCLES` 다. 최상위 패키지 간선 하나가 위반 하나다. `B` 에서 `A` 로 돌아올 수 있으면 간선 `A -> B` 가 위반이다. `shared` 는 그래프 밖이다. 규칙은 컴파일한 클래스를 읽는다.
- 기준 파일은 `backend/config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948` 다. 손으로 고치지 않고 아래 명령으로 줄인다. 절차는 `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」 에 있다.
- 결정의 근거는 `docs/adr/ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md` 의 C5 다.
- `LAYER_DIRECTION` 은 패키지를 넘어서도 건다. `infra` 는 `application` 만 접근할 수 있다. port 를 구현하는 클래스는 `application` 에 둔다.
- `application` 은 타입 하나에 파일 하나다(`backend/AGENTS.md`). 서비스 안에 공개 중첩 타입을 두지 않는다.
- 지금 `user` 가 위 패키지를 쓰는 곳이다. 경로는 `backend/src/main/java/com/bifos/assistant/` 아래다.
  - `user/application/AllowedUserResolver.java` 가 `people.application.SignInPolicy` 의 `revoked(String email)` 을 부른다
  - `user/application/UserProvisioningService.java` 의 `createUser` 가 사용자를 저장한 뒤 `signInPolicy.admit(email)` 을 부르고, 값이 있으면 private `createFirstAgent(AppUser owner, AllowedPerson person)` 로 에이전트를 저장한다. 이 메서드가 `agent.infra.AgentRepository`, `agent.domain.Agent`, `agent.domain.type.AgentVisibility`, `hermes.HermesProperties`, `people.application.PeopleProperties`, `people.domain.AllowedPerson` 을 쓴다
- `UserProvisioningService.resolve` 는 `@Transactional` 이다. 사용자 저장과 첫 에이전트 저장이 한 트랜잭션에서 돈다.
- `people` 은 이미 `user`, `agent`, `hermes` 를 쓴다. `people` 이 port 를 구현해도 새 간선이 생기지 않는다.
- 포맷은 이 phase 에서 돌리지 않는다. `spotlessApply` 결과는 team-lead 가 따로 커밋한다. import 순서를 손으로 정렬하지 않는다.
- 주석과 Javadoc 은 한국어로 쓴다. 테스트 메서드는 영문 camelCase 이름과 한국어 `@DisplayName` 을 갖는다. `gradlew` 는 `backend/` 안에 있다.

**근거 문서**: 위 ADR-068, `docs/backend/people.md` 의 「사람을 더할 때」, `docs/backend/packages.md` 의 「패키지와 책임」, `docs/adr/ADR-059-꺼진-사용자는-control-plane-이-요청마다-막고-웹이-세션을-끊는다.md`

## 의도 메모

- **동작을 바꾸지 않는다.** 첫 에이전트는 사용자를 저장한 바로 뒤에 같은 트랜잭션에서 동기로 만든다. 이벤트 발행이나 `@TransactionalEventListener` 로 바꾸지 않는다. 순서와 트랜잭션이 달라진다.
- 에이전트 저장이 실패하면 지금처럼 사용자 저장도 함께 되돌려진다.
- `AllowedUserResolver` 를 다른 패키지로 옮기지 않는다.
- `ArchitectureRules.java` 를 고치지 않는다.

## 작업 항목

### 1. `user/application/SignInRevocation.java` 신규

```java
public interface SignInRevocation {
    boolean revoked(String email);
}
```

Javadoc 에 「관리자가 끈 주소인지 답한다. 허용 목록을 아는 `people` 이 구현한다」 를 적는다.
`people/application/SignInPolicy.java` 에 `implements SignInRevocation` 을 더한다. `revoked(String email)` 은 이미 있다. `@Override` 를 단다. 본문은 바꾸지 않는다.

### 2. `user/application/FirstSignInListener.java` 신규

```java
public interface FirstSignInListener {
    void onUserCreated(AppUser created, String email);
}
```

Javadoc 에 「사용자를 새로 저장한 바로 뒤, 같은 트랜잭션에서 불린다. `email` 은 로그인 판정에 쓴 주소 그대로다」 를 적는다.

### 3. `people/application/FirstAgentCreator.java` 신규

`@Service`, `@RequiredArgsConstructor`, `implements FirstSignInListener`. `SignInPolicy`, `AgentRepository`, `HermesProperties`, `PeopleProperties`, `Clock` 을 받는다.
`onUserCreated(AppUser created, String email)` 의 본문은 `signInPolicy.admit(email).ifPresent(person -> createFirstAgent(created, person))` 이다.
`UserProvisioningService` 의 private `createFirstAgent` 를 Javadoc 과 함께 그대로 옮긴다. `Agent.of(...)` 의 인자 아홉의 값과 순서가 같아야 한다.
이 메서드에 `@Transactional` 을 붙이지 않는다. 부르는 쪽의 트랜잭션 안에서 돈다.

### 4. `UserProvisioningService` 의 변경

- 필드를 `AppUserRepository users`, `FirstSignInListener firstSignIn`, `Clock clock` 셋으로 한다
- `createUser` 는 사용자를 저장한 뒤 `firstSignIn.onUserCreated(created, email)` 을 부르고 `created` 를 돌려준다
- `createFirstAgent` 와 쓰지 않게 된 import 를 지운다. `resolve` 의 Javadoc 에서 `signInPolicy.admit` 을 가리키는 문장은 「허용 목록에서 그 사람을 찾지 못하면 사용자만 만들고 에이전트는 만들지 않는다」 는 뜻을 유지한 채 `FirstSignInListener` 를 가리키게 고친다

### 5. `AllowedUserResolver` 의 변경

필드 타입을 `SignInPolicy` 에서 `SignInRevocation` 으로 바꾼다. 필드 이름은 `signInRevocation` 으로 한다. 호출은 `revoked(email)` 그대로다. `people` 의 import 를 지운다.

### 6. 문서를 고친다

- `docs/backend/packages.md` 의 「패키지와 책임」 표에서 `people` 의 책임에 「첫 로그인에 그 사람의 에이전트 만들기」 를 더한다. `user` 의 책임 「사용자와 첫 로그인 처리」 는 그대로 둔다
- `docs/backend/people.md` 의 패키지별 책임 표에서 `user` 줄의 「첫 로그인에 에이전트까지 만든다」 를 「첫 로그인에 사용자를 만들고 `FirstSignInListener` 를 같은 트랜잭션에서 부른다」 로 고친다. 그 표의 `people` 줄에 「첫 로그인에 그 사람의 에이전트를 만든다(`FirstAgentCreator`)」 를 더한다. `people` 줄이 없으면 `user` 줄 다음에 새로 넣는다
- ADR-068 의 `status` 줄과 `docs/adr/INDEX.md` 의 ADR-068 줄의 구현 상태 문장 끝에 「C5 가운데 첫 에이전트 만들기는 구현됐다」 를 더한다

고친 문서에 `bash /Users/nhn/personal/fos-skills/content-preview/scripts/style-check.sh <파일>` 을 돌려 종료 코드 0 인지 본다.

### 7. 기준 파일을 줄인다

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
```

`user -> agent`, `user -> people` 두 줄이 빠지고, `user` 가 순환에서 빠지면서 `user` 로 들어오던 간선도 순환에 속하지 않게 되어 함께 빠진다. 합해서 여섯 줄이 빠져 32 줄이 된다.
줄 수가 32 가 아니면 기준 파일을 그대로 두고 남은 줄과 빠진 줄을 보고한다. 새 위반으로 실패해도 다시 얼리지 말고 보고한다.

### 8. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/user/FirstSignInTest.java` 는 `@SpringBootTest` 로 첫 로그인을 확인한다. 허용 목록에 있는 사람의 첫 요청에 사용자와 에이전트가 함께 생기는 것(정상), 허용 목록에 없는 주소는 사용자만 생기는 것(경계), 꺼진 사람은 에이전트가 생기지 않는 것을 이미 단언한다. 이 테스트가 단언을 바꾸지 않은 채 통과해야 한다. 컴파일에 필요하면 import 만 고친다
- `backend/src/test/java/com/bifos/assistant/people/FirstAgentCreatorTest.java` 를 새로 만든다. `@SpringBootTest` 이고 `FirstSignInTest` 의 준비 방식을 따른다
  - 실패: `AgentRepository` 저장이 예외를 내면 `UserProvisioningService.resolve` 가 그 예외를 그대로 던지고 `app_user` 에 그 주소의 줄이 남지 않는다. 저장소를 `@MockitoSpyBean` 으로 감싸 예외를 내게 한다. 같은 방식의 본보기는 `backend/src/test/java/com/bifos/assistant/skill/SkillUseRecorderTest.java` 다

## 검증

```bash
# cwd: backend/
./gradlew test
./gradlew checkstyleMain checkstyleTest
test "$(wc -l < config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948)" -eq 32
! grep -n "^user ->\|-> user " config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948
! grep -rnE "^import (static )?com\.bifos\.assistant\.(people|agent|hermes)\." src/main/java/com/bifos/assistant/user
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/user/application/SignInRevocation.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/user/application/FirstSignInListener.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/people/application/FirstAgentCreator.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/people/application/SignInPolicy.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/user/application/UserProvisioningService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/user/application/AllowedUserResolver.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/people/FirstAgentCreatorTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/user/FirstSignInTest.java` | 수정 |
| `docs/backend/packages.md` | 수정 |
| `docs/backend/people.md` | 수정 |
| `docs/adr/ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md` | 수정 |
| `docs/adr/INDEX.md` | 수정 |
| `backend/config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948` | 수정 |
