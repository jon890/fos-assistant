# Phase 01. 에이전트의 Hermes 주소를 화면에서 고친다

**Execution profile**: standard

## 목표

관리자가 에이전트의 `api_base_url` 을 화면에서 고칠 수 있게 한다.
바꾼 뒤 그 주소가 실제로 응답하는지 저장 전에 확인한다.

**범위 외**:
주소를 실제로 옮기는 것은 phase-02 가 한다.
Hermes 쪽 설정은 이 저장소가 갖지 않는다. 비공개 저장소가 소유한다.

## 컨텍스트

지금은 profile 마다 Hermes gateway 가 하나씩 있고 포트도 하나씩 쓴다.
profile 이 늘 때마다 포트를 정하고 프로세스를 띄워야 한다.

**공유 gateway 하나로 옮기면 그것이 사라진다.**
Hermes 가 `/p/<profile>/...` 접두로 profile 을 구분한다.
실측으로 확인한 것이다.

| 확인한 것 | 결과 |
| --- | --- |
| 접두 라우팅 | 모든 경로가 접두 아래에 열린다 |
| 다른 profile 의 key 로 접근 | 401 로 거절한다 |
| 다른 profile 의 `run_id` 조회 | 404. 존재 여부도 알리지 않는다 |
| profile 20개의 메모리 | 지금 약 5,000 MB, 공유 gateway 256 MB |

**이 저장소가 바꿀 것은 `agent` 표의 `api_base_url` 값 하나다.**
스키마도 호출 코드도 바꾸지 않는다.
`Agent` 가 그 값 뒤에 `/v1/...` 과 `/api/...` 를 붙여 부르고,
Hermes 는 같은 handler 를 접두 있는 경로와 없는 경로 양쪽에 등록한다.

### 그런데 고칠 길이 없다

`AgentAdminController` 의 `UpdateAgentRequest` 는 셋만 받는다.

```java
public record UpdateAgentRequest(
        @NotNull Boolean enabled,
        @NotNull AgentVisibility visibility,
        String ownerEmail) {}
```

`api_base_url` 은 에이전트를 만들 때만 정하고 그 뒤로 못 바꾼다.
**옮기려면 데이터베이스를 직접 고쳐야 하는데 그 길은 쓰지 않는다.**
기록이 남지 않고 되돌릴 근거도 남지 않는다.

**근거 문서**: 비공개 저장소 `fos-home-infra` 의 `docs/hermes-multiplex-gateway.md`.
측정 조건과 전환 순서가 거기 있다.

## 의도 메모

- `api_base_url` 칸을 없애고 전역 주소 하나로 바꾸는 안을 버렸다.
  나중에 Hermes 를 여러 노드로 나누면 profile 마다 다른 노드를 가리켜야 한다.
  지금 합치면 그 길이 막힌다.
- 접두를 코드가 붙이는 안도 버렸다. `apiBaseUrl + "/p/" + profile` 처럼 만들면
  주소의 모양을 코드가 알게 되고, 접두 없이 쓰는 배포로 되돌릴 수 없다.
  **접두는 값의 일부로 둔다.**
- 저장 전 확인을 건너뛰는 안을 버렸다.
  주소를 잘못 적으면 그 에이전트의 모든 대화가 실패하고,
  화면에는 Hermes 에 닿지 못했다는 것만 보인다. 원인을 찾기 어렵다.

## 작업 항목

### 1. 주소를 고칠 수 있게 한다

`UpdateAgentRequest` 에 칸을 더한다.

```java
public record UpdateAgentRequest(
        @NotNull Boolean enabled,
        @NotNull AgentVisibility visibility,
        String ownerEmail,
        String apiBaseUrl) {}
```

**비어 있으면 지금 값을 유지한다.** 다른 것만 고치는 요청이 주소를 지우면 안 된다.

`Agent` 에 주소를 바꾸는 메서드를 더한다.
지금 생성자가 하는 것처럼 **끝의 `/` 를 떼고 저장한다.**
그러지 않으면 `//v1/runs` 처럼 부르게 된다.

### 2. 저장하기 전에 닿는지 본다

주소를 바꾸는 요청이면 저장 전에 그 주소로 한 번 물어본다.

`GET {새 주소}/v1/capabilities` 에 그 profile 의 key 를 실어 보낸다.

| 응답 | 어떻게 |
| --- | --- |
| 200 | 저장한다 |
| 그 밖 | 저장하지 않고 무엇이 왔는지 알린다 |

**이 확인은 그 profile 의 key 로 한다.**
다른 key 로 하면 401 이 와서 주소가 맞는지 틀린지 구분하지 못한다.
`HermesProfileKeyStore` 가 이미 profile 이름으로 key 를 찾아 준다.

확인을 건너뛰는 길을 두지 않는다.
닿지 않는 주소를 저장할 이유가 없다.

### 3. 화면에서 고친다

`/admin/agents` 의 에이전트마다 주소를 고치는 자리를 둔다.

**지금 값을 먼저 보인다.** 빈 칸에 새로 적게 하면 무엇이 바뀌는지 알 수 없다.

저장에 실패하면 무엇이 왔는지 그 자리에 적는다.
「저장하지 못했다」만으로는 주소가 틀린 것인지 Hermes 가 내려간 것인지 모른다.

### 4. 이 phase 를 검증하는 테스트

- **이 phase 가 고치는 것**: 주소를 바꿔 저장하면 그 값이 남고,
  **그 뒤 실행이 새 주소로 나간다**
- 주소를 비워 보내면 지금 값이 그대로 남는다
- 끝에 `/` 를 붙여 보내면 떼고 저장한다
- 확인이 200 이 아니면 저장하지 않고 값이 그대로다
- 확인은 그 에이전트의 profile key 로 나간다

`test/browser/admin.spec.ts` 에 더한다.

- 주소를 고쳐 저장하면 화면에 새 값이 보인다
- 닿지 않는 주소를 저장하려 하면 실패 이유가 그 자리에 보인다

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
관리 화면에서 한 에이전트의 주소 끝에 `/p/<그 profile 이름>` 을 붙여 저장하고,
그 에이전트로 대화를 한 번 보내 답이 오는지 본다.

**이것이 전환의 1단계다.** 지금의 gateway 도 자기 이름의 접두를 받으므로
Hermes 를 건드리지 않고 확인할 수 있다.
되돌리기는 그 값을 원래대로 돌리는 것뿐이다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentAdminController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/Agent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentEndpointProbe.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesModelClient.java` | 참고 |
| `web/src/components/admin/` 의 에이전트 화면 | 수정 |
| `test/browser/admin.spec.ts` | 수정 |

## 끝낸 뒤

`tasks/plan014-shared-gateway/index.json` 의 이 phase 를 `completed` 로 바꾼다.
