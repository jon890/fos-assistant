# Phase 02. TurnCancellation 의 turn 핸들과 실행 참조를 파일로 뺀다

**Execution profile**: deep

## 목표

`chat.application.TurnCancellation` 안의 공개 클래스 `TurnHandle` 과 `RunRef` 를 같은 패키지의 파일로 뺀다. 기준이 11 줄에서 9 줄로 준다.

**범위 외**: 중지와 재개의 판정, 잠금, 스트림을 닫는 순서. `TurnCancellation` 의 공개 메서드 시그니처는 타입 이름만 바뀐다.

## 컨텍스트

- 규칙은 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 의 `SERVICES_DO_NOT_EXPOSE_NESTED_TYPES` 다. 서비스(`@Service`, `@Component`, `@Repository`)와 `infra` 안의 중첩 타입은 `private` 이어야 한다. 최상위 타입은 대상이 아니다. 익명 클래스와 지역 클래스도 대상이 아니다.
- 기준 파일은 `backend/config/archunit/store/48448fa9-9176-4cf5-bcc0-f28f044ecc43` 다. 손으로 고치지 않고 아래 명령으로 줄인다. 절차는 `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」 에 있다.
- `application` 과 `domain` 은 타입 하나에 파일 하나다(`backend/AGENTS.md`).
- **동작을 바꾸지 않는다.** 타입의 칸과 메서드 본문은 그대로다. 바뀌는 것은 타입이 놓인 파일과 이름, 그리고 바깥 클래스가 쓰던 멤버의 접근 수준뿐이다. `ArchitectureRules.java` 를 고치지 않는다.
- 최상위 패키지 사이에 새 간선을 만들지 않는다. 타입은 지금 바깥 클래스와 같은 패키지에 둔다. `TOP_LEVEL_PACKAGES_FREE_OF_CYCLES` 와 `TOP_LEVEL_PACKAGES_FOLLOW_LAYER_ORDER` 와 `LAYER_DIRECTION` 의 기준은 0 줄이고 그대로여야 한다.
- 포맷은 이 phase 에서 돌리지 않는다. 이 phase 커밋 뒤 team-lead 가 `./gradlew spotlessApply` 결과를 별도 커밋으로 낸다. import 순서를 손으로 정렬하지 않는다. 쓰지 않게 된 import 는 이 phase 에서 지운다. checkstyle 의 `UnusedImports` 가 error 다. BSD `sed` 는 `\b` 를 모른다. 여러 파일의 이름을 바꿀 때는 `perl -pi -e` 를 쓴다.
- 주석과 Javadoc 은 한국어로 쓴다. 테스트 메서드는 영문 camelCase 이름과 한국어 `@DisplayName` 을 갖는다. `gradlew` 는 `backend/` 안에 있다.
- 두 클래스는 Lombok `@Getter` 가 붙은 `static final class` 이고 칸과 생성자가 `private` 이다. 바깥 클래스 `TurnCancellation` 이 중첩 관계라 그 `private` 칸을 직접 읽고 쓴다(`handle.stream`, `handle.runs`, `volatile` 칸 대입, `new TurnHandle(...)`, `new RunRef(...)`).
- 바깥에서 쓰는 운영 코드는 `chat/application/ChatService.java`, `RestartReconciler.java`, `NextTurnDispatcher.java`, `DelegationWakeService.java` 다. 테스트는 열 개이고 「변경 파일」 에 모두 적었다.

**근거 문서**: `docs/adr/ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`, `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」, `backend/AGENTS.md` 의 「데이터 클래스는 컨트롤러 안에 두지 않는다」

## 의도 메모

- **동시성 코드다.** 파일로 빼면 `TurnCancellation` 이 `private` 칸에 닿지 못한다. 칸과 생성자의 접근 수준을 패키지 전용으로 낮추고(`private` 을 지운다) `TurnCancellation` 의 메서드 본문에서 바뀌는 것은 `RunRef` 라는 이름 네 곳뿐이다. 그 밖에 중첩 클래스 둘과 그것만 쓰던 import 가 빠진다. getter 호출로 바꾸지 않는다. `volatile`, `final`, 초기값, 칸의 순서를 그대로 둔다.
- 칸을 패키지 전용으로 낮추면 같은 패키지의 다른 클래스도 닿을 수 있게 된다. 두 클래스의 Javadoc 에 「칸은 `TurnCancellation` 만 고친다」 를 적는다.
- `RunRef` 는 `TurnRunRef` 로 이름을 바꾼다. 최상위 타입이 되면 어느 실행의 참조인지 이름에 없기 때문이다.

## 작업 항목

### 1. 중첩 타입을 파일로 뺀다

| 지금 | 옮긴 뒤 |
| --- | --- |
| `TurnCancellation.TurnHandle` | `backend/src/main/java/com/bifos/assistant/chat/application/TurnHandle.java` 의 `public final class TurnHandle` |
| `TurnCancellation.RunRef` | `backend/src/main/java/com/bifos/assistant/chat/application/TurnRunRef.java` 의 `public final class TurnRunRef` |

타입의 Javadoc 은 함께 옮긴다. 본문은 바꾸지 않는다.

### 2. 참조를 고친다

`backend/src/main/java` 와 `backend/src/test/java` 에서 `바깥.중첩` 으로 쓰던 이름과 그 import 를 새 이름으로 바꾼다. 바깥 클래스 안에서 단순 이름으로 쓰던 곳도 새 이름으로 바꾼다.

`./gradlew compileJava compileTestJava` 가 통과해야 한다.

### 3. 이 phase 를 검증하는 테스트

- 타입 이름만 바뀌는 기존 테스트는 이름만 고치고 단언은 바꾸지 않는다. `backend/src/test/java/com/bifos/assistant/chat/application/TurnCancellationTest.java` 와 `backend/src/test/java/com/bifos/assistant/chat/application/TurnCancellationCloseTest.java` 가 중지의 정상 경로와 실패 경로(이미 끝난 turn 은 중지되지 않는다)를 단언한다
- `backend/src/test/java/com/bifos/assistant/chat/application/TurnCancellationTest.java` 에 테스트 하나를 더한다. `open` 으로 연 핸들의 `userId()`, `getConversationId()` 가 연 값이고 `cancelled()` 가 처음에 false 이며 `cancel` 뒤 true 다. `cancelled()` 는 `AtomicBoolean` 을 돌려주므로 `handle.cancelled().get()` 으로 단언한다. 그 밖의 접근자 이름은 `TurnHandle` 에서 읽는다

### 4. 기준 파일을 줄인다

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
```

11 줄에서 9 줄이 된다. 빠지는 둘은 `TurnCancellation$RunRef`, `TurnCancellation$TurnHandle` 이다.
이와 다르거나 다른 규칙의 새 위반으로 실패하면 다시 얼리지 말고 보고한다.

## 검증

```bash
# cwd: backend/
./gradlew test
./gradlew checkstyleMain checkstyleTest
test "$(grep -c "" config/archunit/store/48448fa9-9176-4cf5-bcc0-f28f044ecc43)" -eq 9
! grep -n "TurnCancellation" config/archunit/store/48448fa9-9176-4cf5-bcc0-f28f044ecc43
! grep -rnE "TurnCancellation\.(TurnHandle|RunRef)" src
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
| `backend/src/main/java/com/bifos/assistant/chat/application/TurnHandle.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/TurnRunRef.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/TurnCancellation.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/RestartReconciler.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/NextTurnDispatcher.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/DelegationWakeService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/application/RestartReconcilerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/application/TurnCancellationTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/application/TurnCancellationCloseTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatRunningTurnTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConnectorActionDeliveryTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/DelegationWakeServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/PendingBeforeDelegationTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/PendingMessageServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/presentation/PendingMessageControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/AgentDelegationServiceTest.java` | 수정 |
| `backend/config/archunit/store/48448fa9-9176-4cf5-bcc0-f28f044ecc43` | 수정 |
