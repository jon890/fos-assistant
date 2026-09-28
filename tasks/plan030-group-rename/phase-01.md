# Phase 01. Control Plane 과 저장 값을 group 으로 바꾼다

**Execution profile**: standard

## 목표

backend 의 식별자, enum 값, 컬럼 이름, 오류 문구, 에이전트에 넘기는 Memory 제목을 `family` 에서 `group` 으로 바꾸고,
이미 저장된 값을 migration 으로 옮긴다. 문서가 사용자들이 모인 단위를 이미 「그룹」 이라 부르는데 코드만 `family` 로 남아 있다.

**범위 외**

- web 의 타입, 상수, 화면 문구, `test/browser/` 는 phase 02 가 바꾼다. 이 phase 가 끝나면 web 은 아직 `FAMILY` 를 보내므로 `pnpm test:browser` 가 실패할 수 있다. 이 phase 에서는 돌리지 않는다
- `credential_scope` 의 `SHARED_HOUSEHOLD` 는 바꾸지 않는다. 그룹마다 credential 을 나눌지는 `docs/prd.md` 의 미결 항목이다
- 기존 migration 파일(`V1__control_plane_core.sql`, `V7__memory.sql` 등)은 고치지 않는다. Flyway 검사가 실패한다

## 컨텍스트

**근거 문서**: `docs/prd.md` 첫 절, `docs/data-schema.md` 의 `app_user`·`agent`·`memory` 표, `docs/code-architecture.md` 「memory」 절, `docs/adr/ADR-012-memory-는-사람이-승인한-것만-남는다.md`

- `AGENTS.md` 「용어」 표의 「사용자들이 모인 단위」 줄: 쓰는 말은 그룹, 코드는 `group`
- `docs/prd.md` 는 사람들이 모인 단위를 그룹이라 부른다
- `docs/data-schema.md` 는 이미 `group_id`, `GROUP` 으로 적혀 있다. 이 phase 가 코드를 그 문서에 맞춘다

**지금 모양** (구현 전에 다시 읽는다)

| 무엇 | 자리 | 지금 |
| --- | --- | --- |
| 요청자 | `backend/src/main/java/com/bifos/assistant/shared/auth/CurrentUser.java` | `record CurrentUser(Long id, String email, String displayName, Long familyId, UserRole role)` |
| 사용자 엔티티 | `backend/src/main/java/com/bifos/assistant/user/domain/AppUser.java` | `@Column(name = "family_id", nullable = false) Long familyId`, `of(email, displayName, familyId, role)`, `familyId()` |
| 사용자 저장소 | `backend/src/main/java/com/bifos/assistant/user/infra/AppUserRepository.java` | `boolean existsByFamilyId(Long familyId)` |
| 첫 사용자 | `backend/src/main/java/com/bifos/assistant/user/domain/UserProvisioningService.java` | `public static final long DEFAULT_FAMILY_ID = 1L;` 와 그 위 영어 주석 |
| Memory 범위 | `backend/src/main/java/com/bifos/assistant/memory/domain/MemoryScope.java` | `USER`, `FAMILY` |
| Memory 엔티티 | `backend/src/main/java/com/bifos/assistant/memory/domain/Memory.java` | `@Column(name = "family_id") Long familyId`, `accepted(scope, ownerUserId, familyId, …)`, `isReadableBy(Long userId, Long familyId)`, `familyId()` |
| Memory 응답 | `backend/src/main/java/com/bifos/assistant/memory/presentation/MemoryDtos.java` | `MemoryView(…, Long familyId, …)`. JSON 칸 이름이 `familyId` 다 |
| 에이전트 공개 범위 | `backend/src/main/java/com/bifos/assistant/agent/domain/AgentVisibility.java` | `PRIVATE`, `FAMILY` |
| 주입 제목 | `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java` | `FAMILY_HEADER = "# 우리 가족이 함께 아는 것"` |
| 오류 문구 | `MemoryService.java` 두 곳, `CurrentUserProvider.java` | `"this action is limited to the family admin"` |
| 오류 문구 | `PersonaService.java`, `StarterService.java` | `"only the owner of this agent or the family admin can edit it"` |
| 오류 문구 | `AgentAdminController.java` | `"no such family member"` |
| 컬럼과 색인 | `V1__control_plane_core.sql` | `app_user.family_id`, `KEY ix_app_user_family (family_id)` |
| 컬럼과 색인 | `V7__memory.sql` | `memory.family_id`, `CREATE INDEX idx_memory_family ON memory (family_id, status)` |
| 저장 값 | `agent.visibility`, `memory.scope` | `'FAMILY'` 문자열 |
| 마지막 migration | `backend/src/main/resources/db/migration/` | `V24__chat_artifact.sql`. `V23` 은 Java migration(`backend/src/main/java/db/migration/V23__ConversationPublicId.java`) |

migration 검사 본보기는 `backend/src/test/java/com/bifos/assistant/chat/ChatArtifactMigrationTest.java` 다.
`backend/AGENTS.md` 「엔티티와 마이그레이션은 따로 논다」 를 먼저 읽는다. 테스트는 엔티티로 스키마를 만들고 `*MigrationTest` 만 H2 의 MySQL 모드에서 migration 을 돌린다. 운영은 MySQL 8.4 다.

## 의도 메모

- **컬럼 이름과 값을 한 migration 으로 바꾼다.** 두 번에 나눠 옛 값과 새 값을 함께 읽는 단계를 두는 안은 버렸다. 사용자가 몇 사람뿐이라 배포 직전 DB 백업으로 되돌리기를 대비한다. 그래서 이 버전을 배포한 뒤 이전 이미지로 되돌리려면 DB 도 백업으로 되돌려야 한다
- 요청 본문에 옛 값 `FAMILY` 를 받아 주는 호환 경로를 두지 않는다. 이 API 를 부르는 곳은 이 저장소의 web 과 `test/` 뿐이고 함께 배포된다. 옛 값이 오면 지금 알 수 없는 enum 값이 오는 경우와 같은 오류로 거절된다
- MySQL 에서 `group` 은 예약어지만 `group_id`, `GROUP` 문자열 값은 따옴표 없이 문제없다. 식별자를 `group` 하나로 쓰지 않는다
- 사람이 읽는 오류 문구에서 `member` 를 쓰지 않는다(`AGENTS.md` 「용어」). `"no such family member"` 는 `"no such user"` 로 바꾼다
- 코드 주석에서 `가족` 이 이 단위를 뜻하면 `그룹` 으로 바꾼다. 날짜와 시간대 주석의 「가족이 사는 곳」 처럼 사람들이 사는 곳을 말하는 문장은 그대로 둔다

## Blocked 조건

- 로컬에 Docker 가 없어 MySQL 8.4 컨테이너를 띄울 수 없다 → `PHASE_BLOCKED: MySQL 8.4 로 V25 를 확인할 수 없다`

## 작업 항목

### 1. `V25__group_rename.sql` 을 새로 만든다

`backend/src/main/resources/db/migration/V25__group_rename.sql`

- `app_user.family_id` 를 `group_id` 로, `memory.family_id` 를 `group_id` 로 이름을 바꾼다. 타입과 NULL 허용은 그대로다(`app_user` 는 `BIGINT NOT NULL`, `memory` 는 `BIGINT NULL`)
- 색인 `ix_app_user_family` 를 `ix_app_user_group (group_id)` 로, `idx_memory_family` 를 `idx_memory_group (group_id, status)` 로 바꾼다
- `UPDATE agent SET visibility = 'GROUP' WHERE visibility = 'FAMILY'`, `UPDATE memory SET scope = 'GROUP' WHERE scope = 'FAMILY'`
- 문법은 MySQL 8.4 와 H2 의 MySQL 모드에서 모두 도는 것만 쓴다. 색인 이름 바꾸기가 두 엔진에서 함께 되는 문법이 없으면 지우고 다시 만든다. 두 엔진 모두에서 실제로 돌려 확인한다(작업 항목 5)

### 2. 엔티티, enum, 저장소, 요청자를 바꾼다

- `MemoryScope.FAMILY` → `GROUP`, `AgentVisibility.FAMILY` → `GROUP`
- `CurrentUser` 의 `familyId` → `groupId`
- `AppUser`: `@Column(name = "group_id", nullable = false) Long groupId`, `groupId()`, `of(…, groupId, …)`
- `AppUserRepository.existsByFamilyId` → `existsByGroupId`
- `UserProvisioningService.DEFAULT_FAMILY_ID` → `DEFAULT_GROUP_ID`. 그 위 영어 주석을 한국어로 바꾼다(「지금은 그룹이 하나뿐이다. 그룹을 여럿 두려면 이 값을 조회로 바꾼다」 뜻)
- `Memory`: `@Column(name = "group_id") Long groupId`, `accepted(…, groupId, …)`, `isReadableBy(Long userId, Long groupId)`, `groupId()`. 주석의 `FAMILY 는 같은 가족의 사용자가 본다` 를 `GROUP 은 같은 그룹의 사용자가 본다` 로
- `MemoryDtos.MemoryView` 의 `familyId` → `groupId`. JSON 칸 이름도 `groupId` 가 된다
- 위를 부르는 곳(`MemoryService`, `ContextAssembler`, `Agent.isVisibleTo` 계열, `AgentTokenService`, `ControlPlaneJwtFilter`, `UserProvisioningService`)을 모두 따라 바꾼다

### 3. 문구를 바꾼다

- `ContextAssembler.FAMILY_HEADER` → `GROUP_HEADER = "# 우리 그룹이 함께 아는 것"`
- `"this action is limited to the family admin"` → `"this action is limited to the group admin"` (세 곳)
- `"only the owner of this agent or the family admin can edit it"` → `"only the owner of this agent or the group admin can edit it"` (두 곳)
- `"no such family member"` → `"no such user"`

### 4. backend 검사와 e2e 를 따라 바꾼다

- `backend/src/test/**` 의 `MemoryScope.FAMILY`, `AgentVisibility.FAMILY`, `familyId`, `OTHER_FAMILY` 같은 이름을 `GROUP`, `groupId`, `OTHER_GROUP` 으로. `StarterServiceTest` 의 상수 `FAMILY = "starter-family"` 는 `GROUP = "starter-group"` 으로
- 오류 문구나 주입 제목을 문자열로 비교하는 검사가 있으면 새 문구로
- `test/e2e/scenarios/agents.ts`, `test/e2e/scenarios/memory.ts` 의 `"FAMILY"` 를 `"GROUP"` 으로, `familyList` 를 `groupList` 로, step 과 설명 문구의 `가족` 을 `그룹` 으로. `test/e2e/harness.ts` 의 「가족 사용자」 주석도 같다
- `test/e2e/fake-hermes.ts` 등이 주입 제목 문자열을 비교하면 새 제목으로

### 5. 이 phase 를 검증하는 migration 검사 `GroupRenameMigrationTest`

`backend/src/test/java/com/bifos/assistant/user/GroupRenameMigrationTest.java` 를 `ChatArtifactMigrationTest` 와 같은 방식으로 만든다.

- 정상: V24 까지 적용한 DB 에 `app_user` 한 줄(`family_id = 1`), `visibility = 'FAMILY'` 인 에이전트 한 줄과 `'PRIVATE'` 한 줄, `scope = 'FAMILY'` 인 memory 와 `'USER'` 인 memory 를 넣고 V25 를 적용한다. `group_id` 값이 보존되고 `FAMILY` 만 `GROUP` 이 되며 `PRIVATE`, `USER` 는 그대로인지 본다
- 실패 쪽: V25 뒤에 `family_id` 컬럼이 더는 없다(그 컬럼을 읽는 질의가 실패한다)

MySQL 8.4 에서도 한 번 돌린다. 로컬 일회용 컨테이너(`mysql:8.4`)를 띄워 V1 부터 V25 까지 적용하고 위와 같은 결과인지 확인한 뒤 컨테이너를 지운다. 실행한 명령과 결과를 보고서에 적는다. 이 절차는 저장소에 넣지 않는다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
grep -rniE 'family' backend/src test/e2e
```

- 앞의 넷은 모두 통과한다
- 마지막 grep 은 기존 migration 파일(`V1__control_plane_core.sql`, `V7__memory.sql`)과 `V25__group_rename.sql` 안의 옛 이름만 가리킨다
- MySQL 8.4 확인 결과가 보고서에 있다

## Critical Files

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/resources/db/migration/V25__group_rename.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/shared/auth/CurrentUser.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/user/domain/AppUser.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/user/infra/AppUserRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/user/domain/UserProvisioningService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/MemoryScope.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/Memory.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/presentation/MemoryDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/AgentVisibility.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/auth/CurrentUserProvider.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/PersonaService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/StarterService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentAdminController.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/user/GroupRenameMigrationTest.java` | 신규 |
| `backend/src/test/**` | 수정 |
| `test/e2e/scenarios/agents.ts`, `test/e2e/scenarios/memory.ts`, `test/e2e/harness.ts` | 수정 |
