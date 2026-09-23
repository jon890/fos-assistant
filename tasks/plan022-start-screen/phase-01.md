# Phase 01. 에이전트 소개와 추천 질문을 저장하고 경로로 연다

**Execution profile**: standard

## 목표

에이전트마다 한 줄 소개와 추천 질문 넷까지를 데이터베이스에 두고,
`GET /api/v1/agents` 와 `GET`, `PUT /api/v1/agents/{code}/starters` 로 읽고 쓰게 한다.
새 대화 화면이 에이전트를 고를 때 보일 것을 한 번의 목록 조회로 받게 하기 위해서다.

**범위 외**: 웹 화면과 서버 라우트는 다음 phase 가 맡는다.
성격(`SOUL.md`)을 읽고 쓰는 경로는 바꾸지 않는다. 권한 판정 메서드를 옮기는 것만 한다.

## 컨텍스트

- 에이전트 엔티티는 `backend/src/main/java/com/bifos/assistant/agent/domain/Agent.java` 다.
  getter 는 `code()`, `ownerUserId()` 처럼 필드 이름을 그대로 쓴다.
- 자식 표의 선례는 `agent_model_option` 이다.
  엔티티 `agent/domain/AgentModelOption.java`, 저장소 `agent/infra/AgentModelOptionRepository.java`
  (`findByAgentIdOrderByRankAsc`, `deleteByAgentId`),
  갈아 끼우는 쪽 `agent/application/AgentModelSelector.java` 의 `replace` 가
  `deleteByAgentId` 다음에 `flush()` 를 부르고 새 줄을 넣는다.
  **`flush()` 를 빼면 유일 제약에 걸린다.** 선례에서는 `(agent_id, option_rank)` 이고
  (`V14__agent_model_option.sql:11`), 새 표에서는 `(agent_id, position)` 이다. 같은 순서를 따른다.
- 마이그레이션 선례는 `backend/src/main/resources/db/migration/V14__agent_model_option.sql` 이다.
  JPA 는 `ddl-auto: validate` 라서 엔티티의 칸과 마이그레이션이 어긋나면 기동이 실패한다.
- 볼 수 있는지는 `AgentService.requireReadable(CurrentUser, String code)` 가 판정한다.
  볼 수 없거나 없으면 `ErrorCode.AGENT_NOT_FOUND` 다.
- 고칠 수 있는지는 지금 `agent/application/PersonaService.java` 의
  `private static boolean editable(CurrentUser user, Agent agent)` 가 판정한다.
  `user.isAdmin() || Objects.equals(agent.ownerUserId(), user.id())` 다.
  가족 공개 에이전트는 `ownerUserId` 가 비어 있어 `ADMIN` 만 통과한다.
- 목록 경로는 `agent/presentation/AgentController.java` 의 `readable()` 이고
  `AgentDtos.AgentView(code, name, model, visibility)` 를 돌려준다. `AgentView.from(Agent)` 가 만든다.
- 성격 경로는 `agent/presentation/AgentPersonaController.java` 가 `/api/v1/agents/{code}/persona` 에 둔다.
  새 경로는 같은 모양으로 둔다.
- 컨트롤러 테스트의 선례는 `backend/src/test/java/com/bifos/assistant/agent/AgentPersonaControllerTest.java` 다.
  `MockMvcBuilders.standaloneSetup` 과 `GlobalExceptionHandler` 로 상태 코드를 본다.
- e2e 선례는 `test/e2e/scenarios/persona.ts` 이고 `test/e2e/run.ts` 의 `SCENARIOS` 배열에 등록한다.
  `call(context, "/agents/dad/persona", { token: context.tokens.dad })` 처럼 부른다.

**근거 문서**: `docs/data-schema.md` 의 「agent」 와 「agent_starter_prompt」 절,
`docs/code-architecture.md` 의 「페르소나」 절 아래 「누가 고칠 수 있나」 와 「소개와 추천 질문」,
`docs/flow.md` 의 「새 대화 화면」 절

## 의도 메모

- 추천 질문을 `agent` 의 한 칸에 이어 붙여 넣지 않는다. 여러 값은 자식 표로 둔다.
- 순서를 바꾸는 것도 전체를 다시 쓰는 것으로 본다. 줄 단위 수정 경로를 두지 않는다.
- 성격과 달리 Hermes 를 부르지 않는다. 화면만 읽는 값이다.
- 고칠 수 있는지의 판정을 두 곳에 복사하지 않는다. 한 메서드로 옮겨 성격과 추천 질문이 함께 쓴다.
  복사하면 한쪽만 바뀌어 성격은 못 고치는데 추천 질문은 고치는 에이전트가 생긴다.
- 칸 이름 `position` 은 MySQL 8 의 예약어가 아니다. `rank` 와 달리 그대로 쓴다.

## Blocked 조건

- `docs/data-schema.md` 에 「agent_starter_prompt」 절이 없다 → `PHASE_BLOCKED: 저장 모델 문서가 없다`

## 작업 항목

### 1. 마이그레이션

`backend/src/main/resources/db/migration/` 의 마지막 번호 다음 번호로 파일 하나를 만든다.
번호를 이 문서에 박지 않는다. 앞선 작업이 번호를 이미 썼을 수 있다.
이름은 `V{다음 번호}__agent_starter.sql` 이다.

- `ALTER TABLE agent ADD COLUMN tagline VARCHAR(200) NULL`
- `agent_starter_prompt` 표. `id BIGINT AUTO_INCREMENT`, `agent_id BIGINT NOT NULL`,
  `position INT NOT NULL`, `text VARCHAR(300) NOT NULL`, `created_at DATETIME(6) NOT NULL`,
  `UNIQUE KEY uk_agent_starter_prompt (agent_id, position)`,
  `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4`.
  `agent_model_option` 과 같이 외래 키를 두지 않는다.
- 파일 머리에 왜 자식 표인지 한국어 주석 두 줄을 둔다.

### 2. `agent/domain/Agent.java`

- `@Column(name = "tagline", length = 200) private String tagline;` 과 getter `tagline()`.
- `public void changeTagline(String tagline)`: `null` 이거나 `strip()` 한 결과가 비면 `null`, 아니면 `strip()` 한 값.

### 3. `agent/domain/AgentStarterPrompt.java` 신규

`AgentModelOption` 과 같은 모양의 엔티티다. `@Table(name = "agent_starter_prompt")`.
칸은 `id`, `agentId`, `position`, `text`, `createdAt`.
`static AgentStarterPrompt of(Long agentId, int position, String text)`.
getter 는 `agentId()`, `position()`, `text()`.

### 4. `agent/infra/AgentStarterPromptRepository.java` 신규

```java
List<AgentStarterPrompt> findByAgentIdOrderByPositionAsc(Long agentId);
List<AgentStarterPrompt> findByAgentIdInOrderByAgentIdAscPositionAsc(Collection<Long> agentIds);
void deleteByAgentId(Long agentId);
```

두 번째는 목록 경로가 에이전트 수만큼 질의하지 않게 한 번에 읽는 데 쓴다.

### 5. 고칠 수 있는지의 판정을 `AgentService` 로 옮긴다

`agent/application/AgentService.java` 에 `public boolean isEditableBy(CurrentUser user, Agent agent)` 를 둔다.
본문은 지금 `PersonaService.editable` 과 같다. Javadoc 에 「주인과 `ADMIN` 만 고친다」 를 한국어로 옮긴다.
`PersonaService` 의 `editable` 두 호출을 `agents.isEditableBy(user, agent)` 로 바꾸고 private 메서드를 지운다.

### 6. `agent/application/StarterService.java` 신규

```java
public StarterSnapshot read(CurrentUser user, String code);
@Transactional
public StarterSnapshot write(CurrentUser user, String code, String tagline, List<String> starterPrompts);
public Map<Long, List<String>> promptsOf(List<Agent> agents);
```

`StarterSnapshot` 은 같은 패키지에 따로 둔 record 다. `PersonaSnapshot` 과 같은 자리다.
`public record StarterSnapshot(String tagline, List<String> starterPrompts, boolean editable)`.

- `read`: `agents.requireReadable` 다음에 그 에이전트의 추천 질문을 차례로 읽는다.
- `write`:
  1. `agents.requireReadable` 로 없는 것과 볼 수 없는 것을 `AGENT_NOT_FOUND` 로 거른다.
  2. `agents.isEditableBy` 가 거짓이면 `ErrorCode.FORBIDDEN`.
  3. 추천 질문 목록이 `null` 이면 빈 목록으로 본다.
     줄마다 `strip()` 하고 빈 줄을 버린다.
     남은 것이 `MAX_STARTER_PROMPTS = 4` 를 넘거나 한 줄이 300자를 넘으면 `ErrorCode.VALIDATION_FAILED`.
     소개가 `strip()` 뒤 200자를 넘어도 같다.
  4. `agent.changeTagline(tagline)` 을 부르고 `AgentRepository` 로 저장한다.
  5. `deleteByAgentId`, `flush()`, 0부터 차례로 `save`.
  6. 쓴 결과를 `editable = true` 로 돌려준다.
- `promptsOf`: 에이전트 번호 목록으로 한 번 읽어 번호별 목록을 만든다. 없는 번호는 빈 목록이다.

`MAX_STARTER_PROMPTS` 는 이 클래스의 `public static final int` 다. 화면이 같은 수를 응답으로 받는다.

### 7. `agent/presentation/AgentDtos.java`

- `AgentView` 에 `String tagline, List<String> starterPrompts` 를 뒤에 더한다.
  `from(Agent agent, List<String> starterPrompts)` 로 바꾼다.
- `public record StartersView(String tagline, List<String> starterPrompts, boolean editable, int maxPrompts)`
  와 `static StartersView from(StarterSnapshot)`. `maxPrompts` 는 `StarterService.MAX_STARTER_PROMPTS`.
- `public record WriteStartersRequest(@Size(max = 200) String tagline, @Size(max = 4) List<@Size(max = 300) String> starterPrompts)`.

### 8. `agent/presentation/AgentController.java`

`readable()` 이 `agents.readableBy(user)` 결과로 `starters.promptsOf(list)` 를 한 번 부르고
`AgentView.from(agent, prompts.getOrDefault(agent.id(), List.of()))` 로 만든다.

### 9. `agent/presentation/AgentStarterController.java` 신규

`@RequestMapping("/api/v1/agents")` 로 `AgentPersonaController` 옆에 둔다.

| 메서드 | 경로 | 응답 |
| --- | --- | --- |
| `GET` | `/{code}/starters` | `StartersView` |
| `PUT` | `/{code}/starters` | `StartersView`. 본문은 `@Valid WriteStartersRequest` |

### 10. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/agent/StarterServiceTest.java` 신규.
  `AgentModelOptionTest` 처럼 `@SpringBootTest` 로 저장소를 실제로 쓴다. 유일 제약과 `flush()` 는 가짜 저장소로는 드러나지 않는다.
  - 주인이 소개 `"  집안일을 돕는다  "` 와 추천 질문 `["a", " ", "b"]` 을 쓰면 소개가 `"집안일을 돕는다"`, 질문이 `["a", "b"]`
  - 한 번 더 `["c"]` 로 쓰면 `["c"]` 만 남는다. 유일 제약에 걸리지 않는다
  - 다섯 줄이면 `VALIDATION_FAILED`
  - 주인이 아닌 `MEMBER` 가 쓰면 `FORBIDDEN`, 볼 수 없는 에이전트면 `AGENT_NOT_FOUND`
  - 가족 공개 에이전트를 `MEMBER` 가 쓰면 `FORBIDDEN`, `ADMIN` 이 쓰면 통과
- `backend/src/test/java/com/bifos/assistant/agent/AgentStarterControllerTest.java` 신규.
  `AgentPersonaControllerTest` 와 같은 방식으로 `PUT` 에 301자 한 줄을 보내면 400 인지,
  `GET` 응답에 `maxPrompts` 가 4 인지 본다.
- `PersonaServiceTest` 와 `AgentPersonaControllerTest` 가 그대로 통과해야 한다.
  판정 메서드를 옮긴 것이 동작을 바꾸지 않았다는 근거다.
- `test/e2e/scenarios/starters.ts` 신규. `startersScenario` 를 `persona.ts` 모양으로 만들고
  `test/e2e/run.ts` 의 `SCENARIOS` 에서 `personaScenario` 바로 뒤에 넣는다.
  - `dad` 가 `/agents/dad/starters` 에 쓰고 다시 읽으면 같은 값이다
  - `/agents` 목록의 `dad` 줄에 `tagline` 과 `starterPrompts` 가 실려 온다
  - `kid` 토큰으로 `/agents/dad/starters` 를 읽으면 404 다.
    `agents.ts` 가 끝날 때 `dad` 를 `PRIVATE` 로 되돌려 두므로 이 시점에 `kid` 는 볼 수 없다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
node test/e2e/run.ts
scripts/check-public-safe.sh
grep -n "private static boolean editable" backend/src/main/java/com/bifos/assistant/agent/application/PersonaService.java
```

각 줄을 저장소 root 에서 따로 돌린다. 앞의 셋은 종료 코드 0 이다. 마지막 grep 은 아무것도 내지 않아야 한다.
`test/e2e` 는 `gradlew test` 를 먼저 돌린 뒤에 돌린다. 저장소 `AGENTS.md` 의 「확인」 절이 그 순서를 정한다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V{다음 번호}__agent_starter.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/Agent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/AgentStarterPrompt.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/infra/AgentStarterPromptRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/PersonaService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/StarterService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/StarterSnapshot.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentStarterController.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/agent/StarterServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentStarterControllerTest.java` | 신규 |
| `test/e2e/scenarios/starters.ts` | 신규 |
| `test/e2e/run.ts` | 수정 |

끝나면 `tasks/plan022-start-screen/index.json` 의 이 phase 를 `completed` 로 바꾸고 `current_phase` 를 2로 올린다.
