# Phase 03. 최상위 패키지의 층 순서를 규칙으로 검사한다

**Execution profile**: deep

## 목표

ADR-068 의 층 순서를 ArchUnit 규칙으로 옮긴다(C7). 순환이 없기만 하면 어느 방향이든 통과하는 지금 상태에서는 새 의존이 순서를 다시 흐릴 수 있다.
규칙은 아래 패키지가 위 패키지를 쓰는 간선과, 순서에 없는 새 최상위 패키지를 위반으로 낸다. 기준은 처음부터 0 줄이다.

**범위 외**: 운영 코드. 이 phase 는 `backend/src/main` 을 고치지 않는다. 규칙이 지금 코드에서 위반을 내면 고치거나 얼리지 말고 그 간선을 보고한다.

## 컨텍스트

- 규칙은 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 에 있고, 순환 규칙의 조건은 같은 디렉터리의 `TopLevelPackageCycles.java` 다. 최상위 패키지 간선 하나가 위반 하나다. `shared` 는 그래프 밖이다. 규칙은 컴파일한 클래스를 읽는다.
- 순환 규칙의 기준 파일은 `backend/config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948` 다. 손으로 고치지 않는다. 기준을 다루는 절차는 `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」 에 있다.
- 결정의 근거는 `docs/adr/ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md` 다. 층 순서는 아래에서 위로 `hermes`, `user`, `model`, `agent`, `skill`, `usage`, `memory`, `context`, `chat`, `orchestration`, `mcp`, `people`, `connector` 다.
- **동작을 바꾸지 않는다.**
- 포맷은 이 phase 에서 돌리지 않는다. 이 phase 커밋 뒤 team-lead 가 `./gradlew spotlessApply` 결과를 별도 커밋으로 낸다. import 순서를 손으로 정렬하지 않는다.
- 주석과 Javadoc 은 한국어로 쓴다. 테스트 메서드는 영문 camelCase 이름과 한국어 `@DisplayName` 을 갖는다. `gradlew` 는 `backend/` 안에 있다.
- `TopLevelPackageCycles` 가 최상위 패키지 간선을 모으는 방식(루트 패키지에 바로 있는 클래스와 `com.bifos.assistant` 밖과 `shared` 를 뺀다, 배열은 원소 타입으로 본다)을 그대로 따른다. 위반은 패키지 간선 하나에 하나이고, 그 패키지에서 이름 순으로 첫 클래스에서만 낸다. 위반 문구에 클래스 이름과 줄 번호를 넣지 않는다.
- `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRulesTest.java` 가 규칙마다 `FreezingArchRule.freeze(...).check(MAIN)` 을 부른다. 새 규칙도 같은 모양으로 건다.
- 규칙의 `as(...)` 설명이 기준 파일의 열쇠다. 새 규칙의 기준 파일은 `docs/backend/quality.md` 의 「규칙을 새로 더했다」 절차로 만든다. `stored.rules` 에 한 줄이 더해지고 0 줄짜리 기준 파일이 하나 생긴다.

**근거 문서**: 위 ADR-068 의 C7, `docs/adr/ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`, `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」, `backend/AGENTS.md` 의 「구조 규칙」

## 의도 메모

- `layeredArchitecture()` 를 쓰지 않는다. 패키지 열셋의 허용 관계를 층마다 적어야 하고, 순서에 없는 새 패키지를 잡지 못한다.
- 순서 목록은 규칙 코드의 상수 하나에만 둔다. 문서는 그 순서를 표로 적고 규칙 이름을 가리킨다.
- 판정은 순수 함수로 떼어 그 함수만 따로 테스트한다. ArchUnit 조건 전체를 가짜 클래스로 테스트하지 않는다.

## 작업 항목

### 1. `architecture/TopLevelPackageOrder.java` 신규

`backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrder.java`. `final class TopLevelPackageOrder extends ArchCondition<JavaClass>`.

- `static final List<String> ORDER = List.of("hermes", "user", "model", "agent", "skill", "usage", "memory", "context", "chat", "orchestration", "mcp", "people", "connector");`
- `static List<String> violations(String source, Collection<String> targets)` — 패키지 하나의 위반 문구를 이름 순으로 돌려주는 순수 함수다
  - `source` 가 `ORDER` 에 없으면 `"<source> 는 층 순서에 없다"` 한 줄
  - `targets` 가운데 `ORDER` 에 없는 것은 그 패키지 쪽에서 내므로 여기서는 건너뛴다
  - `ORDER` 에서 `target` 의 자리가 `source` 보다 뒤면 `"<source> -> <target> 는 층 순서를 거스른다"`
- `init` 과 `check` 는 `TopLevelPackageCycles` 와 같은 방식으로 간선을 모으고, 패키지의 첫 클래스에서 `violations` 의 문구를 위반으로 낸다
- 간선을 모으는 코드는 `TopLevelPackageCycles` 의 `init` 안의 것과 같다. 다음 항목의 도우미로 뽑아 둘이 함께 쓴다

### 1-1. `architecture/TopLevelPackageEdges.java` 신규

`backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageEdges.java`. 패키지 전용 `final class` 다. `TopLevelPackageCycles` 의 간선 모으기를 그대로 옮긴다.

- `static TopLevelPackageEdges of(Collection<JavaClass> classes)` — 지금 `TopLevelPackageCycles.init` 이 `edges` 와 `reportingClass` 를 채우는 반복문을 옮긴다
- `Map<String, SortedSet<String>> edges()` — 출발 패키지마다 도착 패키지. 이름 순이다
- `boolean reports(JavaClass item)` — 그 클래스가 자기 패키지에서 이름 순으로 첫 클래스인지
- `static Optional<String> topLevelPackageOf(JavaClass javaClass)` — 지금의 private 메서드를 옮긴다. 루트 패키지에 바로 있는 클래스, `com.bifos.assistant` 밖, `shared` 는 비어 있는 값이다

`TopLevelPackageCycles` 는 이 도우미를 쓰게 고친다. 순환 판정(도달 가능성 계산)과 위반 문구와 내는 순서는 바꾸지 않는다. 클래스 Javadoc 의 설명은 그대로 둔다.

### 2. `ArchitectureRules` 에 규칙을 더한다

```java
public static final ArchRule TOP_LEVEL_PACKAGES_FOLLOW_LAYER_ORDER = classes()
        .that()
        .resideInAPackage("com.bifos.assistant..")
        .should(new TopLevelPackageOrder())
        .as("최상위 패키지는 층 순서의 아래쪽만 쓴다");
```

Javadoc 에 뜻을 적고 근거 줄은 `<p>근거: {@code docs/backend/packages.md} 「최상위 패키지의 층 순서」, ADR-068.` 로 적는다. `test/unit/doc-references.test.ts` 가 Java 파일이 가리킨 문서의 절 이름이 실제 헤딩인지 검사한다. 기존 규칙은 고치지 않는다.

### 3. `ArchitectureRulesTest` 에 테스트를 더한다

`@Test @DisplayName("최상위 패키지가 층 순서의 위쪽을 새로 쓰지 않는다") void topLevelPackagesFollowLayerOrder()` 를 다른 테스트와 같은 모양으로 더한다.

### 4. 이 phase 를 검증하는 `TopLevelPackageOrderTest.java`

`backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrderTest.java` 를 새로 만든다. `violations` 만 테스트한다.

- 정상: `chat` 이 `usage`, `agent`, `hermes` 를 쓰면 위반이 없다
- 실패: `usage` 가 `chat` 을 쓰면 `"usage -> chat 는 층 순서를 거스른다"` 한 줄이다
- 실패: 순서에 없는 `billing` 이 `user` 를 쓰면 `"billing 는 층 순서에 없다"` 한 줄이다
- 경계: 맨 아래 `hermes` 가 아무것도 쓰지 않으면 위반이 없고, 맨 위 `connector` 는 나머지 열둘을 모두 써도 위반이 없다
- `ORDER` 에 같은 이름이 두 번 없고 열셋이다

### 5. 기준 파일을 만든다

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
```

`backend/config/archunit/store/stored.rules` 가 20 줄에서 21 줄이 되고 0 바이트짜리 기준 파일이 하나 생겨 `*-*` 파일이 18 개에서 19 개가 된다. `allowStoreUpdate` 만으로 만들어진다. `git add` 하지 않고 회신에 새 파일 이름을 적는다.
**새 기준 파일이 0 줄이 아니면 지금 코드에 층 순서를 거스르는 간선이 있는 것이다.** 기준 파일과 `stored.rules` 를 그대로 둔 채 어느 간선인지 보고한다. 운영 코드를 고치지 않는다.

### 6. 문서를 고친다

- `docs/backend/packages.md` 에서 「경로 변수와 요청 인자의 형식이 틀리면」 으로 시작하는 문단이 끝난 뒤, `### connector` 앞에 `### 최상위 패키지의 층 순서` 절을 더한다. 순서를 아래에서 위로 표로 적고(자리와 패키지), 「위 패키지는 아래 패키지를 쓰고 아래 패키지는 위 패키지를 import 하지 않는다. 거꾸로 써야 하면 아래 패키지에 port 를 두고 위 패키지가 구현한다. 새 최상위 패키지를 만들면 이 순서의 자리를 정하고 `TopLevelPackageOrder.ORDER` 에 넣는다」 와 「검사: `ArchitectureRules.TOP_LEVEL_PACKAGES_FOLLOW_LAYER_ORDER`, 근거: ADR-068」 을 적는다. `shared` 는 순서 밖이고 어느 패키지도 쓰지 않는다는 한 줄도 적는다
- 같은 문서에서 `TOP_LEVEL_PACKAGES_FREE_OF_CYCLES` 를 검사 이름으로 적은 곳은 그대로 둔다
- `backend/AGENTS.md` 의 「구조 규칙」 에서 「순환 규칙은 패키지 간선 하나를 위반 하나로 센다」 문단 다음에 한 문단을 더한다. 「층 순서 규칙도 패키지 간선 하나를 위반 하나로 센다. 순서는 `docs/backend/packages.md` 의 「최상위 패키지의 층 순서」 가 갖는다.」
- ADR-068 의 `status` 줄을 `accepted` 만 남기고 구현 상태 문장을 지운다. `docs/adr/INDEX.md` 의 ADR-068 줄의 상태 칸은 `Accepted` 만 남긴다. 모두 구현됐다

고친 문서에 `한국어 문체 검사기` 을 돌려 종료 코드 0 인지 본다.

## 검증

```bash
# cwd: backend/
./gradlew test
./gradlew checkstyleMain checkstyleTest
test "$(grep -c "" config/archunit/store/stored.rules)" -eq 21
test "$(ls config/archunit/store/*-* | grep -c "")" -eq 19
test "$(cat config/archunit/store/*-* | grep -c "")" -eq 13
```

`stored.rules` 는 한글을 유니코드 이스케이프로 적으므로 줄 수로 확인한다. 셋째 줄의 13 은 중첩 타입 규칙의 기준 13 줄이다. 다른 기준 파일은 모두 0 줄이어야 한다.

```bash
# cwd: 저장소 root
node --test test/unit/doc-references.test.ts
```

모두 종료 코드 0 이어야 한다. `scripts/quality.sh check` 는 포맷 검사를 함께 돌리므로 team-lead 가 포맷 커밋 뒤 통합 검증에서 돌린다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrder.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrderTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageEdges.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRulesTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageCycles.java` | 수정 |
| `backend/config/archunit/store/stored.rules` | 수정 |
| `backend/config/archunit/store/*-*` | 신규 |
| `docs/backend/packages.md` | 수정 |
| `backend/AGENTS.md` | 수정 |
| `docs/adr/ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md` | 수정 |
| `docs/adr/INDEX.md` | 수정 |
