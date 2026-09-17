# Phase 02. 모델을 Hermes 에 물어 맞춘다

**Execution profile**: standard

## 목표

에이전트의 `model` 을 손으로 적지 않고 Hermes 에 물어 맞춘다.
모델 이름이 두 곳에 적혀 어긋나면 환산 비용이 틀린다.

**범위 외**

- 요청마다 모델을 바꾸는 것은 하지 않는다. 기각한 이유는 ADR-007 에 있다.
- 화면 작업은 phase-03 이 한다.

## 컨텍스트

모델 이름이 두 곳에 있다.

| 어디 | 정하는 것 |
| --- | --- |
| Hermes profile 의 `config.yaml` 의 `model.default` | 실제로 어느 모델을 부를지 |
| `agent.model` | 우리가 무엇으로 기록하고 얼마로 환산할지 |

한쪽만 바꾸면 이런 일이 생긴다. 실측으로 겪었다.

```
Hermes:  gpt-5.6-sol 로 실행   (백만 토큰당 입력 4달러, 출력 20달러)
우리:    gpt-5.5 로 기록·환산  (입력 5달러, 출력 30달러)
         환산 금액이 22% 부풀려진다
```

Hermes 가 자기 모델을 알려주는 엔드포인트가 있다.
`GET {api_base_url}/api/model/options` 가 `model` 을 준다. 실측으로 확인했다.

```json
{ "model": "gpt-5.6-sol", "provider": "openai-codex", "providers": [...] }
```

따를 패턴을 경로로 짚는다.

| 무엇 | 경로 |
| --- | --- |
| Hermes 호출과 timeout 설정 | `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesRunsClient.java` |
| profile key 조회 | `backend/src/main/java/com/bifos/assistant/hermes/HermesProfileKeyStore.java` |
| 응답을 관대하게 읽는 방식 | 같은 파일의 `readUsage` |

**근거 문서**: `docs/data-schema.md`, `docs/adr/ADR-007-에이전트가-모델과-도구를-함께-정한다.md`

## 의도 메모

- Hermes 가 꺼져 있을 때 모델을 모르게 되면 안 된다.
  마지막으로 읽은 값을 `agent.model` 에 남겨 두고, 읽지 못하면 그것을 계속 쓴다.
  `model_synced_at` 이 언제 읽은 값인지 알려준다.
- 실행할 때마다 물어보지 않는다. 실행 경로에 Hermes 호출을 하나 더 얹으면 느려진다.
- 등록할 때와 사람이 요청할 때만 맞춘다.
- `provider` 도 함께 맞출 수 있지만 지금은 `model` 만 한다.
  provider 는 가격표 조회의 열쇠이고 별칭 표가 그것을 다루고 있다.
  둘을 함께 바꾸면 가격 조회가 조용히 깨질 수 있다.

## 작업 항목

### 1. 모델을 읽는 클라이언트를 더한다

`hermes/HermesModelClient.java` 를 만든다.

- `readModel(String apiBaseUrl, String profileName)` 가 `GET {apiBaseUrl}/api/model/options` 를 부른다.
- 인증은 `HermesProfileKeyStore` 가 주는 key 로 한다. `HttpHermesRunsClient` 와 같다.
- 응답의 `model` 이 문자열이면 그것을 돌려주고, 없거나 문자열이 아니면 `null` 이다.
- 부르지 못하면 예외를 던지지 않고 `null` 이다. 로그에 한 줄 남긴다.
- timeout 은 `HermesProperties` 의 것을 쓴다. 새 설정을 만들지 않는다.

`null` 을 돌려주는 것이 핵심이다. 모델을 모르는 것이 실행을 막을 이유가 아니다.

### 2. 에이전트에 모델 맞추기를 더한다

`agent/application/AgentModelSync.java` 를 만든다.

- `sync(Agent agent)` 가 모델을 읽어 다르면 `agent.model` 과 `model_synced_at` 을 갱신한다.
- 읽지 못하면 아무것도 바꾸지 않는다. 마지막 값이 그대로 남는다.
- 바뀌었으면 로그에 옛 값과 새 값을 남긴다. 비용 환산이 그 시점부터 달라지므로 추적할 수 있어야 한다.

`Agent` 에 `syncModel(String model)` 을 더한다.

### 3. 등록할 때 맞춘다

`AgentAdminController` 의 등록에서 `model` 을 **받지 않는다.**
Hermes 에 물어 채운다.

읽지 못하면 등록을 거절한다.
모델을 모르는 에이전트를 만들면 그 뒤 모든 실행의 비용을 계산할 수 없다.
`ErrorCode` 에 `AGENT_MODEL_UNKNOWN` 을 더한다.

### 4. 사람이 다시 맞출 수 있게 한다

`POST /api/v1/admin/agents/{code}/sync-model` 을 만든다. admin 만 쓴다.

- 맞춘 뒤 `code`, `model`, `modelSyncedAt`, 바뀌었는지를 돌려준다.
- 읽지 못하면 `AGENT_MODEL_UNKNOWN` 이다. 기존 값은 그대로 둔다.

### 5. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/agent/AgentModelSyncTest.java` 를 만든다.
`test/e2e/fake-hermes.ts` 가 `/api/model/options` 를 답하도록 고친다.

- 모델이 바뀌면 `agent.model` 과 `model_synced_at` 이 갱신된다.
- 같으면 `model_synced_at` 만 갱신된다.
- 읽지 못하면 기존 값이 그대로 남고 예외가 나지 않는다.
- 응답에 `model` 이 없으면 읽지 못한 것과 같이 다룬다.
- 등록할 때 읽지 못하면 `AGENT_MODEL_UNKNOWN` 으로 거절된다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
```

```bash
# cwd: 저장소 root
grep -rn '"model"' backend/src/main/java/com/bifos/assistant/agent/presentation/ \
  && echo "확인: 등록 요청이 model 을 받지 않는지 위 결과를 본다" || echo "통과"
```

## Critical Files

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesModelClient.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentModelSync.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/Agent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentAdminController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |

## 끝낸 뒤

`tasks/plan003-agents/index.json` 의 이 phase 를 `completed` 로 바꾸고
`current_phase` 를 다음 번호로 올린다.
