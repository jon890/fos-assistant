# Phase 01. 실행마다 모델을 정해 보내고 실제로 돈 모델을 적는다

**Execution profile**: deep

## 목표

에이전트가 쓸 모델을 순서 있는 목록으로 갖고, 실행마다 그중 하나를 골라
`provider` 와 `model` 을 함께 Hermes 에 보낸다.
실행이 끝나면 **실제로 돈 모델**을 읽어 기록한다.

**범위 외**:
막혔을 때 다음 provider 로 넘기는 것은 phase-02 가 한다.
이 phase 는 늘 1순위만 쓴다.
화면은 phase-03 이 한다.

## 컨텍스트

지금은 `/v1/runs` 에 `model` 을 보내지 않는다.
그러면 Hermes 가 profile 이름을 돌려주고, `ExecutionRecorder.modelOf` 가
그것을 보고 **에이전트 표에 적힌 모델을 대신 적는다.**

```java
private static String modelOf(HermesRunResult result, Agent agent) {
    String reported = result.model();
    if (reported == null || reported.isBlank() || reported.equals(agent.hermesProfile())) {
        return agent.model();   // 실제로 돈 모델이 아니다
    }
    return reported;
}
```

**실제로 그 때문에 금액이 틀린 일이 있었다.**
profile 의 모델을 바꿔 돌렸는데 기록에는 옛 모델이 남았고,
그 모델의 단가로 환산액이 계산됐다.

홈서버에서 HTTP 로 직접 물어 확인한 것이 셋이다.

| 물은 것 | 답 |
| --- | --- |
| `/v1/runs` 가 요청의 `model` 과 `provider` 를 적용하는가 | **적용한다. 다만 둘을 함께 보내야 한다** |
| `GET /v1/runs/{run_id}` 의 `model` 이 실제로 돈 모델인가 | **아니다. 요청을 되돌려 줄 뿐이다** |
| 실제로 돈 모델을 알 방법 | **`GET /api/sessions/{session_id}` 의 `model`** |

`provider` 만 주고 `model` 을 빼면 실패한다.
Hermes 가 config 의 모델 문자열을 새 provider 에 그대로 넘겨
`No LLM provider configured` 로 끝난다.

**근거 문서**: 비공개 저장소 `fos-home-infra` 의 `docs/hermes-model-selection.md`.
측정 조건과 실제 출력이 거기 있다.

### Hermes 가 이미 하는 것을 다시 만들지 않는다

**한 provider 에 계정을 여럿 붙일 수 있고, 막힌 계정을 건너뛰는 것은 Hermes 가 한다.**
홈서버에서 확인한 것이다.

```text
openai-codex (2 credentials):
  #1  openai-codex-oauth-1  rate-limited (429) (ready to retry)
  #2  openai-codex-oauth-2  rate-limited usage_limit_reached (429) (2d 19h left)
```

`hermes auth` 가 그 목록을 관리하고 credential 마다 소진과 남은 시간을 기억한다.
계정 하나가 막히면 Hermes 가 같은 provider 의 다음 계정으로 옮겨 간다.

**그래서 층이 둘이다.**

| 층 | 누가 고르나 | 무엇이 바뀌나 |
| --- | --- | --- |
| 같은 provider 안의 계정 | Hermes | 계정만 바뀐다. 모델은 그대로다 |
| provider 와 모델 | Control Plane | 모델이 바뀐다 |

**아래층을 만들지 마라.** credential 을 표에 담거나 계정을 돌려 쓰는 코드를 쓰지 않는다.
계정을 더하는 것은 홈서버에서 `hermes auth add` 로 한다.

우리가 만드는 것은 위층 하나다.
`⚠️ Provider authentication failed:` 가 오는 시점은
**그 provider 의 계정이 전부 막혔을 때**다. 하나만 막힌 것은 우리에게 오지 않는다.

## 의도 메모

- Hermes 의 `fallback_providers` 에 맡기는 안을 버렸다.
  체인이 발동해 다른 모델로 넘어가도 `/v1/runs` 응답 어디에도 그 사실이 남지 않는다.
  맡기면 기록과 실제가 계속 어긋난다.
- 에이전트 표의 `provider` 와 `model` 칸을 그대로 두고 목록만 더하는 안도 버렸다.
  같은 것을 두 곳이 갖게 되어 어느 쪽이 맞는지 다시 갈린다.
- 세션 채팅 경로(`POST /api/sessions/{id}/chat`)로 옮기는 안을 버렸다.
  응답 한 번으로 실제 모델까지 받을 수 있지만 실패가 HTTP 200 본문으로 와서
  실패 판정 코드를 전부 다시 써야 한다. 변경이 크다.

## 작업 항목

### 1. 에이전트가 모델 목록을 갖는다

`backend/src/main/resources/db/migration/V14__agent_model_option.sql` 신규.
**번호는 확인하고 쓴다.** 지금 V13 까지 있다.

```sql
CREATE TABLE agent_model_option (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    agent_id BIGINT NOT NULL,
    rank INT NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_agent_model_option (agent_id, rank)
);
```

`rank` 는 1 부터다. 1 이 1순위다.

**같은 마이그레이션에서 지금 있는 에이전트의 `provider` 와 `model` 을 `rank` 1 로 옮긴다.**
옮기지 않으면 배포 직후 모든 실행이 쓸 모델을 찾지 못한다.

`agent` 표의 `provider` 와 `model` 과 `model_synced_at` 칸은 **지우지 않는다.**
이미 쌓인 실행 기록이 그 값으로 해석되고 있고, 지우면 옛 기록을 읽을 수 없다.
대신 **새 실행에서는 읽지 않는다.** `rank` 1 이 그 자리를 대신한다.

### 2. 요청에 모델과 provider 를 싣는다

`HermesRunCommand` 에 두 칸을 더한다.

```java
public record HermesRunCommand(
        String profileName,
        String apiBaseUrl,
        String input,
        String instructions,
        String sessionId,
        String provider,
        String model) {}
```

`HttpHermesRunsClient.submit` 이 둘을 요청 본문에 넣는다.

```java
body.put("provider", command.provider());
body.put("model", command.model());
```

**둘 중 하나라도 비면 요청을 보내지 않고 실패시킨다.**
Hermes 가 `provider` 만 받으면 엉뚱한 모델로 시도하고 알아보기 어려운 오류를 낸다.

`ChatService` 가 그 값을 채운다. 에이전트의 `rank` 1 을 쓴다.

**자식 실행도 같은 자리를 쓴다.**
`ChildExecutionRunner` 가 부모와 같은 경로로 `HermesRunCommand` 를 만든다.
자식은 자기 에이전트의 `rank` 1 을 쓴다. 부모의 것을 물려받지 않는다.
자식이 다른 에이전트면 그 에이전트가 정한 모델이 맞다.

### 3. 실제로 돈 모델을 읽어 적는다

`HermesRunsClient` 에 세션 조회를 더한다.

```java
/** 그 세션이 마지막으로 실제로 쓴 모델. 읽지 못하면 null 이다. */
String readSessionModel(String apiBaseUrl, String profileName, String sessionId);
```

`GET {apiBaseUrl}/api/sessions/{sessionId}` 의 `model` 을 읽는다.

`ExecutionRecorder` 가 그 값으로 적는다.

| 상황 | 기록할 모델 |
| --- | --- |
| 세션 조회가 값을 줬다 | **그 값** |
| 세션 조회가 실패했거나 비었다 | 요청에 보낸 모델 |

`modelOf` 의 「profile 이름이면 에이전트 값을 쓴다」 분기를 지운다.
이제 우리가 보낸 값을 알고 있어 추측할 필요가 없다.

**세션 조회가 실패해도 실행은 성공으로 남긴다.**
모델 이름을 모르는 것이 답을 버릴 이유가 되지 않는다.
그때는 요청에 보낸 값을 적고 로그에 남긴다.

`provider` 도 같은 방식으로 적는다.
세션 조회가 provider 를 함께 주면 그것을 쓰고, 없으면 요청에 보낸 값을 쓴다.

### 4. 관리 화면이 목록을 다룬다

`AgentAdminController` 에 목록을 읽고 쓰는 경로를 더한다.

| 메서드와 경로 | 하는 일 |
| --- | --- |
| `GET /api/v1/admin/agents/{code}/models` | 순서대로 돌려준다 |
| `PUT /api/v1/admin/agents/{code}/models` | 목록 전체를 바꾼다 |

`PUT` 은 목록 전체를 받아 통째로 바꾼다. 한 줄씩 고치는 경로를 두지 않는다.
순서가 뜻을 갖는 목록이라 부분 수정은 순서가 어긋날 자리를 만든다.

**빈 목록을 거절한다.** 모델이 하나도 없는 에이전트는 실행할 수 없다.

요청과 응답 모양은 `AgentDtos` 에 둔다.
`backend/AGENTS.md` 의 「데이터 클래스는 컨트롤러 안에 두지 않는다」 가 그 규칙을 소유한다.

### 5. 모델 동기화를 다시 본다

`AgentModelSync` 는 지금 Hermes 에서 읽은 모델을 `agent.model` 에 적는다.
새 실행이 그 값을 읽지 않으므로 **그 동기화는 이제 뜻이 없다.**

지우지 말고 **`rank` 1 의 모델을 갱신하도록 바꾼다.**
profile 의 기본 모델이 바뀐 것을 관리자가 화면에서 따라잡는 길은 남겨 둔다.

`provider` 도 함께 읽어 갱신한다.
`GET /api/model/options` 가 provider 를 주는지 확인하고,
주지 않으면 provider 는 건드리지 말고 그 사실을 응답에 적는다.

### 6. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/` 아래에 더한다.

- **이 phase 가 고치는 것**: 세션 조회가 요청과 다른 모델을 주면
  **그 값**이 실행에 기록된다. 요청에 보낸 값이 아니다
- 세션 조회가 실패하면 요청에 보낸 값이 기록되고 실행은 성공으로 남는다
- `provider` 나 `model` 이 비면 Hermes 를 부르지 않고 실패한다
- 자식 실행이 자기 에이전트의 `rank` 1 을 쓴다. 부모의 것을 물려받지 않는다
- 목록을 빈 채로 저장하려 하면 거절한다
- 마이그레이션이 기존 에이전트의 값을 `rank` 1 로 옮긴다

`test/e2e/scenarios/` 에 더한다.

- 가짜 Hermes 가 요청 본문의 `provider` 와 `model` 을 받았는지 확인한다
- 가짜 Hermes 의 세션 조회가 다른 모델을 주면 그 값이 사용량에 보인다

**가짜 Hermes 를 실제와 같은 모양으로 고친다.**
지금 `test/e2e/fake-hermes.ts` 에 세션 조회 경로가 없다.
없는 채로 두면 테스트는 통과하고 운영에서만 동작하지 않는다.
실제로 그렇게 스트리밍이 통째로 동작하지 않은 채 배포된 적이 있다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
scripts/check-public-safe.sh
```

**배포한 뒤 실제로 한 번 확인한다.**
화면에서 대화를 한 번 보내고 사용량 목록의 모델이 실제로 돈 모델인지 본다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V14__agent_model_option.sql` | 신규 (번호는 확인하고 쓴다) |
| `backend/src/main/java/com/bifos/assistant/agent/domain/AgentModelOption.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/infra/AgentModelOptionRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentModelSelector.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/dto/HermesRunCommand.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesRunsClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesRunsClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ChildExecutionRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentModelSync.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentAdminController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentDtos.java` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |

## 끝낸 뒤

`tasks/plan013-model-selection/index.json` 의 이 phase 를 `completed` 로 바꾼다.
