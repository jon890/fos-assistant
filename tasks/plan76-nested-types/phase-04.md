# Phase 04. AgentRunner 의 실행 결과를 파일로 빼고 기준을 비운다

**Execution profile**: standard

## 목표

`orchestration.application.AgentRunner` 안의 공개 record `Run` 을 같은 패키지의 파일로 뺀다. 기준이 1 줄에서 0 줄이 되어 이슈 66 의 표에 든 규칙이 모두 빈다.

**범위 외**: 자식 실행을 돌리는 흐름과 실패 처리.

## 컨텍스트

- 규칙은 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 의 `SERVICES_DO_NOT_EXPOSE_NESTED_TYPES` 다. 서비스(`@Service`, `@Component`, `@Repository`)와 `infra` 안의 중첩 타입은 `private` 이어야 한다. 최상위 타입은 대상이 아니다. 익명 클래스와 지역 클래스도 대상이 아니다.
- 기준 파일은 `backend/config/archunit/store/48448fa9-9176-4cf5-bcc0-f28f044ecc43` 다. 손으로 고치지 않고 아래 명령으로 줄인다. 절차는 `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」 에 있다.
- `application` 과 `domain` 은 타입 하나에 파일 하나다(`backend/AGENTS.md`).
- **동작을 바꾸지 않는다.** 타입의 칸과 메서드 본문은 그대로다. 바뀌는 것은 타입이 놓인 파일과 이름, 그리고 바깥 클래스가 쓰던 멤버의 접근 수준뿐이다. `ArchitectureRules.java` 를 고치지 않는다.
- 최상위 패키지 사이에 새 간선을 만들지 않는다. 타입은 지금 바깥 클래스와 같은 패키지에 둔다. `TOP_LEVEL_PACKAGES_FREE_OF_CYCLES` 와 `TOP_LEVEL_PACKAGES_FOLLOW_LAYER_ORDER` 와 `LAYER_DIRECTION` 의 기준은 0 줄이고 그대로여야 한다.
- 포맷은 이 phase 에서 돌리지 않는다. 이 phase 커밋 뒤 team-lead 가 `./gradlew spotlessApply` 결과를 별도 커밋으로 낸다. import 순서를 손으로 정렬하지 않는다. BSD `sed` 는 `\b` 를 모른다. 여러 파일의 이름을 바꿀 때는 `perl -pi -e` 를 쓴다.
- 주석과 Javadoc 은 한국어로 쓴다. 테스트 메서드는 영문 camelCase 이름과 한국어 `@DisplayName` 을 갖는다. `gradlew` 는 `backend/` 안에 있다.
- 바깥에서 쓰는 운영 코드는 `orchestration/application/ResearchAndBuildFlow.java` 다. 테스트는 `usage/FailedExecutionUsageRoutesTest.java`, `orchestration/AgentRunnerSubmitFailureTest.java`, `orchestration/AgentRunnerConnectorContextTest.java` 다.
- 이 phase 뒤 `backend/config/archunit/store/` 의 기준 파일은 모두 0 줄이다.

**근거 문서**: `docs/adr/ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`, `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」, `backend/AGENTS.md` 의 「데이터 클래스는 컨트롤러 안에 두지 않는다」

## 의도 메모

- 이름을 `AgentRun` 으로 한다. `Run` 은 최상위 타입으로는 뜻이 넓다.

## 작업 항목

### 1. 중첩 타입을 파일로 뺀다

| 지금 | 옮긴 뒤 |
| --- | --- |
| `AgentRunner.Run` | `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRun.java` 의 `public record AgentRun` |

타입의 Javadoc 은 함께 옮긴다. 본문은 바꾸지 않는다.

### 2. 참조를 고친다

`backend/src/main/java` 와 `backend/src/test/java` 에서 `바깥.중첩` 으로 쓰던 이름과 그 import 를 새 이름으로 바꾼다. 바깥 클래스 안에서 단순 이름으로 쓰던 곳도 새 이름으로 바꾼다.

`./gradlew compileJava compileTestJava` 가 통과해야 한다.

### 3. 이 phase 를 검증하는 테스트

- 타입 이름만 바뀌는 기존 테스트 셋은 이름만 고치고 단언은 바꾸지 않는다. `backend/src/test/java/com/bifos/assistant/orchestration/AgentRunnerSubmitFailureTest.java` 가 제출이 실패한 실행의 결과(실패 경로)를, `backend/src/test/java/com/bifos/assistant/orchestration/AgentRunnerConnectorContextTest.java` 가 정상 실행의 결과를 이 record 로 단언한다
- 문서를 고친다. `backend/AGENTS.md` 의 「품질 검사」 에서 「**기준 파일에 든 위반은 허용이 아니라 줄여 갈 목록이다.** 기준마다 연 GitHub 이슈가 있다.」 다음에 「구조 규칙의 기준은 지금 모두 비어 있다. 파일은 새 위반을 받아들여야 할 때를 위해 남긴다.」 를 더한다. `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」 첫 문단에도 같은 뜻의 한 줄을 더한다. 고친 문서에 `한국어 문체 검사기 <파일>` 을 돌려 종료 코드 0 인지 본다

### 4. 기준 파일을 줄인다

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
```

1 줄에서 0 줄이 된다.
이와 다르거나 다른 규칙의 새 위반으로 실패하면 다시 얼리지 말고 보고한다.

## 검증

```bash
# cwd: backend/
./gradlew test
./gradlew checkstyleMain checkstyleTest
test "$(cat config/archunit/store/*-* | grep -c "")" -eq 0
! grep -rnE "AgentRunner\.Run\b" src
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
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRun.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ResearchAndBuildFlow.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/FailedExecutionUsageRoutesTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/AgentRunnerSubmitFailureTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/AgentRunnerConnectorContextTest.java` | 수정 |
| `backend/AGENTS.md` | 수정 |
| `docs/backend/quality.md` | 수정 |
| `backend/config/archunit/store/48448fa9-9176-4cf5-bcc0-f28f044ecc43` | 수정 |
