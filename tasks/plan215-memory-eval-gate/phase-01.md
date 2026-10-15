# Phase 01. 사례 묶음에 취향, 대체, 놓침 사례를 더한다

**Execution profile**: standard

## 목표

합성 측정의 사례 묶음을 버전 2 로 올린다. 취향(`PREFERENCE`)과 놓침(`RECALL_GAP`) 범주를 더하고, 대체 사례에서 새 항목이 어느 옛 항목을 대체하는지 적고, 답 판정이 쓸 「예, 아니오」 질문 칸을 더한다.
다음 단계(질문 기반 선별, 대체 링크)가 개선을 이 사례로 보이게 하려는 것이다.

**범위 외**: recall@k 지표와 기준선 모드, 게이트(phase 02). 답 판정 하네스(phase 05).

## 컨텍스트

- 사례 묶음은 `backend/src/test/resources/memory-eval/family-cases.json` 이고 모양과 참조 검사는 `backend/src/test/java/com/bifos/assistant/context/eval/MemoryEvalDataset.java` 가 갖는다. 지금 버전 1, 42개 사례, 7개 범주다.
- `MemoryEvalDataset` 은 `FAIL_ON_UNKNOWN_PROPERTIES` 로 읽는다. JSON 에 새 칸을 넣으려면 record 에 먼저 칸이 있어야 한다.
- 사례 묶음을 돌리는 테스트는 `MemoryRecallEvalTest` 다. 범주별 보고는 `MemoryEvalScoreboard` 가 `Category.values()` 로 돌므로 enum 에 더하면 보고서에 저절로 열린다.
- 대체 오답지(`forbidden` 의 `SUPERSEDED`)가 있는 기존 사례는 `KU-01`, `KU-02`, `KU-03`, `KU-04`, `KU-07` 이고, 모두 `m2` 가 `m1` 을 대체한다.
- 개인 사실 구역의 후보 조건은 범위 `USER`, 종류 `MEMORY`, 민감도 `NORMAL`, 본문 200자 이하다(`ContextAssembler.factCandidate`). 놓침 사례는 이 조건 밖에 놓아야 지금 조립이 본문을 싣지 못한다.
- 테스트 이름 규칙은 `backend/AGENTS.md` 의 「테스트 이름」 이다. 실제 사람 이름, 메일, 계정, 금액을 쓰지 않는다.

**근거 문서**: `backend/docs/adr/ADR-20261015-memory-eval-gate.md`, `docs/features/memory.md` 의 「사례 묶음」

## 의도 메모

- `supersedes` 는 지금 조립에 아무 영향이 없다. 단계 2 가 이 값으로 `SUPERSEDES` 링크를 만들어 같은 사례를 다시 잰다. 그래서 오답지와 양방향으로 맞는지 지금 검사한다.
- `judge` 는 조립 측정이 읽지 않는다. 자유 서술 점수 대신 기대 답이 정해진 「예, 아니오」 질문으로 쓴다(PrefEval). 질문은 답 글만 보고 판정할 수 있게 쓴다.
- 취향 사례의 질문에는 선호를 가리키는 낱말을 넣지 않는다. 키워드 일치만으로 찾히면 의미 검색의 효과를 재지 못한다.

## 작업 항목

### 1. `MemoryEvalDataset.java` 의 칸과 검사

- `Category` 에 `PREFERENCE`, `RECALL_GAP` 을 더하고 각 상수에 한 줄 Javadoc 을 단다(뜻은 `docs/features/memory.md` 의 범주 표와 같다).
- `MemorySeed` 에 선택 칸 `String supersedes` 를 더한다. 같은 사례의 다른 Memory key 이고, 이 항목이 그 옛 항목을 대체한다는 뜻이다.
- `Case` 에 선택 칸 `List<JudgeCheck> judge` 를 더하고 null 이면 빈 목록으로 둔다. 새 record `JudgeCheck(String id, String question, boolean expect)` 를 `MemoryEvalDataset` 안에 둔다.
- `validateCase` 에 검사를 더한다. 어기면 지금처럼 `IllegalStateException` 이고 메시지에 칸 이름을 넣는다.
  - `supersedes` 는 같은 사례에 있는 다른 key 이고, 자기 자신이 아니며, 범위와 주인(`owner` 또는 `group`)이 같다
  - `supersedes` 가 가리키는 항목은 `forbidden` 에 `SUPERSEDED` 로 있다. 거꾸로 `SUPERSEDED` 항목은 어느 항목의 `supersedes` 가 가리킨다
  - `judge` 의 `id` 는 사례 안에서 겹치지 않고 비지 않는다. `question` 은 비지 않는다

### 2. `family-cases.json` 을 버전 2 로

- `version` 을 2 로, `note` 는 그대로 둔다.
- `KU-01`, `KU-02`, `KU-03`, `KU-04`, `KU-07` 의 `m2` 에 `"supersedes": "m1"` 을 더한다.
- `KNOWLEDGE_UPDATE` 에 대체 사례 3개를 더한다(`KU-08`부터). 하나는 값이 두 번 바뀌어 옛 값 둘이 `SUPERSEDED` 다. 하나는 「땅콩 알레르기 있음」 과 「없음」 처럼 부정만 다른 짝이다. 하나는 날짜가 바뀐 일정이다. 각 사례에 `judge` 를 하나 이상 둔다(예: 「답이 옛 값을 지금 값으로 말하는가?」, 기대 `false`).
- `PREFERENCE` 사례 5개(`PF-01`부터). 선호 기억 하나 이상을 `expect` 에 두고, 질문은 선호를 무시하면 답이 어긋나는 요청이다(예: 기억 「아이는 매운 음식을 못 먹는다」, 질문 「이번 주말 가족 저녁 메뉴 추천해 줘」). 같은 사용자의 비슷하지만 다른 사실을 `DISTRACTOR` 로 하나 이상 둔다. 각 사례에 `judge` 를 하나 이상 둔다(예: 「답이 매운 음식을 권하는가?」, 기대 `false`).
- `RECALL_GAP` 사례 5개(`RG-01`부터). 답에 필요한 사실이 지금 조립에서 본문으로 실리지 않게 놓는다.
  - 본문이 200자를 넘는 `USER` 항목의 본문 속 세부를 묻는다(제목에는 그 낱말이 없다) 2개
  - `GROUP` 범위 `SEARCH` 항목의 본문 세부를 묻는다 1개
  - `filler` 로 색인 예산을 넘겨 그 항목이 `OMITTED` 가 되는 경우 1개
  - 개인 사실 구역 예산(2,000자)을 넘쳐 최근에 고친 항목들에 밀린 오래된 짧은 사실 1개

### 3. 이 phase 를 검증하는 `MemoryRecallEvalTest.java`

- `rejectsBrokenReferencesWhileLoading` 에 실패 입력을 더한다. `supersedes` 가 없는 key 를 가리킨다, 자기 자신을 가리킨다, 다른 주인의 항목을 가리킨다, `SUPERSEDED` 항목을 아무도 가리키지 않는다, `judge` 의 `id` 가 겹친다. 각각 `IllegalStateException` 이고 메시지에 `supersedes` 나 `judge` 가 들어 있다.
- `VALID` 사례 묶음에 `judge` 한 줄을 더해 정상 입력이 읽히는지 함께 본다.
- `measuresRecallAndKeepsBoundary` 는 고치지 않는다. 새 사례를 더해도 권한 경계 노출이 0 이어야 한다.

## 검증

```bash
cd backend && ./gradlew test --tests '*MemoryRecallEvalTest'
cd backend && grep -E 'PREFERENCE|RECALL_GAP' build/reports/memory-eval/report.md
cd backend && python3 -c "import json;d=json.load(open('src/test/resources/memory-eval/family-cases.json'));assert d['version']==2;import collections;c=collections.Counter(x['category'] for x in d['cases']);assert c['PREFERENCE']>=5 and c['RECALL_GAP']>=5 and c['KNOWLEDGE_UPDATE']>=10,c"
cd backend && ./gradlew checkstyleTest spotlessCheck
```

기대값: 테스트가 통과하고 보고서에 두 범주가 있다. `RECALL_GAP` 의 `factsOn` 회수율(본문)은 0% 에 가깝다. 이것이 단계 1 이 올릴 출발점이다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryEvalDataset.java` | 수정 |
| `backend/src/test/resources/memory-eval/family-cases.json` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryRecallEvalTest.java` | 수정 |
