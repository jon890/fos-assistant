# Phase 04. UsageController 의 저장소 호출을 application 서비스로 옮긴다

**Execution profile**: standard

## 목표

`usage.presentation.UsageController` 가 `AgentExecutionRepository` 와 `ConversationRepository` 를 바로 쓰는 위반을 없앤다.
세 조회를 `usage.application` 의 서비스로 옮긴다. `LAYER_DIRECTION` 의 기준이 7 줄에서 0 줄이 된다.

**범위 외**: 응답 모양과 주소. 내부 값을 비우는 판정(`InternalValuePolicy`). 월 합계와 묶음 합계. `usage` 가 `chat` 을 쓰는 패키지 간선은 뒤의 PR 이 port 로 끊는다. 이 phase 는 그 간선을 그대로 둔다.

## 컨텍스트

- 규칙은 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 의 `LAYER_DIRECTION` 이다. `infra` 는 `application` 만 쓸 수 있고, `infra` 가 `application` 을 쓰는 것과 `presentation` 이 `infra` 를 쓰는 것이 위반이다. `domain` 은 어느 층이나 쓴다.
- 기준 파일은 `backend/config/archunit/store/2790ecd4-faaa-4952-b703-a028b68814d5` 다. 손으로 고치지 않고 아래 명령으로 줄인다. 절차는 `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」 에 있다.
- **동작을 바꾸지 않는다.** 응답, 저장되는 값, 설정 이름, 트랜잭션이 열리는 자리가 그대로여야 한다. `ArchitectureRules.java` 를 고치지 않는다.
- 포맷은 이 phase 에서 돌리지 않는다. `spotlessApply` 결과는 team-lead 가 따로 커밋한다. import 순서를 손으로 정렬하지 않는다.
- 주석과 Javadoc 은 한국어로 쓴다. 테스트 메서드는 영문 camelCase 이름과 한국어 `@DisplayName` 을 갖는다. `gradlew` 는 `backend/` 안에 있다.
- BSD `sed` 는 `\b` 를 모른다. 여러 파일의 이름을 바꿀 때는 `perl -pi -e` 를 쓴다.
- `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageController.java` 의 `myExecutions` 가 저장소를 세 번 부른다.
  - `executions.findByUserIdAndRootExecutionIdIsNullOrderByIdDesc(user.id(), PageRequest.of(0, size))`
  - private `idsHavingChildren(page)` 안의 `executions.findParentIdsHavingChildren(...)`. 목록이 비면 부르지 않는다
  - private `conversationPublicIds(page)` 안의 `conversations.findAllById(ids)`. 대화 번호가 하나도 없으면 부르지 않는다
- 컨트롤러의 다른 메서드는 저장소를 쓰지 않는다. 옮긴 뒤 두 저장소 필드는 컨트롤러에서 사라진다.
- 지금 이 세 조회에는 트랜잭션이 없다. 각각 자기 트랜잭션으로 돈다.
- `application` 은 타입 하나에 파일 하나다(`backend/AGENTS.md` 의 「데이터 클래스는 컨트롤러 안에 두지 않는다」). 서비스 안에 공개 중첩 타입을 두지 않는다(`SERVICES_DO_NOT_EXPOSE_NESTED_TYPES`).
- `backend/src/test/java/com/bifos/assistant/usage/UsageControllerTest.java` 는 `@SpringBootTest` 이고 `new UsageController(...)` 를 인자 일곱으로 부른다.
  `QUERIES_PER_LIST = 5` 를 `getQueryExecutionCount()` 로 단언한다. 세는 것은 목록, 자식 확인, 대화 공개 식별자, 스킬 이름, 에이전트 질의다. 이 수가 달라지면 안 된다.
- `HOUSEHOLD_ZONE` 의 Javadoc 은 `AgentExecutionRepository.sumByDayBetween` 을 `{@code}` 로 가리킨다. 이 상수와 Javadoc 은 그대로 둔다.

**근거 문서**: `docs/backend/packages.md` 의 「패키지와 책임」, `docs/adr/ADR-063-관리자-전용-표시와-동작은-관리자-영역에만-두고-일반-경로의-응답은-서버가-역할에-따라-줄인다.md`, `docs/adr/ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`

## 의도 메모

- 서비스 메서드에 `@Transactional` 을 붙이지 않는다. 붙이면 세 조회가 한 트랜잭션에 묶여 지금과 달라진다.
- 조회 순서와 「비면 부르지 않는다」 조건을 그대로 옮긴다.
- 스킬 이름과 에이전트를 읽는 호출(`SkillUsageQuery`, `AgentService`)은 이미 `application` 이라 컨트롤러에 둔다.

## 작업 항목

### 1. `usage/application/RootExecutionPage.java` 신규

`public record RootExecutionPage(List<AgentExecution> executions, Set<Long> idsHavingChildren, Map<Long, UUID> conversationPublicIds)`.
Javadoc 에 세 칸의 뜻을 적는다. 생성자에서 `List.copyOf`, `Set.copyOf`, `Map.copyOf` 로 굳힌다.

### 2. `usage/application/RootExecutionQuery.java` 신규

`@Service`, `@RequiredArgsConstructor`. `AgentExecutionRepository` 와 `ConversationRepository` 를 받는다.

- `public RootExecutionPage page(Long userId, int size)` — 위 세 조회를 지금 순서대로 한다(실행 목록, 자식을 가진 번호, 대화의 공개 식별자)
- 컨트롤러의 private 메서드 `idsHavingChildren` 과 `conversationPublicIds` 를 Javadoc 과 함께 이 클래스의 private 메서드로 옮긴다

### 3. `UsageController` 의 변경

두 저장소 필드와 두 private 메서드를 지우고 `RootExecutionQuery` 를 받는다.
필드 순서는 `RootExecutionQuery rootExecutions`, `CurrentUserProvider currentUser`, `AgentService agents`, `ExecutionTreeService executionTrees`, `SkillUsageQuery skillUsage`, `UsageSummaryService summaries` 다. Lombok 이 이 순서로 생성자를 만든다.
`myExecutions` 는 `size` 를 구하고 사용자를 확인한 뒤 `rootExecutions.page(user.id(), size)` 를 부르고, 그 뒤의 스킬 이름과 에이전트 조회와 응답 변환은 지금과 같게 둔다.
쓰지 않게 된 import 를 지운다.

### 4. 기준 파일을 줄인다

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
```

이 명령이 새 위반으로 실패하면 다시 얼리지 말고 어느 규칙의 어느 줄인지 보고한다.

### 5. 이 phase 를 검증하는 `RootExecutionQueryTest.java`

`backend/src/test/java/com/bifos/assistant/usage/RootExecutionQueryTest.java` 를 새로 만든다. `@SpringBootTest` 이고 `UsageControllerTest` 의 준비 방식을 따른다.

- 정상: 루트 실행 둘과 그 하나의 자식 하나를 저장하면 `page` 의 `executions` 는 루트 둘만 번호 내림차순으로 담고, `idsHavingChildren` 은 자식을 가진 루트의 번호만 담고, `conversationPublicIds` 는 대화 번호를 그 대화의 공개 식별자로 잇는다
- 경계: 실행이 하나도 없는 사용자는 세 칸이 모두 빈다
- 경계: `size` 가 1 이면 가장 최근 루트 하나만 온다

`UsageControllerTest` 는 `RootExecutionQuery` 를 `@Autowired` 로 받아 `new UsageController(...)` 의 인자를 위 필드 순서로 맞춘다. 쓰지 않게 된 저장소 주입은 그 테스트가 다른 데 쓰지 않으면 지운다. 단언과 `QUERIES_PER_LIST` 는 바꾸지 않는다.

## 검증

```bash
# cwd: backend/
./gradlew test
./gradlew checkstyleMain checkstyleTest
test "$(wc -l < config/archunit/store/2790ecd4-faaa-4952-b703-a028b68814d5)" -eq 0
! grep -nE "^import .*\.infra\.|Repository [a-z]+;" src/main/java/com/bifos/assistant/usage/presentation/UsageController.java
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
| `backend/src/main/java/com/bifos/assistant/usage/application/RootExecutionPage.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/application/RootExecutionQuery.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageController.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/RootExecutionQueryTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/usage/UsageControllerTest.java` | 수정 |
| `backend/config/archunit/store/2790ecd4-faaa-4952-b703-a028b68814d5` | 수정 |
