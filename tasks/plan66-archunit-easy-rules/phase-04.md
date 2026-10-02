# Phase 04. 엔티티가 시각을 인자로 받는다

**Execution profile**: standard

## 목표

`*.domain` 의 엔티티가 생성자와 메서드 안에서 `Instant.now()` 를 부르는 자리를 인자로 받은 `Instant` 로 바꿔
`NO_DIRECT_INSTANT_NOW` 의 기준을 0 줄로 만든다. 엔티티에는 빈을 주입할 수 없으므로 부르는 서비스가 시각을 넘긴다.

**범위 외**: 엔티티의 다른 칸과 저장 모양. 마이그레이션은 만들지 않는다. 칸과 타입이 바뀌지 않는다.

## 컨텍스트

- 먼저 `tasks/plan66-archunit-easy-rules/README.md` 를 읽는다.
- 앞 phase 에서 `application` 과 `infra` 의 서비스가 `Clock` 을 주입받았다. 부르는 쪽은 `clock.instant()` 를 넘긴다.
- 이미 시각을 인자로 받는 본보기: `backend/src/main/java/com/bifos/assistant/chat/domain/ChatAttachment.java` 의 `markDeleted(Instant)`,
  `backend/src/main/java/com/bifos/assistant/skill/domain/ExecutionSkillUse.java` 의 `of(..., Instant)`.

**근거 문서**: `docs/adr/ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`

## 의도 메모

- `@PrePersist` 로 시각을 채우지 않는다. 저장하기 전의 객체에서 `createdAt` 이 비게 되어 동작이 바뀐다.
- 정적 시계 보관 클래스를 만들지 않는다.
- 한 메서드 안에서 엔티티에 넘기는 시각과 바로 옆의 저장소 호출에 넘기는 시각은 `clock.instant()` 를 한 번 불러 같은 값을 쓴다.
- 테스트 코드는 이 규칙의 대상이 아니다. 테스트에서 엔티티를 만들 때는 `Instant.now()` 나 그 테스트의 고정 시각을 넘긴다.

## 작업 항목

### 1. 엔티티 아홉의 시그니처 변경

`backend/src/main/java/com/bifos/assistant/` 아래다.
`Instant.now()` 를 부르는 생성자에 닿는 **모든 정적 팩터리와 생성자**의 마지막 인자로 `Instant now` 를 더한다.
한 엔티티에 팩터리가 여럿이면 모두 더한다.

| 파일 | 바꿀 것 |
| --- | --- |
| `agent/domain/Agent.java` | 생성자와 팩터리. `createdAt = now` |
| `chat/domain/ChatArtifact.java` | 생성자와 팩터리 |
| `chat/domain/ChatAttachment.java` | 생성자와 팩터리. 이미 받는 `expiresAt` 뒤에 `now` 를 더한다 |
| `chat/domain/ChatMessage.java` | 생성자와 팩터리 |
| `chat/domain/Conversation.java` | 생성자와 팩터리, `rememberSession(String sessionId, Instant now)` |
| `mcp/domain/AgentToken.java` | 생성자와 팩터리, `markUsed(Instant now)`, `revoke(Instant now)` |
| `orchestration/domain/HermesSessionBinding.java` | 생성자와 팩터리 |
| `people/domain/AllowedPerson.java` | 생성자와 팩터리 |
| `user/domain/AppUser.java` | 생성자와 팩터리 |

### 2. 부르는 운영 코드의 변경

`git grep -n "<엔티티>\.\(of\|[a-zA-Z]*\)(" -- backend/src/main` 으로 부르는 곳을 모두 찾아 `clock.instant()` 를 넘긴다.
`Clock` 필드가 없는 클래스에는 더한다. `presentation` 의 컨트롤러가 엔티티를 직접 만드는 자리(`AgentAdminController.create`)에도 `Clock` 을 주입해 넘긴다.

### 3. 테스트 코드의 호출부 변경

`backend/src/test/java` 에서 위 팩터리와 메서드를 부르는 곳에 시각 인자를 더한다. 수백 곳이다.
컴파일 오류가 0 이 될 때까지 `./gradlew compileTestJava` 로 확인한다. 테스트의 단언과 흐름은 바꾸지 않는다.

### 4. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/chat/EntityClockTest.java` 를 새로 만든다.

- `Conversation` 을 고정 시각으로 만들면 `createdAt` 과 `updatedAt` 이 그 시각이다
- `rememberSession("s", later)` 뒤에 `updatedAt` 이 `later` 다
- `AgentToken` 을 `revoke(first)` 한 뒤 `revoke(second)` 해도 폐기 시각이 `first` 로 남는다

접근자 이름은 각 엔티티 파일에서 읽는다.

## 검증

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
./gradlew test
test "$(wc -l < config/archunit/store/d3d721a0-86e5-4069-8051-dd3e7adf2c55)" -eq 0
! grep -rn "Instant\.now()" src/main/java --include='*.java'
```

```bash
# cwd: 저장소 root. Docker 가 있어야 한다
scripts/check-mysql-migration.sh
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/agent/domain/Agent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/ChatArtifact.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/ChatAttachment.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/ChatMessage.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/Conversation.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/domain/AgentToken.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/domain/HermesSessionBinding.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/people/domain/AllowedPerson.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/user/domain/AppUser.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/**/*.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/EntityClockTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/**/*.java` | 수정 |
| `backend/config/archunit/store/*` | 수정 |
