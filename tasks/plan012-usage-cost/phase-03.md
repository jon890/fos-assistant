# Phase 03. 비용을 축별로 나눠 본다

**Execution profile**: standard

## 목표

쌓인 실행 기록을 축별로 묶어 보는 조회와 화면을 만든다.
어느 에이전트가 비용을 쓰는지, 무엇이 달라져서 비용이 움직였는지 답할 수 있게 한다.

**범위 외**:
모델을 자동으로 고르는 기능을 만들지 않는다.
경고나 상한을 만들지 않는다. 먼저 보이기만 한다.

## 컨텍스트

phase-01 이 금액을 둘로 나눴고 phase-02 가 실행 당시 상태를 남겼다.
이 phase 는 그것을 읽는 쪽이다.

답하려는 질문이 다섯이다.

| 질문 | 필요한 축 |
| --- | --- |
| 어느 에이전트가 비용을 가장 많이 쓰는가 | 에이전트 |
| 어느 모델을 더 싼 것으로 바꿀 수 있는가 | 모델, provider |
| 여러 에이전트로 나눈 실행이 값을 하는가 | 뿌리와 자식 |
| 문맥이 늘어난 것이 비용에 얼마나 영향을 주는가 | `context_chars`, `runtime_fingerprint` |
| 누가 얼마나 쓰는가 | 사용자 |

작업 영역 축은 없다. 그 단위는 제거됐다.

지금 `/usage` 는 실행 목록과 이번 달 합계만 보인다.
목록 50줄을 훑어서는 위 질문에 답할 수 없다.

**근거 문서**: `docs/adr/ADR-014-실제-청구액과-환산액을-나눠-적는다.md`,
`docs/adr/ADR-004-구독제에서도-api-가격으로-환산해-보인다.md`,
`docs/data-schema.md` 의 「agent_execution」 절

## 의도 메모

- 축을 자유롭게 조합하는 화면을 만들지 않는다.
  다섯 질문에 답하는 표 몇 개로 시작한다.
  조합이 필요해지는 시점은 그 표를 쓰면서 안다.
- 합계를 데이터베이스에서 낸다. 줄을 다 읽어 와서 더하지 않는다.
  한 달치 실행 수가 화면이 보여 주는 50줄보다 훨씬 많아질 수 있다.
  기존 `sumCostBetween` 이 같은 이유로 그렇게 돼 있다.
- **자식 토큰은 부모 usage 에 포함되지 않는다.** `ADR-016` 의 「자식 토큰 실측」 절이
  부모 16,583 토큰과 자식 6,544 토큰을 따로 측정해 확정했다.
  그러므로 실행 줄을 전부 세는 것이 실제 사용량이고, 두 번 세어지지 않는다.
  뿌리만 세는 값을 따로 내지 않는다.
- 지금은 자식 실행을 만드는 경로가 없다.
  `ChatService` 가 `parentExecutionId` 와 `rootExecutionId` 를 모두 `null` 로 넘긴다.
  그래서 이 phase 의 합계는 당분간 뿌리 실행만 세는 것과 같은 값이 나온다.
- 기존 `sumCostBetween` 과 `MonthlyCost` 를 고치지 않는다. 새 메서드와 새 record 를 더한다.
  같은 파일을 다른 작업이 동시에 고치고 있다.

## 작업 항목

### 1. 축별 합계를 내는 조회

`usage/infra/AgentExecutionRepository.java` 에 더한다.
한 구간을 받아 축 하나로 묶는다.

```java
/** 에이전트별 합계. RUNNING 은 빠진다. */
List<CostByAgent> sumByAgentBetween(Long userId, Instant from, Instant to);

/** 모델과 provider 별 합계. */
List<CostByModel> sumByModelBetween(Long userId, Instant from, Instant to);

/** 날짜별 합계. 하루 단위로 묶는다. */
List<CostByDay> sumByDayBetween(Long userId, Instant from, Instant to);

/** 설정 지문별 합계. 무엇이 달라져서 비용이 움직였는지 본다. */
List<CostByFingerprint> sumByFingerprintBetween(Long userId, Instant from, Instant to);
```

각 record 가 담을 것은 같다.

| 칸 | 뜻 |
| --- | --- |
| 묶은 키 | 에이전트 번호, 모델 이름, 날짜, 지문 |
| `executions` | 그 묶음의 실행 수 |
| `estimatedMicros` | 환산액 합계 |
| `actualMicros` | 실제 청구액 합계 |
| `inputTokens`, `outputTokens` | 토큰 합계 |
| `avgContextChars` | 문맥 글자 수 평균 |
| `firstSeenAt` | 그 묶음에서 가장 이른 `started_at` |
| `lastSeenAt` | 그 묶음에서 가장 늦은 `started_at` |

`firstSeenAt` 과 `lastSeenAt` 은 「무엇이 달라졌나」 표가 지문의 구간을 그릴 때 쓴다.

**`RUNNING` 을 모든 합계에서 뺀다.** plan008 의 phase-02 가 정한 규칙과 같다.

**실행 줄을 전부 센다.** 뿌리만 세는 값을 따로 내지 않는다.
자식 토큰이 부모 usage 에 포함되지 않는 것을 `ADR-016` 이 실측으로 확정했으므로
전부 세는 것이 실제 사용량이고 두 번 세어지지 않는다.

### 2. 분석 API

| 메서드 | 경로 | 하는 일 |
| --- | --- | --- |
| `GET` | `/api/v1/usage/breakdown` | 축별 합계. `axis` 와 `month` 를 받는다 |

`axis` 는 `agent`, `model`, `day`, `fingerprint` 넷 중 하나다.
모르는 값이면 400 으로 거절한다. 조용히 기본값으로 떨어지지 않는다.

`month` 는 `2026-09` 형태다. 없으면 이번 달이다.
달 경계는 `Asia/Seoul` 로 끊는다. `UsageController` 가 이미 그 상수를 갖고 있다.

**자기 것만 낸다.** 구성원을 가로질러 보는 것은 관리자 화면이 맡는다.
지금 `myExecutions` 가 같은 규칙이다.

### 3. 화면

`/usage` 에 절을 더한다. 새 경로를 만들지 않는다.

```
이번 달
  실제로 나간 돈    $0.00
  API 로 돌렸다면   $14.15

  실행 12건 중 12건이 구독 경로다.

어디에 썼나                        [에이전트 ▾]
  career       $14.09   실행 3건   문맥 평균 7,313자
  bifos        $0.06    실행 9건   문맥 평균 570자

무엇이 달라졌나
  지문 a3f2...  $0.05/실행   실행 9건   9월 17일부터
  지문 8c11...  $0.06/실행   실행 3건   9월 1일부터 9월 17일
```

축은 고르는 단추로 바꾼다. 네 축이 같은 표 모양을 쓴다.

「무엇이 달라졌나」 절은 지문 축을 고정으로 보인다.
지문이 바뀐 구간마다 실행당 평균 비용을 내므로,
설정을 바꾼 것이 비용에 어떤 영향을 줬는지 그 표 하나로 읽힌다.

지문이 하나뿐이면 이 절을 그리지 않는다. 견줄 것이 없다.
**지문이 하나도 없어도 그리지 않는다.**
phase-02 가 `runtime_fingerprint` 를 항상 비워 두는 쪽으로 끝났으면 이 경우가 기본값이 된다.
그때도 `fingerprint` 축 자체는 만든다. 값이 들어오기 시작하면 고칠 곳이 없어야 한다.
지문이 `null` 인 실행은 그 축에서 한 묶음으로 모으지 않고 통째로 뺀다.

### 4. 실행 목록에 문맥 크기를 더한다

지금 목록이 토큰과 금액을 보인다.
`context_chars` 를 한 열 더한다.

같은 질문인데 문맥이 커진 실행을 눈으로 찾을 수 있게 한다.

### 5. 빈 상태

| 상황 | 화면 |
| --- | --- |
| 그 달에 실행이 없다 | 「기록이 없다」 한 줄 |
| 지문이 하나뿐 | 「무엇이 달라졌나」 절을 그리지 않는다 |
| 실제 청구액이 전부 비어 있다 | 그 열에 「구독」 을 적고 금액을 쓰지 않는다 |

### 6. 브라우저 테스트가 쓸 준비 경로

지금 실행을 심는 수단은 `backend/src/test/java/com/bifos/assistant/testsupport/UsageTestSupportController.java`
의 `last-execution/orphaned` 하나뿐이다. 그것으로는 축이 둘로 갈리는 화면을 만들 수 없다.

같은 컨트롤러에 실행 여러 건을 심는 경로를 더한다.
심을 값에 **에이전트, 지문, 시작 시각, 토큰, 두 금액**이 들어간다.

**이 컨트롤러는 test profile 에서만 뜬다.** 운영 코드에 시험용 문을 만들지 않는다.
기존 파일이 이미 그 조건을 갖고 있으면 그대로 따른다.

### 7. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/usage/UsageBreakdownTest.java` 를 새로 만든다.

- **정상 경로**: 에이전트 둘의 실행이 섞여 있으면 `agent` 축이 둘로 묶이고
  각 합계가 그 에이전트 실행의 합과 같다
- `RUNNING` 인 실행이 어느 축에도 세어지지 않는다
- **이 phase 가 다루는 실패**: 모르는 `axis` 를 주면 400 이다.
  기본값으로 떨어지지 않는다
- 남의 실행이 내 합계에 들어가지 않는다
- 달 경계가 `Asia/Seoul` 로 끊긴다.
  9월 30일 23시와 10월 1일 1시의 실행이 다른 달로 묶인다
- `fingerprint` 축이 지문별로 실행당 평균을 낸다

`test/browser/usage-breakdown.spec.ts` 를 새로 만든다.

- 축을 바꾸면 표가 바뀐다
- 지문이 하나면 「무엇이 달라졌나」 절이 없다
- `mobile` 과 `desktop` 두 폭에서 표가 가로로 넘치지 않는다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
cd web && pnpm typecheck
cd web && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
scripts/check-public-safe.sh
```

`AGENTS.md` 의 「확인」 절이 정한 순서다.

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests '*UsageBreakdownTest*'
```

```bash
# cwd: 저장소 root
! grep -rn 'style={{' web/src/components/usage/ | grep -iE 'background|color|border'
```

색 인라인 스타일이 하나라도 있으면 종료 코드가 1 이다.

합계를 데이터베이스에서 내는지 확인한다.
실행 100건을 넣고 축별 조회가 질의 몇 번을 내는지 세어 보고에 적는다.

브라우저에서 390px 과 1280px 을 열어 본 결과도 적는다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/CostByAgent.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/CostByModel.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/CostByDay.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/CostByFingerprint.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageController.java` | 수정 |
| `web/src/app/usage/page.tsx` | 수정 |
| `web/src/components/usage/breakdown-section.tsx` | 신규 |
| `web/src/components/usage/breakdown-table.tsx` | 신규 |
| `web/src/components/usage/fingerprint-section.tsx` | 신규 |
| `web/src/components/usage/execution-table.tsx` | 수정 |
| `web/src/components/usage/execution-card.tsx` | 수정 |
| `web/src/app/api/usage/breakdown/route.ts` | 신규 |
| `backend/src/test/java/com/bifos/assistant/testsupport/UsageTestSupportController.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/UsageBreakdownTest.java` | 신규 |
| `test/browser/usage-breakdown.spec.ts` | 신규 |

## 끝낸 뒤

`tasks/plan012-usage-cost/index.json` 의 이 phase 를 `completed` 로 바꾸고
plan 의 `status` 를 `completed` 로 바꾼다.
