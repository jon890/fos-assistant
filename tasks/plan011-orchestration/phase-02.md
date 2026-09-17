# Phase 02. 흐름 하나를 끝까지 돌린다

**Execution profile**: deep

## 목표

요청 하나를 받아 조사와 구현을 나란히 돌리고 그 둘을 합쳐 답하는 흐름을 만든다.
하나만 만든다.

**범위 외**:
범용 workflow 엔진을 만들지 않는다.
Task 를 자유롭게 정의하는 기능, 일반 의존 그래프, 재시도 정책을 만들지 않는다.
화면은 phase-03 이 만든다.

## 컨텍스트

만들 흐름이다.

```
사용자 요청 → Chief → Researcher 와 Engineer 를 나란히 → Synthesizer → 최종 답
```

phase-01 이 자식 실행 하나를 돌리는 자리를 만들었다.
이 phase 는 그것을 넷으로 잇는다.

**처음부터 범용으로 만들지 않는다.**
이 흐름 하나가 끝까지 도는 것을 먼저 본다.
그 과정에서 무엇이 어려운지 알게 되고, 그것을 알고 나서 일반화한다.
[ADR-016](../../docs/adr/ADR-016-다중-에이전트-조율은-control-plane이-맡는다.md) 이
Task 분해와 에이전트 선택을 「판단」 으로 적었다. 아직 정하지 않은 설계라는 뜻이다.

**근거 문서**: `docs/adr/ADR-016-다중-에이전트-조율은-control-plane이-맡는다.md`,
`docs/adr/ADR-011-실행은-시작할-때-기록하고-끝날-때-갱신한다.md`,
`docs/adr/ADR-014-실제-청구액과-환산액을-나눠-적는다.md`

## 의도 메모

- 에이전트를 새로 만들지 않는다.
  지금 `bifos` 와 `career` 둘뿐이고, 역할이 다른 profile 을 넷 만들면
  그 자체로 홈서버 작업이 커진다.
  같은 에이전트를 다른 지시로 부르는 것으로 시작한다.
- 실패하면 거기서 멈춘다. 재시도하지 않는다.
  재시도를 넣으면 무엇이 몇 번 돌았는지가 비용과 얽히고, 그것을 이 단계에서 풀지 않는다.
- 모델이 에이전트를 고르지 않는다.
  Chief 가 조사할 것과 만들 것을 나누지만, 그것을 어느 에이전트가 할지는 Control Plane 이 정한다.
  ADR-016 이 그렇게 적었다.

## 작업 항목

### 1. 흐름을 정의한다

`orchestration/application/ResearchAndBuildFlow.java` 다.
이름이 흐름의 내용을 가리킨다. `Flow1` 처럼 번호로 두지 않는다.

```java
/**
 * 조사와 구현을 나란히 돌리고 그 둘을 합쳐 답한다.
 *
 * <p>이 흐름 하나만 있다. 범용 엔진이 아니다.
 */
@Service
public class ResearchAndBuildFlow {

    public ChatTurn run(CurrentUser user, Conversation conversation, String text);
}
```

단계가 넷이다.

| 단계 | 하는 일 | 부모 |
| --- | --- | --- |
| Chief | 요청을 읽고 조사할 것과 만들 것을 나눈다 | 없음. 이것이 뿌리다 |
| Researcher | 조사한다 | Chief |
| Engineer | 만든다 | Chief |
| Synthesizer | 둘을 합쳐 최종 답을 만든다 | Chief |

**Chief 의 출력 계약을 정한다.**
모델이 자유롭게 답하면 다음 단계가 무엇을 받을지 모른다.

```json
{"research": "조사할 것 한 문단", "build": "만들 것 한 문단"}
```

파싱하지 못하면 그 흐름을 실패시킨다.
**파싱 실패를 무시하고 원문을 그대로 넘기지 않는다.**
그러면 무엇이 잘못됐는지 모른 채 세 실행이 더 돈다.

`research` 나 `build` 가 비어 있으면 그 단계를 건너뛴다.
둘 다 비면 Chief 의 답을 그대로 최종 답으로 쓰고 흐름을 끝낸다.

### 2. 병렬로 돌린다

Researcher 와 Engineer 를 `CompletableFuture` 로 함께 띄운다.

**한쪽이 실패해도 다른 쪽을 기다린다.**
돌고 있는 것을 중간에 끊지 않는다.
끊어도 Hermes 쪽 실행은 계속 돌고 토큰은 이미 쓰인다.

둘 다 끝난 뒤에 판정한다.

| 상황 | 어떻게 |
| --- | --- |
| 둘 다 성공 | Synthesizer 로 간다 |
| 한쪽 실패 | 멈춘다. Chief 를 `FAILED` 로 갱신하고 어느 단계가 실패했는지 `error_code` 에 적는다 |
| 둘 다 실패 | 같다 |

**`career` 의 `max_concurrent_sessions` 가 1 이다.**
같은 에이전트로 둘을 동시에 부르면 한쪽이 기다린다.
그것이 실제로 그런지 측정해 보고한다. 병렬이 이득인지가 이 흐름의 값을 정한다.

### 3. 부르는 경로

**새 API 를 열지 않는다.** 에이전트 하나를 이 흐름에 묶는다.

`backend/src/main/resources/db/migration/V12__agent_flow.sql` 신규.

```sql
ALTER TABLE agent ADD COLUMN flow VARCHAR(64) NULL;
```

**번호를 확인하고 쓴다.** plan010 이 V10, plan012 가 V11 을 쓴다.
`backend/src/main/resources/db/migration/` 을 보고 비어 있는 다음 번호를 쓴다.

`flow` 가 비어 있으면 지금처럼 Hermes 를 한 번 부른다.
값이 있으면 그 이름의 흐름으로 간다. 지금은 `research-and-build` 하나다.

**모르는 이름이면 기동할 때 실패시킨다.** 실행할 때가 아니다.
잘못 적힌 이름을 배포한 뒤 사용자가 그 에이전트를 고를 때 알게 되면 늦다.

`ChatService` 가 `conversation` 의 에이전트에 `flow` 가 있으면 그 흐름으로 보낸다.

### 4. 사용량 목록이 뿌리만 낸다

자식이 생기면 `GET /api/v1/usage/executions` 가 자식으로 찬다.

**뿌리만 목록에 낸다.** `rootExecutionId` 가 비어 있는 것만이다.
자식은 plan010 이 만든 나무 화면에서 본다.

**월 비용 합계는 자식을 포함한다.**
ADR-016 이 실측으로 확인했다. 자식 토큰은 부모 `usage` 에 들어 있지 않으므로,
각 실행의 금액을 그대로 더하면 두 번 세지 않는다.
지금 `sumCostBetween` 이 이미 그렇게 돈다. **조건을 더하지 마라.**

### 5. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/orchestration/ResearchAndBuildFlowTest.java` 를 새로 만든다.

- **정상 경로**: 흐름 한 번이 실행 넷을 남긴다.
  Chief 가 뿌리이고 나머지 셋의 `rootExecutionId` 가 Chief 를 가리킨다
- Researcher 와 Engineer 의 `parentExecutionId` 가 Chief 다
- **이 phase 가 다루는 실패**: Researcher 가 실패하면 Engineer 가 끝날 때까지 기다린 뒤
  Chief 가 `FAILED` 로 갱신되고 Synthesizer 는 돌지 않는다
- Chief 의 답을 파싱하지 못하면 자식을 하나도 만들지 않고 흐름이 실패한다
- `research` 와 `build` 가 둘 다 비면 Chief 의 답이 최종 답이 되고 실행이 하나만 남는다
- **경계**: 자식 실행의 `userId` 가 전부 부모와 같다
- 사용량 목록에 뿌리 하나만 나온다
- 월 비용 합계가 넷의 금액을 모두 더한 값이다

`test/e2e/scenarios/orchestration.ts` 를 새로 만들고 `test/e2e/run.ts` 의 목록에 더한다.

- `flow` 가 붙은 에이전트로 대화하면 실행 넷이 남고 나무로 조회된다
- `flow` 가 없는 에이전트는 지금처럼 실행 하나만 남는다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
node test/e2e/run.ts
cd web && pnpm typecheck
```

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests '*ResearchAndBuildFlowTest*'
```

**가짜 Hermes 로 통과해도 운영에서 도는지는 모른다.**
실제로 그렇게 스트리밍이 통째로 동작하지 않은 채 배포된 적이 있다.
배포한 뒤 실제로 한 번 왕복시켜 아래를 보고에 적는다.

- 실행 넷이 실제로 남았는지
- Researcher 와 Engineer 의 `startedAt` 과 `finishedAt` 을 견줘
  실제로 병렬로 돌았는지 아니면 줄섰는지
- 넷의 토큰 합계와 걸린 시간

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V12__agent_flow.sql` | 신규 (번호는 확인하고 쓴다) |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ResearchAndBuildFlow.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/Agent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/ResearchAndBuildFlowTest.java` | 신규 |
| `test/e2e/scenarios/orchestration.ts` | 신규 |
| `test/e2e/run.ts` | 수정 |
