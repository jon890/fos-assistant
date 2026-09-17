# Phase 01. memory 표와 접근 권한을 만든다

**Execution profile**: standard

## 목표

Memory 항목을 담을 표와 그것을 읽고 쓰는 API 를 만든다.
누가 무엇을 볼 수 있는지는 Control Plane 이 정한다.

**범위 외**:
실행에 주입하는 것은 phase-02 가 맡는다.
화면은 phase-03 이 만든다.
제목만 실린 항목의 본문을 에이전트가 읽는 경로는 phase-04 가 만든다.

## 컨텍스트

Memory 의 단일 소스는 Control Plane 데이터베이스다.
Hermes 의 내장 memory 를 개인 Memory 로 쓰지 않는다.
주입되는 것은 Control Plane 이 고른 것뿐이다.
phase-04 가 에이전트에게 본문을 읽는 도구를 주지만, 그 도구도 Control Plane 이 응답을 고른다.
어느 경로로도 LLM 이 남의 Memory 에 닿지 않는다.

저장 단위는 긴 글 하나가 아니라 사실 하나가 한 행이다.
주입할 때 필요한 것만 고르기 위해서다.

공개 범위에 기본값을 두지 않는 규칙을 그대로 쓴다.
`agent` 의 `visibility` 가 같은 규칙이고, 그 근거는 등록 실수 한 번이
곧 다른 구성원에게 내용을 보여주는 사고가 되기 때문이다.

**근거 문서**: `docs/adr/ADR-003-memory-권한은-주입으로-강제한다.md`,
`docs/adr/ADR-012-memory-는-사람이-승인한-것만-남는다.md`,
`docs/adr/ADR-015-memory-는-층을-나눠-싣는다.md`,
`docs/data-schema.md` 의 「memory」 절

## 의도 메모

- 에이전트별로 Memory 를 나누는 안을 이번에는 쓰지 않는다.
  지금 에이전트가 둘뿐이고, 나누면 화면과 주입 규칙이 함께 복잡해진다.
  주입한 양이 실제로 문제가 되는지를 `context_chars` 로 먼저 측정하고 그 숫자를 보고 정한다.
- 작업 영역에 묶는 안도 버렸다. 작업 영역은 plan008 이 제거한다.
- `status` 를 두는 이유는 에이전트가 제안한 것과 사람이 받아들인 것을 나누기 위해서다.
  주입되는 것은 `ACCEPTED` 뿐이다.

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V7__memory.sql` 신규

```sql
CREATE TABLE memory (
    id BIGINT NOT NULL AUTO_INCREMENT,
    scope VARCHAR(20) NOT NULL,
    owner_user_id BIGINT NULL,
    family_id BIGINT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    always_inject BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL,
    proposed_by_execution_id BIGINT NULL,
    accepted_by_user_id BIGINT NULL,
    accepted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_memory_owner ON memory (owner_user_id, status);
CREATE INDEX idx_memory_family ON memory (family_id, status);
```

`title` 은 제목 한 줄이다. 색인에 실린다.
`always_inject` 는 본문까지 매 실행에 실을지를 정한다. **기본값은 거짓이다.**
사람이 명시해야 항상 실린다. 근거는
[ADR-015](../../docs/adr/ADR-015-memory-는-층을-나눠-싣는다.md) 에 있다.

`content` 를 `TEXT` 로 못 박는다.
길이를 주지 않은 `@Lob` 문자열을 Hibernate 가 MySQL 에서 `tinytext` 로 기대해 기동이 실패한다.
`chat_message.content` 가 같은 이유로 `LONGTEXT` 다.

### 2. `memory` 패키지를 만든다

`backend/src/main/java/com/bifos/assistant/memory/` 아래다.
다른 도메인과 같은 배치를 따른다. `presentation` 에서 `application`, `domain`, `infra` 로만 흐른다.

| 파일 | 담는 것 |
| --- | --- |
| `domain/Memory.java` | 엔티티. 상태 전이 메서드를 갖는다 |
| `domain/MemoryScope.java` | `USER`, `FAMILY` |
| `domain/MemoryStatus.java` | `PROPOSED`, `ACCEPTED`, `REJECTED` |
| `infra/MemoryRepository.java` | |
| `application/MemoryService.java` | 접근 권한과 상태 전이 |
| `presentation/MemoryController.java` | |
| `presentation/MemoryDtos.java` | |

`Memory` 에 두는 상태 전이 메서드다.

```java
/** 사람이 받아들인다. 이때부터 주입 대상이 된다. */
public void accept(Long userId, Instant at)

/** 사람이 물린다. 주입되지 않는다. */
public void reject()

public void updateContentAndInjection(String content, boolean alwaysInject)
```

`Agent` 와 `Workspace` 가 쓰던 `isReadableBy` 와 같은 모양을 `Memory` 에도 둔다.

```java
/** USER 는 주인만, FAMILY 는 같은 가구의 구성원이 본다. */
public boolean isReadableBy(Long userId, Long familyId)
```

`CurrentUser` 에 `familyId` 를 더하고 `ControlPlaneJwtFilter` 가 `AppUser.familyId()` 를 넣는다.
Memory 권한 검사는 JWT 본문이 아니라 데이터베이스에서 읽은 이 값을 쓴다.

### 3. `MemoryService` 가 권한을 정한다

```java
/** 이 사용자가 볼 수 있는 항목만 낸다. 상태로 거르지 않는다. 화면이 제안도 봐야 한다. */
public List<Memory> readableBy(CurrentUser user)

/** 본문까지 항상 싣는 항목. ACCEPTED 이고 alwaysInject 인 것만이다. */
public List<Memory> alwaysInjectedFor(CurrentUser user)

/** 제목만 싣는 항목. ACCEPTED 이고 alwaysInject 가 아닌 것이다. */
public List<Memory> indexedFor(CurrentUser user)

/** 제목만 실렸던 ACCEPTED 항목의 본문을 낸다. 볼 수 없거나 승인 전이면 MEMORY_NOT_FOUND 다. */
public Memory bodyFor(CurrentUser user, Long id)

public Memory create(CurrentUser user, MemoryScope scope, String title, String content, boolean alwaysInject)
/** 에이전트가 제안한 개인 항목을 만든다. FAMILY 제안은 만들지 않는다. */
public Memory proposeUser(CurrentUser user, String title, String content, Long proposedByExecutionId)
public Memory accept(CurrentUser user, Long id)
public Memory reject(CurrentUser user, Long id)
public Memory update(CurrentUser user, Long id, String content, boolean alwaysInject)
public void delete(CurrentUser user, Long id)
```

**남의 개인 항목은 없는 것과 같은 오류로 응답한다.**
`MEMORY_NOT_FOUND` 를 `ErrorCode` 에 더한다.
존재를 알리는 것 자체가 누출이다. `WorkspaceService.requireReadable` 이 쓰던 방식과 같다.

`scope` 를 주지 않으면 `MEMORY_SCOPE_REQUIRED` 로 거절한다. 기본값을 두지 않는다.

`FAMILY` 항목을 만들고 고치는 것은 관리자만 한다. 구성원은 읽는다.
`AgentAdminController` 가 쓰는 `requireAdmin` 과 같은 검사를 쓴다.

에이전트 제안은 항상 요청자의 `USER` 항목이다.
다른 가족에게 영향을 주는 `FAMILY` 항목은 관리자가 화면에서 직접 만든다.
같은 사용자의 제안 가운데 `title` 과 `content` 가 모두 같은 항목이 있으면 새 행을 만들지 않는다.

### 4. API 를 연다

| 메서드 | 경로 | 하는 일 |
| --- | --- | --- |
| `GET` | `/api/v1/memories` | 내가 볼 수 있는 항목 |
| `POST` | `/api/v1/memories` | 손으로 적는다. `scope` 와 `title` 을 반드시 준다 |
| `PATCH` | `/api/v1/memories/{id}` | 내용을 고친다 |
| `POST` | `/api/v1/memories/{id}/accept` | 제안을 받아들인다 |
| `POST` | `/api/v1/memories/{id}/reject` | 제안을 물린다 |
| `DELETE` | `/api/v1/memories/{id}` | 지운다 |

손으로 적은 항목은 바로 `ACCEPTED` 로 만든다. 사람이 쓴 것이므로 승인 단계가 필요 없다.

### 5. 스키마 문서를 갱신한다

`docs/data-schema.md` 의 `memory` 절에 `title`, `always_inject` 와 각 칸의 기본값을 반영한다.

### 6. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/memory/MemoryServiceTest.java` 를 새로 만든다.

- **정상 경로**: `USER` 항목을 만들면 주인이 읽고, `FAMILY` 항목은 같은 가구의 다른 구성원도 읽는다
- **다른 구성원의 개인 항목**: `readableBy` 와 `alwaysInjectedFor` 와 `indexedFor` 모두에서 빠진다.
  `id` 로 직접 조회해도 `MEMORY_NOT_FOUND` 다
- `scope` 없이 만들면 `MEMORY_SCOPE_REQUIRED` 로 거절된다
- `PROPOSED` 인 항목은 `alwaysInjectedFor` 와 `indexedFor` 둘 다에 들어가지 않는다
- `accept` 뒤에 `alwaysInject` 가 참이면 `alwaysInjectedFor`, 거짓이면 `indexedFor` 에 들어간다
- `alwaysInject` 를 주지 않고 만들면 거짓이다. 기본으로 항상 실리지 않는다
- 남의 항목은 `bodyFor` 에서도 `MEMORY_NOT_FOUND` 다
- `PROPOSED` 항목은 `bodyFor` 에서도 `MEMORY_NOT_FOUND` 다
- 구성원이 `FAMILY` 항목을 만들려 하면 거절된다
- 에이전트 제안은 요청자의 `USER`, `PROPOSED` 로 저장되고 같은 제목과 본문의 중복 제안은 한 행만 남는다

`test/e2e/scenarios/memory.ts` 를 새로 만들고 `test/e2e/run.ts` 의 목록에 더한다.

- 두 사용자를 만들어, 한 사람의 `USER` 항목이 다른 사람의 `GET /api/v1/memories` 에 없다
- 생성, 수정, 삭제의 정상 경로를 검사한다
- 구성원이 `FAMILY` 항목을 생성, 수정, 삭제하려 하면 모두 403 이다

`MemoryControllerTest` 에서는 `proposeUser`로 제안 행을 준비한 뒤 승인과 거절 API의 정상 경로를 검사한다.
제안을 만드는 시험용 공개 API는 추가하지 않는다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
node test/e2e/run.ts
```

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests '*MemoryServiceTest*'
```

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V7__memory.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/Memory.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/MemoryScope.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/MemoryStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/infra/MemoryRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/presentation/MemoryController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/presentation/MemoryDtos.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/shared/auth/CurrentUser.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `docs/data-schema.md` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryControllerTest.java` | 신규 |
| `test/e2e/scenarios/memory.ts` | 신규 |
| `test/e2e/run.ts` | 수정 |
