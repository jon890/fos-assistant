# Memory 회수 측정

Memory 를 바꿀 때마다 「다음 대화에서 알고 있는가」 와 「틀리거나 남의 사실을 싣지 않는가」 를 숫자로 보는 측정이다.
새 기능이 아니라 측정 도구다. 합성 측정과 운영 집계 둘로 나눈다.

| 측정 | 무엇으로 | 무엇을 알 수 있나 | 무엇을 알 수 없나 |
| --- | --- | --- | --- |
| 합성 측정 | 가상 가족 시험 세트를 `ContextAssembler` 로 조립한 결과 | 기대한 사실이 본문으로 실리는가, 옛 값과 권한 밖 항목이 실리는가, 실행마다 몇 글자가 실리는가 | 모델이 그 글을 읽고 실제로 맞게 답하는가 |
| 운영 집계 | 운영 데이터베이스의 기록 칸. 원문을 읽지 않는다 | 되돌리기 비율, 바로 저장 대 제안, `memory_read` 호출률, 실행당 문맥 글자 수 | 답이 맞았는가 |

**합성 측정의 숫자는 모델 답의 오기억률이 아니다.** 모델 없이 조립한 글만 본다. 보고서 첫 줄과 이 문서가 그렇게 밝힌다.
실린 글에 틀린 사실이 있으면 모델이 그것을 쓸 수 있다는 「노출」 을 센다.

## 합성 측정

### 실행

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests '*MemoryRecallEvalTest'
```

CI 의 backend 검사(`./gradlew test`)에 함께 돈다.
결과는 `backend/build/reports/memory-eval/report.md` 와 `report.json` 에 남고 표준 출력에도 나온다.

### 구조

| 파일 | 하는 일 |
| --- | --- |
| `backend/src/test/resources/memory-eval/family-cases.json` | 시험 세트. 가상 이름만 쓴다 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryEvalDataset.java` | 시험 세트를 읽고 참조가 맞는지 검사한다 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryEvalScoreboard.java` | 사례마다의 판정을 모아 지표와 보고서를 만든다 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryRecallEvalTest.java` | 사례마다 사용자와 에이전트와 Memory 를 새로 넣고 조립해 판정한다. 실패 조건을 단언한다 |

사례 하나는 이렇게 돈다.

1. 앞 사례의 Memory 를 모두 지운다.
2. 사례의 Memory 를 표에 직접 넣는다. 시각은 시험 시계 기준 `updatedDaysAgo` 일 전이다.
3. 묻는 사람과 에이전트로 `ContextAssembler.assemble` 을 부른다. 측정 모드마다 한 번씩 부른다.
4. 조립 결과의 문맥 묶음에서 항목마다 상태를 읽는다. 본문으로 실렸으면 `INLINE`, 제목만이면 `TITLE_ONLY`, 빠졌거나 없으면 `ABSENT` 다.

Hermes 대역도 모델도 부르지 않는다. 그래서 빠르고 결과가 늘 같다.

### 측정 모드

| 모드 | 뜻 |
| --- | --- |
| `profileOff` | 프로필 구역을 끈 조립. 프로필 구역이 생기기 전의 동작과 같다 |
| `profileOn` | 운영 기본값의 조립. 프로필 구역 2,000자, 항목 200자 |

모드는 `ContextProperties` 만 바꾼 `ContextAssembler` 를 시험 안에서 따로 만들어 고른다. Spring 컨텍스트를 바꾸지 않는다.

### 시험 세트

범주는 LongMemEval(arXiv 2410.10813)의 다섯 능력에 권한 경계와 부하를 더했다.

| 범주 | 뜻 | 예 |
| --- | --- | --- |
| `INFORMATION_EXTRACTION` | 한 번 말한 사실을 다음 대화에서 안다 | 딸 이름, 좋아하는 음식 |
| `MULTI_SESSION` | 여러 대화에서 나눠 말한 사실을 함께 안다 | 아이 둘의 학교와 학년 |
| `KNOWLEDGE_UPDATE` | 바뀐 사실의 새 값을 안다 | 이직 뒤의 회사. 옛 값이 같이 남은 경우와 고친 경우 |
| `TEMPORAL` | 최근에 바뀐 사실이 예산 안에 든다 | 오래된 항목이 많을 때 지난주에 남긴 사실 |
| `ABSTENTION` | 말한 적 없는 것에 엉뚱한 사실이 붙지 않는다 | 아들 학교를 물었는데 딸 학교만 있다 |
| `BOUNDARY` | 남의 항목과 권한 밖 항목이 실리지 않는다 | 다른 사용자의 개인 항목, 받지 않는 collection, 민감 허용 없는 민감 항목, 제안, 거절, 보관 |
| `LOAD` | 항목이 많을 때 실리는 글자와 빠지는 항목 | 짧은 사실 120개 |

```json
{
  "version": 1,
  "note": "합성 시험 세트. 이름과 사실은 모두 지어낸 것이다",
  "users": [
    { "key": "parentA", "role": "ADMIN" },
    { "key": "childA", "role": "MEMBER" }
  ],
  "agents": [
    { "key": "general", "collections": [{ "collection": "core", "allowSensitive": false }] }
  ],
  "cases": [
    {
      "id": "IE-01",
      "category": "INFORMATION_EXTRACTION",
      "asker": "parentA",
      "agent": "general",
      "question": "우리 딸 이름이 뭐였지?",
      "memories": [
        { "key": "m1", "owner": "parentA", "scope": "USER", "title": "딸 이름", "content": "딸 이름은 홍지수다", "updatedDaysAgo": 3 }
      ],
      "expect": ["m1"],
      "forbidden": []
    }
  ]
}
```

| 칸 | 뜻 |
| --- | --- |
| `users[].key`, `role` | 사례에서 부르는 이름과 역할. 모두 같은 그룹이다 |
| `agents[].collections` | 그 에이전트가 받는 collection 과 민감 허용 |
| `cases[].question` | 사람이 읽으라고 둔 질문. 판정에 쓰지 않는다 |
| `memories[]` | `key`, `owner`(USER 범위일 때), `scope`, `title`, `content`. 선택 칸은 `collection`(기본 `core`), `retrieval`(기본 `SEARCH`), `status`(기본 `ACCEPTED`), `sensitivity`(기본 `NORMAL`), `entryType`(기본 `MEMORY`), `updatedDaysAgo`(기본 0) |
| `filler` | 선택. `{ "owner", "count", "contentChars" }`. 짧은 `USER` 항목을 그 수만큼 지어 넣는다. `LOAD` 와 `TEMPORAL` 이 쓴다 |
| `expect` | 답에 필요한 항목의 `key` |
| `forbidden[]` | `{ "memory", "kind" }`. 실리면 안 되는 항목. `kind` 는 아래 표 |

| `forbidden.kind` | 뜻 | 실리면 |
| --- | --- | --- |
| `BOUNDARY` | 남의 항목, 권한 밖 collection, 민감 허용 없는 민감 항목, 제안, 거절, 보관 | 실패. 제목만 실려도 실패다 |
| `SUPERSEDED` | 새 값이 따로 있는 옛 값 | 오기억 노출로 센다 |
| `DISTRACTOR` | 질문과 비슷하지만 다른 사람이나 다른 것의 사실 | 오기억 노출로 센다 |

시험 세트를 읽을 때 `key` 가 겹치거나 없는 이름을 가리키면 시험이 실패한다.
시험 세트에는 실제 사람 이름, 메일, 계정, 금액을 쓰지 않는다. 이 저장소는 공개 저장소다.

### 지표

모드마다, 범주마다 낸다.

| 지표 | 계산 |
| --- | --- |
| 회수율(본문) | `expect` 항목 가운데 `INLINE` 인 수 / `expect` 항목 수 |
| 회수율(제목 이상) | `INLINE` 이나 `TITLE_ONLY` 인 수 / `expect` 항목 수 |
| 오기억 노출률 | `SUPERSEDED` 와 `DISTRACTOR` 항목 가운데 `INLINE` 인 수 / 그 항목 수 |
| 권한 경계 노출 | `BOUNDARY` 항목 가운데 `ABSENT` 가 아닌 수 |
| 실행당 Memory 글자 수 | 조립 결과의 `chars`. 공통 답변 지침은 빼고 센다. 평균, 중앙값, 최댓값 |
| 빠진 항목 | 조립 결과의 `omittedItems` 합 |

**실패 조건은 권한 경계 노출 하나다.** 어느 모드에서든 0 이 아니면 시험이 실패한다.
나머지 지표는 보고서에만 남긴다. 기준선이 아직 없고, 프로필 구역이 오기억 노출을 늘리는 것은 알고 고른 비용이기 때문이다([ADR-20261008 / memory-profile](../adr/ADR-20261008-memory-profile.md)).

## 운영 집계

운영 데이터베이스에서 읽기만 한다. 본문과 제목 칸을 읽지 않는다.
접속과 실행 방법은 운영 저장소가 갖는다. 여기에는 SELECT 문만 둔다. 기간은 바꿔 쓴다.

**되돌리기 비율.** 바로 저장한 기록 가운데 사람이 되돌린 비율이다. 뒤처리 추출(#310 의 4단계)을 켤지 정하는 근거다.

```sql
SELECT kind,
       COUNT(*) AS captures,
       SUM(undone_at IS NOT NULL) AS undone,
       ROUND(SUM(undone_at IS NOT NULL) / COUNT(*), 3) AS undo_ratio
FROM memory_capture
WHERE created_at >= NOW() - INTERVAL 30 DAY
  AND kind IN ('CREATED', 'UPDATED')
GROUP BY kind;
```

**바로 저장 대 제안, 제안의 결말.** 제안은 `memory.status` 로 결말을 본다. 지운 항목은 `memory` 에 줄이 없어 `GONE` 이다.

```sql
SELECT c.kind,
       COALESCE(m.status, 'GONE') AS memory_status,
       COUNT(*) AS captures
FROM memory_capture c
LEFT JOIN memory m ON m.id = c.memory_id
WHERE c.created_at >= NOW() - INTERVAL 30 DAY
GROUP BY c.kind, COALESCE(m.status, 'GONE')
ORDER BY c.kind, memory_status;
```

**실행당 문맥 글자 수와 빠진 항목.** 대화의 루트 실행만 본다. `context_chars` 는 공통 답변 지침을 함께 센다.

```sql
SELECT COUNT(*) AS executions,
       ROUND(AVG(context_chars)) AS avg_context_chars,
       MAX(context_chars) AS max_context_chars,
       SUM(context_omitted_items > 0) AS executions_with_omitted
FROM agent_execution
WHERE parent_execution_id IS NULL
  AND conversation_id IS NOT NULL
  AND context_chars IS NOT NULL
  AND started_at >= NOW() - INTERVAL 30 DAY;
```

**실행마다 실린 층별 항목 수.** `MEMORY_PROFILE` 은 프로필 구역이 생긴 뒤부터 나온다.

```sql
SELECT s.source,
       s.body_mode,
       COUNT(*) AS items,
       COUNT(DISTINCT s.execution_id) AS executions
FROM execution_context_source s
JOIN agent_execution e ON e.id = s.execution_id
WHERE s.source LIKE 'MEMORY\_%'
  AND e.started_at >= NOW() - INTERVAL 30 DAY
GROUP BY s.source, s.body_mode;
```

**`memory_read` 호출률.** 대화의 루트 실행 가운데 `memory_read` 를 한 번이라도 부른 비율이다. 프로필 구역이 이 값을 줄이는지 본다.

```sql
SELECT COUNT(*) AS executions,
       SUM(EXISTS (
           SELECT 1 FROM execution_event ev
           WHERE ev.execution_id = e.id
             AND ev.event_type = 'TOOL_STARTED'
             AND ev.tool_name LIKE '%memory\_read'
       )) AS executions_with_read
FROM agent_execution e
WHERE e.parent_execution_id IS NULL
  AND e.conversation_id IS NOT NULL
  AND e.started_at >= NOW() - INTERVAL 30 DAY;
```

## 기준값

합성 측정의 첫 기준값은 이 문서를 만든 PR 의 보고서에 있다. 숫자를 이 문서에 옮겨 적지 않는다. 시험 세트를 고치면 숫자가 바뀐다.
운영 집계의 숫자는 운영 저장소에 남긴다.
