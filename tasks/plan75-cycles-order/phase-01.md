# Phase 01. memory 가 context 를 port 로 부른다

**Execution profile**: standard

## 목표

`memory.presentation.MemoryController` 가 `context.ContextAssembler` 를 쓰지 않게 한다. `memory` 는 층 순서에서 `context` 보다 아래다.
`memory` 에 port 를 두고 `context` 가 구현한다. 순환 기준이 2 줄에서 0 줄이 된다.

**범위 외**: Memory 목록의 응답 모양과 「빠질 항목」 표시의 판정. `ContextAssembler` 의 조립 규칙.

## 컨텍스트

- 규칙은 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 에 있고, 순환 규칙의 조건은 같은 디렉터리의 `TopLevelPackageCycles.java` 다. 최상위 패키지 간선 하나가 위반 하나다. `shared` 는 그래프 밖이다. 규칙은 컴파일한 클래스를 읽는다.
- 순환 규칙의 기준 파일은 `backend/config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948` 다. 손으로 고치지 않는다. 기준을 다루는 절차는 `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」 에 있다.
- 결정의 근거는 `docs/adr/ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md` 다. 층 순서는 아래에서 위로 `hermes`, `user`, `model`, `agent`, `skill`, `usage`, `memory`, `context`, `chat`, `orchestration`, `mcp`, `people`, `connector` 다.
- **동작을 바꾸지 않는다.**
- 포맷은 이 phase 에서 돌리지 않는다. 이 phase 커밋 뒤 team-lead 가 `./gradlew spotlessApply` 결과를 별도 커밋으로 낸다. import 순서를 손으로 정렬하지 않는다.
- 주석과 Javadoc 은 한국어로 쓴다. 테스트 메서드는 영문 camelCase 이름과 한국어 `@DisplayName` 을 갖는다. `gradlew` 는 `backend/` 안에 있다.
- `backend/src/main/java/com/bifos/assistant/memory/presentation/MemoryController.java` 의 `readable()` 이 `Set.copyOf(context.assembleForOwner(user).omittedMemoryIds())` 로 상한 때문에 빠질 Memory 번호를 읽는다.
- `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java` 는 `@Service` 이고 `public AssembledContext assembleForOwner(CurrentUser user)` 를 갖는다. `context` 는 하위 층 패키지가 없는 평평한 패키지라 `LAYER_DIRECTION` 의 대상이 아니다.
- `application` 은 타입 하나에 파일 하나다(`backend/AGENTS.md`).

**근거 문서**: 위 ADR-068, `docs/backend/memory.md`, `docs/adr/ADR-015-memory-는-층을-나눠-싣는다.md`

## 의도 메모

- port 는 번호 집합만 돌려준다. `AssembledContext` 를 `memory` 로 옮기지 않는다.
- 구현 쪽에 `@Transactional` 을 붙이지 않는다. 지금도 붙어 있지 않다.

## 작업 항목

### 1. `memory/application/OmittedMemories.java` 신규

```java
public interface OmittedMemories {
    Set<Long> omittedFor(CurrentUser user);
}
```

Javadoc 에 「지금 조립하면 상한 때문에 실리지 않을 Memory 의 번호다. 조립 규칙을 아는 `context` 가 구현한다」 를 적는다.

### 2. `ContextAssembler` 가 port 를 구현한다

`implements OmittedMemories` 를 더하고 `@Override public Set<Long> omittedFor(CurrentUser user)` 를 더한다. 본문은 `Set.copyOf(assembleForOwner(user).omittedMemoryIds())` 다. 다른 메서드는 바꾸지 않는다.

### 3. `MemoryController` 의 변경

`ContextAssembler context` 필드를 `OmittedMemories omittedMemories` 로 바꾸고 `readable()` 의 그 줄을 `Set<Long> omitted = omittedMemories.omittedFor(user);` 로 바꾼다. `context` 의 import 를 지운다. 그 밖의 줄은 바꾸지 않는다.

### 4. 이 phase 를 검증하는 테스트

고칠 기존 테스트는 없다. `new MemoryController(` 로 만드는 테스트 다섯은 모두 `@Autowired ContextAssembler context` 를 넘기고, `ContextAssembler` 가 port 를 구현하므로 그대로 컴파일된다.
`backend/src/test/java/com/bifos/assistant/memory/MemoryControllerTest.java` 의 `marksOnlyItemsNotLoadedForLackOfSpace` 가 port 를 거친 「빠질 항목」 표시를 이미 단언한다. 그대로 통과해야 한다.

`backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` 에 테스트 둘을 더한다. 테스트 상한은 8000 자다.

- 정상: 그 파일의 기존 테스트처럼 `memories.create(ADMIN, MemoryScope.GROUP, "너무 긴 항목", "가".repeat(9_000), true)` 와 짧은 항목 하나를 만든다. `assembler.omittedFor(ADMIN)` 이 긴 항목의 번호 하나를 담고 `Set.copyOf(assembler.assembleForOwner(ADMIN).omittedMemoryIds())` 와 같다
- 경계: 모두 실리는 준비(그 파일에서 빠진 항목이 없다고 단언하는 기존 테스트의 준비)에서는 빈 집합이다

상수와 도우미 이름은 그 파일의 것을 쓴다.

### 5. ADR 의 구현 상태를 고친다

ADR-068 의 `status` 줄과 `docs/adr/INDEX.md` 의 ADR-068 줄에서 끝부분 「`chat` 이 `orchestration` 을 쓰는 하나(C3, C4)를 끊었다. `memory` 가 `context` 를 쓰는 간선과 C7 은 아직 구현 전이다」 를 「`chat` 이 `orchestration` 을 쓰는 하나(C3, C4), `memory` 가 `context` 를 쓰는 하나를 끊었다. C7 은 아직 구현 전이다」 로 고친다. 앞부분은 그대로 둔다.
고친 문서에 `한국어 문체 검사기` 을 돌려 종료 코드 0 인지 본다.

### 6. 기준 파일을 줄인다

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
```

2 줄에서 0 줄이 된다. 이와 다르거나 새 위반으로 실패하면 다시 얼리지 말고 보고한다.

## 검증

```bash
# cwd: backend/
./gradlew test
./gradlew checkstyleMain checkstyleTest
test "$(grep -c "" config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948)" -eq 0
! grep -rnE "^import (static )?com\.bifos\.assistant\.context\." src/main/java/com/bifos/assistant/memory
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/memory/application/OmittedMemories.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/presentation/MemoryController.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` | 수정 |
| `docs/adr/ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md` | 수정 |
| `docs/adr/INDEX.md` | 수정 |
| `backend/config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948` | 수정 |
