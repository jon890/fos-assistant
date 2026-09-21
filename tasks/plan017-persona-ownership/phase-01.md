# Phase 01. 페르소나를 담는 표와 읽고 쓰는 경로

**Execution profile**: standard

## 목표

에이전트의 성격을 데이터베이스가 갖게 한다.
한 행이 한 판이고, 고치면 새 판이 생기고 옛 판은 남는다.

**범위 외**:
Hermes 의 `SOUL.md` 에 미는 것은 phase-02 가, 화면은 phase-03 이 한다.
이 phase 는 표와 그것을 읽고 쓰는 경로까지다.
이 phase 만으로는 데이터베이스에만 쌓이고 에이전트의 답은 달라지지 않는다.

## 컨텍스트

지금 성격을 고치려면 비공개 저장소의 파일을 고치고 배포해야 한다.
`agent` 표에 그 본문을 담는 칸이 없다.

**근거 문서**:
`docs/data-schema.md` 의 「agent_persona」 절,
`docs/code-architecture.md` 의 「페르소나」 절,
`docs/flow.md` 의 「페르소나를 고칠 때」 절,
`docs/adr/ADR-019-페르소나는-control-plane-이-갖고-hermes-에-민다.md`.

### 판이 둘이다

「지금 판」과 「반영된 판」이 다를 수 있다.
이 phase 는 미는 일을 하지 않으므로 `synced_at` 은 언제나 비어 있고,
「반영된 판」은 없는 상태로 남는다. 그 칸을 채우는 것은 phase-02 다.
**그래도 칸과 조회 메서드는 이 phase 에서 만든다.** 표를 두 번 고치지 않기 위해서다.

## 의도 메모

- `agent` 에 칸 하나를 더하는 안을 버렸다.
  사람이 시간을 들여 쓴 글이라 잘못 고쳤을 때 돌아갈 자리가 필요하다.
- 행을 고쳐 쓰는 안을 버렸다. 이 저장소는 기록이 가리키는 것을 지우거나 덮지 않는다.
- 반영 실패를 적는 상태 칸을 두지 않았다.
  「지금 판」과 「반영된 판」이 다른 것으로 판정한다. 상태를 두 곳에 두면 어긋난다.
- 빈 본문을 받지 않는다. 빈 `SOUL.md` 를 밀면 그 사람의 성격이 지워진다.
  기본으로 되돌리고 싶으면 옛 판의 본문으로 새 판을 만든다.

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V17__agent_persona.sql`

표 하나를 만든다. 칸과 제약은 `docs/data-schema.md` 의 「agent_persona」 절이 정한다.

- `agent_id` 와 `revision` 에 함께 걸리는 유니크 제약을 둔다
- `body` 는 `TEXT` 로 못 박는다. 길이를 주지 않은 `@Lob` 문자열은 기동을 실패시킨다.
  `backend/AGENTS.md` 의 「엔티티와 마이그레이션은 따로 논다」 가 그 사고를 적었다
- `synced_at` 은 비워 둘 수 있다
- **어떤 본문도 이 파일에 넣지 않는다.** 이관은 사람이 화면에서 한다

번호를 쓰기 전에 아래로 다음 빈 번호를 확인한다. 이미 쓰였으면 그다음 번호로 만든다.

```bash
# cwd: 저장소 root
ls backend/src/main/resources/db/migration/
```

### 2. `agent` 패키지에 페르소나를 더한다

새 패키지를 만들지 않는다. 성격은 에이전트의 속성이다.
층 흐름은 `docs/code-architecture.md` 의 「backend 패키지」가 정한다.

| 경로 | 무엇 |
| --- | --- |
| `agent/domain/AgentPersona.java` | 엔티티. `of(agentId, revision, body, authorUserId)` 로 만든다 |
| `agent/infra/AgentPersonaRepository.java` | 아래 셋을 낸다 |
| `agent/application/PersonaService.java` | 판을 만들고 고르는 규칙 |

repository 가 내는 것이다.

| 메서드 | 무엇 |
| --- | --- |
| `findTopByAgentIdOrderByRevisionDesc` | 지금 판 |
| `findTopByAgentIdAndSyncedAtIsNotNullOrderByRevisionDesc` | 반영된 판 |
| `findByAgentIdOrderByRevisionDesc` | 판 목록 |
| `findByAgentIdAndRevision` | 한 판 |

`AgentPersona` 는 `com.bifos.assistant.agent.domain.Agent` 의 엔티티 작성 방식을 그대로 따른다.
`agent_id` 를 연관관계가 아니라 번호로 갖는다. `AgentModelOption` 이 그렇게 돼 있다.

`PersonaService` 가 내는 것이다.

| 메서드 | 하는 일 |
| --- | --- |
| `current(Agent)` | 지금 판. 없으면 비어 있다 |
| `published(Agent)` | 반영된 판. 없으면 비어 있다 |
| `revisions(Agent)` | 판 목록. 최신이 앞 |
| `revision(Agent, int)` | 그 판. 없으면 `PERSONA_NOT_FOUND` |
| `write(Agent, CurrentUser, String body, Integer baseRevision)` | 다음 판을 만든다 |

`write` 의 규칙이다.

- `baseRevision` 이 지금 판의 번호와 다르면 `PERSONA_STALE` 로 거절한다.
  판이 하나도 없을 때는 `baseRevision` 이 비어 있어야 한다
- 새 판의 번호는 지금 판의 번호에 하나를 더한 값이고, 첫 판은 1 이다
- 본문 앞뒤 공백을 떼고 저장한다. 떼고 나서 비면 거절한다

### 3. 누가 고칠 수 있는지 판정하는 자리

`agent/application/PersonaAccess.java` 를 만든다.
판정 규칙은 `docs/code-architecture.md` 의 「누가 고칠 수 있나」가 정한다.

| 메서드 | 규칙 |
| --- | --- |
| `requireReadable(Agent, CurrentUser)` | `Agent.isReadableBy` 가 거짓이면 `AGENT_NOT_FOUND` |
| `requireWritable(Agent, CurrentUser)` | 주인도 `ADMIN` 도 아니면 `FORBIDDEN` |

**볼 수 없는 에이전트는 없는 에이전트와 같은 응답을 준다.**
`code` 를 훑어 남의 에이전트가 있는지 알아낼 수 없게 한다.
`usage` 가 남의 실행을 숨기는 방식과 같다.

### 4. `agent/presentation/AgentPersonaController.java`

경로와 응답 모양은 `docs/code-architecture.md` 의 「경로」가 정한다.
이 phase 에서 `POST /api/v1/agents/{code}/persona/sync` 는 만들지 않는다. phase-02 가 만든다.

요청과 응답 record 는 `agent/presentation/AgentDtos.java` 에 더한다.
컨트롤러 안에 record 를 두지 않는다. `backend/AGENTS.md` 가 그렇게 정한다.

`PersonaView` 에 담을 것이다.

| 칸 | 뜻 |
| --- | --- |
| `revision` | 지금 판의 번호. 판이 없으면 비어 있다 |
| `body` | 지금 판의 본문. 판이 없으면 빈 문자열 |
| `authorName` | 그 판을 쓴 사람의 이름 |
| `updatedAt` | 그 판을 만든 시각 |
| `publishedRevision` | 반영된 판의 번호. 없으면 비어 있다 |
| `synced` | 지금 판과 반영된 판이 같은가 |
| `editable` | 지금 요청자가 고칠 수 있는가 |
| `maxChars` | 본문 상한. 화면이 남은 글자 수를 보인다 |

`PersonaRevisionView` 는 `revision` 과 `authorName` 과 `createdAt` 과 `chars` 를 담는다.
**목록에 본문을 담지 않는다.** 판이 쌓이면 응답이 그만큼 커진다.

`WritePersonaRequest` 는 `body` 와 `baseRevision` 을 받는다.
`body` 는 `@NotBlank` 이고 `@Size(max = 8000)` 이다. 상한은 `docs/code-architecture.md` 의 「페르소나」가 정한다.

### 5. 오류 코드를 더한다

`shared/error/ErrorCode.java` 에 둘을 더한다. 기존 이름 짓는 방식을 따른다.

| 코드 | 상태 | 언제 |
| --- | --- | --- |
| `PERSONA_NOT_FOUND` | 404 | 그 판이 없다 |
| `PERSONA_STALE` | 409 | 보고 있던 판이 지금 판이 아니다 |

**반영 실패를 오류 코드로 두지 않는다.** 응답의 `synced` 가 그것을 말한다.

### 6. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/agent/PersonaServiceTest.java`

| 무엇 | 기대 |
| --- | --- |
| 판이 없는 에이전트에 처음 쓴다 | `revision` 이 1 이다 |
| 판이 없는데 `baseRevision` 을 주고 쓴다 | `PERSONA_STALE` |
| 지금 판의 번호로 쓴다 | `revision` 이 하나 는다. 앞 판의 행이 그대로 있다 |
| 한 판 전의 번호로 쓴다 | `PERSONA_STALE`. 행이 늘지 않았다 |
| 공백만 있는 본문 | 거절. 행이 늘지 않았다 |
| 반영된 판이 없다 | `published` 가 비어 있다 |
| 옛 판의 본문으로 다시 쓴다 | 새 판이 생긴다. 옛 행을 살리지 않는다 |

`backend/src/test/java/com/bifos/assistant/agent/AgentPersonaControllerTest.java`

| 무엇 | 기대 |
| --- | --- |
| 자기만 보는 자기 에이전트를 주인이 읽는다 | 200 |
| 남의 자기만 보는 에이전트를 읽는다 | `AGENT_NOT_FOUND`. `FORBIDDEN` 이 아니다 |
| 가족 공용 에이전트를 `MEMBER` 가 읽는다 | 200 이고 `editable` 이 거짓 |
| 가족 공용 에이전트를 `MEMBER` 가 쓴다 | `FORBIDDEN`. 행이 늘지 않았다 |
| 가족 공용 에이전트를 `ADMIN` 이 쓴다 | 200 |
| 8000자를 넘는 본문 | 400 |
| 판 목록 응답 | 본문이 들어 있지 않다 |

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
scripts/check-public-safe.sh
```

`pnpm build` 가 요구하는 환경 변수는 `web/AGENTS.md` 의 「검사」 절이 갖는다.

아래가 아무것도 내지 않아야 한다. 성격 본문이 저장소에 들어가면 안 된다.

```bash
# cwd: 저장소 root
grep -rn "SOUL" backend/src/main/resources/db/migration/
```

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V17__agent_persona.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/AgentPersona.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/infra/AgentPersonaRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/PersonaService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/PersonaAccess.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentPersonaController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/PersonaServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentPersonaControllerTest.java` | 신규 |

## 끝낸 뒤

`tasks/plan017-persona-ownership/index.json` 의 이 phase 를 `completed` 로 바꾸고
`current_phase` 를 2로 올린다.

**배포하지 않는다.** 쓴 성격이 Hermes 에 닿지 않아 에이전트의 답이 달라지지 않는다.
phase-02 와 phase-03 을 함께 배포한다.
