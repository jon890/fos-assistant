# Phase 01. AgentToolService 의 응답 모델 둘을 파일로 뺀다

**Execution profile**: standard

## 목표

`agent.application.AgentToolService` 안의 공개 record `ToolView` 와 `ToolsetsView` 를 같은 패키지의 파일로 뺀다. 컨트롤러와 DTO 가 서비스를 import 해 모델을 쓰고 있다. 기준이 13 줄에서 11 줄로 준다.

**범위 외**: 도구 목록을 만드는 판정. 응답 모양.

## 컨텍스트

- 규칙은 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 의 `SERVICES_DO_NOT_EXPOSE_NESTED_TYPES` 다. 서비스(`@Service`, `@Component`, `@Repository`)와 `infra` 안의 중첩 타입은 `private` 이어야 한다. 최상위 타입은 대상이 아니다. 익명 클래스와 지역 클래스도 대상이 아니다.
- 기준 파일은 `backend/config/archunit/store/48448fa9-9176-4cf5-bcc0-f28f044ecc43` 다. 손으로 고치지 않고 아래 명령으로 줄인다. 절차는 `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」 에 있다.
- `application` 과 `domain` 은 타입 하나에 파일 하나다(`backend/AGENTS.md`).
- **동작을 바꾸지 않는다.** 타입의 칸과 메서드 본문은 그대로다. 바뀌는 것은 타입이 놓인 파일과 이름, 그리고 바깥 클래스가 쓰던 멤버의 접근 수준뿐이다. `ArchitectureRules.java` 를 고치지 않는다.
- 최상위 패키지 사이에 새 간선을 만들지 않는다. 타입은 지금 바깥 클래스와 같은 패키지에 둔다. `TOP_LEVEL_PACKAGES_FREE_OF_CYCLES` 와 `TOP_LEVEL_PACKAGES_FOLLOW_LAYER_ORDER` 와 `LAYER_DIRECTION` 의 기준은 0 줄이고 그대로여야 한다.
- 포맷은 이 phase 에서 돌리지 않는다. 이 phase 커밋 뒤 team-lead 가 `./gradlew spotlessApply` 결과를 별도 커밋으로 낸다. import 순서를 손으로 정렬하지 않는다. BSD `sed` 는 `\b` 를 모른다. 여러 파일의 이름을 바꿀 때는 `perl -pi -e` 를 쓴다.
- 주석과 Javadoc 은 한국어로 쓴다. 테스트 메서드는 영문 camelCase 이름과 한국어 `@DisplayName` 을 갖는다. `gradlew` 는 `backend/` 안에 있다.
- 바깥에서 쓰는 곳은 `agent/presentation/AgentDtos.java`, `agent/presentation/AgentToolController.java` 와 테스트 `agent/AgentToolServiceTest.java`, `agent/AgentToolServiceAccessTest.java` 다.

**근거 문서**: `docs/adr/ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`, `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」, `backend/AGENTS.md` 의 「데이터 클래스는 컨트롤러 안에 두지 않는다」

## 의도 메모

- 이름에 `Agent` 를 붙인다. `agent.presentation.AgentDtos` 에 `ToolsetView` 와 `ToolsetsView` 가 이미 있어 같은 단순 이름이 한 파일에서 겹친다.

## 작업 항목

### 1. 중첩 타입을 파일로 뺀다

| 지금 | 옮긴 뒤 |
| --- | --- |
| `AgentToolService.ToolView` | `backend/src/main/java/com/bifos/assistant/agent/application/AgentToolView.java` 의 `public record AgentToolView` |
| `AgentToolService.ToolsetsView` | `backend/src/main/java/com/bifos/assistant/agent/application/AgentToolsetsView.java` 의 `public record AgentToolsetsView` |

타입의 Javadoc 은 함께 옮긴다. 본문은 바꾸지 않는다.

### 2. 참조를 고친다

`backend/src/main/java` 와 `backend/src/test/java` 에서 `바깥.중첩` 으로 쓰던 이름과 그 import 를 새 이름으로 바꾼다. 바깥 클래스 안에서 단순 이름으로 쓰던 곳도 새 이름으로 바꾼다.
`AgentDtos` 와 `AgentToolController` 는 `AgentToolService` 를 import 하던 줄을 새 타입의 import 로 바꾼다. 컨트롤러가 서비스를 주입받는 import 는 남는다.
`./gradlew compileJava compileTestJava` 가 통과해야 한다.

### 3. 이 phase 를 검증하는 테스트

새 테스트를 만들지 않는다. `backend/src/test/java/com/bifos/assistant/agent/AgentToolServiceTest.java` 와 `backend/src/test/java/com/bifos/assistant/agent/AgentToolServiceAccessTest.java` 가 이 모델로 도구 목록의 정상 경로(켜진 도구가 그대로 온다)와 실패 경로(읽을 수 없는 에이전트는 `AGENT_NOT_FOUND`)를 단언한다. 두 테스트의 타입 이름만 고치고 단언은 바꾸지 않는다.

### 4. 기준 파일을 줄인다

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
```

13 줄에서 11 줄이 된다. 빠지는 둘은 `AgentToolService$ToolView`, `AgentToolService$ToolsetsView` 다.
이와 다르거나 다른 규칙의 새 위반으로 실패하면 다시 얼리지 말고 보고한다.

## 검증

```bash
# cwd: backend/
./gradlew test
./gradlew checkstyleMain checkstyleTest
test "$(grep -c "" config/archunit/store/48448fa9-9176-4cf5-bcc0-f28f044ecc43)" -eq 11
! grep -n "AgentToolService" config/archunit/store/48448fa9-9176-4cf5-bcc0-f28f044ecc43
! grep -rnE "AgentToolService\.(ToolView|ToolsetsView)" src
git diff --exit-code -- config/archunit/store ':!config/archunit/store/48448fa9-9176-4cf5-bcc0-f28f044ecc43'
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentToolView.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentToolsetsView.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentToolService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentToolController.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentToolServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentToolServiceAccessTest.java` | 수정 |
| `backend/config/archunit/store/48448fa9-9176-4cf5-bcc0-f28f044ecc43` | 수정 |
