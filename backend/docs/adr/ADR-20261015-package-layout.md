## ADR-20261015 / package-layout: 기능은 하위 기능과 역할 두 축으로 나누고 관리자 전용 화면은 admin 으로 모은다

- **status**: `accepted`
- Date: 2026-10-15
- **보완하는 결정**: [ADR-068](ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md) 이 정한 최상위 패키지의 층 순서는 그대로 둔다.
  이 결정은 그 안쪽의 배치와 기능 안의 층 방향을 정하고, `admin` 을 그 순서의 맨 위에 더한다.

### 결정

**기능 패키지는 `<기능>[.<하위 기능>].<역할>` 로 나눈다.
하위 기능은 이동 전 `application` 직하 클래스가 30개 이상인 기능만 둔다. 이동 뒤에는 어느 `application` 도 25개를 넘지 않게 규칙으로 지킨다.
관리자 전용 화면과 API 는 루트 `admin.<하위 기능>` 으로 모은다.**

역할은 일곱 가지다.

| 역할 | 패키지 | 두는 것 |
| --- | --- | --- |
| presentation | `presentation` | 컨트롤러와 `*Dtos`, 웹 계층의 어댑터 |
| application | `application` | Spring 빈(서비스, 컴포넌트)만. 빈이 소유한 package-private 도우미는 허용한다 |
| dto | `application.dto` | 서비스가 주고받는 record, enum, 값 클래스. 옛 `application.model` 도 이 이름으로 통일한다 |
| port | `application.port` | 구현을 바깥에 두는 인터페이스와 그 인터페이스가 던지는 예외. infra 가 구현하는 것과 위 기능의 `application` 이 구현하는 것을 함께 둔다 |
| config | `config` | `@ConfigurationProperties` 와 그 값 record |
| domain | `domain` | 엔티티, 영속 ID, 도메인 정책, 저장 enum. 기존처럼 `domain.entity` 와 `domain.type` 하위 패키지를 둘 수 있다 |
| infra | `infra` | 저장소와 외부 시스템의 어댑터 |

`application` 아래에는 `dto` 와 `port` 만 하위 패키지로 둔다. `model`, `toolset`, `execution` 같은 이름은 쓰지 않는다.
`domain` 에 이미 있는 port(`BrowserRuntime`, `PriceCatalog` 처럼 `infra` 가 구현하고 엔티티나 변환기도 부르는 인터페이스)는 이번에 옮기지 않는다.

### 하위 기능

**나눈 기능은 루트에 역할 패키지를 두지 않고, 하위 기능마다 위 역할 패키지를 갖는다.**
하위 기능 사이의 의존은 아래에서 위로 한 방향이다. 순서는 아래 표가 정하고 위 하위 기능만 아래를 쓴다.
거꾸로 써야 하면 아래 하위 기능의 `application.port` 에 인터페이스를 두고 위에서 구현한다.

| 기능 | 하위 기능(아래에서 위로) |
| --- | --- |
| `chat` | `conversation`, `delivery`, `modelchoice`, `attachment`, `artifact`, `contentdraft`, `starter`, `turn`, `nextturn`, `recovery`, `purge` |
| `connector` | `connection`, `action`, `financial`, `contentdraft`, `approval` |
| `proactive` | `check`, `evaluation`, `autonomy`, `loop`, `surface`, `feedback` |
| `usage` | `pricing`, `execution`, `subagent`, `tree`, `summary` |
| `skill` | `usage`, `library`, `package` |

하위 기능이 맡는 일은 아래와 같다.

| 하위 기능 | 맡는 일 |
| --- | --- |
| `chat.conversation` | 대화와 메시지의 저장, 대화 접근 확인, 목록 읽기, 실행 질문, 메모리 사용 표시, 첫 응답 지연 집계 |
| `chat.delivery` | 위임과 예약이 낸 결과를 대화에 전하는 기록과 재시도 상태 |
| `chat.modelchoice` | 모델과 effort 선택, 모델 단계, 숨김, 에이전트 기본 모델 |
| `chat.attachment` | 사진 첨부의 저장과 검사, 원본 조회, 첨부 관찰 |
| `chat.artifact` | 에이전트가 만든 결과물 파일 |
| `chat.contentdraft` | 원고 초안과 snapshot |
| `chat.starter` | 추천 질문 |
| `chat.turn` | 한 번의 대화 turn 의 실행과 중지, 사건 스트림, 대화 API 의 조립 |
| `chat.nextturn` | 응답 중에 보낸 메시지와 위임 결과로 다음 turn 열기 |
| `chat.recovery` | 재기동 뒤 남은 실행의 정리 |
| `chat.purge` | 지운 대화의 지연 정리 |
| `connector.connection` | 커넥터 카탈로그, 사용자별 연결, 바인딩, 설치와 확인, 도구 정책 값 |
| `connector.action` | 도구 호출 한 줄과 허락 기록, 판정 결과 값 |
| `connector.financial` | 금융 준비, 주문 상한, 실행 보증(claim, ticket, guard) |
| `connector.contentdraft` | 원고 작업의 시작과 저장 승인 |
| `connector.approval` | 도구 호출의 판정, 승인 흐름의 조율, 승인 API |
| `proactive.check` | 먼저 살펴보기 turn, 결과 계약의 검사, 문제 후보의 저장, 발견 반응 |
| `proactive.evaluation` | 문제 후보의 가치 평가와 판단 provider |
| `proactive.autonomy` | 행동 수준의 판정과 자동 실행 |
| `proactive.loop` | 매일 깨우기와 루프 설정, 지운 에이전트의 정리 참여 |
| `proactive.surface` | 사용자에게 보이는 문제 후보의 읽기 모델과 그 API |
| `proactive.feedback` | 판단 피드백의 replay 읽기 모델 |
| `usage.pricing` | 가격표와 비용 환산 |
| `usage.execution` | 실행 기록, 실행 사건, 사용자 실행 한도 |
| `usage.subagent` | native 하위 에이전트 사용량의 재조회와 원장 |
| `usage.tree` | 실행 트리와 루트 실행 목록 |
| `usage.summary` | 월 합계와 축별 집계 |
| `skill.usage` | 스킬 호출 이력과 집계 |
| `skill.library` | 올린 스킬의 읽기와 쓰기, 버전 디렉터리, Hermes 에 게시 |
| `skill.package` | 스킬 zip 묶음의 받기와 검사, 미리보기 |

나누지 않은 기능(`agent`, `memory`, `attention`, `task` 등)은 루트에 역할 패키지를 둔다.
`agent` 의 `application.toolset`, `purge`, `admin` 하위 패키지는 없애고 평평하게 만든다. 관리자 쪽은 `admin.agent` 와 `admin.toolset` 으로 간다.
`hermes` 는 클라이언트의 인터페이스를 `application.port` 에, 구현을 `infra` 에, 값을 `application.dto` 에 둔다.

### admin

**관리자 전용 화면과 API 만 `admin` 이다. 사용자 기능 안에서 권한에 따라 보이는 범위만 다른 것은 그 기능에 남긴다.**

`admin.<하위 기능>` 은 `application`(필요할 때 `application.dto`)과 `presentation` 만 둔다. 엔티티, 저장소, 설정을 갖지 않는다.
하위 기능은 `agent`, `toolset`, `model`, `connector`, `people`, `browser`, `workspace`, `usage`, `attention`, `proactive`, `memory`, `skill`, `mcp` 이고, 관리자 화면의 한 묶음이 기능의 한 패키지에 대응한다.

- **기능은 admin 을 모른다.** `admin` 은 기능의 `application` 과 `application.dto`, `domain.type` 만 부른다. 기능의 `presentation`, `infra`, 엔티티는 쓰지 않는다.
- **`CurrentUserProvider.requireAdmin` 은 admin 안에서만 부른다.** 사용자 API 와 관리자 API 가 한 컨트롤러에 섞여 있으면 관리자 쪽을 `admin` 의 컨트롤러로 나눈다.
- **admin 전용 응답 모양은 admin 의 `*Dtos` 가 갖는다.** 기능의 `*Dtos` 를 가져다 쓰지 않는다.
- 관리자 화면에서만 쓰는 읽기 질의도 기능의 `application` 이 갖고, `admin` 은 그것을 부른다.

`admin` 은 최상위 순서의 맨 위다. 어느 패키지도 `admin` 을 import 하지 않는다. 나중에 떼어 내려면 `admin` 만 가져가면 된다.

### 층 방향의 개정

`LAYER_DIRECTION` 은 `infra` 가 `application` 을 쓰지 못하게 막아 왔다. 그래서 `infra` 가 구현하는 인터페이스는 `domain` 에 둘 수밖에 없었다.
`application.port` 를 두려면 `infra` 가 `application.port` 와 `application.dto` 를 쓸 수 있어야 한다.

| 층 | 쓸 수 있는 층 |
| --- | --- |
| presentation | application, application.port, application.dto, domain, config |
| application | application.port, application.dto, infra, domain, config |
| application.port | application.dto, domain |
| application.dto | application.port, domain |
| infra | application.port, application.dto, domain, config |
| domain | domain |
| config | domain, application.dto, application.port, infra |

`application`(빈)을 쓸 수 있는 층은 `presentation` 뿐이다. `infra`, `domain`, `config`, `application.port`, `application.dto` 는 `application` 의 빈을 쓰지 않는다.

### 규칙으로 거는 것과 거는 방식

**ArchUnit 으로 판정할 수 있는 것만, 예외 없이 지킬 수 있는 모양으로 규칙에 둔다.**
지금 위반은 기준 파일로 얼리고 클래스를 옮길 때마다 줄인다([ADR-042](../../../docs/adr/ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md)).
기준 파일의 대조는 패키지 이름을 무시하고 단순 클래스 이름으로 한다. 클래스를 옮겨도 기준이 흔들리지 않고, 옮긴 PR 이 기준을 다시 얼리지 않는다.
실패 메시지에는 「어디에 두라」 를 적는다.

규칙으로 판정하는 것은 다음이다.

- `application` 직하에는 빈만 둔다. 인터페이스는 `application.port` 로, record 와 enum 은 `application.dto` 로 간다.
- `application` 아래 하위 패키지는 `dto` 와 `port` 뿐이다. `port` 에는 인터페이스(와 그 예외)만, `dto` 에는 빈과 인터페이스가 없다.
- `@ConfigurationProperties` 는 `config` 에 둔다.
- `application` 직하 클래스는 25개를 넘지 않는다.
- 운영 클래스의 단순 이름은 겹치지 않는다. 기준 파일 대조가 그것에 기댄다.
- 나눈 기능은 루트에 역할 패키지가 없고, 하위 기능은 위 순서의 아래쪽만 쓴다.
- `admin` 의 모양, 의존 방향, `CurrentUserProvider.requireAdmin` 을 부르는 자리, 모든 관리자 엔드포인트가 `requireAdmin` 을 부르는 것.

규칙의 판정과 실패 메시지는 `PackageLayoutRules.java` 가 갖고, 까닭과 예외는 [코드 규칙 목록](../../../docs/code-rules/INDEX.md) 의 rule-70 부터 rule-84 가 갖는다. 얼림 여부는 [`code-architecture.md`](../code-architecture.md) 의 「패키지 배치 규칙」 표가 갖는다.

판정할 수 없는 것은 문서와 `claude-review` 의 기준(`.github/workflows/code-review-prompt.txt`)에 둔다.

- 하위 기능을 나누는 경계가 업무에 맞는지, 새 하위 기능의 이름이 적절한지
- 한 서비스의 일이 관리자 전용인지 권한에 따라 범위만 다른 것인지
- `application.dto` 의 타입이 정말 서비스의 입출력인지
- package-private 도우미가 빈 하나에 딸려 있는지

### 맥락

`chat.application` 에 클래스 119개가, `connector.application` 에 64개가 직하로 있다. `proactive` 에 42개, `usage` 에 32개, `skill` 에 30개가 있다.
한 기능의 한 패키지가 이만큼 크면 어느 클래스가 어느 일을 맡는지 이름으로만 찾아야 하고, 하위 일 사이의 의존은 검사할 수 없다.

2026-10-15 에 클래스 의존을 뽑아 하위 기능으로 나눠 보니 다음이 드러났다.

- 클래스 수준에서 서로 얽힌 곳은 거의 없다. 순환으로 묶인 클래스는 `chat` 의 다섯 개(`ChatService`, `ChatTurnRouting`, `ChatTurnRunner`, `ChatConversationQueries`, `ChatDeliveryTurns`)와 `connector` 의 쌍 셋뿐이다. 그래서 하위 기능은 같은 흐름을 한 곳에 두는 쪽으로 나눈다.
- 하위 기능 사이의 역방향 의존은 `chat` 2, `connector` 4, `proactive` 1, `skill` 2 로 모두 9건이다.
- `application` 직하의 public 클래스 가운데 빈이 아닌 것이 28개다(정책, 해시, 값 함수).
- 관리자 컨트롤러 13개가 기능의 `*Dtos` 를 쓰고, `AgentAdminService` 는 저장소와 엔티티를 직접 쓴다.
- 사용자 API 와 관리자 API 가 섞인 컨트롤러가 셋이다(`AgentToolController`, `ToolsetRequestController`, `UsageController` 의 축별 집계 한 경로).
- 관리자 전용 연산을 기능 서비스가 한 번 더 확인하는 곳이 `AgentService` 와 `ConnectorConnectionService` 에 있다. 이 방어적 확인은 규칙이 막지 않고 그대로 둔다. `MemoryService` 의 같은 이름 도우미는 그룹 공용 Memory 의 쓰기 권한 판정이다.

### 대안 기각

- **역할을 위에 두는 구조**(`application/chat`, `domain/chat` 처럼 층이 최상위): 기능 하나를 고칠 때 파일이 일곱 군데로 흩어지고, 기능 단위 순서를 정한 ADR-068 과 어긋난다.
- **패키지는 그대로 두고 클래스 이름 접두사로 구분**: `ChatTurn*` 같은 약속은 검사할 수 없고, 한 패키지의 크기가 줄지 않는다.
- **하위 기능을 Gradle 모듈로 분리**: 하위 기능 사이의 역방향 의존 9건과 엔티티 스캔, 테스트 구성이 정리되기 전에는 빌드만 무거워진다. 패키지 경계를 먼저 굳히고, `admin` 처럼 떼어 낼 수 있는 곳부터 나중에 모듈로 뺀다.
- **`admin` 을 기능마다 `presentation.admin` 으로 둔다**: 기능이 admin 을 모르게 할 수 없고 `requireAdmin` 이 기능 안에서 불린다. 떼어 낼 때 흩어진 것을 다시 모아야 한다.
- **인터페이스를 구현 위치로 나눠 `port` 에는 `infra` 구현만 둔다**: 같은 인터페이스를 위 기능이 구현하는 경우와 구분하는 것은 규칙으로 판정할 수 없다. 인터페이스는 모두 `port` 다.
- **port 를 `domain` 에 둔다**(`crypto` 방식): `LAYER_DIRECTION` 때문에 생긴 우회다. `domain` 은 엔티티와 정책만 갖게 하고, 방향 규칙을 고친다.
- **`application.model` 을 유지한다**: 사용자가 `dto` 로 통일하기로 했다.
- **하위 기능 사이의 순환을 허용한다**: 하위 기능의 경계가 뜻을 잃는다. 지금 있는 9건은 기준 파일에 얼리고 줄인다.
- **한 번에 전체를 옮긴다**: PR 크기 상한(운영 코드 1500줄)을 넘고 리뷰할 수 없다. 기능과 하위 기능 단위로 나눠 직렬로 머지한다.
- **하위 기능을 25개 기준 없이 모든 기능에 둔다**: 열 개 안팎인 기능까지 쪼개면 패키지만 늘고 찾기 쉬워지지 않는다.

### 결과

- 얻는 것:
  - 한 하위 기능의 `application` 은 스무 개 안팎이다. 가장 큰 `chat.turn.application` 이 21개다.
  - 하위 기능 사이와 `admin` 의 방향이 검사로 판정되고, 위반은 기준 파일의 줄 수로 보인다.
  - 관리자 쪽을 한 패키지에서 훑으면 어떤 API 가 관리자 전용인지 안다.
  - `application.dto` 와 `application.port` 이름이 모든 기능에서 같다.
- 감당할 것:
  - 이동 PR 이 15개이고 앞뒤의 규칙, 선행 분리, 마무리까지 모두 18개 PR 이다. 이동 PR 은 많은 파일의 `import` 를 바꾸므로 다른 작업의 PR 과 충돌한다. 이동 PR 은 직렬로 짧게 연다.
  - 한 번에 끊지 못한 것은 기준 파일에 남는다. 하위 기능 사이의 역방향 의존 9건, `application` 직하의 public 비빈 클래스 32개, `admin` 이 기능의 저장소와 엔티티와 domain 값을 쓰는 14건이다.
  - package-private 이던 클래스 11개는 하위 기능이 갈리며 public 이 된다.
  - JPQL 과 로거 이름의 전체 이름 문자열은 컴파일이 잡지 못한다. 이동하는 클래스가 든 열한 개 파일을 이동 PR 이 직접 고친다.
  - 파일 길이 기준 목록, 위험 라벨의 경로, 문서의 경로 표기를 이동 PR 이 함께 고친다.
  - 하위 기능 이름은 업무 용어라 나중에 바뀔 수 있다. 바꿀 때는 한 하위 기능을 통째로 옮기는 PR 하나다.

### 이동 순서

각 PR 은 이동만 한다. 쓰지 않는 코드 정리(서비스 토큰, `ResearchAndBuildFlow`, 옛 커넥터 호환, 작은 것 묶음)가 먼저 머지된 뒤에 시작한다.
동작을 바꾸는 선행 분리(관리자 엔드포인트의 컨트롤러 분리)는 그 앞의 별도 PR 이다.

1. 규칙과 기준 파일, 그리고 문서 개정. 코드를 옮기지 않는다. 문서는 이동이 끝나기 전까지 「이동 진행 중」 알림을 단다.
2. 사용자 API 와 관리자 API 가 섞인 컨트롤러를 나누고, 관리자 컨트롤러가 쓰는 응답 모양을 admin 용 `*Dtos` 로 가른다. 제자리에서 한다.
3. 관리자 컨트롤러와 서비스를 `admin` 으로 옮긴다.
4. 작은 기능 열네 개를 두 묶음으로 옮긴다. `user`, `crypto`, `followup`, `notification`, `model`, `context`, `feedback`, `people`, `task`, `workspace` 가 먼저이고, `browser`, `attention`, `orchestration`, `mcp` 가 다음이다.
5. `memory` 와 `agent`, 그다음 `hermes`.
6. `skill`, 그다음 `usage`.
7. `proactive`. `check` 가 먼저이고 나머지 다섯 하위 기능이 다음이다.
8. `connector`. `connection` 이 먼저이고 나머지 네 하위 기능이 다음이다.
9. `chat`. `conversation`, `modelchoice`, `delivery`, `starter` 가 먼저이고, 그다음 `attachment` 와 `artifact`, 그다음 `contentdraft`, 마지막으로 `turn`, `nextturn`, `recovery`, `purge` 다.
10. 기준 파일의 남은 줄을 확인하고, `admin` 규칙의 옛 이름 허용과 문서의 알림을 지운다.

### 적용 범위

`backend/src/main/java/com/bifos/assistant` 의 최상위 패키지 가운데 `shared` 를 뺀 것, 그리고 새 `admin`.
`shared` 의 패키지 구성과 `LivePropertiesConfig` 는 이번에 바꾸지 않는다.
패키지마다 맡는 책임은 [`backend/docs/code-architecture.md`](../code-architecture.md) 가, 검사는 `ArchitectureRules.java` 가 갖는다.
