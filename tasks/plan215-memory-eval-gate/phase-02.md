# Phase 02. recall@k, 기준선 모드 둘, 악화 금지 게이트

**Execution profile**: standard

## 목표

합성 측정에 recall@5 와 recall@10 을 더하고, 「기억 없음」(`none`)과 「전체 투입」(`fullInjection`) 기준선 모드를 더한다.
운영 기본값 모드(`factsOn`)의 전체 recall@5 와 오기억 노출률을 기준 파일로 커밋하고, 나빠지면 테스트가 실패하게 한다.

**범위 외**: 사례 추가(phase 01). 실행 기록의 선택 근거(phase 03). 조립 코드(`ContextAssembler`)는 바꾸지 않는다.

## 컨텍스트

- 측정 테스트는 `backend/src/test/java/com/bifos/assistant/context/eval/MemoryRecallEvalTest.java`, 지표 계산과 보고서는 같은 패키지의 `MemoryEvalScoreboard.java` 다. 모드는 지금 `factsOff`, `factsOn` 둘이고 `assemblers` 맵에 넣은 순서가 보고서의 열 순서다.
- 항목 상태는 `stateOf(Long memoryId, AssembledContext context)` 가 묶음(`context.bundle().items()`)에서 `ref` 로 찾아 정한다. 묶음 항목의 순서가 지시문에 실린 순서다.
- 「전체 투입」 의 항목은 권한 조건을 지난 것만이다. `MemoryService.accessOf(agentId)`, `alwaysInjectedFor(user, access)`, `indexedFor(user, access)` 가 공개 메서드이고 `ContextAssembler` 가 쓰는 것과 같다.
- Gradle 속성을 테스트 JVM 의 시스템 속성으로 넘기는 본보기는 `backend/build.gradle.kts` 의 `archunitFreezeProperties` 다.
- `gradlew` 는 `backend/` 안에 있고 테스트의 작업 디렉터리도 `backend/` 다(`REPORT_DIR` 이 `build/reports/memory-eval` 로 상대 경로다).

**근거 문서**: `backend/docs/adr/ADR-20261015-memory-eval-gate.md`, `docs/features/memory.md` 의 「지표(Memory 회수 측정)」 와 「기준 파일」

## 의도 메모

- recall@k 의 순서는 `INLINE` 항목을 실은 순서대로 놓고 그 뒤에 `TITLE_ONLY` 항목을 실은 순서대로 놓는다. 본문을 받은 것이 모델에게 먼저 쓰인다고 본다. `OMITTED` 와 `ABSENT` 는 순위가 없다.
- 게이트는 `factsOn` 전체 값 두 개에만 건다. 범주마다 걸면 사례 하나가 비율을 크게 흔든다. 기준선 모드 둘은 비교용이라 게이트를 걸지 않는다.
- 결과는 결정적이므로 비교에 큰 허용 오차를 두지 않는다. 부동소수 비교만 1e-9 로 한다.
- 기준 파일을 쓰는 실행은 게이트를 건너뛴다. 쓰고 나서 같은 실행으로 통과시키면 나빠진 값도 그대로 기준이 된다. 그래서 쓰기는 명시한 Gradle 속성이 있을 때만 한다.

## 작업 항목

### 1. `MemoryEvalScoreboard.java` 의 순위와 지표

- `ItemVerdict` 에 `Integer rank` 를 더한다. 위 순서로 매긴 1 부터의 순위이고 순위가 없으면 null 이다.
- `Metrics` 에 `Ratio recallAt5`, `Ratio recallAt10` 을 더한다. 분자는 `expect` 항목 가운데 `rank` 가 k 이하인 수, 분모는 `expect` 항목 수다.
- `markdown()` 과 `json()` 에 두 값을 더한다. 열 이름은 `recall@5`, `recall@10` 이고 분모가 0 이면 지금처럼 「해당 없음」(JSON 은 `null`)이다.

### 2. `MemoryRecallEvalTest.java` 의 모드

- 모드는 `none`, `factsOff`, `factsOn`, `fullInjection` 순서다. `none` 과 `fullInjection` 은 `ContextAssembler` 를 쓰지 않고 판정을 직접 만든다.
  - `none`: 모든 항목이 `ABSENT`, 글자 수 0, 빠진 항목 0
  - `fullInjection`: 그 사례의 에이전트로 `accessOf` 를 구하고 `alwaysInjectedFor` 와 `indexedFor` 의 합집합을 항상 층 번호 순, 그다음 색인 번호 순으로 늘어놓아 모두 `INLINE` 으로 둔다. 나머지는 `ABSENT` 다. 글자 수는 항목마다 `- [번호] 제목: 본문` 한 줄과 줄바꿈 하나를 더한 길이다
- `factsOff`, `factsOn` 은 지금처럼 조립하고 묶음 순서로 순위를 매긴다.
- 보고서 앞머리 문장(`MemoryEvalScoreboard.HEADLINE`)은 바꾸지 않는다.

### 3. 기준 파일과 게이트

- 새 `backend/src/test/java/com/bifos/assistant/context/eval/MemoryEvalBaseline.java`: `record MemoryEvalBaseline(int datasetVersion, String mode, double recallAt5, double falseMemoryExposure)`. 클래스패스 `/memory-eval/baseline.json` 을 읽는 `load()`, `backend/src/test/resources/memory-eval/baseline.json` 에 쓰는 `write(Path)`, 지금 값과 견주어 어긋남을 문장 목록으로 돌려주는 `violations(int datasetVersion, Metrics factsOn)` 을 둔다.
  - 버전이 다르면 「사례 묶음 버전 X 와 기준 파일 버전 Y 가 다르다. `./gradlew test --tests '*MemoryRecallEvalTest' -PmemoryEval.writeBaseline=true` 로 다시 만든다」
  - recall@5 가 기준보다 1e-9 넘게 낮으면, 오기억 노출률이 기준보다 1e-9 넘게 높으면 각각 한 문장
- `measuresRecallAndKeepsBoundary` 끝에서 시스템 속성 `memoryEval.writeBaseline` 이 `true` 면 `factsOn` 전체 값으로 기준 파일을 쓰고, 아니면 `violations` 가 비었는지 단언한다. 권한 경계 노출 단언은 그대로 먼저 둔다.
- `backend/build.gradle.kts` 의 `tasks.withType<Test>().configureEach` 에서 Gradle 속성 `memoryEval.writeBaseline` 이 있으면 같은 이름의 시스템 속성으로 넘긴다.
- 새 `backend/src/test/resources/memory-eval/baseline.json` 은 위 명령으로 만든다. 값을 직접 타이핑하지 않는다.
- `docs/features/memory.md` 의 「기준 파일」 첫 줄 끝의 `ref-ignore` 주석을 지운다. 이제 그 경로에 파일이 있다.

### 4. 이 phase 를 검증하는 테스트

`MemoryRecallEvalTest.java` 에 Spring 없이 도는 테스트를 더한다.

- 판정 셋을 직접 만들어 recall@5 와 recall@10 의 분자와 분모를 확인한다. 순위 6 인 `expect` 항목은 recall@5 에 들지 않고 recall@10 에 든다. 순위가 null 인 항목은 어디에도 들지 않는다.
- `MemoryEvalBaseline.violations` 가 같은 값이면 빈 목록, recall@5 가 낮거나 노출률이 높거나 버전이 다르면 그 문장을 낸다.

## 검증

```bash
cd backend && ./gradlew test --tests '*MemoryRecallEvalTest' -PmemoryEval.writeBaseline=true
cd backend && ./gradlew test --tests '*MemoryRecallEvalTest'
cd backend && grep -E 'recall@5' build/reports/memory-eval/report.md && grep -E 'fullInjection' build/reports/memory-eval/report.md
cd backend && python3 -c "import json;b=json.load(open('src/test/resources/memory-eval/baseline.json'));d=json.load(open('src/test/resources/memory-eval/family-cases.json'));assert b['datasetVersion']==d['version'] and b['mode']=='factsOn',b"
cd backend && ./gradlew checkstyleTest spotlessCheck
node --test test/unit/doc-references.test.ts test/unit/doc-code-references.test.ts
```

기대값: 첫 명령이 기준 파일을 만들고, 두 번째 명령이 게이트를 지나 통과한다. 보고서에 네 모드와 recall@5 가 있다.
포맷이 어긋나면 기능 커밋 뒤에 `./gradlew spotlessApply` 결과를 따로 커밋한다(`backend/AGENTS.md` 의 「포맷」).

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryEvalScoreboard.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryRecallEvalTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryEvalBaseline.java` | 신규 |
| `backend/src/test/resources/memory-eval/baseline.json` | 신규 |
| `backend/build.gradle.kts` | 수정 |
| `docs/features/memory.md` | 수정 |
