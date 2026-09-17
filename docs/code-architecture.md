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
| `agent` | 에이전트 등록, 공개 범위, Hermes profile 연결, 모델 동기화 |
| `hermes` | Runs API 호출과 profile key 조회 |
| `chat` | 대화, 메시지, 한 번의 실행 흐름 |
| `usage` | 실행 기록, 비용 환산, 사용량 조회 |
| `workspace` | 작업 영역 등록, 접근 권한, 안내문 읽기 |

## 한 번의 대화가 지나는 길

1. `ChatController` 가 현재 사용자를 확인한다.
2. `ChatService` 가 요청한 에이전트를 사용자가 쓸 수 있는지 확인하고 `conversation` 에 기록한다.
   이어지는 대화는 요청의 `agentCode` 를 무시하고 처음 기록한 에이전트를 쓴다.
3. 새 대화면 요청의 `workspaceCode` 를 `WorkspaceService.requireReadable` 로 확인해 `conversation` 에 기록한다.
   이어지는 대화는 요청의 `workspaceCode` 를 무시하고 그 대화에 이미 기록된 영역을 쓴다.
4. `WorkspaceService.briefing` 이 그 영역의 `AGENTS.md` 를 읽어 실행의 `instructions` 로 넣는다.
   영역이 없으면 `instructions` 는 비운다.
5. `HermesProfileKeyStore` 가 그 profile 이름의 key 파일을 읽는다. 없으면 거기서 끝난다.
6. `HttpHermesRunsClient` 가 실행을 제출하고 끝날 때까지 조회한다.
7. `CostEstimator` 가 그 토큰을 models.dev 가격표로 환산한다.
8. `ExecutionRecorder` 가 사용자, 에이전트, 영역, provider, 모델, 토큰, 소요 시간, 환산 금액을 한 줄로 남긴다.
9. 실패해도 8번은 남는다. 실패는 토큰을 보고하지 않으므로 금액만 비어 있다.

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

## 작업 영역

작업 영역(workspace)은 도메인 지식과 운영 규칙을 담은 `AGENTS.md` 하나를 가진 디렉터리다.
그 내용은 이 저장소가 아니라 별도 저장소 `fos-agents` 가 정본으로 갖고 있고,
이 저장소는 그 디렉터리를 읽기 전용으로 마운트해 읽기만 한다. 근거는
[`adr/ADR-006-작업-영역은-읽기-전용-의존이고-공개-범위는-기본값이-없다.md`](adr/ADR-006-작업-영역은-읽기-전용-의존이고-공개-범위는-기본값이-없다.md) 에 있다.

- 마운트 루트는 `assistant.workspace.root` (`ASSISTANT_WORKSPACE_ROOT`) 가 정한다.
- 영역은 관리자가 `POST /api/v1/admin/workspaces` 로 하나씩 등록한다. 마운트를 훑어 자동으로
  등록하지 않는다. 등록할 때 공개 범위(`PRIVATE` 또는 `FAMILY`)를 반드시 명시해야 한다.
- `Workspace.isReadableBy` 가 조회와 실행 모두에서 접근 권한을 정한다. 남의 개인 영역은
  존재하지 않는 것과 같은 `WORKSPACE_NOT_FOUND` 로 응답한다.
- `WorkspaceService.resolveGuide` 가 등록된 `sourcePath` 를 마운트 루트 아래로만 정규화해
  풀이한다. 마운트 밖을 가리키면 안내문을 읽지 않는다.
- 대화가 시작될 때 고른 영역은 그 대화의 `workspace_id` 로 고정된다. 이어지는 대화의 요청이
  다른 영역을 주어도 무시하고 처음 영역을 계속 쓴다.

## 비밀값을 두는 곳

| 값 | 두는 곳 |
| --- | --- |
| 구성원의 AI credential | 그 사람의 Hermes profile `.env` |
| profile 의 API server key | 홈서버의 mode 600 파일. 파일 이름이 profile 이름이다 |
| 웹과 Control Plane 이 나눠 가지는 HMAC 비밀값 | 두 서비스의 환경 변수 |

데이터베이스에는 어떤 비밀값도 넣지 않는다.
`agent` 는 profile 이름과 주소만 적는다.
profile key 와 AI credential 은 계속 홈서버 파일에 둔다.

## 문서

| 문서 | 담는 것 |
| --- | --- |
| [`flow.md`](flow.md) | 화면 전환과 호출 순서 |
| [`data-schema.md`](data-schema.md) | 표와 칸의 뜻 |
| [`hermes-integration.md`](hermes-integration.md) | Hermes 확장 지점 |
| [`adr/INDEX.md`](adr/INDEX.md) | 되돌리기 어려운 결정 |

## 아직 만들지 않은 것

- Memory 와 Shared Memory
- 실행 Graph 와 SSE 중계

각각은 `tasks/plan001-mvp/` 에 단계로 나뉘어 있다.
