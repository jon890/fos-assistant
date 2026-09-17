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
| `user` | 사용자과 첫 로그인 처리 |
| `agent` | 에이전트 등록, 공개 범위, Hermes profile 연결, 모델 동기화 |
| `hermes` | Runs API 호출과 profile key 조회 |
| `chat` | 대화, 메시지, 한 번의 실행 흐름 |
| `usage` | 실행 기록, 실행 사건, 비용 환산, 사용량 조회 |
| `memory` | 개인과 가족 공용 Memory, 제안과 승인 |
| `context` | 실행에 넣을 `instructions` 조립 |
| `mcp` | 제목만 주입한 Memory 본문 조회와 장기 토큰 인증 |

## 한 번의 대화가 지나는 길

1. `ChatController` 가 현재 사용자를 확인한다.
2. `ChatService` 가 요청한 에이전트를 사용자가 쓸 수 있는지 확인하고 `conversation` 에 기록한다.
   이어지는 대화는 요청의 `agentCode` 를 무시하고 처음 기록한 에이전트를 쓴다.
3. `ContextAssembler` 가 이 실행에 넣을 `instructions` 를 조립한다.
   요청자가 볼 수 있는 Memory 만 고른다.
4. `ExecutionRecorder` 가 `RUNNING` 상태로 실행 한 줄을 먼저 만든다.
   조립한 글자 수를 `context_chars` 에 적는다.
5. `HermesProfileKeyStore` 가 그 profile 이름의 key 파일을 읽는다. 없으면 거기서 끝난다.
6. `HttpHermesRunsClient` 가 실행을 제출한다. 받은 `run_id` 를 그 자리에서 실행 줄에 적는다.
7. 스트림으로 오는 사건을 화면으로 중계하면서 `execution_event` 로도 옮겨 적는다.
8. 실행이 끝나면 `CostEstimator` 가 토큰을 models.dev 가격표로 환산한다.
9. `ExecutionRecorder` 가 4번에서 만든 줄을 `SUCCEEDED` 로 갱신한다.
   토큰, 소요 시간, 환산 금액이 이때 채워진다.
10. 실패해도 그 줄은 남는다. `FAILED` 로 갱신되고 금액만 비어 있다.

`instructions` 를 조립하는 순서는 아래와 같다.

| 순서 | 담는 것 |
| --- | --- |
| 1 | 가족 공용 Memory 중 `ACCEPTED` 이고 항상 주입하는 본문 |
| 2 | 요청자 개인 Memory 중 `ACCEPTED` 이고 항상 주입하는 본문 |
| 3 | 나머지 접근 가능한 항목의 제목과 번호 색인 |
| 4 | 이 실행에만 필요한 문맥 |

다른 사용자의 개인 Memory 는 고르는 단계에서 빠진다.
문자열을 만든 뒤에 지우는 것이 아니라 애초에 넣지 않는다.

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

## Memory

Memory 는 에이전트가 실행할 때 `instructions` 로 받는 사실이다.
단일 소스는 Control Plane 데이터베이스이고, Hermes 의 내장 memory 는 쓰지 않는다.

- 범위는 `USER` 와 `FAMILY` 둘뿐이고 등록할 때 반드시 명시한다.
- 에이전트가 제안하면 `PROPOSED` 로 들어오고, 사람이 받아들여야 `ACCEPTED` 가 된다.
  주입되는 것은 `ACCEPTED` 뿐이다.
- `ContextAssembler` 가 요청자의 `USER` 항목과 그 가족의 `FAMILY` 항목만 골라 조립한다.
  다른 사용자의 개인 항목은 고르는 단계에서 빠진다.
- `always_inject` 가 참이면 본문을 싣고, 거짓이면 제목과 번호만 색인에 싣는다.
- 색인의 본문은 `memory_read` MCP 도구로 읽는다.
  장기 토큰이 요청자를 정하며 요청 본문은 사용자를 바꾸지 못한다.
  Control Plane 은 접근할 수 없는 항목과 없는 항목을 같은 응답으로 숨긴다.
- 조립한 글자 수를 실행의 `context_chars` 에 남긴다.
  주입할 양이 실제로 문제가 되는 시점을 숫자로 판단하기 위해서다.

근거는 [`adr/ADR-003-memory-권한은-주입으로-강제한다.md`](adr/ADR-003-memory-권한은-주입으로-강제한다.md) 와
[`adr/ADR-012-memory-는-사람이-승인한-것만-남는다.md`](adr/ADR-012-memory-는-사람이-승인한-것만-남는다.md) 에 있다.
층을 나누는 근거는
[`adr/ADR-015-memory-는-층을-나눠-싣는다.md`](adr/ADR-015-memory-는-층을-나눠-싣는다.md) 에 있다.

## 실행 사건

Hermes 가 스트림으로 보내는 사건을 우리 이름으로 옮겨 `execution_event` 에 적는다.
화면은 우리 이름만 읽고 Hermes 의 원래 이름을 알지 않는다.

- 옮겨 적는 자리가 한 곳이다. Hermes 가 이름을 바꾸면 그 한 곳만 고친다.
- 받은 payload 를 통째로 넣지 않고 필요한 칸만 고른다.
  도구가 읽어 온 문서 전체가 사건에 실려 오는 것을 그대로 저장하지 않기 위해서다.
- 모르는 사건은 버리고 버렸다는 사실만 로그로 남긴다.

실시간 스트리밍은 그대로 둔다.
화면에 흘리는 것과 저장하는 것이 같은 스트림을 두 가지로 쓴다.
저장하는 답은 여전히 실행 결과에서 가져온다.

근거는 [`adr/ADR-013-실행-사건은-우리-모델로-정규화해-저장한다.md`](adr/ADR-013-실행-사건은-우리-모델로-정규화해-저장한다.md) 에 있다.

## web 화면 구조

화면은 셋이다. 나머지는 이 셋을 이루는 부품이다.

| 경로 | 화면 |
| --- | --- |
| `/` | 대화 |
| `/usage` | 사용량 |
| `/memory` | 개인과 가족 공용 Memory |
| `/executions/{id}` | 실행 하나의 도구와 하위 에이전트 나무 |
| `/admin/agents` | 에이전트 관리 |

`app/` 은 경로와 서버에서 읽는 것만 담고, 화면을 이루는 부품은 `components/` 에 둔다.
`components/ui/` 는 어느 화면에도 속하지 않는 조각이고, 그 밖의 파일은 한 화면의 부품이다.

```
web/src/
  app/                    경로, 서버 컴포넌트, 서버 라우트
  components/
    ui/                   버튼, 입력, 표, 뼈대, 아이콘
    chat/                 대화 화면의 부품
    usage/                사용량 화면의 부품
    admin/                관리 화면의 부품
  lib/                    Control Plane 호출과 형식 변환
```

### 색과 간격은 테마 토큰이 소유한다

색을 `style={{ background: "var(--surface)" }}` 처럼 인라인으로 적지 않는다.
`globals.css` 의 `@theme` 에 토큰을 선언하고 `bg-surface` 같은 Tailwind 클래스로 쓴다.

인라인 스타일에는 `hover:` 와 `md:` 와 `disabled:` 를 붙일 수 없다.
그래서 인라인으로 적은 색 하나가 그 요소의 반응형과 상태 변화를 함께 막는다.

토큰은 `globals.css` 한 곳에서만 선언한다. 밝기 모드도 거기서 갈린다.

### 우리 화면의 정체성

브랜드 색은 테라코타다. 기본값이 `#B05A3C` 다.
집을 연상하게 하는 따뜻한 흙색이고, 집에서 쓰는 비서라는 성격과 맞다.

| 토큰 | 쓰는 곳 |
| --- | --- |
| `brand` | 보내기 단추, 고른 항목, 링크, 상태 표시 |
| `brand-strong` | 눌렀을 때와 마우스를 올렸을 때 |
| `brand-soft` | 내 말풍선 배경 |
| `on-brand` | 브랜드 색 위에 얹는 글자 |

브랜드 색을 본문 글자에 쓰지 않는다.
읽는 글이 색을 가지면 무엇이 누를 수 있는 것인지 알 수 없게 된다.

글꼴은 Pretendard 다. 한국어 화면에서 인상의 절반을 글꼴이 정한다.

**글꼴 파일을 우리가 들고 배포한다.** 외부 CDN 에서 받지 않는다.
가족만 쓰는 화면이라 화면을 열 때마다 외부에 요청이 나가는 것을 원하지 않는다.
망이 끊긴 집에서도 같은 화면이 나와야 한다.

대화 화면의 구조는 ChatGPT 의 배치를 따른다.

| 요소 | 규칙 |
| --- | --- |
| 대화 열 | 가운데 정렬한 좁은 열. 화면 폭을 다 쓰지 않는다 |
| 내가 보낸 줄 | 오른쪽 정렬 말풍선. 폭은 열의 70% 까지 |
| 비서가 답한 줄 | 열 전체 폭. 배경과 테두리가 없다 |
| 입력창 | 둥근 알약 하나. 그 안 오른쪽에 원형 보내기 단추 |

비서의 답만 폭을 다 쓰는 이유는 표와 코드 블록이 오기 때문이다.
좁은 말풍선에 넣으면 그 안에서 가로로 밀어야 읽힌다.

### 화면 밖에서 오는 글은 마크다운으로 읽는다

에이전트의 답은 마크다운이다.
`react-markdown` 과 `remark-gfm` 으로 그려 표와 목록과 코드 블록이 제 모양으로 보이게 한다.

**들어온 HTML 을 그대로 그리지 않는다.** `react-markdown` 의 기본값이 그렇고 그 기본값을 바꾸지 않는다.
에이전트의 답은 우리가 쓴 글이 아니라 모델이 만든 글이고, 도구가 읽어 온 남의 글이 섞일 수 있다.

### 화면을 검증하는 방법

테스트가 둘이다. 무엇을 확인하느냐가 갈린다.

| 위치 | 확인하는 것 | 띄우는 것 |
| --- | --- | --- |
| `test/e2e/` | Control Plane 의 응답과 권한과 기록 | 가짜 Hermes, 백엔드, 데이터베이스 |
| `test/browser/` | 화면의 배치와 동작 | 위에 더해 웹과 Chromium |

브라우저 테스트는 `mobile` 과 `desktop` 두 폭에서 돈다. 각각 390px 와 1280px 다.

**운영 코드에 시험용 문을 만들지 않는다.**
로그인은 테스트가 NextAuth 세션 쿠키를 직접 만들어 넣는다.
테스트일 때만 켜지는 우회를 두면 그 문이 운영에도 남는다.

## 사용자을 더할 때

셋을 해야 그 사람이 쓸 수 있다.

1. Google 동의 화면의 테스트 사용자에 그 사람 주소를 더한다
2. `ASSISTANT_ALLOWED_EMAILS` 에 그 주소를 더한다
3. 그 사람의 Hermes profile 을 만들고 에이전트로 바인딩한다

세 번째는 홈서버 작업이라 비공개 저장소 `fos-home-infra` 가 절차를 갖는다.
profile 을 사람마다 나누는 근거는
[`adr/ADR-002-profile은-나누고-ai-계정은-가족이-함께-쓴다.md`](adr/ADR-002-profile은-나누고-ai-계정은-가족이-함께-쓴다.md) 에 있다.

## 비밀값을 두는 곳

| 값 | 두는 곳 |
| --- | --- |
| 사용자의 AI credential | 그 사람의 Hermes profile `.env` |
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

- 개인과 가족 공용 Memory
- 실행 사건 저장과 실행 나무 화면
- 여러 에이전트를 잇는 실행 구조
- 실제 청구액과 환산액을 나눠 보는 비용 분석

SSE 중계와 스트리밍은 끝났다.
`HermesRunEventStream` 이 받아 `ChatService.stream` 이 화면으로 중계한다.
