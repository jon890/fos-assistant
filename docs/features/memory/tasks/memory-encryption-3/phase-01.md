# Phase 01. 옛 민감도·옛 key·민감 허용 칸 지우기

**Execution profile**: standard

## 목표

`docs/features/memory/spec.md` 의 REQ5 「민감도 칸과 에이전트의 민감 허용 칸이 없다」 를 닫는다. 코드가 읽고 쓰지 않는 옛 칸 여섯 개를 운영 스키마에서 지우고, 그 칸을 만들던 테스트 도구와 옛 이미지를 흉내 내던 fixture, 문서를 맞춘다.

**범위 외**: 운영 배포와 확인, 홈서버의 옛 key 파일과 이관 직전 백업 삭제(스펙 DECISION12, 코디네이터), 바로 저장 조건 6, 7(스펙 DECISION8).

## 컨텍스트

지울 칸:

| 표 | 칸 |
| --- | --- |
| `memory` | `sensitivity`, `content_key_id` |
| `memory_revision` | `sensitivity`, `content_key_id` |
| `agent_memory_collection` | `allow_sensitive` |
| `agent_memory_collection_change` | `allow_sensitive` |

- PR2 가 배포돼 엔티티는 이 칸을 매핑하지 않는다. 운영의 옛 key 줄(`content_key_id` 가 찬 줄)은 0건이다(2026-11-03 운영 조회).
- H2 테스트 DB 는 엔티티로 스키마를 만들고, 옛 칸은 `backend/src/test/resources/memory-legacy-columns.sql` 이 따로 만든다. 칸이 사라지면 이 파일과 `application-test.yml` 의 import 항목, 칸에 값을 넣던 테스트를 함께 정리한다.
- 칸을 지우는 앞에서 옛 key 줄이 남았는지 보고 있으면 멈추는 선례는 `backend/src/main/java/db/migration/V35__DropAgentTokenUserId.java` 다. 마이그레이션 검사가 H2 에서 모든 마이그레이션을 돌리므로 조건부로 실패하는 문장은 Java 로 쓴다.
- DDL 과 DML 을 한 파일에 섞지 않고, 되돌리기 어려운 삭제는 표마다 파일을 나눈다(`backend/docs/data-schema.md` 「마이그레이션 작성 규칙」).

**요구사항**: REQ4, REQ5, REQ8

**근거 문서**: `docs/features/memory/spec.md`, `docs/features/memory/design.md`, `docs/adr/ADR-20261103-memory-sensitivity-removal.md`, `backend/docs/data-schema.md`

## 의도 메모

- 확인은 옛 key 줄(`content_key_id` 가 찬 줄)만 본다. `sensitivity` 와 `allow_sensitive` 는 코드가 이미 읽지 않는 값이고, 지워도 잃는 동작이 없다.
- 확인 마이그레이션은 DDL 을 하지 않는다. 멈추면 아무 칸도 지워지지 않아 실패한 한 파일만 고치고 다시 올린다.
- 옛 칸을 하나씩 다른 파일로 나눈다. MySQL DDL 은 되돌려지지 않아 중간에 실패해도 앞 표의 삭제는 남는다.
- 이 PR 을 배포한 뒤에는 PR1 이전 이미지로 되돌리지 못한다. 그 이미지는 칸을 매핑해 `ddl-auto: validate` 가 실패한다. PR2 이미지는 칸을 매핑하지 않아 칸이 없어도 뜬다. PR 본문에 적는다.

## 작업 항목

### 1. 마이그레이션

`backend/src/main/java/db/migration/V20261103054416__MemoryLegacyKeyGuard.java`: `memory` 와 `memory_revision` 의 `content_key_id` 가 찬 줄을 센다. 하나라도 있으면 건수만 담은 `IllegalStateException` 으로 멈춘다. 본문과 key id 는 넣지 않는다.

`backend/src/main/resources/db/migration/` 에 SQL 네 개를 둔다. 표마다 `ALTER TABLE ... DROP COLUMN` 한 문장이다.

- `V20261103054417__memory_legacy_columns_drop.sql`: `memory` 의 `sensitivity`, `content_key_id`
- `V20261103054418__memory_revision_legacy_columns_drop.sql`: `memory_revision` 의 `sensitivity`, `content_key_id`
- `V20261103054419__agent_memory_collection_allow_sensitive_drop.sql`: `allow_sensitive`
- `V20261103054420__agent_memory_collection_change_allow_sensitive_drop.sql`: `allow_sensitive`

### 2. 테스트 도구와 fixture

- `backend/src/test/resources/memory-legacy-columns.sql` 을 지우고 `application-test.yml` 의 import 목록에서 뺀다.
- 칸에 값을 넣던 테스트를 지금 동작으로 고친다: `MemoryEncryptionTest`, `McpMemoryToolTest`, `ContextAssemblerQuestionTest`, `AgentMemoryCollectionServiceTest`.
- `backend/src/test/resources/purge/fixture.sql` 의 `memory`, `memory_revision`, `agent_memory_collection(_change)` 삽입에서 옛 칸을 뺀다.
- 확인 마이그레이션 테스트: 옛 key 줄이 있으면 멈추고 없으면 지나가는 것을 본다.

### 3. 문서

- `backend/docs/data-schema.md`: 옛 칸 설명을 지운 것으로 고친다.
- `docs/features/memory/design.md`, `spec.md`, `docs/adr/ADR-20261103-memory-sensitivity-removal.md`, `backend/docs/adr/ADR-20261103-memory-encryption.md`: 칸을 지운 사실과 진행 칸을 맞춘다.
- 이 PR 이 기능의 마지막 코드 PR 이므로 스펙 머리의 「구현을 마친 변경」 문구와 REQ5 의 `deferred` 표시를 정리한다.

## 검증

```bash
cd backend && ./gradlew test --tests '*MemoryLegacyKeyGuard*' --tests '*MemoryEncryptionTest' --tests '*McpMemoryToolTest' --tests '*ContextAssemblerQuestionTest' --tests '*AgentMemoryCollectionServiceTest'
```

```bash
scripts/dev/check-local.sh --fast
```

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/db/migration/V20261103054416__MemoryLegacyKeyGuard.java` | 신규 |
| `backend/src/main/resources/db/migration/V20261103054417__memory_legacy_columns_drop.sql` | 신규 |
| `backend/src/main/resources/db/migration/V20261103054418__memory_revision_legacy_columns_drop.sql` | 신규 |
| `backend/src/main/resources/db/migration/V20261103054419__agent_memory_collection_allow_sensitive_drop.sql` | 신규 |
| `backend/src/main/resources/db/migration/V20261103054420__agent_memory_collection_change_allow_sensitive_drop.sql` | 신규 |
| `backend/src/test/resources/memory-legacy-columns.sql` | 삭제 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/src/test/resources/purge/fixture.sql` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryEncryptionTest.java` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
| `docs/features/memory/design.md` | 수정 |
| `docs/features/memory/spec.md` | 수정 |
| `docs/adr/ADR-20261103-memory-sensitivity-removal.md` | 수정 |
