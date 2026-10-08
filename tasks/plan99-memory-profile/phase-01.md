# Phase 01. Memory 회수 합성 측정 하네스

**Execution profile**: standard

## 목표

가상 가족 시험 세트를 `ContextAssembler` 로 조립해 회수율, 오기억 노출률, 권한 경계 노출, 실행당 Memory 글자 수를 보고서로 낸다.
프로필 구역을 넣기 전의 기준값을 먼저 잡는다.

**범위 외**: 프로필 구역 구현과 `profileOn` 모드(phase 02). 운영 집계 SELECT 는 이미 `docs/backend/memory-eval.md` 에 있다. 모델이나 Hermes 대역을 부르지 않는다.

## 컨텍스트

- 측정의 계약은 `docs/backend/memory-eval.md` 의 「합성 측정」 이 갖는다. 시험 세트의 칸, 범주, `forbidden.kind`, 지표, 실패 조건, 보고서 경로가 거기 있다. 그대로 만든다.
- 이 phase 에서는 측정 모드를 `profileOff` 하나만 낸다. 지금 조립기가 프로필 구역을 모르므로 주입받은 `ContextAssembler` 의 결과가 곧 `profileOff` 다. 보고서와 JSON 은 모드를 키로 갖는 모양으로 만들어 phase 02 가 `profileOn` 을 더하기만 하게 한다.
- 따를 패턴: `backend/src/test/java/com/bifos/assistant/proactive/eval/` 의 `EvalDataset`(JSON 읽기, 참조 검사), `EvalScoreboard`(지표), `ProactiveEvalGateTest`(보고서를 `backend/build/reports/proactive-eval/` 에 쓰고 표준 출력에 냄).
- 통합 검사 기반은 `docs/backend/testing.md` 를 따른다. `@BackendIntegrationTest` 를 달고, 컨텍스트 키를 바꾸는 선언(`@MockitoBean`, `@TestPropertySource` 등)을 두지 않는다. 시계는 `@Autowired TestClock`.
- 데이터 넣기
  - 사용자는 표에 넣지 않고 `new CurrentUser(id, "<key>@example.com", "<key>", groupId, role)` 로 만든다. 모두 같은 `groupId`. `ContextAssemblerTest.user(...)` 와 같다.
  - 에이전트는 `AgentRepository.save(Agent.of(code, ...))` 로 만든다(`ContextAssemblerTest.setUp` 참고). 저장하면 `core` 줄이 생긴다. fixture 의 `collections` 가 전체 목록이므로 `JdbcTemplate` 으로 `agent_memory_collection` 의 그 에이전트 줄을 지운 뒤 `AgentMemoryCollectionRepository.save(AgentMemoryCollection.of(agentId, collection, allowSensitive, now))` 로 넣는다.
  - Memory 는 `MemoryRepository` 에 직접 저장한다. `ACCEPTED` 는 `Memory.accepted(scope, ownerUserId, groupId, title, StoredContent.plain(content), new MemoryPlacement(collection, retrieval, sensitivity), acceptedByUserId, at)`, `PROPOSED` 는 `Memory.proposed(...)`, `REJECTED` 는 `proposed` 뒤 `reject(at)`, `DOCUMENT` 는 `Memory.document(...)`. `GROUP` 은 `ownerUserId` 를 비우고 `groupId` 를 준다. `at` 은 `clock.instant()` 에서 `updatedDaysAgo` 일을 뺀 값이다.
  - 사례마다 `MemoryRepository.deleteAll()` 로 비운다.
- 상태 판정은 글을 grep 하지 않고 `AssembledContext.bundle().items()` 를 본다. `ref` 가 `ContextItem.memoryRef(id)` 인 항목의 `bodyMode` 가 `INLINE` 이면 `INLINE`, `TITLE_ONLY` 면 `TITLE_ONLY`, `OMITTED` 이거나 항목이 없으면 `ABSENT`.
- 글자 수는 `assemble(user, agentId)` 결과의 `chars()` 다. `withResponseInstructions` 를 거치지 않는다.

**근거 문서**: `docs/backend/memory-eval.md`, `docs/backend/memory.md` 의 「범위와 조립」, `docs/backend/testing.md`

## 의도 메모

- Hermes 대역(`StubHermesRunsClient`)으로 turn 을 돌리지 않는다. 대역이 지어낸 답으로는 오기억률이 나오지 않고 느리기만 하다. 조립 결과가 측정 대상이다.
- 실패 조건은 권한 경계 노출 하나다. 회수율이나 노출률에 문턱을 두지 않는다. 기준선이 없고 프로필 구역은 노출을 늘리는 것을 알고 고른다.
- 시험 세트에는 실제 사람 이름, 메일, 계정, 금액, 대화 원문을 쓰지 않는다. 공개 저장소다. 이름은 「홍지수」, 「김민준」 같은 흔한 가상 이름을 쓴다.

## 작업 항목

### 1. `backend/src/test/resources/memory-eval/family-cases.json`

`docs/backend/memory-eval.md` 의 형식대로 만든다. 사례는 40개 안팎이다.

| 범주 | 수 | 담을 것 |
| --- | --- | --- |
| `INFORMATION_EXTRACTION` | 8 | 이름, 관계, 선호, 생일, 알레르기 아닌 식성, 사는 동네처럼 짧은 사실 하나. 본문 200자 이하. 일부는 본문이 200자를 넘는 긴 사실로 만들어 프로필 대상이 아닌 경우를 넣는다 |
| `MULTI_SESSION` | 6 | 같은 사람에 관한 사실 2~4개를 함께 묻는다 |
| `KNOWLEDGE_UPDATE` | 7 | 옛 값과 새 값이 둘 다 `ACCEPTED` 로 남은 경우(옛 값은 `SUPERSEDED`, 옛 값이 더 오래됨)와 한 항목만 남은 경우 |
| `TEMPORAL` | 5 | `filler` 로 오래된 짧은 항목을 많이 넣고 최근에 남긴 사실 하나를 기대한다 |
| `ABSTENTION` | 6 | 기대 항목 없이 비슷한 다른 사실을 `DISTRACTOR` 로 둔다 |
| `BOUNDARY` | 8 | 다른 사용자의 개인 항목, 받지 않는 collection, 민감 허용 없는 `SENSITIVE`, `PROPOSED`, `REJECTED`, `ARCHIVE`, `SOURCE` 아닌 `DOCUMENT` 의 남의 것 등. 모두 `BOUNDARY` |
| `LOAD` | 2 | `filler` 120개, 본문 40자 |

### 2. `backend/src/test/java/com/bifos/assistant/context/eval/MemoryEvalDataset.java`

record 들로 JSON 을 읽는다(Jackson). `load()` 는 클래스패스 `/memory-eval/family-cases.json` 을 읽고 아래를 검사해 어긋나면 `IllegalStateException` 을 던진다.

- 사례 `id` 가 겹치지 않는다
- 사례 안의 Memory `key` 가 겹치지 않고, `expect` 와 `forbidden.memory` 가 그 사례의 `key` 를 가리킨다
- `asker`, `owner` 가 `users` 에, `agent` 가 `agents` 에 있다
- `category` 와 `forbidden.kind` 가 문서의 값이다

### 3. `backend/src/test/java/com/bifos/assistant/context/eval/MemoryEvalScoreboard.java`

모드 이름과 사례 판정(사례 id, 범주, 항목마다의 상태, `chars`, `omittedItems`)을 받아 `docs/backend/memory-eval.md` 「지표」 의 여섯 지표를 모드마다, 범주마다 계산한다.
`report.md` 는 첫 줄에 「합성 측정: 모델 없이 조립 결과로 판정했다. 모델 답의 오기억률이 아니다.」 를 쓰고, 모드를 열로 둔 표를 낸다. `report.json` 은 같은 값을 모드, 범주 순으로 담는다.

### 4. `backend/src/test/java/com/bifos/assistant/context/eval/MemoryRecallEvalTest.java`

`@BackendIntegrationTest`. 테스트 하나가 시험 세트 전체를 돌린다.

- 시계를 고정한다(`clock.set(...)`).
- 사례마다 데이터를 넣고, 주입받은 `ContextAssembler` 로 `profileOff` 판정을 만든다.
- 보고서를 `backend/build/reports/memory-eval/report.md` 와 `report.json` 에 쓰고 표준 출력에 낸다.
- 단언: 권한 경계 노출이 0 이다. 실패하면 어느 사례의 어느 항목인지 메시지에 담는다.
- 따로 작은 테스트 하나로 시험 세트 검사가 깨진 참조를 잡는지 확인한다. 잘못된 JSON 문자열을 `MemoryEvalDataset` 의 읽기 함수에 직접 넘겨 `IllegalStateException` 을 기대한다.

## 검증

```bash
cd backend && ./gradlew test --tests '*MemoryRecallEvalTest'
test -s backend/build/reports/memory-eval/report.md && head -1 backend/build/reports/memory-eval/report.md
cd backend && ./gradlew spotlessCheck checkstyleTest
```

기대값: 시험 통과, 보고서 첫 줄이 「합성 측정」 으로 시작한다. 보고서의 `profileOff` 수치를 PR 본문에 옮길 수 있게 남긴다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/test/resources/memory-eval/family-cases.json` | 신규 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryEvalDataset.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryEvalScoreboard.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryRecallEvalTest.java` | 신규 |
