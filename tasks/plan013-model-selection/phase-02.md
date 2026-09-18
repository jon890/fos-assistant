# Phase 02. 막히면 그 턴에서 다음 모델로 넘긴다

**Execution profile**: deep

## 목표

1순위 provider 가 막히면 **그 턴 안에서** 다음 순위로 다시 보낸다.
막힌 provider 를 기억해 정해 둔 시간 동안 건너뛴다.
답이 끊기지 않는다.

**범위 외**:
화면에 그 사실을 보이는 것은 phase-03 이 한다.
한 provider 안에서 계정을 돌려 쓰는 것은 만들지 않는다. Hermes 가 이미 한다.

## 컨텍스트

phase-01 이 에이전트마다 순서 있는 모델 목록을 만들었다.
이 phase 는 그 목록을 실제로 쓴다.

### 무엇을 보고 막혔다고 판정하나

홈서버에서 실측한 것이다. **HTTP 상태로는 알 수 없다.**

| 무엇 | 값 |
| --- | --- |
| `POST /v1/runs` | 접수는 늘 202 |
| `GET /v1/runs/{run_id}` | 늘 200 |
| 막혔을 때 | 본문의 `status` 가 `failed`, `error` 가 `⚠️ Provider authentication failed:` 로 시작 |

그 접두사는 Hermes 가 붙이는 고정 문자열이고 `_ProviderAuthResolutionError` 하나만
그 경로를 탄다. 그래서 판정 근거로 쓸 수 있다.

**다른 실패 문자열은 상류가 보낸 것이라 문구가 바뀔 수 있다.**
`HTTP 404: 404 page not found` 와 `HTTP 402: This request requires more credits`
가 측정에서 나왔는데, 이 둘로는 판정하지 않는다.

### 넘김이 일어나는 시점

**한 provider 의 계정이 전부 막혔을 때만 우리에게 온다.**
계정 하나가 막힌 것은 Hermes 가 같은 provider 의 다음 계정으로 옮겨 처리한다.
phase-01 의 「Hermes 가 이미 하는 것을 다시 만들지 않는다」 절이 그 경계를 갖는다.

### 왜 턴 단위인가

모델이 바뀌어도 맥락은 남는다. 실측으로 확인했다.
같은 `session_id` 로 모델만 바꿔 이어도 앞 turn 의 답을 기억했고,
도구 호출이 섞인 turn 도 그대로 복원됐다.

그래서 한 대화 안에서 모델이 바뀌어도 된다.
실행마다 어느 모델로 돌았는지 기록하므로 비용도 실행 단위로는 정확하다.

**근거 문서**: `fos-home-infra` 의 `docs/hermes-model-selection.md` 의
「소진됐을 때 오는 것」 과 「모델을 바꿔 이어갈 때의 맥락」 절.

## 의도 메모

- 실패한 실행을 지우고 다시 만드는 안을 버렸다.
  실행 기록은 실패해도 남긴다는 것이 이 저장소의 규칙이다.
  막혀서 실패한 실행도 그대로 남기고, 다음 시도를 **새 실행**으로 만든다.
  그래야 무엇이 얼마나 막혔는지 나중에 볼 수 있다.
- 무한히 넘기는 안을 버렸다. 목록의 끝에 닿으면 실패로 끝낸다.
- 모든 실패에 넘기는 안을 버렸다. 모델 이름이 틀렸거나 입력이 잘못된 것은
  다음 provider 에서도 똑같이 실패한다. 넘기면 같은 실패를 목록 수만큼 되풀이한다.

## 작업 항목

### 1. 막힌 provider 를 기억한다

`backend/src/main/resources/db/migration/V15__provider_state.sql` 신규.
**번호는 확인하고 쓴다.** phase-01 이 V14 를 쓴다.

```sql
CREATE TABLE provider_state (
    provider VARCHAR(64) NOT NULL PRIMARY KEY,
    blocked_until DATETIME(6) NOT NULL,
    blocked_reason VARCHAR(255) NULL,
    updated_at DATETIME(6) NOT NULL
);
```

**이 표는 provider 단위다. 에이전트마다 두지 않는다.**
credential 은 provider 마다 하나의 묶음이고 그 묶음이 막힌 것이다.
에이전트마다 두면 같은 provider 가 막힌 것을 에이전트 수만큼 따로 배운다.

`blocked_until` 이 지금보다 뒤면 그 provider 를 건너뛴다.

식는 시간은 `assistant.model.provider-cooldown` 으로 둔다. 기본값은 `PT30M` 이다.

**Hermes 가 남은 시간을 알려주지 않는다.** `hermes auth list` 는 알지만 CLI 뿐이고
HTTP 경로가 없다. 그래서 우리가 정한 시간으로 식힌다.
너무 짧으면 매번 실패 왕복이 한 번 더 들고, 너무 길면 풀린 뒤에도 안 쓴다.

### 2. 순서대로 시도한다

`AgentModelSelector` 가 에이전트의 목록에서 **막히지 않은 것만** 순서대로 준다.

```java
/** 이 에이전트가 지금 쓸 수 있는 모델을 순위대로 준다. 없으면 빈 목록이다. */
List<ModelOption> availableFor(Agent agent);
```

`ChatService` 가 그 목록을 차례로 시도한다.

1. 목록의 다음 항목으로 실행을 하나 만들고 Hermes 에 보낸다
2. `status` 가 `failed` 이고 `error` 가 `⚠️ Provider authentication failed:` 로 시작하면
   - 그 provider 를 `blocked_until = now + cooldown` 으로 적는다
   - 그 실행은 `FAILED` 로 남긴다
   - 목록의 다음 항목으로 1번부터 다시 한다
3. 그 밖의 실패는 넘기지 않고 그대로 실패시킨다
4. 목록이 끝나면 실패로 끝낸다

**목록이 비면 Hermes 를 부르지 않고 실패한다.** 전부 막힌 경우다.

`error_code` 를 나눠 적는다.

| 상황 | `error_code` |
| --- | --- |
| 그 provider 가 막혀 다음으로 넘어감 | `PROVIDER_BLOCKED` |
| 전부 막혀 더 쓸 것이 없음 | `NO_MODEL_AVAILABLE` |

### 3. 넘어간 것을 한 나무로 묶는다

넘기며 만든 실행들은 **같은 대화의 turn 하나**에 속한다.
`parent_execution_id` 로 잇지 않는다. 자식이 아니라 다시 시도한 것이다.

`agent_execution` 에 칸을 하나 더한다.

```sql
ALTER TABLE agent_execution ADD COLUMN retry_of_execution_id BIGINT NULL;
```

첫 시도는 비어 있고, 넘어가서 만든 실행은 **직전 실행의 번호**를 담는다.

**같은 마이그레이션에 넣는다.** 표를 만드는 것과 칸을 더하는 것이 한 변경이다.

### 4. 성공했을 때 막힘을 푼다

어떤 provider 로 실행이 성공하면 그 provider 의 `blocked_until` 을 지운다.
식는 시간이 남아 있어도 실제로 됐으면 막히지 않은 것이다.

### 5. 스트리밍 경로도 같다

`ChatService.stream` 도 같은 순서를 쓴다.

**넘어가는 동안 화면에 답 조각을 흘리지 않는다.**
실패한 시도의 조각이 화면에 남으면 읽는 사람이 그것을 답으로 읽는다.
넘어간 뒤 성공한 실행의 조각만 흘린다.

넘어갔다는 것은 사건으로 남긴다. phase-03 이 그것을 화면에 그린다.

`ExecutionEventType` 에 값을 하나 더한다.

```java
PROVIDER_SWITCHED
```

`detail` 에 넘어간 곳의 provider 와 모델을 적는다.
**막힌 쪽의 오류 문자열을 적지 않는다.** 상류가 보낸 문장이라 무엇이 들어올지 모른다.

### 6. 이 phase 를 검증하는 테스트

- **이 phase 가 고치는 것**: 1순위가 `⚠️ Provider authentication failed:` 로 실패하면
  2순위로 다시 보내고 **답이 온다**. 대화가 실패로 끝나지 않는다
- 그 provider 가 `provider_state` 에 막힌 것으로 적힌다
- 막힌 동안 다음 턴은 1순위를 건너뛴다. Hermes 를 그 provider 로 부르지 않는다
- 식는 시간이 지나면 다시 1순위부터 시도한다
- 다른 실패 문자열은 넘기지 않는다. 한 번 실패하고 끝난다
- 목록이 전부 막히면 `NO_MODEL_AVAILABLE` 로 실패하고 Hermes 를 부르지 않는다
- 넘어가서 만든 실행이 `retry_of_execution_id` 로 직전 실행을 가리킨다
- 성공하면 그 provider 의 막힘이 풀린다
- 스트리밍에서 실패한 시도의 답 조각이 화면으로 나가지 않는다

`test/e2e/scenarios/` 에 더한다.

- 가짜 Hermes 가 첫 provider 에 그 오류를 주면 두 번째로 넘어가 답이 온다
- 그 대화의 사용량에 실행이 둘 남고 하나는 실패, 하나는 성공이다

**가짜 Hermes 가 provider 마다 다르게 답할 수 있어야 한다.**
지금은 그럴 수 없다. 그 자리를 먼저 만든다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
scripts/check-public-safe.sh
```

**배포 뒤 실제 확인은 하지 않아도 된다.**
막힌 provider 를 일부러 만들 수 없다. 테스트로 고정하는 것으로 갈음한다.
실제로 막히는 날이 오면 그때 사용량 화면에서 확인한다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V15__provider_state.sql` | 신규 (번호는 확인하고 쓴다) |
| `backend/src/main/java/com/bifos/assistant/agent/domain/ProviderState.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/infra/ProviderStateRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentModelSelector.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/AgentExecution.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/ExecutionEventType.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |

## 끝낸 뒤

`tasks/plan013-model-selection/index.json` 의 이 phase 를 `completed` 로 바꾼다.
