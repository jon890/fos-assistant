# Phase 01. UserRole 을 shared.domain.type 으로 옮긴다

**Execution profile**: standard

## 목표

`shared.auth.CurrentUser` 가 `user.domain.type.UserRole` 을 쓰는 위반을 없앤다.
역할은 모든 기능 패키지가 권한을 판정할 때 읽는 값이므로 `shared.domain.type` 으로 옮긴다(ADR-068 의 S1).
`SHARED_DOES_NOT_DEPEND_ON_DOMAINS` 의 기준이 14 줄에서 9 줄로 준다.

**범위 외**: `UserRole` 의 상수 이름과 순서. `CurrentUser` 의 칸. `ControlPlaneJwtFilter` 가 `user` 를 쓰는 위반은 다음 phase 가 맡는다. 이 phase 의 diff 는 `package` 줄, `import` 줄, 문서, 기준 파일뿐이어야 한다.

## 컨텍스트

- 규칙은 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 의 `SHARED_DOES_NOT_DEPEND_ON_DOMAINS` 다. `shared` 아래의 클래스는 다른 최상위 패키지의 클래스를 쓰지 못한다.
- 기준 파일은 `backend/config/archunit/store/25192a86-f325-4b77-b6d1-3c590c06ead7` 다. 손으로 고치지 않고 아래 명령으로 줄인다. 절차는 `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」 에 있다.
- 결정의 근거는 `docs/adr/ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md` 다.
- **동작을 바꾸지 않는다.** 응답, 저장되는 값, 인증 판정, 트랜잭션이 열리는 자리가 그대로여야 한다. `ArchitectureRules.java` 를 고치지 않는다.
- 포맷은 이 phase 에서 돌리지 않는다. `spotlessApply` 결과는 team-lead 가 따로 커밋한다. import 순서를 손으로 정렬하지 않는다.
- 주석과 Javadoc 은 한국어로 쓴다. 테스트 메서드는 영문 camelCase 이름과 한국어 `@DisplayName` 을 갖는다. `gradlew` 는 `backend/` 안에 있다.
- BSD `sed` 는 `\b` 를 모른다. 여러 파일의 이름을 바꿀 때는 `perl -pi -e` 를 쓴다.
- `backend/src/main/java/com/bifos/assistant/user/domain/type/UserRole.java` 는 상수 `ADMIN`, `MEMBER` 를 가진 enum 이고 다른 타입을 import 하지 않는다.
- `AppUser.role` 이 `@Enumerated(EnumType.STRING)` 으로 이 enum 을 저장한다. DB 에는 상수 이름만 저장되고 패키지 이름은 저장되지 않는다.
- `ENUMERATED_FIELDS_USE_DOMAIN_TYPE` 은 저장되는 enum 이 `..domain.type..` 패키지에 있기를 요구한다. `com.bifos.assistant.shared.domain.type` 은 이 조건을 만족한다.
- `backend/src/test/java/com/bifos/assistant/architecture/StoredEnumNamesTest.java` 가 `UserRole` 의 상수 이름과 패키지가 `.domain.type` 으로 끝나는지 단언한다.
- JPQL 문자열에 `UserRole` 의 전체 이름을 적은 곳이 있는지 `grep -rn "domain\.type\.UserRole" backend/src/main --include='*.java' | grep -v "^.*import "` 로 확인한다. 있으면 같이 고친다. 이 문자열은 컴파일이 잡지 못하고 `scripts/check-mysql-migration.sh` 가 잡는다.

**근거 문서**: 위 ADR-068 의 S1, `backend/AGENTS.md` 의 「enum 은 저장 여부로 둘 곳을 정한다」, `docs/backend/packages.md` 의 「패키지와 책임」

## 의도 메모

- `CurrentUser` 가 역할 대신 관리자 여부만 갖게 하는 방법은 쓰지 않는다(ADR-068 의 「대안 기각」).
- `user/domain/type` 디렉터리는 비게 된다. 빈 디렉터리와 `package-info.java` 를 남기지 않는다.

## 작업 항목

### 1. `git mv` 로 옮기고 `package` 줄을 고친다

`backend/src/main/java/com/bifos/assistant/user/domain/type/UserRole.java` 를
`backend/src/main/java/com/bifos/assistant/shared/domain/type/UserRole.java` 로 옮긴다. 본문은 `package` 줄만 바꾼다.

### 2. 참조를 고친다

`backend/src/main/java` 와 `backend/src/test/java` 에서 `import com.bifos.assistant.user.domain.type.UserRole;` 을 `import com.bifos.assistant.shared.domain.type.UserRole;` 로 바꾼다. 95 파일 안팎이다.
`./gradlew compileJava compileTestJava` 가 통과할 때까지 고친다.

### 3. 문서를 고친다

- `docs/backend/packages.md` 의 「패키지와 책임」 표에 `shared/util` 줄 다음으로 `| `shared/domain/type` | 모든 패키지가 권한 판정에 읽는 역할 값 |` 줄을 더한다.
- `backend/AGENTS.md` 의 「enum 은 저장 여부로 둘 곳을 정한다」 표 아래에 한 문단을 더한다. 「`UserRole` 은 `shared.domain.type` 에 둔다. `shared.auth.CurrentUser` 가 읽는 값이라 `user` 에 두면 `shared` 가 `user` 를 쓰게 된다(ADR-068).」
- ADR-068 의 `status` 줄과 `docs/adr/INDEX.md` 의 ADR-068 줄에서 구현 상태를 「S1, S3 이 구현됐다. C1 부터 C7, S2 는 아직 구현 전이다」 로 고친다.

고친 문서에 `bash /Users/nhn/personal/fos-skills/content-preview/scripts/style-check.sh <파일>` 을 돌려 종료 코드 0 인지 본다.

### 4. 기준 파일을 줄인다

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
```

이 명령이 새 위반으로 실패하면 다시 얼리지 말고 어느 규칙의 어느 줄인지 보고한다.

### 5. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/architecture/StoredEnumNamesTest.java` 의 import 를 새 패키지로 고친다. 단언은 바꾸지 않는다.
정상: `UserRole` 의 상수가 `ADMIN`, `MEMBER` 순서 그대로다. 실패: 패키지가 `.domain.type` 으로 끝나지 않으면 그 타입 이름을 내며 실패한다.

## 검증

```bash
# cwd: backend/
./gradlew test
./gradlew checkstyleMain checkstyleTest
test "$(wc -l < config/archunit/store/25192a86-f325-4b77-b6d1-3c590c06ead7)" -eq 9
! grep -n "UserRole" config/archunit/store/25192a86-f325-4b77-b6d1-3c590c06ead7
! grep -rn "user\.domain\.type" src config
test ! -e src/main/java/com/bifos/assistant/user/domain/type
```

```bash
# cwd: 저장소 root. Docker 가 있어야 한다
scripts/check-mysql-migration.sh
node test/e2e/run.ts
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/user/domain/type/UserRole.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/shared/domain/type/UserRole.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/**/*.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/StoredEnumNamesTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/**/*.java` | 수정 |
| `docs/backend/packages.md` | 수정 |
| `backend/AGENTS.md` | 수정 |
| `docs/adr/ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md` | 수정 |
| `docs/adr/INDEX.md` | 수정 |
| `backend/config/archunit/store/25192a86-f325-4b77-b6d1-3c590c06ead7` | 수정 |
