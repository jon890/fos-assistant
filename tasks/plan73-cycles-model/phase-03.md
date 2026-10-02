# Phase 03. 대화의 공개 식별자를 port 로 읽는다

**Execution profile**: standard

## 목표

`usage.application.RootExecutionQuery` 와 `skill.application.SkillUsageQuery` 가 `chat` 의 `ConversationRepository` 와 `Conversation` 을 쓰지 않게 한다.
대화 번호를 공개 식별자로 바꾸는 조회를 port 로 받고 `chat` 이 구현한다. `usage -> chat` 과 `skill -> chat` 간선이 없어진다.

**범위 외**: 조회 결과와 질의 수. 사용량 목록과 스킬 합계의 응답 모양.

## 컨텍스트

- 규칙은 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 의 `TOP_LEVEL_PACKAGES_FREE_OF_CYCLES` 다. 최상위 패키지 간선 하나가 위반 하나다. `B` 에서 `A` 로 돌아올 수 있으면 간선 `A -> B` 가 위반이다. `shared` 는 그래프 밖이다. 규칙은 컴파일한 클래스를 읽는다.
- 기준 파일은 `backend/config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948` 다. 손으로 고치지 않고 아래 명령으로 줄인다. 절차는 `docs/backend/quality.md` 의 「구조 규칙의 기준 파일」 에 있다.
- 결정의 근거는 `docs/adr/ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md` 다. 층 순서는 아래에서 위로 `hermes`, `user`, `model`, `agent`, `skill`, `usage`, `memory`, `context`, `chat`, `orchestration`, `mcp`, `people`, `connector` 다.
- `LAYER_DIRECTION` 은 패키지를 넘어서도 건다. `domain` 은 어느 층이나 쓸 수 있다. `application` 은 `presentation` 과 `application` 만 접근할 수 있고 `infra` 는 `application` 만 접근할 수 있다. port 를 구현하는 클래스는 위 패키지의 `application` 에 둔다.
- `application` 과 `domain` 은 타입 하나에 파일 하나다(`backend/AGENTS.md`).
- **동작을 바꾸지 않는다.** 저장되는 값, 질의 수, 트랜잭션이 열리는 자리가 그대로여야 한다. `ArchitectureRules.java` 를 고치지 않는다.
- 포맷은 이 phase 에서 돌리지 않는다. `spotlessApply` 결과는 team-lead 가 따로 커밋한다. import 순서를 손으로 정렬하지 않는다. BSD `sed` 는 `\b` 를 모른다. 여러 파일의 이름을 바꿀 때는 `perl -pi -e` 를 쓴다.
- 주석과 Javadoc 은 한국어로 쓴다. 테스트 메서드는 영문 camelCase 이름과 한국어 `@DisplayName` 을 갖는다. `gradlew` 는 `backend/` 안에 있다.
- `usage/application/RootExecutionQuery.java` 의 private `conversationPublicIds` 는 대화 번호가 하나도 없으면 부르지 않고, 있으면 `conversations.findAllById(ids)` 로 읽어 번호와 공개 식별자의 표를 만든다. 지운 대화도 들어간다.
- `skill/application/SkillUsageQuery.java` 의 private `activeConversationPublicIds` 는 번호가 비면 `Map.of()` 를 돌려주고, 아니면 `conversations.findAllById(conversationIds)` 로 읽어 `deletedAt() == null` 인 대화만 표에 넣는다.
- `backend/src/test/java/com/bifos/assistant/usage/UsageControllerTest.java` 는 목록 한 번의 질의 수를 다섯으로 단언한다(`QUERIES_PER_LIST`).

**근거 문서**: 위 ADR-068, `docs/adr/ADR-025-대화는-주소에-공개-식별자를-쓰고-번호는-안에만-둔다.md`, `docs/backend/skill.md`

## 의도 메모

- 두 port 의 메서드 이름을 다르게 둔다. 한 클래스가 둘을 구현하고, 하나는 지운 대화를 넣고 하나는 뺀다.
- 「비면 부르지 않는다」 는 조건은 부르는 쪽에 그대로 둔다. 구현 쪽에도 빈 입력에 빈 표를 돌려주는 방어를 둔다. 빈 `in` 절은 데이터베이스마다 다르게 동작한다.
- 구현에 `@Transactional` 을 붙이지 않는다. 지금도 조회 하나가 자기 트랜잭션으로 돈다.

## 작업 항목

### 1. port 둘 신규

- `usage/application/ConversationPublicIds.java`: `Map<Long, UUID> publicIdsOf(Collection<Long> conversationIds);`. Javadoc 에 「지운 대화도 넣는다」 를 적는다
- `skill/application/ActiveConversationPublicIds.java`: `Map<Long, UUID> activePublicIdsOf(Collection<Long> conversationIds);`. Javadoc 에 「지운 대화는 뺀다」 를 적는다

### 2. `chat/application/ConversationPublicIdLookup.java` 신규

`@Service`, `@RequiredArgsConstructor`, 두 port 를 구현한다. `ConversationRepository` 를 받는다.
두 메서드의 본문은 위 두 private 메서드의 본문을 그대로 옮긴다. 빈 입력이면 `Map.of()` 를 돌려준다.

### 3. 부르는 쪽의 변경

- `RootExecutionQuery`: `ConversationRepository` 필드의 타입을 `ConversationPublicIds` 로 바꾸고 필드 이름 `conversations` 는 그대로 둔다. private 메서드의 `findAllById` 줄을 `publicIdsOf(ids)` 호출로 바꾼다. 번호가 없으면 부르지 않는 조건은 그대로 둔다
- `SkillUsageQuery`: `ConversationRepository` 필드의 타입을 `ActiveConversationPublicIds` 로 바꾸고 필드 이름 `conversations` 는 그대로 둔다. private `activeConversationPublicIds` 를 지운 뒤 호출을 port 호출로 바꾼다
- 두 클래스에서 `chat` 의 import 를 지운다

### 4. 이 phase 를 검증하는 테스트

- 두 클래스를 쓰는 기존 테스트 넷(`SkillUsageQueryTest`, `RootExecutionQueryTest`, `UsageBreakdownTest`, `UsageControllerTest`)은 모두 `@Autowired` 로 받아 고치지 않는다. 그대로 통과해야 한다
- `backend/src/test/java/com/bifos/assistant/chat/ConversationPublicIdLookupTest.java` 를 새로 만든다. `@SpringBootTest` 이고 `ConversationWriterTest` 의 준비 방식을 따른다
  - 정상: 대화 둘을 저장하면 `publicIdsOf` 가 두 번호를 각 대화의 공개 식별자로 잇는다
  - 경계: 하나를 지운 뒤 `publicIdsOf` 는 둘 다 담고 `activePublicIdsOf` 는 지우지 않은 하나만 담는다
  - 경계: 빈 목록에는 둘 다 빈 표를 돌려준다
- `UsageControllerTest` 의 `QUERIES_PER_LIST` 단언이 그대로 통과해야 한다

### 5. ADR 의 구현 상태를 고친다

두 조회가 저장소를 직접 쓴다고 적은 문서는 없다. 고칠 문서는 ADR 과 색인뿐이다.
`docs/adr/ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md` 의 `status` 줄의 구현 상태와 `docs/adr/INDEX.md` 의 ADR-068 줄의 `Accepted.` 뒤 문장을 아래로 바꾼다.

「S1 부터 S3 까지 구현됐다. 순환 간선 가운데 `user` 가 `people` 과 `agent` 를 쓰는 둘과 `agent` 가 `people` 을 쓰는 하나(C5), `agent` 가 `skill` 과 `orchestration` 을 쓰는 둘, `agent` 가 `chat` 과 `usage` 를 쓰는 둘(C1), `usage` 와 `memory` 가 `chat` 을 쓰는 둘(C2), `skill` 이 `chat` 을 쓰는 하나를 끊었다. `chat` 이 `orchestration` 을 쓰는 간선과 `memory` 가 `context` 를 쓰는 간선, C3, C4, C7 은 아직 구현 전이다」

고친 문서에 `bash /Users/nhn/personal/fos-skills/content-preview/scripts/style-check.sh <파일>` 을 돌려 종료 코드 0 인지 본다.

### 6. 기준 파일을 줄인다

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
```

14 줄에서 4 줄이 된다. 빠지는 열 줄은 `chat -> context`, `chat -> memory`, `chat -> skill`, `chat -> usage`, `memory -> usage`, `orchestration -> context`, `orchestration -> usage`, `skill -> chat`, `usage -> chat`, `usage -> skill` 이다.
남는 넷은 `chat -> orchestration`, `context -> memory`, `memory -> context`, `orchestration -> chat` 이다.
이와 다르거나 새 위반으로 실패하면 다시 얼리지 말고 보고한다.

## 검증

```bash
# cwd: backend/
./gradlew test
./gradlew checkstyleMain checkstyleTest
test "$(grep -c "" config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948)" -eq 4
! grep -n "usage\|skill" config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948
! grep -rnE "^import (static )?com\.bifos\.assistant\.chat\." src/main/java/com/bifos/assistant/usage src/main/java/com/bifos/assistant/skill src/main/java/com/bifos/assistant/memory src/main/java/com/bifos/assistant/model
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
| `backend/src/main/java/com/bifos/assistant/usage/application/ConversationPublicIds.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/ActiveConversationPublicIds.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationPublicIdLookup.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/application/RootExecutionQuery.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillUsageQuery.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationPublicIdLookupTest.java` | 신규 |
| `docs/adr/ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md` | 수정 |
| `docs/adr/INDEX.md` | 수정 |
| `backend/config/archunit/store/0fd01c41-aa58-43cb-81c5-236ae5119948` | 수정 |
