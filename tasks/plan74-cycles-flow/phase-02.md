# Phase 02. 위임 답 자르기를 chat 의 port 로 받는다

**Execution profile**: standard

## 목표

`chat.application.RecoveredRunRecorder` 가 `orchestration.application.DelegationOutput` 을 쓰지 않게 한다(ADR-068 의 C4).
`chat` 에 port 를 두고 `DelegationOutput` 이 구현한다. `chat -> orchestration` 간선이 없어져 순환 기준이 4 줄에서 2 줄이 된다.

**범위 외**: 위임 답을 자르는 규칙과 상한(`DelegationProperties`). 재기동 뒤 남은 실행을 정리하는 흐름.

## 컨텍스트

- 규칙은 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 의 `TOP_LEVEL_PACKAGES_FREE_OF_CYCLES` 다. 최상위 패키지 간선 하나가 위반 하나다. `B` 에서 `A` 로 돌아올 수 있으면 간선 `A -> B` 가 위반이다. `shared` 는 그래프 밖이다. 규칙은 컴파일한 클래스를 읽는다.
- 기준 파일은 `backend/config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948` 이고 지금 4 줄이다. `chat -> orchestration`, `context -> memory`, `memory -> context`, `orchestration -> chat` 이다. 손으로 고치지 않고 아래 명령으로 줄인다. 절차는 `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」 에 있다.
- 결정의 근거는 `docs/adr/ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md` 의 C3 과 C4 다. 층 순서에서 `orchestration` 은 `chat` 보다 위다. `orchestration` 이 `chat` 을 쓰는 것은 맞는 방향이다.
- `LAYER_DIRECTION` 은 패키지를 넘어서도 건다. `domain` 은 어느 층이나 쓸 수 있다. `application` 은 `presentation` 과 `application` 만 접근할 수 있다. port 를 구현하는 클래스는 위 패키지의 `application` 에 둔다.
- `application` 과 `domain` 은 타입 하나에 파일 하나다(`backend/AGENTS.md`).
- **동작을 바꾸지 않는다.** `ArchitectureRules.java` 를 고치지 않는다.
- 포맷은 이 phase 에서 돌리지 않는다. 이 phase 커밋 뒤 team-lead 가 `./gradlew spotlessApply` 결과를 별도 커밋으로 낸다. import 순서를 손으로 정렬하지 않는다. BSD `sed` 는 `\b` 를 모른다. 여러 파일의 이름을 바꿀 때는 `perl -pi -e` 를 쓰고 패턴 끝을 `;` 로 고정한다.
- 주석과 Javadoc 은 한국어로 쓴다. 테스트 메서드는 영문 camelCase 이름과 한국어 `@DisplayName` 을 갖는다. `gradlew` 는 `backend/` 안에 있다.
- `backend/src/main/java/com/bifos/assistant/orchestration/application/DelegationOutput.java` 는 `@Component` 이고 `public String clip(String output)` 과 `public String partial(String output)` 을 갖는다. 상한은 `DelegationProperties` 에서 읽는다.
- 쓰는 곳은 `chat/application/RecoveredRunRecorder.java`(두 곳)와 `orchestration/application/AgentRunner.java`(두 곳)다. `AgentRunner` 는 같은 패키지라 그대로 둔다.
- 앞 phase 에서 `Flow`, `FlowRegistry`, `DelegationFinished`, `RunSession` 이 `chat` 으로 옮겨졌다. `chat` 의 main 이 `orchestration` 을 import 하는 곳은 `RecoveredRunRecorder` 의 이 한 줄만 남았다.

**근거 문서**: 위 ADR-068 의 C4, `docs/backend/agent-delegation.md`, `docs/adr/ADR-061-재기동-때-남은-실행은-hermes-에-물어-정하고-도는-실행에는-다시-붙는다.md`

## 의도 메모

- `DelegationOutput` 을 `chat` 으로 옮기지 않는다. 상한을 가진 `DelegationProperties` 가 `orchestration` 에 있고 위임을 끝까지 돌린 자리도 같은 규칙을 쓴다.
- `RestartReconciler` 와 `RecoveredRunRecorder` 를 `orchestration` 으로 올리지 않는다(ADR-068 의 C4).

## 작업 항목

### 1. `chat/application/DelegationOutputClip.java` 신규

```java
public interface DelegationOutputClip {
    String clip(String output);

    String partial(String output);
}
```

Javadoc 에 「위임 실행의 답을 실행 줄에 적을 길이로 맞춘다. 상한을 가진 `orchestration` 이 구현한다」 를 적고, 두 메서드의 Javadoc 은 `DelegationOutput` 의 것을 옮겨 적는다.

### 2. `DelegationOutput` 이 port 를 구현한다

`implements DelegationOutputClip` 과 `@Override` 둘을 더한다. 본문은 바꾸지 않는다.

### 3. `RecoveredRunRecorder` 의 변경

필드 타입을 `DelegationOutput` 에서 `DelegationOutputClip` 으로 바꾼다. 필드 이름 `delegationOutput` 과 호출 두 줄은 그대로 둔다. `orchestration` 의 import 를 지운다.

### 4. 이 phase 를 검증하는 테스트

- `RecoveredRunRecorder` 를 `new` 로 만드는 테스트의 인자를 맞춘다. `git grep -n "new RecoveredRunRecorder(" -- backend/src/test` 로 찾는다. `DelegationOutput` 객체는 그대로 넘길 수 있다. 단언은 바꾸지 않는다
- `backend/src/test/java/com/bifos/assistant/orchestration/DelegationOutputTest.java` 를 새로 만든다. 같은 이름의 테스트가 이미 있으면 거기에 더한다. `new DelegationOutput(new DelegationProperties(...))` 로 상한을 작게 주어 만든다. `DelegationProperties` 의 칸은 그 record 에서 읽는다
  - 정상: 상한보다 짧은 답은 `clip` 이 그대로 돌려준다
  - 경계: 상한을 넘는 답은 상한까지 자르고 잘렸다는 한 줄을 붙인다. 상한 자리에서 대리 쌍이 갈리면 그 앞에서 자른다
  - 실패: null 은 `clip` 이 빈 글을, `partial` 이 null 을 돌려준다. 공백뿐인 답도 `partial` 이 null 이다
  - port 타입(`DelegationOutputClip`)으로 받아 불러도 같은 결과다

### 5. 문서와 ADR 의 구현 상태를 고친다

- ADR-068 의 `status` 줄의 구현 상태와 `docs/adr/INDEX.md` 의 ADR-068 줄의 `Accepted.` 뒤 문장을 그 시점의 문장을 읽고 고친다. 끊은 간선에 「`chat` 이 `orchestration` 을 쓰는 하나(C3, C4)」 를 더하고 「아직 구현 전」 목록에서 그 간선과 C3, C4 를 뺀다

고친 문서에 `bash /Users/nhn/personal/fos-skills/content-preview/scripts/style-check.sh <파일>` 을 돌려 종료 코드 0 인지 본다.

### 6. 기준 파일을 줄인다

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
```

4 줄에서 2 줄이 된다. 빠지는 줄은 `chat -> orchestration` 과 `orchestration -> chat` 이다. 남는 둘은 `context -> memory` 와 `memory -> context` 다.
이와 다르거나 새 위반으로 실패하면 다시 얼리지 말고 보고한다.

## 검증

```bash
# cwd: backend/
./gradlew test
./gradlew checkstyleMain checkstyleTest
test "$(grep -c "" config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948)" -eq 2
! grep -n "orchestration\|chat" config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948
! grep -rnE "^import (static )?com\.bifos\.assistant\.orchestration\." src/main/java/com/bifos/assistant/chat
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/application/DelegationOutputClip.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/DelegationOutput.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/RecoveredRunRecorder.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/DelegationOutputTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/**/*.java` | 수정 |
| `docs/adr/ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md` | 수정 |
| `docs/adr/INDEX.md` | 수정 |
| `backend/config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948` | 수정 |
