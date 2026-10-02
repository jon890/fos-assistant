# Phase 02. ControlPlaneJwtFilter 가 shared.auth 의 port 로 사용자를 받는다

**Execution profile**: deep

## 목표

`shared.auth.ControlPlaneJwtFilter` 가 `user.application.AllowedUserResolver` 와 `user.domain.AppUser` 를 쓰는 위반을 없앤다.
`shared.auth` 에 port 를 두고 `user.application` 이 구현한다(ADR-068 의 S2).
`SHARED_DOES_NOT_DEPEND_ON_DOMAINS` 의 기준이 9 줄에서 0 줄이 된다.

**범위 외**: 토큰 검증, 건너뛰는 경로, 꺼진 사용자의 응답, 권한 문자열(`ROLE_` 과 역할 이름). `AllowedUserResolver.resolveAllowed` 의 동작. `UserProvisioningService`.

## 컨텍스트

- 규칙은 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 의 `SHARED_DOES_NOT_DEPEND_ON_DOMAINS` 다. `shared` 아래의 클래스는 다른 최상위 패키지의 클래스를 쓰지 못한다.
- 기준 파일은 `backend/config/archunit/store/25192a86-f325-4b77-b6d1-3c590c06ead7` 다. 손으로 고치지 않고 아래 명령으로 줄인다. 절차는 `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」 에 있다.
- 결정의 근거는 `docs/adr/ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md` 다.
- **동작을 바꾸지 않는다.** 응답, 저장되는 값, 인증 판정, 트랜잭션이 열리는 자리가 그대로여야 한다. `ArchitectureRules.java` 를 고치지 않는다.
- 포맷은 이 phase 에서 돌리지 않는다. `spotlessApply` 결과는 team-lead 가 따로 커밋한다. import 순서를 손으로 정렬하지 않는다.
- 주석과 Javadoc 은 한국어로 쓴다. 테스트 메서드는 영문 camelCase 이름과 한국어 `@DisplayName` 을 갖는다. `gradlew` 는 `backend/` 안에 있다.
- BSD `sed` 는 `\b` 를 모른다. 여러 파일의 이름을 바꿀 때는 `perl -pi -e` 를 쓴다.
- `backend/src/main/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilter.java` 의 `authenticate(String token)` 이 `users.resolveAllowed(email, 이름)` 으로 `Optional<AppUser>` 를 받는다.
  비어 있으면 `TokenOutcome.REVOKED` 다. 있으면 `new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role())` 를 인증 주체로 올리고 권한은 `"ROLE_" + user.role().name()` 하나다.
- `backend/src/main/java/com/bifos/assistant/user/application/AllowedUserResolver.java` 의 `resolveAllowed(String email, String displayName)` 은 `@Transactional` 이고, 꺼진 주소면 비어 있는 값을, 아니면 `UserProvisioningService.resolve` 의 사용자를 돌려준다. 판정과 조회가 같은 트랜잭션에서 돈다(ADR-059).
- `resolveAllowed` 를 부르는 곳은 이 필터와 `backend/src/test/java/com/bifos/assistant/user/RevokedUserTest.java` 다.
- `backend/src/test/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilterTest.java` 는 `mock(AllowedUserResolver.class)` 로 `new ControlPlaneJwtFilter(new AuthProperties(SECRET), users, json)` 를 만든다.
- `UserRole` 은 앞 phase 에서 `com.bifos.assistant.shared.domain.type` 으로 옮겨졌다.

**근거 문서**: 위 ADR-068 의 S2, `docs/adr/ADR-059-꺼진-사용자는-control-plane-이-요청마다-막고-웹이-세션을-끊는다.md`, `docs/backend/packages.md` 의 「패키지와 책임」

## 의도 메모

- **인증 경계를 건드리는 phase 다.** 필터에서 바뀌는 것은 받는 타입과, `AppUser` 에서 `CurrentUser` 를 만들던 한 줄이 port 구현으로 옮겨 가는 것뿐이다.
- **트랜잭션이 그대로여야 한다.** 새 메서드가 같은 클래스의 `resolveAllowed` 를 `this` 로 부르면 프록시를 거치지 않는다. 새 메서드에도 `@Transactional` 을 붙여 판정과 조회가 지금처럼 한 트랜잭션에서 돌게 한다.
- `AllowedUserResolver` 를 `shared` 로 옮기지 않는다. `people` 과 `user` 의 서비스를 쓰는 클래스다.

## 작업 항목

### 1. `shared/auth/TokenUserResolver.java` 신규

`backend/src/main/java/com/bifos/assistant/shared/auth/TokenUserResolver.java`.

```java
public interface TokenUserResolver {
    Optional<CurrentUser> resolveCurrentUser(String email, String displayName);
}
```

Javadoc 에 「웹 토큰이 가리키는 주소를 현재 사용자로 바꾼다. 꺼진 주소면 비어 있는 값이다. 구현은 `user.application` 에 있다」 를 적는다.

### 2. `AllowedUserResolver` 가 port 를 구현한다

`implements TokenUserResolver` 를 더하고 아래 메서드를 더한다.

- `@Override @Transactional public Optional<CurrentUser> resolveCurrentUser(String email, String displayName)`
- 본문은 `resolveAllowed(email, displayName)` 의 결과를 `new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role())` 로 바꿔 돌려준다

`resolveAllowed` 는 그대로 둔다.

### 3. `ControlPlaneJwtFilter` 의 변경

- 필드와 생성자 인자의 타입을 `AllowedUserResolver` 에서 `TokenUserResolver` 로 바꾼다. 인자 순서는 그대로다
- `authenticate` 에서 `users.resolveCurrentUser(email, 이름)` 을 부르고, 비어 있으면 지금처럼 `TokenOutcome.REVOKED` 를 돌려준다
- 받은 `CurrentUser` 를 그대로 인증 주체로 쓰고 권한은 `"ROLE_" + principal.role().name()` 으로 만든다
- `user` 패키지의 import 를 지운다. 그 밖의 줄은 바꾸지 않는다

### 4. 문서를 고친다

- `docs/backend/packages.md` 의 「패키지와 책임」 표 아래에 있는 「`shared` 가 `user` 를 쓰는 기존 위반은 기준 파일 … 새 위반만 검사에 걸린다」 문장을 지운다. 검사 이름을 적은 앞 문장은 남긴다
- ADR-068 의 `status` 줄과 `docs/adr/INDEX.md` 의 ADR-068 줄에서 구현 상태를 「S1 부터 S3 까지 구현됐다. C1 부터 C7 은 아직 구현 전이다」 로 고친다
- `UserProvisioningService` 의 Javadoc 이 「`ControlPlaneJwtFilter` 가 `AllowedUserResolver` 를 거쳐 매 요청 부른다」 고 적는다. 사실 그대로이므로 고치지 않는다

고친 문서에 `bash /Users/nhn/personal/fos-skills/content-preview/scripts/style-check.sh <파일>` 을 돌려 종료 코드 0 인지 본다.

### 5. 기준 파일을 줄인다

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
```

이 명령이 새 위반으로 실패하면 다시 얼리지 말고 어느 규칙의 어느 줄인지 보고한다.

### 6. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilterTest.java`: 대역을 `mock(TokenUserResolver.class)` 로 바꾸고 `resolveCurrentUser` 가 `CurrentUser` 를 돌려주게 한다. 기존 단언(인증 주체의 칸, 권한 문자열, 꺼진 사용자의 응답, 건너뛰는 경로)은 바꾸지 않는다. `user` 패키지의 import 가 남지 않게 한다
- `backend/src/test/java/com/bifos/assistant/user/RevokedUserTest.java` 에 테스트 둘을 더한다
  - 정상: 허용된 주소로 `resolveCurrentUser` 를 부르면 번호, 주소, 표시 이름, 그룹, 역할이 저장된 사용자와 같은 `CurrentUser` 가 온다
  - 실패: 꺼진 주소로 부르면 비어 있는 값이고 `app_user` 줄이 새로 생기지 않는다

준비 방식은 그 테스트의 기존 테스트를 따른다.

## 검증

```bash
# cwd: backend/
./gradlew test
./gradlew checkstyleMain checkstyleTest
test "$(wc -l < config/archunit/store/25192a86-f325-4b77-b6d1-3c590c06ead7)" -eq 0
! grep -rnP "^import com\.bifos\.assistant\.(?!shared\.)" src/main/java/com/bifos/assistant/shared
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/shared/auth/TokenUserResolver.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/user/application/AllowedUserResolver.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilter.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilterTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/user/RevokedUserTest.java` | 수정 |
| `docs/backend/packages.md` | 수정 |
| `docs/adr/ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md` | 수정 |
| `docs/adr/INDEX.md` | 수정 |
| `backend/config/archunit/store/25192a86-f325-4b77-b6d1-3c590c06ead7` | 수정 |
