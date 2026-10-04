# Phase 01. 첫 반응 시간 집계

**Execution profile**: standard

## 목표

사용자 turn 의 첫 반응 시간, 제출까지, 첫 조각까지를 날짜와 모델 단계별 중앙값과 90번째 백분위로 집계해 관리자 사용량 화면에 보인다.
문맥 묶음(phase 02 부터)이 제출까지의 구간을 늘리는지 견줄 기준값을 먼저 남긴다.

**범위 외**: 브라우저가 그리는 시간, 지금 화면의 지표(plan79), 문맥 묶음 자체(phase 02).

## 컨텍스트

- 실행 줄에 네 시각이 이미 남는다. `backend/src/main/java/com/bifos/assistant/usage/domain/AgentExecution.java` 의 `requestReceivedAt()`, `submittedAt()`, `firstDeltaAt()`, `finishedAt()` 이고, 단계는 `modelTier()`(`model.domain.type.ModelTier` 의 `FAST`, `BALANCED`, `DEEP`, 비어 있을 수 있다)다
- 자동 turn(위임 결과를 전하는 turn)은 요청 대신 내부 trigger 시각을 `request_received_at` 에 적는다. 그래서 집계에서 뺀다
- 실행 줄에는 자동 turn 인지 적는 칸이 없다. 그 turn 의 답 메시지(`chat_message`, `role=ASSISTANT`, `execution_id`=그 실행)보다 앞선 메시지 가운데 `ASSISTANT` 가 아닌 가장 최근 줄의 `role` 로 가린다. `USER` 면 사용자 turn 이고 `SYSTEM` 이면 자동 turn 이다. 다시 생성은 앞선 질문이 `USER` 라 사용자 turn 으로 든다
- 이 판정은 `chat_message` 와 `agent_execution` 을 함께 읽는다. `usage` 는 `chat` 을 import 하지 못하므로(ADR-068) 집계는 `chat` 패키지가 맡는다. `chat` 은 `usage` 위에 있어 `AgentExecution` 을 읽을 수 있다
- 관리자 사용량 화면은 `web/src/app/admin/usage/page.tsx` → `web/src/components/usage/usage-screen.tsx` 의 `UsageScreen`(서버 컴포넌트, `admin={true}`)이다. `callControlPlane` 으로 Control Plane 을 바로 부른다. `summary` 탭에 `MonthlySummary` 와 `BreakdownSection` 이 있다
- 관리자 전용 경로의 본보기는 `backend/src/main/java/com/bifos/assistant/chat/presentation/ModelAdminController.java` 다. `@RequestMapping("/api/v1/admin")` 과 `currentUser.requireAdmin()` 을 쓴다. `MEMBER` 는 `ErrorCode.FORBIDDEN`(403)을 받는다
- 날짜는 `Asia/Seoul` 로 끊는다. `UsageController.HOUSEHOLD_ZONE` 과 같은 값이다
- 집계 범위는 요청한 관리자 자신의 실행이다. `UsageController.breakdown` 이 자기 것만 내는 것과 맞춘다

**근거 문서**: `docs/model-tiers.md` 의 「표시와 검증」 과 「첫 반응 시간」, `docs/adr/ADR-063-관리자-전용-표시와-동작은-관리자-영역에만-두고-일반-경로의-응답은-서버가-역할에-따라-줄인다.md`

## 의도 메모

- 백분위를 데이터베이스에서 구하지 않는다. MySQL 8.4 와 H2 가 함께 받는 백분위 함수가 없다. 30일치 한 사람의 turn 은 수천 건 이하라 Java 에서 정렬해 구한다
- 백분위는 nearest-rank 방식이다. 값 n 개를 오름차순으로 두고 p 백분위는 `ceil(p/100 × n)` 번째 값이다
- 시각이 비어 있는 실행은 그 지표에서만 뺀다. 0 으로 채우지 않는다. 한 번에 받는 경로(`POST /api/v1/chat/messages`)는 `first_delta_at` 이 비어 첫 반응 시간과 첫 조각까지에 들지 않고 제출까지에만 든다
- 답 메시지가 없는 실행(제출 전 실패 같은 것)은 뺀다. 사용자 turn 인지 가릴 수 없다

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/chat/infra/ChatMessageRepository.java` 에 조회 하나

기간 안의 사용자 turn 루트 실행의 네 값만 읽는 JPQL 을 더한다. 반환은 새 record `chat.application.model.TurnTiming(Instant requestReceivedAt, Instant submittedAt, Instant firstDeltaAt, ModelTier modelTier)` 의 생성자 식이다.

조건:
- `AgentExecution e` 와 `ChatMessage a` 를 `a.executionId = e.id` 로 잇고 `a.role = ASSISTANT`
- `e.userId = :userId`, `e.conversationId is not null`, `e.parentExecutionId is null`, `e.requestReceivedAt >= :from`, `e.requestReceivedAt < :to`
- `(select q.role from ChatMessage q where q.id = (select max(p.id) from ChatMessage p where p.conversationId = a.conversationId and p.id < a.id and p.role <> ASSISTANT)) = USER`

`RepositoryQueryMysqlTest` 가 이 메서드를 스스로 찾아 실제 MySQL 에서 돌린다. 인자 타입은 `Long`, `Instant` 라 `RepositoryQuerySweep` 에 더할 것이 없다.

### 2. `backend/src/main/java/com/bifos/assistant/chat/application/FirstResponseLatencyService.java` (신규)

`summarize(Long userId, int days)` 가 `LatencySummary` 를 낸다.
- `days` 는 1 이상 90 이하. 밖이면 `ApiException(ErrorCode.VALIDATION_FAILED, ...)`
- 기간은 주입받은 `Clock` 의 지금에서 `days` 일 전부터 지금까지
- 줄마다 날짜(`Asia/Seoul` 의 `LocalDate`)와 `modelTier`(비면 `null`)로 묶는다
- 묶음마다 `count`(답 메시지가 있는 사용자 turn 수), 세 지표 각각의 `count`, `p50Ms`, `p90Ms` 를 낸다. 값이 없는 지표는 셋 다 `null` 이 아니라 `count` 0 과 `null` 백분위다
- 날짜 오름차순, 같은 날짜 안에서는 `FAST`, `BALANCED`, `DEEP`, 단계 없음 순서

반환 타입 `chat.application.model.LatencySummary` 와 `chat.application.model.LatencyRow`, `chat.application.model.LatencyStat(long count, Long p50Ms, Long p90Ms)` 를 각각 파일 하나로 둔다.

### 3. `backend/src/main/java/com/bifos/assistant/chat/presentation/LatencyAdminController.java` (신규)

`@RequestMapping("/api/v1/admin/usage")` 의 `@GetMapping("/latency")`, 인자 `@RequestParam(defaultValue = "30") int days`.
`currentUser.requireAdmin()` 으로 요청자를 받고 `FirstResponseLatencyService.summarize(admin.id(), days)` 를 응답으로 옮긴다.
응답 record 는 `ChatDtos.java` 에 둔다. 모양:

```json
{ "days": 30, "rows": [ { "date": "2026-10-03", "modelTier": "FAST", "turns": 4,
  "firstResponse": { "count": 3, "p50Ms": 1800, "p90Ms": 4200 },
  "toSubmit": { "count": 4, "p50Ms": 120, "p90Ms": 300 },
  "toFirstDelta": { "count": 3, "p50Ms": 1650, "p90Ms": 3900 } } ] }
```

### 4. `backend/src/test/java/com/bifos/assistant/chat/FirstResponseLatencyTest.java` (신규)

`@SpringBootTest`, `@ActiveProfiles("test")`. `UsageBreakdownTest` 처럼 `CurrentUserProvider` 를 대역으로 두고 `doCallRealMethod().when(currentUser).requireAdmin()` 으로 실제 판정을 탄다. 실행 줄과 메시지를 저장소로 심고 고정 `Clock` 을 준다.

| 입력 | 기대 |
| --- | --- |
| `USER` 질문 뒤 `ASSISTANT` 답, 시각 셋이 다 있는 `FAST` 실행 셋(첫 반응 1000, 2000, 9000ms) | 그 날짜 `FAST` 줄의 `firstResponse` 가 `count` 3, `p50Ms` 2000, `p90Ms` 9000 |
| 같은 대화에서 `SYSTEM` 알림 뒤 `ASSISTANT` 답(자동 turn) | 세지 않는다 |
| `USER` 질문, 첫 답, 다시 생성한 두 번째 `ASSISTANT` 답(`replaces_message_id`) | 두 번째 답의 실행도 사용자 turn 으로 센다 |
| `first_delta_at` 이 빈 사용자 turn | `turns` 와 `toSubmit` 에는 들고 `firstResponse` 와 `toFirstDelta` 에는 들지 않는다 |
| `parent_execution_id` 가 있는 자식 실행 | 세지 않는다 |
| 다른 사용자의 실행 | 세지 않는다 |
| 한국 시각 자정을 넘는 실행(세계 표준시 15:30) | 다음 날짜 줄로 묶인다 |
| `MEMBER` 역할의 요청 | `FORBIDDEN` |
| `days=0`, `days=91` | `VALIDATION_FAILED` |

### 5. `web/src/components/usage/latency-section.tsx` (신규)

서버 컴포넌트에서 받은 응답을 표로 그린다. 제목 「첫 반응 시간」, 설명 한 줄 「최근 30일 동안 보낸 질문에 첫 글자가 나오기까지 걸린 시간이에요. 화면을 그리는 시간은 들지 않아요.」.
넓은 화면은 `web/src/components/ui/table.tsx` 의 표로 날짜, 단계, 질문 수, 첫 반응(중앙값과 90번째), 제출까지, 첫 조각까지를 그린다. 좁은 화면(`md` 미만)은 줄마다 카드로 그린다. `breakdown-table.tsx` 의 넓은 화면과 좁은 화면 분기를 따른다.
줄이 없으면 `EmptyState` 로 「아직 잴 질문이 없어요」. 밀리초는 `1.8초` 처럼 초로 그린다. 바깥 요소에 `data-testid="latency-section"` 을 둔다.

### 6. `web/src/components/usage/usage-screen.tsx`

`isAdmin` 일 때만 `Promise.all` 에 `callControlPlane<LatencySummary>("/api/v1/admin/usage/latency?days=30")` 를 더한다. `summary` 탭에서 `BreakdownSection` 아래에 `LatencySection` 을 그린다. 실패하면 그 자리에 응답의 `message` 한 줄만 그린다. 일반 사용량 화면(`/usage`)은 이 조회를 하지 않는다.

### 7. `test/browser/usage-latency.spec.ts` (신규)

`test/browser/chat.spec.ts` 가 입력창으로 보내는 방식을 따라 화면에서 질문 하나를 보낸다(스트리밍 경로라 `first_delta_at` 이 남는다). 답이 끝난 뒤 `/admin/usage` 를 열어 `latency-section` 에 오늘 날짜 줄이 있고 질문 수가 1 이상인지 본다.
`MEMBER` 역할 세션으로 `/usage` 를 열면 `latency-section` 이 없는지 본다. 역할을 바꾸는 방법은 `test/browser/admin-area.spec.ts` 의 `MEMBER` 검사를 따른다.

### 8. `docs/model-tiers.md` 와 대조

「첫 반응 시간」 절의 세는 실행 기준(답 메시지보다 앞선 `ASSISTANT` 가 아닌 가장 최근 메시지의 `role` 이 `USER`)과 집계 범위(요청한 관리자 자신의 실행)가 작업 항목 1, 2 와 같은지 확인한다.
절 머리의 「**집계는 아직 구현 전이다.** …」 줄을 지운다.

## 검증

```bash
# cwd: backend/
./gradlew test --tests 'com.bifos.assistant.chat.FirstResponseLatencyTest' --tests 'com.bifos.assistant.architecture.*'
./gradlew test
./gradlew checkstyleMain checkstyleTest
```

```bash
# cwd: 저장소 root
scripts/check-mysql-migration.sh
```

```bash
# cwd: web/
pnpm typecheck
pnpm test:browser usage-latency
```

기대값: 모두 종료 코드 0. `RepositoryQueryMysqlTest` 가 새 조회를 실제 MySQL 에서 실행한다.
`grep -rn 'style={{' web/src/components/usage/latency-section.tsx` 의 출력이 비어 있다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/infra/ChatMessageRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/model/TurnTiming.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/model/LatencySummary.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/model/LatencyRow.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/model/LatencyStat.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/FirstResponseLatencyService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/LatencyAdminController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/FirstResponseLatencyTest.java` | 신규 |
| `web/src/components/usage/latency-section.tsx` | 신규 |
| `web/src/components/usage/usage-screen.tsx` | 수정 |
| `test/browser/usage-latency.spec.ts` | 신규 |
| `docs/model-tiers.md` | 수정 |
