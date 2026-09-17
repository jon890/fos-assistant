# Phase 05. 한 항목이 커도 나머지를 싣는다

**Execution profile**: standard

## 목표

`always_inject` 본문 하나가 문맥 한도를 넘어도 색인과 나머지 항목이 실리게 한다.
무엇이 잘렸는지 사용자가 화면에서 본다.

**범위 외**:
한도값 자체를 바꾸지 않는다. `assistant.context.max-chars` 는 그대로 8000 이다.
저장할 때 본문 길이를 제한하지 않는다. 긴 사실을 쓰는 것 자체를 막지 않는다.

## 컨텍스트

실측으로 찾은 결함이다.
`always_inject` 항목 하나의 본문을 8,023 글자로 키웠더니 이렇게 됐다.

| 본문 글자 수 | 실린 문맥 글자 수 | `context_chars` |
| --- | --- | --- |
| 7,881 | 7,995 | 7,995 |
| 7,952 | 7,975 | 7,975 |
| **8,023** | **0** | **0** |
| 9,940 | 0 | 0 |

**한 항목이 한도를 넘는 순간 Memory 가 통째로 빈다.**
그 항목만 빠지는 것이 아니라 색인까지 사라진다.
색인이 없으면 `memory_read` 로 읽을 번호도 알 수 없으므로 에이전트는 Memory 를 전혀 받지 못한다.

**대화에는 아무 표시가 나지 않는다.** 서버 로그의 `WARN` 한 줄만 남는다.
입력 토큰도 문맥이 없는 값으로 떨어져, 숫자만 보면 항목을 지운 것과 구별되지 않는다.

원인은 `ContextAssembler.ContextBuilder` 다.

```java
if (truncated) {
    return;                    // 한 번 참이 되면 그 뒤 모든 항목을 건너뛴다
}
String itemWithHeader = next(selected, selectedHeaders, header, item);
if (selected.length() + itemWithHeader.length() > maxChars) {
    truncated = true;          // 첫 항목이 크면 selected 가 빈 채로 끝난다
    return;
}
```

첫 항목이 한도를 넘으면 `selected` 가 빈 채로 `build()` 에 닿고,
`AssembledContext.empty()` 가 나온다.

**근거 문서**: `docs/adr/ADR-015-memory-는-층을-나눠-싣는다.md`,
`docs/code-architecture.md` 의 「Memory」 절

## 의도 메모

- 저장할 때 길이를 막는 안을 버렸다.
  긴 사실을 쓰는 것 자체는 막을 이유가 없고,
  항목 여럿이 모여 한도를 넘는 경우는 그 방법으로 풀리지 않는다.
- 한도를 올리는 안도 버렸다. 올려도 그보다 큰 항목이 생기면 같은 일이 난다.
  한도가 있는 한 넘치는 경우를 다뤄야 한다.
- 넘친 항목을 잘라서 싣지 않는다. 잘린 사실은 틀린 사실이 될 수 있다.
  「집 주소는 서울시 강남구」 가 「집 주소는 서울시」 로 잘리면 그 자체로 잘못된 정보다.

## 작업 항목

### 1. 넘치는 항목을 건너뛰고 계속 담는다

`ContextBuilder.append` 를 고친다.
한 항목이 들어가지 않으면 **그 항목만 건너뛰고 다음 항목을 계속 본다.**

```java
private void append(String header, String item) {
    appendTo(full, fullHeaders, header, item);
    String itemWithHeader = next(selected, selectedHeaders, header, item);
    if (selected.length() + itemWithHeader.length() > maxChars) {
        omitted.add(header);      // 무엇이 빠졌는지 기억한다
        return;                   // truncated 로 전체를 멈추지 않는다
    }
    appendTo(selected, selectedHeaders, header, item);
}
```

`truncated` 플래그를 없애고 빠진 항목을 모으는 자리로 바꾼다.

**항상 층을 먼저 담고 색인 층을 나중에 담는 순서는 그대로 둔다.**
다만 큰 본문 하나가 색인 전체를 밀어내지 않게 된다.

### 2. 색인 층에 우선권을 준다

본문 하나가 커서 항상 층이 한도를 거의 채우면 색인이 들어갈 자리가 없어진다.
색인은 한 줄에 12 토큰으로 작고, 그것이 없으면 도구로 읽을 길도 사라진다.

**색인 층에 쓸 자리를 먼저 떼어 둔다.**

```java
long indexBudget = Math.min(색인 전체 길이, maxChars / 4);
long bodyBudget = maxChars - indexBudget;
```

항상 층은 `bodyBudget` 안에서 담고, 색인 층은 남은 자리에 담는다.
색인이 짧으면 떼어 둔 자리가 남으므로 항상 층이 그만큼 더 쓴다.

비율은 `assistant.context.index-budget-ratio` 로 둔다. 기본값은 4 다.

### 3. 무엇이 빠졌는지 남긴다

`AssembledContext` 에 빠진 항목 수를 더한다.

```java
public record AssembledContext(String instructions, long chars, int omittedItems) {
    public static AssembledContext empty();
}
```

`ExecutionRecorder` 가 그 값을 실행에 적는다.

`backend/src/main/resources/db/migration/V13__context_omitted.sql` 신규.
**번호는 확인하고 쓴다.** plan010 이 V10, plan012 가 V11, plan011 이 V12 를 쓴다.

```sql
ALTER TABLE agent_execution ADD COLUMN context_omitted_items INT NULL;
```

### 4. 화면이 그것을 보인다

`/memory` 화면에서 그 항목에 표시를 단다.

| 상황 | 화면 |
| --- | --- |
| 그 항목이 한도를 넘어 실리지 않음 | 「길어서 실리지 않음」 을 그 항목에 한 줄로 |
| 여러 항목이 모여 넘침 | 같은 표시를 넘친 항목들에 |

**표시는 항목 목록에서 본다.** 대화 화면에 끼우지 않는다.
답을 읽는 중에 경고가 끼면 읽는 흐름이 끊긴다.

사용량 화면의 실행 목록에도 `context_omitted_items` 가 0 보다 크면 한 줄로 보인다.

### 5. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` 에 더한다.

- **이 phase 가 고치는 것**: 첫 항목의 본문이 한도를 넘으면
  그 항목만 빠지고 **색인과 나머지 항목이 실린다**.
  `instructions` 가 `null` 이 아니고 색인 제목이 들어 있다
- 항상 층이 한도를 거의 채워도 색인 층이 실린다
- 빠진 항목 수가 `omittedItems` 에 담긴다
- 모든 항목이 한도를 넘으면 그때는 비어도 된다. 그 경우 `omittedItems` 가 항목 수와 같다
- 한도 안에 다 들어가면 `omittedItems` 가 0 이다

`test/e2e/scenarios/memory.ts` 에 더한다.

- 본문이 한도를 넘는 `always_inject` 항목이 있어도
  Hermes 로 나가는 `instructions` 에 다른 항목의 색인이 들어 있다

`test/browser/memory.spec.ts` 에 더한다.

- 실리지 않은 항목에 표시가 보인다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
node test/e2e/run.ts
cd web && pnpm typecheck
cd web && pnpm test:browser
```

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests '*ContextAssemblerTest*'
```

**배포한 뒤 실제로 한 번 확인한다.**
긴 본문을 가진 항목을 만들고 대화를 보내 `context_chars` 가 0 이 아닌 것을 본다.
확인한 뒤 그 항목을 지운다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/AssembledContext.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/ContextProperties.java` | 수정 |
| `backend/src/main/resources/db/migration/V13__context_omitted.sql` | 신규 (번호는 확인하고 쓴다) |
| `backend/src/main/java/com/bifos/assistant/usage/domain/AgentExecution.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageController.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `web/src/components/memory/memory-item.tsx` | 수정 |
| `web/src/components/usage/` 의 실행 목록 | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` | 수정 |
| `test/e2e/scenarios/memory.ts` | 수정 |
| `test/browser/memory.spec.ts` | 수정 |

## 끝낸 뒤

`tasks/plan009-memory/index.json` 의 이 phase 를 `completed` 로 바꾸고
plan 의 `status` 를 `completed` 로 바꾼다.
