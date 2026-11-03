# phase 1: 민감 구분 제거와 평문 해시

## 목표

`docs/features/memory/spec.md#REQ5` 와 스펙 DECISION17 을 구현한다. 설계는 `docs/features/memory/design.md` 의 「PR2: 민감 구분 제거와 평문 해시」 부터 「결정」 까지, 결정의 근거는 `docs/adr/ADR-20261103-memory-sensitivity-removal.md` 다.

**근거 문서**: `docs/features/memory/spec.md`, `docs/features/memory/design.md`, `docs/adr/ADR-20261103-memory-sensitivity-removal.md`, `backend/docs/data-schema.md`

**범위 외**: 옛 칸의 DROP(배포 확인 뒤 다음 PR), 홈서버의 옛 key 파일 삭제(코디네이터), 바로 저장 조건 6, 7(스펙 DECISION8).

## 작업 항목

### crypto 의 HMAC

`crypto`: `OwnedTextCipher.mac()` 과 `DataKeyService` 구현. 쓰임마다 데이터 key 에서 HMAC key 를 뽑는다

### 기억 도메인

`memory.domain`: `Memory`, `MemoryRevision` 에서 `sensitivity`, `contentKeyId` 매핑 제거. `MemoryPlacement` 는 두 칸. `StoredContent` 삭제. 임베딩 원천과 검색 후보에 범위와 주인 번호를 더한다

### 기억 응용

`memory.application`: `MemoryTexts` 의 옛 key 제거와 `proposalDedupKey()`, `embeddingFingerprint()`. `MemoryTextMigration.rekeyHashes()`. `MemoryContentCipher`, `MemoryEncryptionProperties`, 설정 `assistant.memory.encryption.*` 삭제

### 판정과 질의

판정과 질의: `MemoryAccess`, `MemoryQueries`, `MemorySearchCandidates`, `MemoryEmbeddingRepository`, `MemoryCollectionCount` 에서 민감도 제거. 수정 거절과 `ALWAYS` 거절 제거

### 에이전트와 관리자

`agent`, `admin`: 민감 허용 매핑과 요청, 응답 제거. 최근 변경에서 `SENSITIVE_CHANGED` 제외

### 화면

화면: 민감 배지, 숨김 문구, 문서의 민감 선택, 「민감 항목까지」 제거

### 마이그레이션

마이그레이션 `V20261103054413`: 기본값이 없던 두 칸에 기본값. H2 는 `memory-legacy-columns.sql`

### 문서

문서: 기능 파일, `data-schema.md`, ADR, 운영 문서의 옛 key 환경 변수

### 테스트

민감 구분이 없어진 동작, 옛 key 설정 없는 기동과 저장, HMAC 과 기동 이관을 `backend/src/test/java/com/bifos/assistant/memory/MemoryEncryptionTest.java` 와 `backend/src/test/java/com/bifos/assistant/agent/AgentMemoryCollectionServiceTest.java` 에서 본다. 민감 구분을 전제한 기존 테스트는 지금 동작으로 고친다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/crypto/**/*.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/**/*.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryContentCipher.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/memory/config/MemoryEncryptionProperties.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/memory/domain/StoredContent.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/agent/**/*.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/admin/memory/**/*.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/application/*.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpMemoryRemember.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/LivePropertiesConfig.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/main/resources/db/migration/V20261103054413__memory_sensitivity_defaults.sql` | 신규 |
| `backend/src/test/**/*` | 신규·수정 |
| `web/src/**/*` | 수정 |
| `test/**/*` | 수정 |
| `docs/**/*.md` | 신규·수정 |
| `backend/docs/**/*.md` | 수정 |
| `README.md` | 수정 |
| `README.ko.md` | 수정 |

## 검증

```bash
# cwd: backend/
./gradlew test --tests '*Memory*' --tests '*ContextAssembler*' --tests '*Mcp*' --tests '*Agent*' --tests '*ArchitectureRules*'
./gradlew checkstyleMain checkstyleTest spotlessCheck
```

```bash
# cwd: web/
pnpm lint && pnpm typecheck && pnpm format:check
```

```bash
# cwd: 저장소 root
node --test test/unit/agent-memory.test.ts test/unit/error-message.test.ts
scripts/dev/check-local.sh --fast
```

관측할 결과: 직전 이미지가 `SENSITIVE` 로 남긴 줄이 목록에 본문과 함께 보이고 고쳐지며, collection 을 받은 에이전트에 실린다(`MemoryEncryptionTest`). 옛 key 설정 없이 뜬 서버가 기억을 저장한다. 제안 중복 키와 임베딩 지문이 평문 SHA-256 이 아니고 기동 이관이 옛 해시를 바꾼다.
