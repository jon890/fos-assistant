# backend

Control Plane 이다. Spring Boot 4 와 MySQL 을 쓴다.

저장소 전체에 걸리는 규칙은 루트 [`AGENTS.md`](../AGENTS.md) 가 갖는다.
공개 저장소에 무엇을 적지 않는지도 그 문서가 정한다.

- 패키지와 경계: [`backend/docs/code-architecture.md`](docs/code-architecture.md)
- 기능의 흐름: [`docs/features/`](../docs/features/)
- 표와 칸: [`backend/docs/data-schema.md`](docs/data-schema.md)
- Hermes 호출: [`hermes/docs/hermes-contract.md`](../hermes/docs/hermes-contract.md)

## 변경 전에 고를 규칙

| 변경 대상 | 규칙 |
| --- | --- |
| 패키지와 의존 방향 | [rule-2](../docs/code-rules/rule-2.md), [rule-3](../docs/code-rules/rule-3.md), [rule-4](../docs/code-rules/rule-4.md), [rule-5](../docs/code-rules/rule-5.md), [rule-6](../docs/code-rules/rule-6.md), [rule-10](../docs/code-rules/rule-10.md), [rule-11](../docs/code-rules/rule-11.md), [rule-12](../docs/code-rules/rule-12.md), [rule-13](../docs/code-rules/rule-13.md) |
| 패키지 배치(하위 기능, 역할, `admin`) | [rule-5](../docs/code-rules/rule-5.md), [rule-70](../docs/code-rules/rule-70.md), [rule-71](../docs/code-rules/rule-71.md), [rule-72](../docs/code-rules/rule-72.md), [rule-73](../docs/code-rules/rule-73.md), [rule-74](../docs/code-rules/rule-74.md), [rule-75](../docs/code-rules/rule-75.md), [rule-76](../docs/code-rules/rule-76.md), [rule-77](../docs/code-rules/rule-77.md), [rule-78](../docs/code-rules/rule-78.md), [rule-79](../docs/code-rules/rule-79.md), [rule-80](../docs/code-rules/rule-80.md), [rule-81](../docs/code-rules/rule-81.md), [rule-82](../docs/code-rules/rule-82.md), [rule-83](../docs/code-rules/rule-83.md), [rule-84](../docs/code-rules/rule-84.md) |
| 관리자 등록과 정리 | [rule-7](../docs/code-rules/rule-7.md), [rule-8](../docs/code-rules/rule-8.md), [rule-9](../docs/code-rules/rule-9.md) |
| 데이터 클래스와 저장 타입 | [rule-15](../docs/code-rules/rule-15.md), [rule-25](../docs/code-rules/rule-25.md), [rule-26](../docs/code-rules/rule-26.md), [rule-27](../docs/code-rules/rule-27.md), [rule-30](../docs/code-rules/rule-30.md), [rule-31](../docs/code-rules/rule-31.md) |
| 설정과 검증 | [rule-23](../docs/code-rules/rule-23.md), [rule-24](../docs/code-rules/rule-24.md), [rule-28](../docs/code-rules/rule-28.md), [rule-29](../docs/code-rules/rule-29.md) |
| auditing과 시각 | [rule-1](../docs/code-rules/rule-1.md), [rule-19](../docs/code-rules/rule-19.md) |
| 트랜잭션과 백그라운드 작업 | [rule-18](../docs/code-rules/rule-18.md), [rule-20](../docs/code-rules/rule-20.md), [rule-21](../docs/code-rules/rule-21.md), [rule-22](../docs/code-rules/rule-22.md) |
| Jackson과 HTTP 및 테스트 설정 | [rule-14](../docs/code-rules/rule-14.md), [rule-54](../docs/code-rules/rule-54.md) |
| 코드 모양과 경고 | [rule-32](../docs/code-rules/rule-32.md), [rule-33](../docs/code-rules/rule-33.md), [rule-34](../docs/code-rules/rule-34.md), [rule-35](../docs/code-rules/rule-35.md), [rule-36](../docs/code-rules/rule-36.md), [rule-37](../docs/code-rules/rule-37.md), [rule-38](../docs/code-rules/rule-38.md), [rule-39](../docs/code-rules/rule-39.md), [rule-40](../docs/code-rules/rule-40.md), [rule-41](../docs/code-rules/rule-41.md), [rule-42](../docs/code-rules/rule-42.md) |
| 마이그레이션과 MySQL 쿼리 | [rule-50](../docs/code-rules/rule-50.md), [rule-51](../docs/code-rules/rule-51.md) |
| 테스트 이름과 공유 컨텍스트 | [rule-16](../docs/code-rules/rule-16.md), [rule-17](../docs/code-rules/rule-17.md) |
| 한국어 주석과 문서 | [rule-52](../docs/code-rules/rule-52.md) |

## 패키지 배치

**이동 진행 중이다.** 기능은 `<기능>[.<하위 기능>].<역할>` 로 나누고 관리자 전용 화면은 `admin` 으로 모으기로 했다. 아직 옮기지 않은 기능은 옛 구조(`application.model` 같은 이름)로 남아 있고, 규칙은 그 위반을 기준 파일에 얼려 두었다. 이동이 끝나면 이 알림을 지운다.

- 새 클래스는 목표 구조로 둔다. 인터페이스는 `application.port`, 서비스가 주고받는 값은 `application.dto`, 설정 값은 `config` 다. `application` 직하에는 빈만 둔다.
- 기능은 `admin` 을 모르고, `admin` 은 기능의 `application` 과 `application.dto` 만 부른다. `CurrentUserProvider.requireAdmin` 은 `admin` 안에서만 부른다.
- 결정과 근거는 [ADR-20261015 / package-layout](docs/adr/ADR-20261015-package-layout.md), 층 방향과 하위 기능의 순서는 [`code-architecture.md`](docs/code-architecture.md) 의 「패키지 배치 규칙」 이 갖는다.
- 하위 기능의 경계와 한 서비스의 일이 관리자 전용인지는 규칙이 판정하지 못한다. 리뷰가 본다([rule-84](../docs/code-rules/rule-84.md)).

## 품질 검사

구조 규칙, 코드 규칙, 포맷은 `./gradlew qualityCheck` 하나가 묶어 검사한다. 파일을 바꾸지 않는다.
`scripts/checks/quality.sh check` 끝의 「경고 목록(실패 아님)」 은 실패시키지 않지만 모두 고칠 후보다.

기준 파일을 갱신하거나 규칙을 뺄 때는 [`backend/docs/code-architecture.md`](docs/code-architecture.md) 를 읽는다.

## 구조 규칙

실행 조건은 `src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java`가 갖는다.
까닭과 예외는 [코드 규칙 목록](../docs/code-rules/INDEX.md)에서 번호로 찾는다.
`./gradlew test`가 다른 테스트와 함께 검사하고 `./gradlew archTest`는 구조 규칙만 검사한다.
순환과 층 순서의 집계 구현은 `TopLevelPackageCycles`, `TopLevelPackageOrder`에서, 패키지 배치 규칙의 판정은 `PackageLayoutRules`와 `SubFeatureOrder`에서 확인한다.

## 코드 규칙

코드 모양 규칙은 Gradle 내장 `checkstyle` 플러그인의 Checkstyle 이 확인한다. 실행 조건은 `config/checkstyle/checkstyle.xml`이, 까닭과 예외는 [rule-32](../docs/code-rules/rule-32.md)부터 [rule-42](../docs/code-rules/rule-42.md)가 갖는다.
`./gradlew test` 에는 걸리지 않으므로 따로 돌린다.

```bash
# cwd: backend/
./gradlew checkstyleMain checkstyleTest
```

### 자동으로 고치기

자동으로 고칠 수 있는 Checkstyle 위반은 OpenRewrite 레시피와 포매터가 이 순서로 고친다.

```bash
# cwd: backend/
./gradlew rewriteChanged
./gradlew spotlessApply
```

메서드 사이의 빈 줄은 마지막 Eclipse JDT 포맷 단계가 프로젝트 설정에 맞춘다.
`rewriteChanged` 는 `origin/main` 과의 공통 조상 뒤에 바뀐 파일만 고치므로 `git fetch origin` 뒤에 돌린다.
daemon 이 `rewriteRun` 도중 멈추면 범위 밖 파일을 되돌리는 단계도 돌지 못한다. 바꾸기 전 내용을 떠 둔 디렉터리는 `rewriteChanged:` 로 시작하는 로그 줄에 있다.

## 포맷

Java 포맷은 Spotless의 Eclipse JDT 통합과 `config/spotless/eclipse-formatter.xml`이 정한다.
버전은 Gradle version catalog로 고정한다. IntelliJ 설치나 환경 변수는 필요하지 않다.

```bash
# cwd: backend/
./gradlew spotlessCheck
./gradlew spotlessApply
```

기본은 `origin/main`과의 공통 조상 뒤 바뀐 파일만 검사하고 포맷한다. `git fetch origin` 뒤에 돌린다.
main/test Java 전체를 확인하거나 적용할 때는 위 명령에 `-PformatAll=true`를 더한다.
자동 import 최적화와 멤버 재정렬은 하지 않는다. 명시 import와 순서를 유지하고 wildcard는 Checkstyle이 금지한다.
`spotlessCheck`는 원본을 바꾸지 않는다.
설정 대응과 IntelliJ 차이, IDE에서 실행하는 방법은 [`backend/docs/code-architecture.md`](docs/code-architecture.md)의 「Eclipse JDT와 Spotless의 Java 포맷」이 갖는다.
실제 포맷 회귀는 저장소 루트에서 `python3 test/spotless-jdt.test.py`로 검사한다.

전체 포맷 커밋을 `git blame`에서 제외하려면 저장소 루트에서 `git config blame.ignoreRevsFile .git-blame-ignore-revs`를 실행한다.

포맷 범위와 커밋 분리는 [rule-43](../docs/code-rules/rule-43.md), 파일 길이는 [rule-44](../docs/code-rules/rule-44.md), 기존 위반 기준은 [rule-45](../docs/code-rules/rule-45.md)를 따른다.

## JPA auditing 범위

계약은 [`code-architecture.md`의 「JPA 생성 시각」](docs/code-architecture.md#jpa-생성-시각)이 갖는다.
검사: `ArchitectureRules.AUDITING_LISTENERS_AND_CLOCK`, `JpaAuditingPlacementTest`,
`JpaAuditingTest`, `JpaAuditingMysqlTest`, `JpaAuditingMigrationTest`와 기존 MemoryCollection 저장 검사.
실행: `./gradlew archTest`와 `./gradlew test --tests '*JpaAuditingTest' --tests '*JpaAuditingMigrationTest' --tests '*MemoryCollectionAuditingTest'`.
MySQL 검사는 루트의 `scripts/checks/check-mysql-migration.sh --tests '*JpaAuditingMysqlTest' --tests '*MemoryCollectionAuditingMysqlTest'`로 돌린다.

## MySQL 검사

마이그레이션 변경은 [rule-50](../docs/code-rules/rule-50.md), 저장소 쿼리 변경은 [rule-51](../docs/code-rules/rule-51.md)을 읽는다.
실제 MySQL 검사는 Docker가 있어야 돈다.
버전 규칙과 DDL·DML 분리, 실행 방법은 [`data-schema.md`](docs/data-schema.md)의 「마이그레이션 작성 규칙」이 갖는다.

## 실행 기록

실행 기록의 보존은 [rule-49](../docs/code-rules/rule-49.md)를 따른다.
실행 상태와 비용 합계의 의미는 [ADR-011](docs/adr/ADR-011-실행은-시작할-때-기록하고-끝날-때-갱신한다.md)과 [ADR-004](docs/adr/ADR-004-구독제에서도-api-가격으로-환산해-보인다.md)가 갖는다.

## 테스트와 주석

`gradlew`는 `backend/` 안에 있다.
테스트 이름은 [rule-16](../docs/code-rules/rule-16.md), 주석의 언어와 기존 영어 주석 정리는 [rule-52](../docs/code-rules/rule-52.md)를 따른다.
