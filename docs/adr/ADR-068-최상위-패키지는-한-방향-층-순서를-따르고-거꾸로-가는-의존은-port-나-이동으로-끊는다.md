## ADR-068: 최상위 패키지는 한 방향 층 순서를 따르고 거꾸로 가는 의존은 port 나 이동으로 끊는다

- **status**: `accepted`. S1, S3 이 구현됐다. C1 부터 C7, S2 는 아직 구현 전이다
- **결정**: backend 의 최상위 패키지를 아래에서 위로 다음 순서에 둔다. 위 패키지는 아래 패키지를 쓰고, 아래 패키지는 위 패키지를 import 하지 않는다.
  `hermes`, `user`, `model`, `agent`, `skill`, `usage`, `memory`, `context`, `chat`, `orchestration`, `mcp`, `people`, `connector` 순이다. `shared` 는 이 순서 밖이고 어느 패키지도 쓰지 않는다.
  이 순서에서 거꾸로 가는 의존은 아래 셋 가운데 하나로 끊는다.
  - 타입을 아래 패키지로 옮긴다.
  - 아래 패키지의 `application` 에 port 인터페이스를 두고 위 패키지의 `application` 이 구현한다.
  - 조율하는 일을 위 패키지의 서비스로 옮긴다.

  끊는 간선 12 개와 방법이다.

  | 간선 | 방법 |
  | --- | --- |
  | `user` 가 `people` 을 씀 | `user` 에 port 를 두고 `people` 이 구현한다 |
  | `user` 가 `agent` 를 씀 | 첫 에이전트 만들기를 `people` 로 옮긴다(C5) |
  | `agent` 가 `people` 을 씀 | port 둘을 두고, 기본 비용 방식 설정은 `agent` 로 옮긴다(C5) |
  | `agent` 가 `skill` 을 씀 | `agent` 에 port 를 두고 `skill` 이 구현한다 |
  | `agent` 가 `orchestration` 을 씀 | `agent` 에 port 를 두고 흐름 등록부가 구현한다 |
  | `agent` 가 `chat` 과 `usage` 를 씀 | 추천 질문 생성을 `chat` 으로 옮긴다(C1) |
  | `usage` 와 `memory` 가 `chat` 을 씀 | 모델 선택 타입을 `model` 로 옮기고(C2), 실행 기록은 대화에서 읽던 값만 받고, 대화의 공개 식별자는 port 로 받는다 |
  | `skill` 이 `chat` 을 씀 | `skill` 에 port 를 두고 `chat` 이 구현한다 |
  | `chat` 이 `orchestration` 을 씀 | 흐름의 계약과 session 값과 위임 종료 사건을 `chat` 으로 옮기고(C3), 위임 결과 자르기는 port 로 받는다(C4) |
  | `memory` 가 `context` 를 씀 | `memory` 에 port 를 두고 `context` 가 구현한다 |

  끊는 자리마다 정한 것은 아래와 같다.

  | 번호 | 정한 것 |
  | --- | --- |
  | C1 | 추천 질문 생성은 `agent` 에서 `chat` 으로 옮긴다. 주소는 그대로 둔다 |
  | C2 | `ModelChoice` 와 `ModelTier` 는 새 최상위 패키지 `model` 의 `domain` 에 둔다. 이번에는 타입만 옮기고 서비스는 `chat` 에 둔다 |
  | C3 | 흐름의 계약(`Flow`, `FlowRegistry`)은 `chat` 이 갖고 구현은 `orchestration` 이 갖는다 |
  | C4 | 재기동 뒤 남은 실행을 정리하는 일은 `chat` 에 두고, 위임 결과를 자르는 일만 port 로 받는다 |
  | C5 | profile 만들기는 `people` 이 갖고 `agent` 는 port 로 부른다. 첫 로그인의 첫 에이전트 만들기는 `user` 에서 `people` 로 옮기고 같은 트랜잭션에서 동기로 부른다 |
  | C6 | `ChatService` 는 이 작업에서 나누지 않는다 |
  | C7 | 순환이 없어진 뒤 이 순서를 ArchUnit 규칙으로 검사한다 |
  | S1 | `UserRole` 은 `shared.domain.type` 에 둔다 |
  | S2 | `ControlPlaneJwtFilter` 는 `shared.auth` 의 port 로 사용자를 받고 `user.application` 이 구현한다 |
  | S3 | `SecurityConfig` 는 `shared.auth` 의 필터 타입으로 MCP 토큰 필터를 받고 `mcp` 가 그 타입을 구현한다 |

- **맥락**: `TOP_LEVEL_PACKAGES_FREE_OF_CYCLES` 의 기준에 순환 간선 38 개가 얼려 있었다([ADR-042](ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md)).
  `agent`, `chat`, `context`, `mcp`, `memory`, `orchestration`, `people`, `skill`, `usage`, `user` 열 개가 한 순환 덩어리였다.
  2026-10-03 에 import 로 간선을 뽑아 보니 기준에 든 38 개 가운데 위 순서에서 거꾸로 가는 간선은 12 개이고 클래스 참조로는 43 개였다. 12 는 끊어야 하는 간선의 최소 수다.
  `model` 은 그때 없던 패키지다. C2 로 만들며 `user` 바로 위에 둔다.
  기준에 없는 거꾸로 간선이 하나 더 있다. `hermes` 가 `usage` 의 길이 상한 상수를 읽는다. 컴파일 때 값으로 바뀌는 상수라 규칙이 보지 못한다. 상한을 인자로 받게 고쳐 함께 끊는다.
  원인은 셋으로 모였다. 아래 층이어야 할 `user` 와 `agent` 가 조율을 직접 한다. 값 타입이 쓰는 쪽보다 위 패키지에 있다. 서비스가 다른 패키지의 엔티티를 통째로 받아 값 둘만 읽는다.
  `SHARED_DOES_NOT_DEPEND_ON_DOMAINS` 의 기준 17 줄은 `shared` 가 `user` 와 `mcp` 를 쓰는 것이었다.
- **대안 기각**:
  - `user` 를 `agent` 위에 둔다. 참조는 하나 적지만 `user` 가 첫 로그인에 에이전트를 만드는 구조가 굳고, `chat`, `mcp`, `people` 이 모두 `user` 를 아래로 본다.
  - `chat` 을 `orchestration` 위에 둔다. `orchestration` 이 `chat` 을 쓰는 참조 23 개를 끊어야 한다. 반대 방향은 10 개다.
  - `ModelChoice` 와 `ModelTier` 를 `usage.domain` 에 둔다. 옮길 것이 가장 적지만 모델 단계와 숨김과 기본 모델이 `usage` 의 것으로 읽힌다. 이 값들은 따로 커지는 영역이다.
  - `CurrentUser` 가 역할 대신 관리자 여부만 갖는다. 생성 호출 120여 곳이 바뀌고 권한 문자열을 만드는 자리가 역할 이름을 다시 적어야 한다.
  - `SecurityConfig` 를 새 최상위 패키지로 뺀다. 필터 하나를 받으려고 패키지를 하나 만든다.
  - 저장소와 store 같은 `infra` 클래스가 다른 패키지의 port 를 바로 구현한다. `LAYER_DIRECTION` 은 패키지를 넘어서도 `infra` 가 `application` 을 쓰는 것을 막는다.
- **결과**:
  - 얻는 것:
    - 한 패키지를 고칠 때 함께 읽어야 하는 범위가 그 아래 패키지로 정해진다.
    - 새 의존이 어느 방향이어야 하는지가 순서 하나로 답해진다.
    - 스키마와 저장 값은 바뀌지 않는다. 다른 패키지의 엔티티를 관계로 참조하는 곳은 `connector` 의 한 곳뿐이고 순환 밖이다.
  - 감당할 것:
    - port 인터페이스가 열 개 안팎 생긴다. 호출을 따라 읽을 때 구현을 한 번 더 찾아야 한다.
    - 새 최상위 패키지를 만들 때마다 순서의 어느 자리인지 정해야 한다.
    - `people` 은 MCP 토큰을 발급하므로 `mcp` 보다 위에 있어야 한다.
    - `chat` 이 흐름의 계약과 추천 질문까지 가져 더 커진다.
- **적용 범위**: `backend/src/main/java/com/bifos/assistant` 의 최상위 패키지. 패키지마다 맡는 책임은 [`backend/packages.md`](../backend/packages.md) 가, 검사는 `ArchitectureRules.java` 가 갖는다.
  패키지 안의 층 방향(`presentation`, `application`, `infra`, `domain`)은 이 결정과 따로이고 `LAYER_DIRECTION` 이 검사한다.
