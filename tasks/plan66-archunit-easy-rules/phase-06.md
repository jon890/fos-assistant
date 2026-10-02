# Phase 06. 저장소와 domain 의 트랜잭션을 application 으로 옮긴다

**Execution profile**: standard

## 목표

저장소 인터페이스의 `@Transactional` 아홉과 `user.domain.UserProvisioningService` 의 하나를 `application` 으로 옮겨
`TRANSACTIONAL_ONLY_IN_APPLICATION` 의 기준을 0 줄로 만든다.

**범위 외**: 쿼리 본문과 `@Modifying` 옵션. 호출 순서.

## 컨텍스트

- 먼저 `tasks/plan66-archunit-easy-rules/README.md` 를 읽는다.
- 대상 저장소 메서드는 모두 `@Modifying` JPQL update 다. 트랜잭션 없이 부르면 `TransactionRequiredException` 이 난다.
  지금은 메서드에 붙은 `@Transactional` 이 「부르는 쪽에 트랜잭션이 있으면 거기 참여하고 없으면 그 문장만의 트랜잭션을 연다」 를 맡는다.
- 부르는 쪽은 모두 `application` 의 클래스이고, 오래 도는 turn 처럼 일부러 트랜잭션 밖에서 부르는 자리가 있다.
- `SubagentSessionRegistrar` 는 `TransactionTemplate` 을 쓴다. 이 phase 는 그 방식을 쓰지 않는다. 아래 의도 메모를 본다.

| 저장소 | 메서드 |
| --- | --- |
| `chat/infra/ConversationRepository.java` | `touchSession`, `assignSessionIfAbsent`, `resetAutoTurns`, `incrementAutoTurns`, `fillTitleIfBlank`, `renameIfActive`, `deleteIfActive` |
| `chat/infra/ChatArtifactRepository.java` | `markDeleted` |
| `usage/infra/AgentExecutionRepository.java` | `markResultDelivered` |

**근거 문서**: `docs/backend/packages.md` 의 「backend 패키지」, `docs/adr/ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`

## 의도 메모

- **트랜잭션이 열리고 닫히는 자리가 그대로여야 한다.** 그래서 부르는 메서드에 `@Transactional` 을 붙이지 않는다.
  붙이면 Hermes 호출까지 한 트랜잭션에 들어간다.
- 저장소 메서드와 인자가 같은 `application` 빈을 두고 그 메서드에 `@Transactional` 을 붙인다. 전파 기본값이 같아 참여와 새로 열기가 지금과 같다.
- 부르는 쪽마다 트랜잭션 유무를 분석해 애너테이션만 지우는 방법은 쓰지 않는다. 하나라도 틀리면 운영에서 예외가 난다.
- `TransactionTemplate` 을 호출부마다 넣는 방법은 손으로 만드는 테스트가 많아 바꿀 곳이 늘어난다.

## 작업 항목

### 1. `chat/application/ConversationWriter.java` 신규

`@Service`, `@RequiredArgsConstructor`, `ConversationRepository` 를 받는다.
위 표의 `ConversationRepository` 메서드 일곱과 이름, 인자, 반환 타입이 같은 메서드를 두고 각각 `@Transactional` 을 붙여 저장소에 넘긴다.
클래스 Javadoc 에 「저장소의 조건부 update 를 부르는 쪽의 트랜잭션에 참여시키고, 없으면 그 문장만의 트랜잭션을 연다」 를 적는다.

### 2. `chat/application/ChatArtifactWriter.java`, `usage/application/ExecutionDeliveryWriter.java` 신규

같은 모양이다. `ChatArtifactWriter.markDeleted(Long, String, Instant)` 와 `ExecutionDeliveryWriter.markResultDelivered(Long, Instant)` 다.
인자와 반환 타입은 저장소 인터페이스에서 읽는다.

### 3. 저장소 인터페이스의 변경

위 표의 메서드 아홉에서 `@Transactional` 과 쓰지 않게 된 import 를 지운다. `@Modifying` 과 `@Query` 는 그대로 둔다.
각 메서드의 Javadoc 에 「트랜잭션은 `<Writer 이름>` 이 연다」 를 한 줄 더한다. Javadoc 이 없으면 만든다.

### 4. 호출부의 변경

`backend/src/main/java` 에서 아래를 부르는 곳을 Writer 호출로 바꾼다. Writer 를 주입한다.

- `ConversationRepository` 의 일곱: `chat/application/ChatService.java`, `chat/application/RecoveredRunRecorder.java`, `chat/application/ConversationSessions.java`, `orchestration/application/ResearchAndBuildFlow.java`
- `ChatArtifactRepository.markDeleted`: `chat/application/ArtifactCleaner.java`
- `AgentExecutionRepository.markResultDelivered`: `chat/application/ChatService.java`, `mcp/application/McpToolService.java`, `orchestration/application/AgentDelegationService.java`

`git grep -n "\.\(touchSession\|assignSessionIfAbsent\|resetAutoTurns\|incrementAutoTurns\|fillTitleIfBlank\|renameIfActive\|deleteIfActive\|markResultDelivered\)(" -- backend/src/main` 과
`git grep -n "artifacts\.markDeleted(" -- backend/src/main` 으로 남은 직접 호출이 Writer 안에만 있는지 확인한다.
`attachment.markDeleted(...)` 와 `agent.markDeleted(...)` 는 엔티티 메서드라 대상이 아니다.

### 5. `UserProvisioningService` 를 `user.application` 으로 옮긴다

`backend/src/main/java/com/bifos/assistant/user/domain/UserProvisioningService.java` 를 `git mv` 로
`backend/src/main/java/com/bifos/assistant/user/application/UserProvisioningService.java` 로 옮기고 `package` 를 고친다.
`user/application/AllowedUserResolver.java` 와 `backend/src/test/java/com/bifos/assistant/user/FirstSignInTest.java` 의 import 를 고친다.
`git grep -n "user\.domain\.UserProvisioningService"` 가 0 건이어야 한다.
이 이동으로 `LAYER_DIRECTION` 의 기준에서 이 클래스의 줄이 빠진다. 새 위반이 나오면 README 의 다시 얼리는 절차를 따르지 말고 멈춰서 보고한다. 층이 바뀌는 이동이라 새 위반은 실제 위반이다.

### 6. 테스트 코드의 변경과 이 phase 를 검증하는 테스트

- 손으로 만드는 테스트에 Writer 를 넘긴다. 저장소 대역을 쓰는 테스트는 그 대역으로 Writer 를 만든다.
- 저장소의 위 메서드를 트랜잭션 없이 직접 부르던 테스트는 Writer 를 부르게 고친다.
- `backend/src/test/java/com/bifos/assistant/chat/ConversationWriterTest.java` 를 새로 만든다. `@SpringBootTest` 다.
  정상: 트랜잭션 밖에서 `ConversationWriter.fillTitleIfBlank` 를 부르면 1 을 돌려주고 다시 읽은 대화의 제목이 바뀌어 있다.
  실패: 트랜잭션 밖에서 `ConversationRepository.fillTitleIfBlank` 를 직접 부르면 `InvalidDataAccessApiUsageException` 이 난다.

## 검증

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
./gradlew test
test "$(wc -l < config/archunit/store/54473729-2b30-4508-9d02-64e810d6f34b)" -eq 0
! grep -n "UserProvisioningService" config/archunit/store/2790ecd4-faaa-4952-b703-a028b68814d5
! grep -rln "Transactional" src/main/java --include='*.java' | grep "/infra/\|/presentation/\|/domain/"
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
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationWriter.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatArtifactWriter.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionDeliveryWriter.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ConversationRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ChatArtifactRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/RecoveredRunRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationSessions.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ArtifactCleaner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ResearchAndBuildFlow.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentDelegationService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/user/domain/UserProvisioningService.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/user/application/UserProvisioningService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/user/application/AllowedUserResolver.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationWriterTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/**/*.java` | 수정 |
| `backend/config/archunit/store/*` | 수정 |
