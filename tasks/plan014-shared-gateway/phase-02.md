# Phase 02. 공유 gateway 로 주소를 옮기고 확인한다

**Execution profile**: standard

## 목표

모든 에이전트의 주소를 공유 gateway 쪽으로 옮기고,
대화와 스트리밍과 모델 조회와 다중 에이전트 흐름이 그대로 도는 것을 확인한다.

**범위 외**:
Hermes 쪽 설정과 profile 별 gateway 를 내리는 것은 이 저장소가 하지 않는다.
비공개 저장소 `fos-home-infra` 가 그 절차를 소유한다.
이 phase 는 **그쪽이 공유 gateway 를 세운 뒤**에 시작한다.

## 컨텍스트

phase-01 이 주소를 화면에서 고칠 수 있게 했다.
이 phase 는 그것을 써서 실제로 옮긴다.

전환은 세 단계다. 이 저장소가 하는 것은 1단계와 3단계의 앞부분이다.

| 단계 | 누가 | 무엇 |
| --- | --- | --- |
| 1 | 이 저장소 | 지금 주소에 접두만 붙인다. Hermes 를 건드리지 않는다 |
| 2 | `fos-home-infra` | 공유 gateway 를 세운다. 기존 gateway 는 그대로 둔다 |
| 3 | 이 저장소 | 주소를 공유 gateway 로 옮긴다 |
| 4 | `fos-home-infra` | 기존 gateway 를 내리고 포트 설정을 지운다 |

**1단계는 phase-01 의 「배포한 뒤 실제로 한 번 확인한다」 가 이미 한다.**
이 phase 는 나머지 에이전트에 그것을 넓히고 3단계를 한다.

### 주소를 옮기는 것은 한 걸음이 아니라 두 걸음이다

**이 계획서는 3단계를 한 걸음으로 적었다. 실제로는 두 걸음이었다.**

| 걸음 | 무엇 | 누가 |
| --- | --- | --- |
| 3a | `api_base_url` 에 `/p/<profile>` 접두를 붙인다 | 이 저장소 |
| 3b | 호스트와 포트를 공유 listener 쪽으로 바꾼다 | 이 저장소. 다만 인프라가 먼저 열어야 한다 |

3a 는 지금의 profile 별 gateway 가 자기 이름의 접두를 그대로 받으므로 혼자 할 수 있다.
3b 는 공유 listener 가 Control Plane 이 있는 컨테이너에서 닿아야 할 수 있다.
**실측에서 그 listener 는 Hermes 컨테이너의 loopback 에만 묶여 있어 닿지 못했다.**
그 profile 에 `API_SERVER_HOST` 가 없어 기본값으로 떨어진 것이고,
그것을 여는 것은 `fos-home-infra` 가 소유한다.

다음에 읽는 사람이 한 걸음으로 알면 같은 자리에서 막힌다.

### 무엇을 확인해야 끝인가

조사에서 **재지 못한 것**이 셋 있고, 그것이 이 phase 의 확인 항목이다.

| 무엇 | 왜 재지 못했나 |
| --- | --- |
| 접두 아래의 실행이 그 profile 의 credential 로 도는가 | 측정용 profile 에 credential 이 없었다 |
| 다중 에이전트 흐름 전체가 공유 gateway 로 도는가 | 같다 |
| 동시 실행 한도에 닿아 429 가 나오는가 | 실행이 즉시 실패해 쌓이지 않았다 |

**앞의 둘은 이 전환의 판정을 바꿀 수 있다.**
소스로는 확인했지만 실행으로는 보지 못했다.

**근거 문서**: 비공개 저장소 `fos-home-infra` 의 `docs/hermes-multiplex-gateway.md`.

## 의도 메모

- 모든 에이전트를 한 번에 옮기는 안을 버렸다.
  하나를 먼저 옮겨 확인하고 나머지를 옮긴다.
  한 번에 옮기면 문제가 생겼을 때 무엇 때문인지 좁히지 못한다.
- 옮기고 나서 기존 gateway 를 바로 내리는 안을 버렸다.
  되돌릴 곳이 사라진다. 며칠 두고 본 뒤에 내린다.

## 작업 항목

### 1. 동시 실행 한도를 먼저 본다

**이것이 이 전환의 가장 큰 대가다.**

지금은 profile 마다 동시 실행 한도가 따로다.
공유 gateway 로 옮기면 **모든 profile 이 한 한도를 나눠 쓴다.**

한도를 넘으면 429 가 오고 `HttpHermesRunsClient` 가 그것을 `HERMES_UNAVAILABLE` 로 바꿔
실행을 실패시킨다. **재시도하지 않는다.**
한 사람이 한도를 채우면 다른 사람의 대화가 그 자리에서 실패한다.

이 저장소가 할 것은 둘이다.

- **429 를 다른 오류와 구분해 적는다.** `error_code` 를 `HERMES_BUSY` 로 나눈다.
  지금은 Hermes 가 내려간 것과 붐비는 것이 같은 값으로 기록돼 구분되지 않는다
- **화면이 그것을 다르게 말한다.** 「Hermes 에 닿지 못했다」 가 아니라
  「지금 붐빈다. 잠시 뒤 다시 보내라」 로 그린다

**재시도는 넣지 않는다.** 붐비는데 다시 보내면 더 붐빈다.
사람이 다시 보내는 것이 맞다.

한도값 자체는 이 저장소가 갖지 않는다. `fos-home-infra` 가 정한다.

### 2. 한 에이전트를 먼저 옮긴다

관리 화면에서 에이전트 하나의 주소를 공유 gateway 쪽으로 바꾼다.

**가장 덜 쓰는 에이전트로 한다.** 실패해도 영향이 작다.

바꾼 뒤 확인할 것이다.

| 무엇 | 어떻게 |
| --- | --- |
| 대화 한 번 | 화면에서 보내고 답이 온다 |
| 스트리밍 | 답 조각이 흐르고 `done` 뒤 이력이 맞다 |
| 모델 조회 | 관리 화면의 모델 동기화가 값을 읽는다 |
| 실행 기록 | 사용량 목록에 그 실행이 남는다 |
| Memory 본문 조회 | 색인에 있는 항목의 본문을 도구로 읽는다 |

**Memory 조회가 특히 중요하다.**
그것은 Hermes 가 Control Plane 으로 거꾸로 오는 경로라
접두를 붙인 주소와 무관하게 돌아야 한다. 실제로 그런지 본다.

### 3. 다중 에이전트 흐름을 확인한다

`research-and-build` 흐름을 한 번 돌린다.
부모 실행 하나가 자식 실행 여럿을 만들고 서로 다른 profile 로 돌 수 있다.

확인할 것이다.

| 경계 | 무엇을 본다 |
| --- | --- |
| 사용자 | 자식 실행의 주인이 부모와 같다 |
| Memory | 자식이 그 사용자의 것만 받는다 |
| credential | 자식이 자기 profile 의 것으로 돈다 |
| 사용량 | 자식의 토큰이 각각 기록된다 |

**나무 화면에서 자식이 모두 보이는지 본다.**
`/executions/{id}` 에 부모와 자식이 한 나무로 그려져야 한다.

### 4. 나머지를 옮긴다

확인이 끝나면 남은 에이전트를 옮긴다.
하나씩 옮기고 그때마다 대화를 한 번 보낸다.

**옮긴 주소를 기록해 둔다.** 되돌릴 때 원래 값이 필요하다.
이 저장소에 적지 않는다. 주소에 포트가 들어 있다.

### 5. 며칠 두고 본다

기존 gateway 를 내리는 것은 `fos-home-infra` 가 하고, 그 전에 시간을 둔다.

그동안 볼 것이다.

- 사용량 화면에 `HERMES_BUSY` 로 실패한 실행이 있는가
- 응답이 느려졌는가. 사용량 목록의 소요 시간으로 본다

**`HERMES_BUSY` 가 한 번이라도 나오면 한도를 올려야 한다.**
그 판단의 근거를 `fos-home-infra` 에 넘긴다.

### 6. 이 phase 를 검증하는 테스트

- **이 phase 가 고치는 것**: Hermes 가 429 를 주면 `HERMES_BUSY` 로 적힌다.
  Hermes 가 닿지 않는 것과 다른 값이다
- 그 실행이 사용량 목록에 「붐빔」으로 보인다
- 429 를 받아도 재시도하지 않는다. Hermes 를 한 번만 부른다

`test/e2e/scenarios/` 에 더한다.

- 가짜 Hermes 가 429 를 주면 그 대화가 붐빈다는 오류로 끝난다

`test/browser/` 에 더한다.

- 붐벼서 실패한 실행이 목록에서 다르게 보인다

## 확인 결과

접두를 붙인 주소로 운영에서 실제로 돌려 확인했다.
호스트를 공유 listener 쪽으로 옮기는 걸음은 하지 않았다. 위의 3b 가 그 이유다.

### 확인한 것

| 무엇 | 결과 |
| --- | --- |
| 실행 제출 | 접두 아래로 나가 실행 번호를 받는다 |
| 대화 한 번 | 답이 온다 |
| 스트리밍 | 답 조각이 흐르고 `done` 뒤 이력이 맞다 |
| 모델 조회 | 그 profile 의 모델을 읽는다. listener 주인의 값이 아니다 |
| 실행 기록 | 토큰과 환산 금액이 남는다 |
| **Memory 본문 조회** | **돈다** |
| profile 경계 | 남의 접두를 그 gateway 에 보내면 404 다 |

**Memory 본문 조회가 돈 것이 이 확인에서 가장 무겁다.**
그것은 Hermes 에서 Control Plane 으로 거꾸로 오는 경로라서,
접두를 붙인 주소 아래에서도 그 길이 산다는 뜻이다.
앞으로 `agent_delegate` 가 같은 길을 쓴다.

기억 본문에만 적어 둔 확인 문구를 답으로 받아 판정했다.
제목만 주입되므로 그 문구는 도구로 본문을 읽어야 알 수 있다.

### 확인하지 못한 것

- **다중 에이전트 흐름을 운영에서 돌려 보지 못했다.**
  `ResearchAndBuildFlow` 는 참조 구현이고 넓히지 않기로 했다.
  앞으로 에이전트 조합은 Hermes 가 정하고 Control Plane 은 MCP 도구로 경계만 준다.
  그래서 이것을 운영에서 돌리려고 profile 을 새로 만들지 않았다.
  그 경로는 `test/e2e/scenarios/orchestration.ts` 가 가짜 Hermes 로 고정하고 있다.
- **codex 를 쓰는 profile 의 실행은 확인하지 못했다.** 그 계정이 사용 한도에 닿아 있다.

### 하는 김에 드러난 틈 둘

고치지 않았다. 사실만 적는다.

- **한 profile 에 에이전트를 하나만 둘 수 있다.**
  `agent` 표의 `uk_agent_hermes_profile` 이 그것을 막는다.
  이것은 Hermes 의 profile 모델과 맞으므로 그대로 두는 쪽이 맞다.
- **이미 만든 에이전트의 `flow` 를 뒤에 바꿀 길이 없다.**
  생성 요청에만 그 칸이 있고 수정 요청에는 없다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
scripts/check-public-safe.sh
```

**이 phase 는 배포 뒤 확인이 본체다.**
위 작업 항목 2번과 3번이 그것이고, 그것을 하지 않으면 이 phase 는 끝난 것이 아니다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesRunsClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `web/src/components/usage/execution-list.tsx` | 수정 |
| `web/src/components/chat/` 의 오류 표시 | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/browser/usage.spec.ts` | 수정 |

## 끝낸 뒤

`tasks/plan014-shared-gateway/index.json` 의 이 phase 를 `completed` 로,
plan 의 `status` 를 `completed` 로 바꾼다.

**기존 gateway 를 내리는 것은 이 plan 이 끝난 뒤 `fos-home-infra` 가 한다.**
그쪽에 며칠 동안 본 결과를 함께 넘긴다.
