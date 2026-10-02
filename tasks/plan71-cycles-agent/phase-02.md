# Phase 02. agent 가 skill 과 orchestration 을 쓰지 않게 한다

**Execution profile**: standard

## 목표

`agent` 가 `skill.infra.SkillStore` 와 `orchestration.application.FlowRegistry` 를 쓰는 간선을 port 로 끊는다.

**범위 외**: `SkillStore` 의 본문과 위치. 흐름을 등록하고 찾는 방식. `agent` 가 `chat` 과 `usage` 를 쓰는 간선(추천 질문)은 다음 PR 이 맡는다.

## 컨텍스트

- 규칙은 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 의 `TOP_LEVEL_PACKAGES_FREE_OF_CYCLES` 다. 최상위 패키지 간선 하나가 위반 하나다. `B` 에서 `A` 로 돌아올 수 있으면 간선 `A -> B` 가 위반이다. `shared` 는 그래프 밖이다. 규칙은 컴파일한 클래스를 읽는다.
- 기준 파일은 `backend/config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948` 다. 손으로 고치지 않고 아래 명령으로 줄인다. 절차는 `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」 에 있다.
- 결정의 근거는 `docs/adr/ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md` 다. 층 순서에서 `agent` 는 `skill`, `orchestration`, `people` 보다 아래다.
- `LAYER_DIRECTION` 은 패키지를 넘어서도 건다. `infra` 는 `application` 만 접근할 수 있다. port 를 구현하는 클래스는 위 패키지의 `application` 에 둔다. `infra` 의 클래스가 port 를 직접 구현하지 않는다.
- `application` 은 타입 하나에 파일 하나다(`backend/AGENTS.md`). 서비스 안에 공개 중첩 타입을 두지 않는다.
- **동작을 바꾸지 않는다.** 호출 순서, 예외, 트랜잭션이 열리는 자리가 그대로여야 한다. `ArchitectureRules.java` 를 고치지 않는다.
- 포맷은 이 phase 에서 돌리지 않는다. `spotlessApply` 결과는 team-lead 가 따로 커밋한다. import 순서를 손으로 정렬하지 않는다.
- 주석과 Javadoc 은 한국어로 쓴다. 테스트 메서드는 영문 camelCase 이름과 한국어 `@DisplayName` 을 갖는다. `gradlew` 는 `backend/` 안에 있다.
- `agent` 가 `skill` 을 쓰는 곳은 둘이다. 경로는 `backend/src/main/java/com/bifos/assistant/` 아래다.
  - `agent/application/AgentLifecycleService.java` 의 `skillStore.deleteAll(profileName)`
  - `agent/application/AgentToolService.java` 의 `skillStore.hasUploadedSkills(agent.hermesProfile())`
- `skill/infra/SkillStore.java` 의 두 메서드는 `public boolean hasUploadedSkills(String profile)` 과 `public void deleteAll(String profile)` 이다.
- `agent` 가 `orchestration` 을 쓰는 곳은 하나다. `agent/application/AgentAdminService.java` 가 `flows.find(flow) != null` 로 흐름 이름이 있는지 본다.
- `orchestration/application/FlowRegistry.java` 의 `public Flow find(String name)` 은 모르는 이름에 null 을 돌려준다. `FlowRegistry` 는 이미 `agent.infra.AgentRepository` 를 쓴다.

**근거 문서**: 위 ADR-068, `docs/backend/skill.md`, `docs/backend/agent.md`, `docs/adr/ADR-034-올린-스킬은-control-plane-이-버전-디렉터리에-쓰고-hermes-는-읽기만-한다.md`

## 의도 메모

- `SkillStore` 가 port 를 직접 구현하지 않는다. `infra` 가 다른 패키지의 `application` 을 쓰게 되어 `LAYER_DIRECTION` 을 어긴다. `skill.application` 에 넘겨주는 클래스를 둔다.
- 넘겨주는 클래스에 `@Transactional` 을 붙이지 않는다. 파일 시스템 작업이고 지금도 부르는 쪽의 흐름 안에서 돈다.

## 작업 항목

### 1. `agent/application/ProfileSkillFiles.java` 신규와 구현

```java
public interface ProfileSkillFiles {
    boolean hasUploaded(String profile);

    void deleteAll(String profile);
}
```

`skill/application/ProfileSkillFilesAdapter.java` 를 새로 만든다. `@Service`, `@RequiredArgsConstructor`, `implements ProfileSkillFiles`. `SkillStore` 를 받아 `hasUploaded` 는 `hasUploadedSkills(profile)` 로, `deleteAll` 은 `deleteAll(profile)` 로 넘긴다.

### 2. `agent/application/KnownFlows.java` 신규와 구현

```java
public interface KnownFlows {
    boolean known(String name);
}
```

`FlowRegistry` 에 `implements KnownFlows` 를 더하고 `@Override public boolean known(String name) { return find(name) != null; }` 을 더한다. 이미 구현하는 `ApplicationRunner` 는 그대로 둔다.

### 3. `agent` 서비스 셋의 변경

- `AgentLifecycleService`: `SkillStore skillStore` 를 `ProfileSkillFiles skillFiles` 로 바꾸고 호출을 `skillFiles.deleteAll(profileName)` 으로 바꾼다
- `AgentToolService`: 같은 필드 변경. 호출을 `skillFiles.hasUploaded(agent.hermesProfile())` 로 바꾼다
- `AgentAdminService`: `FlowRegistry flows` 를 `KnownFlows flows` 로 바꾸고 `flows.find(flow) != null` 을 `flows.known(flow)` 로 바꾼다

그 밖의 줄은 바꾸지 않는다. 생성자 인자의 순서는 필드 순서를 따르므로 필드 자리를 옮기지 않는다.

### 4. 기준 파일을 줄인다

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
```

`agent -> skill`, `agent -> orchestration` 줄이 빠진다. 다른 줄이 함께 빠질 수 있다. 줄이 늘거나 새 위반으로 실패하면 다시 얼리지 말고 보고한다. 끝난 뒤의 줄 수와 빠진 줄을 회신에 적는다.

### 5. 이 phase 를 검증하는 테스트

- `new AgentLifecycleService(`, `new AgentToolService(`, `new AgentAdminService(` 를 부르는 테스트의 인자를 맞춘다. `backend/src/test/java/com/bifos/assistant/agent/AgentApiBaseUrlUpdateTest.java`, `AgentToolServiceTest.java`, `AgentLifecycleServiceTest.java` 가 있다. `git grep -n "new AgentLifecycleService(\|new AgentToolService(\|new AgentAdminService(" -- backend/src/test` 로 모두 찾는다. `SkillStore` 나 `FlowRegistry` 를 실제 객체로 넘기던 자리는 그 객체를 감싼 port 구현(`new ProfileSkillFilesAdapter(skillStore)` 나 그 `FlowRegistry`)을 넘긴다. 단언은 바꾸지 않는다
- `backend/src/test/java/com/bifos/assistant/skill/ProfileSkillFilesAdapterTest.java` 를 새로 만든다. `SkillStoreTest` 가 임시 디렉터리로 `SkillStore` 를 만드는 방식을 따른다
  - 정상: 스킬을 하나 쓴 profile 은 `hasUploaded` 가 true 이고, `deleteAll` 뒤에는 false 다
  - 경계: 아무것도 쓰지 않은 profile 은 `hasUploaded` 가 false 이고 `deleteAll` 이 예외 없이 끝난다
- `backend/src/test/java/com/bifos/assistant/orchestration/FlowRegistryTest.java` 가 있으면 거기에, 없으면 새로 만들어 테스트 둘을 더한다. 등록된 흐름 이름은 `known` 이 true 이고 모르는 이름과 null 은 false 다. `find(null)` 이 예외를 내면 `known(null)` 도 같은 예외를 내는 것으로 단언한다

## 검증

```bash
# cwd: backend/
./gradlew test
./gradlew checkstyleMain checkstyleTest
! grep -n "^agent -> \(people\|skill\|orchestration\) " config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948
! grep -rnE "^import (static )?com\.bifos\.assistant\.(people|skill|orchestration)\." src/main/java/com/bifos/assistant/agent
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/agent/application/ProfileSkillFiles.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/KnownFlows.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/ProfileSkillFilesAdapter.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/FlowRegistry.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentLifecycleService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentToolService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentAdminService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/skill/ProfileSkillFilesAdapterTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/**/*.java` | 수정 |
| `backend/config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948` | 수정 |
