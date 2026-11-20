# 기억 요약과 상세 두 층 기술 설계

스펙: [`spec.md`](spec.md)(확인 2026-11-20). 지금 동작은 [기능 파일](../memory.md)이 갖고, 이 설계는 바뀌는 곳만 적는다.
PR1 은 크기 때문에 PR1a(REQ1 부터 REQ5, REQ7, REQ8)와 PR1b(REQ6)로 나눴다. 이 문서의 「PR1」 절은 둘을 함께 적고, `memory_capture.replaced_memory_ids` 칸과 합치기 지침, 대화의 기록 줄은 PR1b 가 만든다.
PR 마다 그 PR 의 요구사항 절만 구현 근거로 삼는다. PR2 와 PR3 의 절은 그 PR 을 시작할 때 다시 대조하고 고친다.

## 요구사항 연결

| ID | PR | 해결 방법 |
| --- | --- | --- |
| REQ1 | PR1 | `memory.summary`, `memory_revision.summary` 칸을 더하고 제목·본문과 같은 주인 key 로 암호화한다. AAD 의 칸 이름은 `summary` 다. 길이 상한 200자는 `Memory.SUMMARY_MAX` 가 갖는다 |
| REQ2 | PR1 | `ContextAssembler` 의 개인 사실 구역을 요약 구역으로 바꾼다. 후보는 색인 후보 가운데 `USER`, `MEMORY`, 풀림, 요약이 빈 글이 아닌 것이다. 질문 관련 구역 후보에서 빼지 않는다 |
| REQ3 | PR1 | 고르는 순서와 건너뛰기는 지금 `chooseFacts` 를 쓴다. 빠진 요약은 색인 줄의 선택 근거에 `summary-over-budget` 을 붙여 실행 기록에 남긴다 |
| REQ4 | PR1 | `group_setting` 의 새 키 `MEMORY_CONTEXT` 와 읽는 쪽 `MemoryContextSettings`, 관리자 API `/api/v1/admin/memory-settings` |
| REQ5 | PR1 | `memory_remember` 의 선택 인자 `summary`. 판정은 `McpMemoryRemember` 와 `MemoryCaptureService` 의 지금 자리에서 요약까지 본다 |
| REQ6 | PR1b | `memory_remember` 의 선택 인자 `replaces`. 바로 저장이면 같은 트랜잭션에서 대체 칸을 채우고, 제안이면 `memory_capture.replaced_memory_ids` 에 두었다가 받아들일 때 채운다. 기록 목록 응답과 대화의 기록 줄이 옛 제목을 보인다 |
| REQ7 | PR1 | `ContextAssembler.MEMORY_INSTRUCTIONS` 와 요약 구역 머리 글을 고치고, 기억 회수 측정의 합성 사례에 세 질문을 더한다 |
| REQ8 | PR1 | 기동 이관 `MemorySummaryBackfill`. 요약 칸이 `NULL` 인 줄만 본다 |
| REQ9 | PR2 | 목록 응답에 요약, 묶음, 「요약에 못 들어감」, 대체 정보를 더하고 사용량 API 를 둔다. 화면은 `web/src/components/memory/` |
| REQ10 | PR2 | 관리자 화면 `/admin/memory` 와 사람마다 사용량 API |
| REQ11 | PR3 | 정비 루프의 새 동작 `SUMMARY` 와 Hermes 실행 한 번으로 문장을 만드는 생성기 |
| REQ12 | PR3 | 평가 세트와 채점 기준을 저장소에 두고, 실제 모델 실행은 코디네이터가 운영에서 돌린다 |

## 책임과 경계

- `memory` 모듈이 요약 칸의 저장, 암호화, revision 기록, 대체, 이관, 그룹 설정 `MEMORY_CONTEXT` 의 모양과 검사를 갖는다. 새 모듈은 만들지 않는다.
- `context` 모듈의 `ContextAssembler` 가 요약 구역을 조립한다. 예산은 `MemoryService` 를 거쳐 `MemoryContextSettings` 에서 받는다. `context` 가 `user` 를 새로 import 하지 않게 한다(패키지 순환을 늘리지 않는다).
- `mcp` 모듈의 `McpMemoryRemember` 가 도구 인자와 조건 1~5 를 본다. 조건 3 과 6, 7 은 지금처럼 각 자리가 본다.
- 관리자 API 는 이미 있는 `admin/memory/presentation`(`AgentMemorySettingController` 옆)에 둔다. 서비스는 `memory` 모듈의 것을 쓴다. 키 상수 `MEMORY_CONTEXT` 는 `user.domain.GroupSetting` 에 `MEMORY_COLLECTIONS` 와 같은 방식으로 더한다.
- `mcp` 의 인자 경로 전체(`McpDtos.MemoryRememberArguments.FIELDS`, `McpController.remember`, `McpToolService.remember`, `McpMemoryRemember`)와 `MemoryRememberRequest` 가 새 인자를 받는다. 모르는 인자와 모양 오류의 `-32602` 는 지금처럼 `McpController` 가 낸다.
- 그룹 설정 줄의 읽기와 쓰기는 `user.application.GroupSettingStore` 를 쓴다. 모델 설정과 도구 숨김이 같은 방식이다.

## 인터페이스와 데이터

### 스키마(PR1)

마이그레이션 하나에 DDL 만 둔다. 번호는 `orch queue reserve` 로 받는다.

| 표 | 칸 | 뜻 |
| --- | --- | --- |
| `memory` | `summary TEXT NULL` | 요약 줄. 암호문이거나(키가 있으면) 평문. `NULL` 은 「이 기능 전에 만든 줄이라 아직 이관하지 않았다」, 빈 글(암호화한 빈 글)은 「요약 없음」 이다 |
| `memory_revision` | `summary TEXT NULL` | 그 revision 의 요약. 이 기능 전 판은 `NULL` 이고 「요약 없음」 으로 읽는다 |
| `memory_capture` | `replaced_memory_ids VARCHAR(512) NULL` | 합치기 요청에서 대신할 수 있다고 판정한 옛 기억 번호의 JSON 배열. 합치기가 아니면 `NULL` |

- 이 기능 뒤에 쓰는 모든 경로는 `summary` 를 `NULL` 로 두지 않는다. 요약이 없으면 빈 글을 암호화해 둔다. 그래서 `NULL` 이 이관 표지가 되고, 사용자가 요약을 지운 줄을 이관이 다시 채우지 않는다
- 테이블은 늘지 않는다

### 글 타입

`MemoryText(title, content)` 에 `summary` 를 더한다. 풀지 못하면 지금처럼 셋 다 없다. `StoredMemoryText`, `SealedMemoryText` 도 `summary` 를 갖는다.
`MemoryTexts.seal` 은 요약을 `memory:<번호>:summary:<범위>:<주인>` AAD 로 암호화하고 `open` 은 저장된 요약이 `NULL` 이면 빈 글로 낸다.
`Memory.summary()` 는 푼 요약이고 없으면 빈 글이다. `Memory.hasSummary()` 는 빈 글이 아닌가다.
요약을 적는 엔티티 메서드는 `USER` 범위의 `MEMORY` 가 아니면 빈 글 밖의 값을 거절한다(`IllegalArgumentException`). 그룹 기억과 문서에 요약이 생기지 않는 불변식이고, 그 요청을 받는 화면 경로는 PR2 가 `VALIDATION_FAILED` 로 바꿔 낸다.

요약을 다루는 기존 경로를 모두 바꾼다.

- `MemoryTextMigration` 의 `rewrite`, `verify`, `migrateRevision` 이 요약도 함께 다시 암호화하고 해시를 대조한다. 평문 줄의 평문 요약이 제목·본문만 암호화된 채 남지 않게 한다. 요약이 `NULL` 인 줄은 `NULL` 로 둔다(「이관 전」 표지를 지우지 않는다)
- `MemoryRevisionWriter.saveRevision`(revision 을 남길 때 다시 암호화하는 분기 포함)과 `revise` 가 요약을 받는다. `MemoryService.revise` 와 `update` 도 요약 인자를 받는다(`null` 은 지금 값 유지)
- 되돌리기 `MemoryCaptureService.restore` 는 이전 revision 의 본문과 함께 요약도 되돌린다. 이전 revision 의 요약이 `NULL` 이면 빈 글로 되돌린다
- 화면 수정(`PATCH /api/v1/memories/{id}`, PR2 전까지 요약 칸이 없다)은 본문이 바뀌고 지금 요약이 옛 본문과 같으면 요약을 새 본문으로 바꾸되 새 본문이 200자를 넘으면 요약을 비운다. 요약이 옛 본문과 다르면 그대로 둔다. 이관으로 「요약 = 본문」 이 된 기억을 화면에서 고쳐도 옛 사실이 요약으로 남지 않게 한다

### 조립(PR1)

```text
항상 층 → 요약 구역(MEMORY_FACTS) → 질문 관련 구역(MEMORY_SEARCH) → 색인(MEMORY_INDEX)
```

- 요약 구역 한 줄은 `- [번호] 제목: 요약` 이다. 줄바꿈은 공백 하나로 바꾼다(`MemoryItems.oneLine`). 항목 본문 칸(`ContextItem.body`)에는 요약을 넣는다. 「참고한 기억」 은 지금처럼 `MEMORY_FACTS` 줄을 읽는다
- 요약 구역에 든 기억도 질문 관련 구역의 후보다. `MemorySearchSection.place` 에 「색인에 다시 놓지 않을 번호」(요약 구역에 든 번호)를 넘긴다. 요약 구역에 든 기억이 색인에 두 번 오르지 않게 한다
- 요약 구역의 고른 규칙 이름은 `facts-recent-v2` 로 올린다(`ContextSelection` 의 「규칙을 바꾸면 버전을 올린다」)
- 예산은 `MemoryContextSettings.forGroup(groupId)` 의 `summaryMaxChars` 와 `maxChars` 다. `assistant.context.max-chars`, `facts-max-chars`, `facts-item-max-chars` 설정은 지운다. 그룹이 없는 사용자는 기본값이다
- 요약 구역 예산이 0 이 되는 값은 받지 않는다(최솟값 1,000). 운영에서 구역을 끄는 길은 없다. 회수 측정의 `factsOff` 모드는 테스트가 예산을 0 으로 주는 seam(`MemoryContextSettings` 의 값을 테스트가 바꾸는 것)으로 유지한다
- 요약 구역은 색인 몫을 떼어 둔 뒤의 자리에 담긴다. 그래서 저장 검사에 `summaryMaxChars ≤ maxChars - maxChars / index-budget-ratio` 를 더한다(기본 비율 4 에서 전체의 3/4). 스펙의 「요약은 전체를 넘지 못한다」 보다 좁고, 설정 값과 실제로 담기는 예산이 어긋나지 않게 한다
- 넘친 요약은 색인 줄로 가고, 그 색인 항목의 선택 근거 이름을 `summary-over-budget-v1` 로 둔다. 질문 점수가 있어도 이 근거가 앞선다. 넘친 번호 집합을 `MemorySearchPlacement` 에 넘겨 `appendIndex` 가 점수 근거로 덮지 않게 한다. `execution_context_source` 에 그대로 남는다. `context_omitted_items` 는 지금처럼 색인에서도 빠진 항목만 센다
- `assembleForOwner` 는 지금처럼 collection 을 필터링하지 않고 조립한다. PR2 의 사용량과 「요약에 못 들어감」 표시가 이 결과를 쓴다(스펙 DECISION23)

### 그룹 설정 `MEMORY_CONTEXT`(PR1, PR3 에서 칸을 더함)

```json
{"version":1,"summaryMaxChars":4000,"maxChars":10000}
```

- PR3 이 `dailySummaryProposals`(기본 3, 0~20)와 `summaryProposalTier`(`INSTANT`, `MEDIUM`, `HIGH`, 기본은 평가로 고른 값)를 선택 칸으로 더한다. 칸이 없으면 기본값이다. 버전은 올리지 않는다
- 범위는 스펙 REQ4 다. 서버가 저장할 때 검사하고 `VALIDATION_FAILED` 로 거절한다
- 읽을 때 모르는 버전이나 깨진 JSON 은 기본값으로 돌고 경고 로그를 남긴다(스펙 DECISION19). 모델 설정과 반대인 까닭은 그 결정이 갖는다
- 저장은 `readForUpdate` 로 잠그고 줄 전체를 쓴다

### 관리자 API(PR1)

| 경로 | 뜻 |
| --- | --- |
| `GET /api/v1/admin/memory-settings` | 요청자 그룹의 지금 값과 기본값, 허용 범위 |
| `PUT /api/v1/admin/memory-settings` | 본문 `{summaryMaxChars, maxChars}`. 저장한 값을 낸다 |

`ADMIN` 이 아니면 `FORBIDDEN` 이다. 바뀐 값은 다음 조립부터 쓰인다. 캐시를 두지 않는다.

### `memory_remember`(PR1)

| 인자 | 모양 | 뜻 |
| --- | --- | --- |
| `summary` | 문자열, 0~200자, 선택 | 새 기억이면 그 요약. 빠지면 요약 없음. `memory_id` 로 고칠 때 빠지면 지금 요약을 두고, 빈 글이면 지운다 |
| `replaces` | 정수 배열, 1~10개, 선택, 중복 없음 | 새 기억이 대신할 옛 기억 번호. `memory_id` 와 함께 주면 `-32602` 다(`McpController`) |

- 바로 저장 조건 3 은 본문과 요약을 따로 질문 원문과 대조한다. 요약이 없거나 빈 글이면 요약은 대조하지 않는다. 둘 중 하나라도 어긋나면 제안이다. 조건 7 은 제목, 본문, 요약을 본다
- 같은 제목과 본문의 제안이 이미 있고 이번 호출이 바로 저장 조건이면 지금은 그 제안을 받아들인다(`existingOutcome`). 이때 저장된 제안의 요약에도 조건 7 을 다시 적용하고, 어긋나면 받아들이지 않고 「같은 제안이 이미 있다」 로 둔다. 이번 호출의 `summary` 와 `replaces` 는 이 분기에서 쓰지 않는다(결과 글에 「이미 있는 제안을 받아들였다」 만 알린다)
- 중복 판정은 지금처럼 제목과 본문이다. 같은 제목과 본문의 새 기억은 요약이 달라도 「이미 안다」 다
- `memory_id` 로 고치는 경로는 본문을 주지 않고 요약만 바꿀 수 없다. 본문은 지금처럼 필수 인자다. 「이미 안다」 판정은 본문과 요약(주지 않았으면 지금 값)이 모두 같을 때다. 같은 본문에 다른 요약을 주면 요약만 바뀐 revision 이 하나 생긴다
- 대신할 수 있는 번호는 `MemoryCaptureService.replaceable` 이 판정한다: 요청자의 `USER`, `MEMORY`, `ACCEPTED`, 풀림, 대체되지 않음, 받는 collection, 새 기억 자신이 아님. 나머지는 건너뛰고 결과 글에 「대신하지 못함: 번호 목록」 만 적는다. 까닭은 적지 않는다
- 대체 칸의 매핑은 지금처럼 읽기 전용(`insertable = false, updatable = false`)으로 둔다. 쓰기는 조건부 네이티브 UPDATE 하나다: `SET superseded_by_memory_id = :new WHERE id IN (:ids) AND scope = 'USER' AND owner_user_id = :user AND entry_type = 'MEMORY' AND status = 'ACCEPTED' AND superseded_by_memory_id IS NULL AND id <> :new` 에 collection 조건(받는 collection 이 있을 때)을 더한다. 판정과 쓰기가 한 문장이라 남의 줄을 잠그지 않고 경쟁도 없다. 번호는 정렬해서 넘긴다. 영향받은 줄의 번호는 같은 트랜잭션에서 다시 조회해 결과 글과 기록에 쓴다. 풀리는지는 쓰기 전에 엔티티를 잠그지 않고 읽어 제외한다
- 바로 저장이든 제안이든 판정을 통과한 번호를 `memory_capture.replaced_memory_ids` 에 둔다. 바로 저장은 위 UPDATE 로 실제 대체한 번호만 둔다
- 제안을 받아들이는 `MemoryService.accept` 는 그 기억의 `PROPOSED` 기록을 찾아 같은 UPDATE 를 돈다. 이 경로에는 에이전트의 collection 범위가 없으므로 collection 조건을 빼고 주인·범위·상태 조건만 건다(대신할 옛 기억도 주인 자신의 기억이다). 실제로 대체한 번호로 기록의 `replaced_memory_ids` 를 고쳐, 대신하지 않은 제목이 「합쳤어요」 에 보이지 않게 한다
- 되돌리기와 삭제는 지금 규칙(대신한 새 항목을 지우거나 승인을 벗어나게 하면 옛 항목의 칸을 비운다)이 그대로 옛 기억을 살린다
- 결과 글과 `isError` 의 문구는 `McpMemoryRemember` 가 갖는다

### 대화의 기록 줄(PR1)

`GET` 기록 목록(`MemoryCaptureController`)의 항목에 `replacedTitles`(문자열 배열)를 더한다. `replaced_memory_ids` 가운데 지금 요청자가 볼 수 있는 기억의 지금 제목이다. 풀지 못한 기억은 뺀다.
화면은 `web/src/components/chat/memory-*` 의 기록 줄과 제안 카드가 「n개를 합쳤어요」 와 제목 목록을 보인다. 평문으로만 그린다(ADR-009).

### 지침(PR1)

- `MEMORY_INSTRUCTIONS` 에 더한다: 남길 때 한 줄 요약(`summary`)을 함께 쓴다. 금방 바뀌는 진행 중인 일(지원 현황, 예약)은 요약 없이 `archivable` 을 참으로 남긴다. 사용자가 기억 정리를 부탁하면 같은 주제의 기억을 하나로 합쳐 `replaces` 에 옛 번호를 준다
- 요약 구역 머리 글에 더한다: 일 맡기기, 이직·경력, 돈·가계부, 여행·일정처럼 이 사람의 사정이 답을 바꾸는 질문이면 답하기 전에 관련 줄의 번호로 `memory_read` 를 불러 상세를 읽는다. 진행 중인 일은 `memory_search` 로 찾는다
- 지침 글이 바뀌므로 지시문 지문이 한 번 바뀐다

### 이관(PR1)

`MemorySummaryBackfill` 이 기동 뒤 한 번 돈다. `MemoryTextMigration` 과 같은 틀이다.

0. `MemoryTextMigration` 뒤에 돈다. 두 클래스 모두에 `@Order` 를 붙인다(암호화 이관 100, 요약 이관 200). 한쪽에만 붙이면 붙인 쪽이 먼저 돈다. 평문 줄에 요약 암호문을 먼저 쓰면 평문 분기가 그 암호문을 요약으로 내기 때문이다
1. `summary IS NULL` 이고 암호화가 켜진 설치면 `data_key_id IS NOT NULL` 인 `memory` 줄의 번호를 읽는다. 아직 데이터 key 로 옮기지 못한 줄은 다음 기동에 본다
2. 줄마다 새 트랜잭션에서 쓰기 잠금으로 다시 읽는다. 여전히 `NULL` 이면 아래로 정하고 암호화해 적는다. revision 번호와 갱신 시각은 바꾸지 않는다
   - 대상(스펙 REQ8: `USER`, `MEMORY`, `ACCEPTED`, `SEARCH`, 대체되지 않음, 풀림, 본문 200자 이하)이면 요약 = 본문
   - 아니면 빈 글
3. 풀지 못한 줄은 건너뛰고 번호만 로그에 남긴다. 다음 기동에 다시 본다

`memory_revision.summary` 는 이관하지 않는다. `NULL` 은 「요약 없음」 으로 읽는다.

### 화면(PR2)

PR2 를 시작할 때 이 절을 자세히 쓴다. 지금 정한 것만 적는다.

- 목록 응답(`MemoryDtos`)에 `summary`, `summaryOmitted`, `alwaysInjected`, `supersededByMemoryId` 를 더한다. 묶음은 화면이 이 칸으로 나눈다
- 사용량 `GET /api/v1/memory/summary-usage` 는 `assembleForOwner` 의 요약 구역 글자 수와 예산을 낸다
- 관리자 사용량은 사람마다 같은 계산을 하고 숫자만 낸다

### 정비 루프 요약 제안(PR3)

PR3 을 시작할 때 이 절을 자세히 쓴다. 지금 정한 것만 적는다.

- 동작 이름 `SUMMARY` 를 정비 사건의 `reason_code` 에 더한다. 제안 문장은 사건의 `detail` 에 주인 key 로 암호화해 둔다. 평문을 DB 에 두지 않는다
- 후보는 요약이 있는 기억 가운데, 마지막으로 요약이 바뀐 revision 보다 뒤에 본문이 바뀐 revision 이 있는 것이다. revision 기록에서 계산하므로 칸을 더하지 않는다
- 문장은 Hermes 실행 한 번으로 만든다. 입력은 그 기억의 상세와 지금 요약만이고 도구를 주지 않는다. 결과는 200자 이하 한 줄인지 검사하고 아니면 문장 없는 후보로 남긴다
- 받아들이면 `memory_remember` 와 같은 수정 경로로 요약만 바꾸고, 후보를 만든 revision 과 지금 revision 이 다르면 거절한다(스펙 DECISION20)
- 실제 모델 평가는 [`answer-quality.md`](../answer-quality.md) 의 답변 품질 평가와 같은 틀로 코디네이터가 운영에서 돌린다

## 실패와 일관성

- 요약을 암호화하지 못하면 지금 제목·본문과 같이 `MEMORY_ENCRYPTION_UNAVAILABLE` 로 저장을 거절한다. 평문으로 내려 저장하지 않는다
- 합치기의 대체 칸은 새 기억 저장과 같은 트랜잭션에서 쓴다. 저장이 실패하면 대체도 남지 않는다
- 이관은 줄마다 트랜잭션이고 실패한 줄만 남긴다. 기동은 잇는다. 이관 전에 실행이 돌면 그 줄은 요약 칸이 `NULL` 이라 요약이 없는 것으로 읽혀 잠깐 색인에만 있다. 이관이 끝나면 다음 실행부터 요약 구역에 든다
- 배포 순서: 마이그레이션과 코드가 한 이미지다. 직전 이미지로 되돌려도 새 칸만 남고 옛 코드는 읽지 않는다. 되돌린 동안 만든 기억은 `summary` 가 `NULL` 이라 다시 올릴 때 이관 대상이 된다. 되돌린 동안 본문을 고친 기억은 요약이 옛 사실로 남을 수 있다. 되돌리기는 드물고 사용자가 화면과 대화에서 고칠 수 있어 이 위험을 받아들인다. 되돌렸다가 다시 올렸으면 운영 확인에서 `updated_at` 이 배포 시각 뒤이고 요약이 있는 줄을 확인한다
- 관리자 설정 저장 둘이 동시에 오면 줄 잠금으로 하나씩 돈다. 줄이 없던 첫 저장 둘은 한쪽이 실패한다(`GroupSettingStore` 의 지금 동작)

## 검증 전략

| ID | 경계와 환경 |
| --- | --- |
| REQ1 | 엔티티와 `MemoryTexts` 단위 테스트(다른 칸 AAD 로 풀리지 않음), H2 통합 테스트로 revision 기록의 요약, 그룹과 문서 거절 |
| REQ2, REQ3 | `ContextAssembler` 통합 테스트: 요약 줄 모양, collection 필터링, `ALWAYS`/`ARCHIVE` 제외, 질문 관련 구역에 상세가 함께 드는 것, 예산 넘침의 건너뛰기와 선택 근거 |
| REQ4 | 관리자 API 통합 테스트(권한, 범위, 줄 없음 기본값, 깨진 JSON 기본값)와 조립이 바뀐 값을 쓰는 테스트 |
| REQ5, REQ6 | `McpMemoryRemember` 와 `MemoryCaptureService` 통합 테스트: 바로 저장과 제안, 요약의 부정·민감 검사, 요약 유지와 지우기, 합치기의 바로 저장·제안 받아들이기·되돌리기, 대신할 수 없는 번호 건너뛰기, 기록 목록의 `replacedTitles` |
| REQ7 | 기억 회수 측정의 합성 사례 세 개와 기존 게이트. 픽스처(`MemoryEvalFixture`)는 넣은 뒤 요약 이관을 불러 요약을 채운다. 요약 구역의 뜻이 바뀌므로 기준 파일은 이 PR 에서 다시 만들고, 이전 값과 나란히 PR 본문에 적는다(기능 파일 「기준 파일」 의 갱신 절차). 배포 뒤 실제 대화 한 번 왕복은 코디네이터가 운영에서 확인한다 |
| REQ8 | 이관 통합 테스트: 대상과 대상 밖, 두 번 실행, 풀지 못한 줄 건너뛰기, 이관 전후 요약 구역 번호 집합 포함 |
| REQ9, REQ10 | PR2 의 화면 테스트와 API 테스트 |
| REQ11, REQ12 | PR3 의 정비 루프 통합 테스트와 운영 평가 결과 |

MySQL 의 새 칸은 Draft CI 의 `backend-mysql` 로 확인한다.

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | 요약 저장 자리 | `memory`, `memory_revision` 의 새 칸 | 작성자 | revision 기록, 암호화, 권한이 기억 한 줄 단위로 맞는다(스펙 DECISION2). 본문 안에 섞으면 검색과 `memory_read` 가 모두 바뀐다 |
| DECISION2 | 「아직 이관하지 않음」 표지 | 요약 칸 `NULL`. 이 기능 뒤 쓰기는 늘 빈 글이라도 적는다 | 작성자 | 따로 표지 칸이나 표를 두지 않고, 사용자가 지운 요약을 이관이 다시 채우지 않는다 |
| DECISION3 | 예산을 읽는 자리 | `group_setting` 의 `MEMORY_CONTEXT` 를 조립마다 읽는다. 캐시 없음 | 작성자 | 그룹 설정의 기존 방식이고 줄 하나 읽기라 비용이 작다. 저장 뒤 비울 캐시가 없다 |
| DECISION4 | 넘친 요약의 기록 | 색인 항목의 선택 근거 이름 `summary-over-budget` | 작성자 | 기존 실행 기록 칸으로 남기고, 빠진 항목 수의 뜻(색인에서도 빠짐)을 바꾸지 않는다 |
| DECISION5 | 제안 상태 합치기의 대신할 번호 | `memory_capture.replaced_memory_ids` 에 두고 받아들일 때 다시 판정 | 작성자 | 제안 카드가 받아들일 때까지 아무것도 바꾸지 않는다. 받아들이는 트랜잭션이 기록을 이미 읽는다 |
| DECISION6 | 설정 파일의 기억 예산 값 | `max-chars`, `facts-max-chars`, `facts-item-max-chars` 를 지운다 | 작성자 | 같은 값의 원본이 둘이면 어느 쪽이 쓰이는지 헷갈린다(스펙 DECISION4) |
| DECISION7 | 요약 제안 문장의 저장 | 정비 사건 `detail` 에 주인 key 로 암호화 | 작성자 | 기억 글을 평문으로 두지 않는다는 결정([ADR-20261103 / memory-encryption](../../../backend/docs/adr/ADR-20261103-memory-encryption.md))을 따른다 |
| DECISION8 | PR2 전 화면 수정의 요약 | 지금 요약이 옛 본문과 같으면 새 본문으로 따라가고, 새 본문이 200자를 넘으면 비운다 | 작성자 | 이관한 요약이 화면 수정 뒤 옛 사실로 남지 않는다. 사용자가 따로 쓴 요약은 건드리지 않는다 |
| DECISION9 | 대체 칸 쓰기 | 조건부 네이티브 UPDATE 하나. 엔티티 매핑은 읽기 전용 그대로 | 작성자 | 남의 줄을 잠그지 않고 경쟁과 낡은 엔티티 flush 가 칸을 되살리지 않는다 |
| DECISION10 | 요약 예산의 저장 검사 | 스펙 범위에 더해 요약 ≤ 전체 - 색인 몫 | 작성자 | 색인 몫을 먼저 떼어 두는 조립 순서 때문에 그보다 큰 값은 실제로 담기지 않는다 |
| DECISION11 | 직전 이미지로 되돌렸을 때의 낡은 요약 | 받아들인다. 다시 올렸으면 운영 확인에서 본다 | 작성자 | 되돌리기가 드물고 사용자가 고칠 수 있다. 막으려면 옛 코드가 알 수 없는 표지가 필요하다 |

## 결정 기록 연결

| 결정 | 종류 | ADR 또는 필요 없음의 이유 | 현재 규칙의 원본 |
| --- | --- | --- | --- |
| 요약 구역(스펙 DECISION2, DECISION4) | 기억 조립 | [ADR-20261008 / memory-facts](../../adr/ADR-20261008-memory-facts.md) 를 PR1 에서 갱신한다 | [`docs/features/memory.md`](../memory.md) 「요약 구역」 |
| 정비 루프의 LLM 제안(스펙 DECISION9) | 정비 정책 | [ADR-20261015 / memory-maintenance](../../adr/ADR-20261015-memory-maintenance.md) 를 PR3 에서 갱신한다 | [`docs/features/memory.md`](../memory.md) 「정비 루프」 |
| 설계 DECISION1~7 | 내부 구조 | 필요 없음. 되돌리기 쉬운 배치이고 이 설계와 기능 파일이 원본이다 | 이 문서 |
