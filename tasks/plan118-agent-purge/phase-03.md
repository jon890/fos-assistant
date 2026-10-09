# Phase 03. 행이 없는 에이전트를 사용량 합계, 기억 출처, 스킬 사용 목록이 「지운 에이전트」 로 보인다

**Execution profile**: standard

## 목표

지운 에이전트는 7일 뒤 행이 사라진다. 그 뒤에도 사용량 합계, 기억의 출처, 내 스킬 사용 목록이 그 에이전트를 「지운 에이전트」 로 보이게 한다.
지금은 사용량 합계가 에이전트 번호를 이름 자리에 쓰고, 기억 출처는 「에이전트가 남김」 으로 바뀌고, 스킬 사용 목록은 그 줄을 뺀다.

**범위 외**: 정리 작업 자체는 phase 01, 02 다. 대화 화면, 실행 기록, 실행 트리는 이미 행이 없어도 「지운 에이전트」 로 그린다.

## 컨텍스트

**근거 문서**: `backend/docs/flow.md` 의 「에이전트 만들기와 지우기」 에서 「내가 부른 스킬 합계」 부터 「기억의 출처」 까지의 세 줄, `backend/docs/adr/ADR-20261009-agent-purge.md` 의 「결과」.

- 화면이 이름 없는 에이전트를 그리는 함수: `web/src/lib/format.ts` 의 `agentLabel(name: string | null)` 가 null 을 「지운 에이전트」 로 바꾼다. 실행 기록(`web/src/components/usage/execution-table.tsx`)이 이미 쓴다.
- 사용량 합계: `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageDtos.java` 의 `BreakdownRow.ofAgent`. `cost.agentId()` 가 null 이면 「시스템 판단」, `agentName()` 이 null 이면 지금은 `code`(에이전트 번호 문자열)를 `label` 로 쓴다.
- 기억 출처: `backend/src/main/java/com/bifos/assistant/memory/application/MemorySources.java`. `executions.agentIdsOf(...)` 가 실행 번호별 에이전트 번호를 주고 `agents.byIds(...)` 가 행을 준다. 지금은 행이 없으면 `MemorySource.UNNAMED` 다. 지운 에이전트는 `new MemorySource(null, true)` 다.
- 스킬 사용 목록: `backend/src/main/java/com/bifos/assistant/skill/application/SkillUsageQuery.java` 의 `byUser`. 에이전트 행이 없으면 `continue` 로 뺀다. 결과 record 는 `skill/application/UserSkillUsage`(`agentCode`, `agentName`, ...)이고, `usage/presentation/UsageDtos.MySkillUsageView.from` 이 화면으로 옮긴다. web 의 타입은 `web/src/lib/skill.ts` 의 `SkillUsageRow`(`agentName: string`)이고 `web/src/components/usage/skill-usage-list.tsx` 가 `{row.agentName}` 을 그린다.

## 의도 메모

- 이름을 서버가 정하는 곳(사용량 합계의 `label`, 「시스템 판단」 과 같은 자리)은 서버가 「지운 에이전트」 를 낸다. 이름을 비워 보내는 곳(스킬 사용 목록)은 web 의 `agentLabel` 이 그린다. 지금 각 화면이 쓰는 방식을 따른다.
- 사용량 합계의 `code`(줄의 열쇠)는 번호 문자열 그대로 둔다. 행이 없는 에이전트가 여럿이면 줄이 나뉘어야 한다.
- 기억 출처에서 실행에 에이전트 번호가 없는 것(에이전트 없이 돈 실행)은 지금처럼 `UNNAMED` 다. 번호는 있는데 행이 없는 것만 지운 에이전트로 본다.

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageDtos.java` 수정

`BreakdownRow.ofAgent` 의 `label` 을 `cost.agentId() == null ? "시스템 판단" : cost.agentName() == null ? "지운 에이전트" : cost.agentName()` 로 바꾼다. `code` 는 그대로 둔다.

### 2. `backend/src/main/java/com/bifos/assistant/memory/application/MemorySources.java` 수정

`of` 에서 `Long agentId = agentByExecution.get(memory.proposedByExecutionId())` 를 먼저 읽는다. `agentId` 가 있는데 `agentById` 에 행이 없으면 `new MemorySource(null, true)`, `agentId` 가 null 이면 `MemorySource.UNNAMED` 다. 행이 있으면 지금의 `describe` 그대로다.

### 3. `backend/src/main/java/com/bifos/assistant/skill/application/SkillUsageQuery.java` 와 `UserSkillUsage.java` 수정

`byUser` 에서 `agentId` 는 있는데 행이 없는 묶음을 빼지 않고 `agentCode` 와 `agentName` 을 null 로 낸다. `agentId` 가 null 인 묶음(에이전트 없이 돈 실행)은 지금처럼 뺀다. 정렬의 `thenComparing(UserSkillUsage::agentCode)` 를 `Comparator.nullsLast(Comparator.naturalOrder())` 로 바꾼다. 메서드 Javadoc 에 「행이 없는 에이전트는 이름 없이 낸다」 를 더한다.
`UserSkillUsage` 의 `@param agentCode`, `@param agentName` 에 「에이전트 행이 없으면 null」 을 더한다.

### 4. web 수정

- `web/src/lib/skill.ts` 의 `SkillUsageRow.agentName` 을 `string | null` 로 바꾸고 주석에 「행이 없는 에이전트는 null」 을 단다.
- `web/src/components/task/task-list.tsx` 89줄의 `{task.agentName ?? "에이전트 없음"}` 을 `{agentLabel(task.agentName)}` 로 바꾼다. 예약 작업의 에이전트 행이 정리로 사라져도 다른 화면처럼 「지운 에이전트」 로 보인다.
- `web/src/components/usage/skill-usage-list.tsx` 가 `agentLabel(row.agentName)` 을 그리고(`@/lib/format` 에서 import), `li` 의 `key` 도 `agentLabel(row.agentName)` 으로 만든다.

### 5. 이 phase 를 검증하는 시험

- `backend/src/test/java/com/bifos/assistant/usage/UsageBreakdownTest.java`: 「에이전트 행이 없는 실행도 agent 축에 번호로 묶이고 다른 줄은 그대로다」 검사의 `label` 단언을 `"지운 에이전트"` 로 바꾸고, `code` 가 번호 문자열인지는 그대로 본다. `@DisplayName` 을 「에이전트 행이 없는 실행도 agent 축에 번호로 묶이고 이름은 지운 에이전트다」 로 바꾼다.
- `backend/src/test/java/com/bifos/assistant/memory/MemorySourcesTest.java`: `describesSourceByVisibility` 에 실행의 에이전트 번호는 있는데 `byIds` 에 없는 기억 하나를 더해 `new MemorySource(null, true)` 인지 본다. 실행에 에이전트 번호가 없는 기억(`unknownExecution`)은 그대로 `UNNAMED` 다.
- `backend/src/test/java/com/bifos/assistant/skill/SkillUsageQueryTest.java`: 검사 하나를 더한다. 「에이전트 행이 없는 호출도 이름 없이 합계에 남는다」. 그 사용자의 실행 하나에 스킬 사용을 남긴 뒤 그 실행의 `agent_id` 를 `JdbcTemplate` 으로 없는 번호(예: `Long.MAX_VALUE - 1`)로 바꾸고, `byUser` 결과에 `agentCode` 와 `agentName` 이 null 인 줄이 그 스킬 이름과 횟수로 있는지 본다. 같은 검사에서 다른 실행의 `agent_id` 를 NULL 로 바꾼 스킬 사용은 결과에 없는지도 본다.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.usage.UsageBreakdownTest' --tests 'com.bifos.assistant.memory.MemorySourcesTest' --tests 'com.bifos.assistant.skill.SkillUsageQueryTest'
cd backend && ./gradlew spotlessCheck checkstyleMain checkstyleTest
cd web && pnpm exec tsc --noEmit && pnpm lint
.omc/scripts/heavy-lock scripts/check-local.sh usage task
```

- 마지막 줄은 사용량 화면 브라우저 검사(`test/browser/usage.spec.ts`)다. 스킬 탭이 기존 이름으로 그대로 보인다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemorySources.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillUsageQuery.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/skill/application/UserSkillUsage.java` | 수정 |
| `web/src/lib/skill.ts` | 수정 |
| `web/src/components/usage/skill-usage-list.tsx` | 수정 |
| `web/src/components/task/task-list.tsx` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/UsageBreakdownTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemorySourcesTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillUsageQueryTest.java` | 수정 |
