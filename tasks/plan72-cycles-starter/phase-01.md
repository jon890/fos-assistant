# Phase 01. 추천 질문 생성을 chat 으로 옮긴다

**Execution profile**: standard

## 목표

`agent` 가 `chat` 과 `usage` 를 쓰는 간선을 끊는다. 두 간선의 참조가 모두 `StarterSuggestionService` 한 클래스에서 나온다.
이 서비스는 대화와 메시지를 읽어 Hermes 를 한 번 돌리고 실행 줄을 적는다. 하는 일이 `chat` 의 층이라 `chat` 으로 옮긴다(ADR-068 의 C1).

**범위 외**: 추천 질문을 만드는 조건과 프롬프트, 캐시, 응답 모양, 주소 `/api/v1/agents/{code}/starters`, 설정 prefix `assistant.starters`. 이 phase 의 diff 는 `package` 줄, `import` 줄, DTO 하나의 이동, 문서, 기준 파일뿐이어야 한다.

## 컨텍스트

- 규칙은 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 의 `TOP_LEVEL_PACKAGES_FREE_OF_CYCLES` 다. 최상위 패키지 간선 하나가 위반 하나다. `shared` 는 그래프 밖이다. 규칙은 컴파일한 클래스를 읽는다.
- 기준 파일은 `backend/config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948` 다. 손으로 고치지 않고 아래 명령으로 줄인다. 절차는 `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」 에 있다.
- 결정의 근거는 `docs/adr/ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md` 의 C1 이다. 층 순서에서 `chat` 은 `agent` 와 `usage` 보다 위다.
- 옮길 것이다. 경로는 `backend/src/main/java/com/bifos/assistant/` 아래다.

| 지금 | 옮긴 뒤 |
| --- | --- |
| `agent/application/StarterSuggestionService.java` | `chat/application/StarterSuggestionService.java` |
| `agent/application/StarterSuggestions.java` | `chat/application/StarterSuggestions.java` |
| `agent/application/StarterStatus.java` | `chat/application/StarterStatus.java` |
| `agent/application/StarterProperties.java` | `chat/application/StarterProperties.java` |
| `agent/presentation/AgentStarterController.java` | `chat/presentation/AgentStarterController.java` |

- `agent/presentation/AgentDtos.java` 안의 record `StartersView` 가 `StarterSuggestions` 를 쓴다. 이 record 를 `chat/presentation/ChatDtos.java` 로 옮긴다. `presentation` 의 요청과 응답 모양은 그 패키지의 `*Dtos.java` 하나에 모은다(`backend/AGENTS.md`).
- `StarterSuggestionService` 를 쓰는 운영 코드는 `chat/application/ChatService.java` 와 `AgentStarterController` 둘이다. 서비스는 `agent.application.AgentService` 를 쓴다. `chat` 이 `agent` 를 쓰는 것은 층 순서와 맞다.
- 테스트는 `backend/src/test/java/com/bifos/assistant/agent/StarterSuggestionServiceTest.java`, `backend/src/test/java/com/bifos/assistant/agent/AgentStarterControllerTest.java`, `backend/src/test/java/com/bifos/assistant/shared/ValidatedPropertiesBindingTest.java` 가 이 타입들을 쓴다.
- 포맷은 이 phase 에서 돌리지 않는다. 이 phase 커밋 뒤 team-lead 가 `./gradlew spotlessApply` 결과를 별도 커밋으로 낸다. import 순서를 손으로 정렬하지 않는다. BSD `sed` 는 `\b` 를 모른다. 여러 파일의 이름을 바꿀 때는 `perl -pi -e` 를 쓴다.
- 주석과 Javadoc 은 한국어로 쓴다. `gradlew` 는 `backend/` 안에 있다.

**근거 문서**: 위 ADR-068 의 C1, `docs/adr/ADR-036-추천-질문은-사용자의-대화-이력으로-모델이-만들고-메모리에만-둔다.md`, `docs/backend/agent.md` 의 「추천 질문」, `docs/backend/packages.md` 의 「패키지와 책임」

## 의도 메모

- **동작을 바꾸지 않는다.** 서비스 본문은 `package` 와 import 밖의 줄을 바꾸지 않는다. 주소가 그대로라 웹은 고치지 않는다.
- 컨트롤러 이름 `AgentStarterController` 는 그대로 둔다. 주소가 `/api/v1/agents` 아래다.
- `StarterStatus` 와 `StarterSuggestions` 를 `application.model` 로 옮기지 않는다. 이번에는 최상위 패키지만 바꾼다.
- `ArchitectureRules.java` 를 고치지 않는다.

## 작업 항목

### 1. `git mv` 로 다섯 파일을 옮기고 `package` 줄을 고친다

위 표대로 옮긴다. 같은 패키지가 되어 필요 없어진 import 는 지우고, 다른 패키지가 되어 필요해진 import(예: `agent.application.AgentService`)는 더한다.

### 2. `StartersView` 를 `ChatDtos` 로 옮긴다

`AgentDtos.java` 의 `StartersView` record 를 Javadoc 과 함께 `ChatDtos.java` 로 옮긴다. `from` 의 접근 수준과 본문은 그대로다.
`AgentStarterController` 의 import 를 `ChatDtos.StartersView` 로 고친다. `AgentDtos` 에서 쓰지 않게 된 import 를 지운다.

### 3. 참조를 고친다

- `chat/application/ChatService.java` 의 `agent.application.StarterSuggestionService` import 를 지운다. 같은 패키지가 된다
- 테스트 셋의 import 를 새 패키지로 고친다. `StarterSuggestionServiceTest.java` 와 `AgentStarterControllerTest.java` 는 `git mv` 로 `backend/src/test/java/com/bifos/assistant/chat/` 로 옮기고 `package` 줄을 고친다. 단언은 바꾸지 않는다
- `./gradlew compileJava compileTestJava` 가 통과할 때까지 고친다

### 4. 문서를 고친다

- `docs/backend/packages.md` 의 「패키지와 책임」 표에서 `agent` 의 책임에서 「추천 질문 생성」 을 빼고 `chat` 의 책임 끝에 「, 추천 질문 생성」 을 더한다
- `docs/backend/agent.md` 의 「추천 질문」 절 첫머리에 한 줄을 더한다. 「코드는 `chat` 패키지에 있다. 대화와 메시지를 읽고 실행을 적기 때문이다(ADR-068). 주소는 `/api/v1/agents/{code}/starters` 그대로다.」 그 문서의 첫 줄 소개 문장은 그대로 둔다
- `docs/adr/ADR-036-추천-질문은-사용자의-대화-이력으로-모델이-만들고-메모리에만-둔다.md` 에는 클래스의 패키지 경로가 적혀 있지 않다. 고치지 않는다. `git grep -n "agent[./]application[./]Starter\|agent[./]presentation[./]AgentStarter" -- docs backend/src AGENTS.md backend/AGENTS.md` 가 0 건이어야 한다
- ADR-068 의 `status` 줄의 구현 상태와 `docs/adr/INDEX.md` 의 ADR-068 줄의 `Accepted.` 뒤 문장을 아래로 바꾼다

「S1 부터 S3 까지 구현됐다. 순환 간선 가운데 `user` 가 `people` 과 `agent` 를 쓰는 둘과 `agent` 가 `people` 을 쓰는 하나(C5), `agent` 가 `skill` 과 `orchestration` 을 쓰는 둘, `agent` 가 `chat` 과 `usage` 를 쓰는 둘(C1)을 끊었다. 나머지 간선과 C2 부터 C4, C7 은 아직 구현 전이다」

고친 문서에 `bash /Users/nhn/personal/fos-skills/content-preview/scripts/style-check.sh <파일>` 을 돌려 종료 코드 0 인지 본다.

### 5. 기준 파일을 줄인다

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
```

22 줄에서 15 줄이 된다. 빠지는 일곱 줄은 `agent -> chat`, `agent -> usage`, `chat -> agent`, `memory -> agent`, `orchestration -> agent`, `skill -> agent`, `usage -> agent` 다. `agent` 가 순환 덩어리에서 떨어져 기준 파일에 `agent` 가 든 줄이 남지 않는다.
15 줄이 아니거나 새 위반으로 실패하면 다시 얼리지 말고 보고한다. `LAYER_DIRECTION` 과 `CONTROLLERS_HAVE_NO_NESTED_RECORDS` 에 새 위반이 없어야 한다.

### 6. 이 phase 를 검증하는 테스트

옮긴 `StarterSuggestionServiceTest.java` 와 `AgentStarterControllerTest.java` 가 단언을 바꾸지 않은 채 통과해야 한다.
정상: 대화 이력이 있으면 추천 질문을 만들어 `READY` 로 돌려준다(`StarterSuggestionServiceTest`). 실패: 볼 수 없는 에이전트의 추천은 `AGENT_NOT_FOUND` 이고 만들지 않는다(`StarterSuggestionServiceTest`). `AgentStarterControllerTest` 는 응답 모양이 그대로인지 본다.
`ValidatedPropertiesBindingTest.java` 의 `StarterProperties` import 를 고쳐 설정 바인딩이 그대로인지 확인한다.

## 검증

```bash
# cwd: backend/
./gradlew test
./gradlew checkstyleMain checkstyleTest
test "$(grep -c "" config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948)" -eq 15
! grep -n "agent" config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948
! grep -rnE "^import (static )?com\.bifos\.assistant\.(chat|usage)\." src/main/java/com/bifos/assistant/agent
! grep -rn "agent\.application\.Starter\|agent\.presentation\.AgentStarter" src
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/agent/application/StarterSuggestionService.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/agent/application/StarterSuggestions.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/agent/application/StarterStatus.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/agent/application/StarterProperties.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentStarterController.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/chat/application/StarterSuggestionService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/StarterSuggestions.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/StarterStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/StarterProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/AgentStarterController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/StarterSuggestionServiceTest.java` | 삭제 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentStarterControllerTest.java` | 삭제 |
| `backend/src/test/java/com/bifos/assistant/chat/StarterSuggestionServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/AgentStarterControllerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/shared/ValidatedPropertiesBindingTest.java` | 수정 |
| `docs/backend/packages.md` | 수정 |
| `docs/backend/agent.md` | 수정 |
| `docs/adr/ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md` | 수정 |
| `docs/adr/INDEX.md` | 수정 |
| `backend/config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948` | 수정 |
