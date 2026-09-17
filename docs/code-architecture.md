# 구조

## 경계

```
브라우저
  └ Next.js (web/)            세션은 여기까지만 산다
      └ 서버 라우트에서 짧은 수명의 토큰을 발급해 호출
          └ Spring Boot (backend/)   Control Plane
              └ Hermes API server    /p/<profile>/v1/runs
```

브라우저는 Control Plane 토큰을 갖지 않는다.
Next.js 서버 라우트가 세션에서 메일 주소를 꺼내 매 요청마다 토큰을 새로 만든다.
그래서 사용자가 요청 본문을 고쳐 남의 자료를 달라고 할 수 없다.

## backend 패키지

도메인별로 나누고 각 도메인 안은 `presentation` 에서 `application`, `domain`, `infra` 로만 흐른다.

| 패키지 | 책임 |
| --- | --- |
| `shared/auth` | 토큰 검사와 현재 사용자 |
| `shared/error` | 오류 코드와 응답 형태 |
| `user` | 가족 구성원과 첫 로그인 처리 |
| `credential` | 사용자와 Hermes profile 의 바인딩 |
| `hermes` | Runs API 호출과 profile key 조회 |
| `chat` | 대화, 메시지, 한 번의 실행 흐름 |
| `usage` | 실행 기록, 비용 환산, 사용량 조회 |

## 한 번의 대화가 지나는 길

1. `ChatController` 가 현재 사용자를 확인한다.
2. `ChatService` 가 그 사용자의 바인딩을 찾는다. 없으면 `HERMES_BINDING_MISSING` 으로 끝난다.
3. `HermesProfileKeyStore` 가 그 profile 이름의 key 파일을 읽는다. 없으면 거기서 끝난다.
4. `HttpHermesRunsClient` 가 실행을 제출하고 끝날 때까지 조회한다.
5. `CostEstimator` 가 그 토큰을 models.dev 가격표로 환산한다.
6. `ExecutionRecorder` 가 사용자, provider, 모델, 토큰, 소요 시간, 환산 금액을 한 줄로 남긴다.
7. 실패해도 6번은 남는다. 실패는 토큰을 보고하지 않으므로 금액만 비어 있다.

환산은 이 자리에서 한 번만 하고 쓴 가격표를 함께 적는다.
조회할 때 다시 계산하면 가격이 바뀔 때 지난달 합계가 따라 움직인다.
근거는 [`adr/ADR-004-구독제에서도-api-가격으로-환산해-보인다.md`](adr/ADR-004-구독제에서도-api-가격으로-환산해-보인다.md) 에 있다.

## 가격표

`usage/infra/ModelsDevPriceCatalog` 가 기동할 때 models.dev 카탈로그를 한 번 읽어 메모리에 둔다.
경로는 `ASSISTANT_PRICING_CATALOG` 가 정한다.

Hermes profile 디렉터리를 그대로 붙이지 않는다.
그 디렉터리에는 `.env` 와 `auth.json` 이 함께 있어서 credential 까지 컨테이너에 들어간다.
배포할 때 카탈로그 파일만 중립 경로로 복사하고 그쪽을 읽기 전용으로 붙인다.

카탈로그가 없거나 읽히지 않아도 기동은 계속하고 금액만 비워 둔다.

## 비밀값을 두는 곳

| 값 | 두는 곳 |
| --- | --- |
| 구성원의 AI credential | 그 사람의 Hermes profile `.env` |
| profile 의 API server key | 홈서버의 mode 600 파일. 파일 이름이 profile 이름이다 |
| 웹과 Control Plane 이 나눠 가지는 HMAC 비밀값 | 두 서비스의 환경 변수 |

데이터베이스에는 어떤 비밀값도 넣지 않는다.
`hermes_profile_binding` 은 누구의 profile 이 무엇인지만 적는다.

## 아직 만들지 않은 것

- Memory 와 Shared Memory
- 실행 Graph 와 SSE 중계

각각은 `tasks/plan001-mvp/` 에 단계로 나뉘어 있다.
