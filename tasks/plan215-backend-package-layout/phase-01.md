# Phase 01. 구조 규칙과 기준 파일

**Execution profile**: standard

## 목표

이동 전에 새 배치 규칙을 `ArchitectureRules` 에 넣고 지금 있는 위반을 기준 파일에 얼린다. 클래스는 옮기지 않는다.
이후 이동 PR 마다 기준 파일의 줄이 줄어드는 것이 진행 표시다. 실패 메시지는 「어디에 두라」 를 적는다.

**범위 외**: 클래스 이동(phase 03 부터), 컨트롤러 분리(phase 02).

이 phase 는 PR 하나(1-1)다.

## 컨텍스트

- 규칙은 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java`, 최상위 순서는 같은 디렉터리의 `TopLevelPackageOrder.java`, 기준 파일은 `backend/config/archunit/store/`, 설정은 `backend/src/test/resources/archunit.properties` 에 있다.
- 기준 파일을 갱신하는 방법은 `backend/docs/code-architecture.md` 의 「구조 규칙의 기준 파일」 이 갖는다.
- 결정과 근거는 `backend/docs/adr/ADR-20261015-package-layout.md` 다. 층 표와 하위 기능의 순서가 거기 있다.
- 규칙의 `as(...)` 설명이 기준 파일의 열쇠다. 설명을 바꾸면 그 규칙을 다시 얼려야 한다.
- 이 phase 는 쓰지 않는 코드 정리가 머지된 뒤에 시작한다. 정리가 지운 클래스(`ServiceToken*`, `Flow` 와 `FlowRegistry`, `MemoryProposer`, `ModelTierSeedImporter`)는 규칙 시작 건수에 들지 않는다.

## 의도 메모

- **메시지에 개수를 적지 않는다.** 개수가 바뀌면 기준 줄의 문구가 달라져 기준이 흔들린다. 패키지 단위 규칙은 패키지마다 한 줄, 클래스 단위 규칙은 클래스마다 한 줄이다.
- **기준 대조는 패키지 이름을 무시한다.** 이동 PR 이 클래스를 옮겨도 위반 줄의 전체 이름이 바뀐다는 까닭으로 기준을 다시 얼릴 필요가 없게 하려는 것이다. 이 대조는 운영 클래스의 단순 이름이 유일하다는 것에 기댄다. 지금 겹치는 이름은 없고, 규칙 11 이 앞으로도 겹치지 않게 지킨다.
- 하위 기능 패키지가 아직 없을 때는 순서 규칙과 admin 규칙이 아무것도 보지 않는다. 이동 PR 이 하위 기능 패키지나 `admin` 패키지를 만들 때 생기는 위반은 그 PR 이 기준에 더하고, 더할 줄의 목록은 phase 마다 적었다. 목록 밖의 새 위반은 더하지 않고 고친다.
- 기존 위반을 고치는 것은 이 phase 의 일이 아니다. 얼리는 것까지만 한다.

## 작업 항목

### 1. 기준 대조의 패키지 무시

`archunit.properties` 의 `freeze.lineMatcher` 에 `ViolationLineMatcher` 구현을 등록한다. 구현은 위반 줄 둘에서 `com.bifos.assistant.` 로 시작하는 전체 이름의 패키지 부분을 지워 단순 클래스 이름만 남기고, 기본 matcher 와 같은 방식(줄 번호와 숫자 무시)으로 비교한다.
`com.bifos.assistant.` 접두의 이름 가운데 대문자로 시작하는 마지막 마디(클래스 이름)만 남긴다. 패키지 이름만 있는 줄(규칙 2, 7, 8 의 `<패키지>`)은 건드리지 않는다. 마지막 마디만 남기면 서로 다른 기능의 `application` 이 겹쳐 새 위반이 기존 줄에 가려지기 때문이다.
이 구현에 단위 테스트를 붙인다. 같은 줄의 전체 이름만 다르면 같다고 하고, 단순 이름이 다르면 다르다고 하고, 패키지만 있는 줄은 정확히 같아야 같다고 한다.

### 2. 새 규칙

규칙은 `ArchitectureRules` 에 상수로 둔다. 이름과 판정, 실패 메시지, 얼림은 아래와 같다. 메시지의 `<…>` 는 실제 이름으로 채운다.

**규칙 1. `APPLICATION_ROOT_HOLDS_ONLY_BEANS`**

- 판정: 패키지 이름이 `application` 으로 끝나는 곳의 최상위 타입 가운데 아래 넷은 위반이다. `@ConfigurationProperties` 가 붙은 타입은 규칙 4 가 다루므로 뺀다. package-private 클래스와 중첩 타입은 보지 않는다.
  - 인터페이스(`sealed` 인터페이스도 포함하며, 값이면 `application.dto` 로 간다)
  - record
  - enum
  - public 이면서 `@Service` 나 `@Component` 가 아닌 클래스
- 메시지:
  - 인터페이스: `<전체 이름> 는 인터페이스다. application 직하에는 빈만 둔다. 구현을 바깥(infra 나 위 기능의 application)에 두는 인터페이스는 <기능>.<하위 기능>.application.port 로 옮긴다.`
  - record 와 enum: `<전체 이름> 는 값이다. application 직하에는 빈만 둔다. 서비스가 주고받는 값은 <기능>.<하위 기능>.application.dto 로 옮긴다.`
  - public 비빈: `<전체 이름> 는 public 클래스인데 Spring 빈이 아니다. 빈으로 만들거나, 같은 패키지에서만 쓰면 package-private 으로 낮추거나, 정책이나 값 함수면 domain 으로 옮긴다.`
- 얼림: 예. 정리 전 건수는 인터페이스 33, record 73, enum 3, public 비빈 28 이다(`@ConfigurationProperties` record 21개는 규칙 4 가 맡는다). 이동 중에 public 으로 올라가는 비빈 도우미 셋(`SkillInputRules`, `ConnectorActionDetails`, `ConnectorActionSignals`)과 `application` 직하로 처음 오는 `ResultHeader` 가 줄을 더하고, 정책 함수 둘(`InternalValuePolicy`, `ToolDetailPolicy`)이 `domain` 으로 가며 줄이 준다. 이동이 끝나면 public 비빈 30개가 남는다(이 계획 밖에서 줄인다).

**규칙 2. `APPLICATION_SUBPACKAGES_ARE_DTO_AND_PORT`**

- 판정: `application` 아래 하위 패키지 가운데 `dto` 와 `port` 가 아닌 것에 클래스가 있으면 패키지마다 위반 한 줄이다.
- 메시지: `<패키지> 는 application 의 하위 패키지로 허용되지 않는다. application 아래에는 dto 와 port 만 둔다. model 은 dto 로, 서비스는 application 으로, 인터페이스는 port 로 옮긴다.`
- 얼림: 예. 18개 패키지다(`attention`, `followup`, `memory`, `skill`, `chat`, `workspace`, `usage`, `feedback`, `browser`, `people`, `task`, `connector`, `proactive` 의 `application.model`, `agent` 의 `application.model` 과 `application.toolset`, `agent.admin.application.model`, `agent.purge.application.model`, `connector.application.execution`).

**규칙 3. `PORT_PACKAGE_HOLDS_ONLY_INTERFACES` 와 `DTO_PACKAGE_HOLDS_ONLY_VALUES`**

- 판정: `application.port` 의 최상위 타입은 인터페이스이거나, 그 패키지의 인터페이스가 던지는 예외 클래스다. `application.dto` 의 최상위 타입은 Spring 빈, `@Entity`, 컨트롤러가 아니고, `sealed` 가 아닌 인터페이스도 아니다.
- 메시지: `<전체 이름> 는 port 패키지에 있지만 인터페이스가 아니다. 값은 application.dto 로, 빈은 application 으로 옮긴다.` 와 `<전체 이름> 는 dto 패키지에 있지만 값이 아니다. 빈은 application 으로, 인터페이스는 application.port 로 옮긴다.`
- 얼림: 아니오. 새 패키지라 시작 위반이 없다.

**규칙 4. `CONFIGURATION_PROPERTIES_IN_CONFIG`**

- 판정: `@ConfigurationProperties` 가 붙은 타입은 마지막 마디가 `config` 인 패키지에 있다. `shared` 는 보지 않는다.
- 메시지: `<전체 이름> 는 @ConfigurationProperties 인데 config 패키지 밖에 있다. <기능>.<하위 기능>.config 로 옮긴다.`
- 얼림: 예. 정리 전 31개, 정리 뒤 29개다.

**규칙 5. `LAYER_DIRECTION` 개정**

- 판정: 기능을 가로질러 `presentation`, `application`(빈), `application.port`, `application.dto`, `infra`, `domain`, `config` 일곱 층이 ADR 의 「층 방향의 개정」 표대로만 서로를 쓴다. `application`(빈)은 `..application..` 에서 `..application.port..` 와 `..application.dto..` 를 뺀 것이다.
- `as` 설명: `층은 presentation 에서 application 을 거쳐 port, dto, infra, domain, config 로 흐른다. infra 와 domain 은 application 의 빈을 쓰지 않는다. 막힌 의존은 application.port 에 인터페이스를 두고 구현을 infra 에 두어 끊는다.`
- 얼림: 설명을 바꾸므로 다시 얼린다. 이동 전에는 새 층 이름이 없어 위반이 늘지 않는다. 이동 중에 임시로 생기는 줄은 해당 PR 이 더하고 짝이 옮겨지는 PR 이 지운다. 이동이 끝나면 두 줄이 남는다. 하나는 dto 인 `ConnectorExecutionGuardContract` 가 application 의 `ConnectorExecutionSnapshot` 을 쓰는 줄이고, 다른 하나는 dto 인 `CheckedSkillPackage` 가 infra 의 `SkillFilePaths` 를 쓰는 줄이다.

**규칙 6. `SUB_FEATURES_FOLLOW_ORDER`**

- 판정: 순서 상수(`SubFeatureOrder.ORDER`)에 등록된 기능의 클래스는 같은 기능의 자기보다 위 하위 기능의 클래스를 쓰지 않는다. 등록된 기능 아래에 순서에 없는 하위 기능 패키지가 있으면 위반이다.
- 메시지: `<기능>.<하위 기능 A> 의 <A 클래스> 가 위 하위 기능 <기능>.<하위 기능 B> 의 <B 클래스> 를 쓴다. 이 기능의 하위 기능은 아래쪽만 쓴다(순서: <A 가 있는 순서>). 아래 하위 기능의 application.port 에 인터페이스를 두고 위에서 구현하거나, 타입을 아래로 옮긴다.` 와 `<기능>.<하위 기능> 은 순서에 없다. SubFeatureOrder 에 자리를 정해 넣는다.`
- 얼림: 예. 이동 전 시작 건수는 0이고, 이동 PR 이 더하는 9줄은 phase 마다 적었다. 이동이 끝나도 9줄이 남는다.

**규칙 7. `SPLIT_FEATURES_HAVE_NO_ROOT_ROLES`**

- 판정: 순서 상수에 등록된 기능은 루트에 역할 패키지(`application`, `domain`, `infra`, `presentation`, `config` 와 그 하위 패키지)를 갖지 않는다. 패키지마다 위반 한 줄이다.
- 메시지: `<기능>.<역할 패키지> 에 클래스가 남아 있다. <기능> 은 하위 기능으로 나뉜 기능이라 루트에 역할 패키지를 두지 않는다. 클래스는 <기능>.<하위 기능>.<역할> 로 옮긴다. 어느 하위 기능인지는 ADR 의 「하위 기능」 표를 본다.`
- 얼림: 예. 37개 패키지다(`chat` 9, `connector` 10, `proactive` 6, `usage` 6, `skill` 6).

**규칙 8. `APPLICATION_PACKAGE_SIZE`**

- 판정: `application` 직하의 최상위 타입이 25개를 넘으면 그 패키지마다 위반 한 줄이다.
- 메시지: `<패키지> 는 application 직하의 클래스가 상한(25)을 넘었다. 값과 인터페이스는 dto 와 port 로 옮기고, 그래도 넘으면 하위 기능으로 나눈다.`
- 얼림: 예. 이동 전 수로 `chat`, `connector`, `proactive`, `usage`, `skill` 의 `application` 5줄이다. 값과 인터페이스를 옮기면 `usage`(17개)와 `skill`(13개)은 상한 아래로 내려가지만, 두 기능을 하위 기능으로 나누는 것은 이동 전 직하 30개 이상이라는 기준에 따른 결정이다. 줄은 두 기능이 나뉠 때 사라진다.

**규칙 9. admin 의 모양과 방향** (네 규칙)

- `ADMIN_PACKAGE_SHAPE`: `admin.<하위 기능>` 아래에는 `application`, `application.dto`, `presentation` 만 있다. 메시지: `<전체 이름> 는 admin 에 있지만 허용된 패키지가 아니다. admin 은 application 과 presentation 만 갖는다. 엔티티와 저장소와 설정은 기능이 갖는다.` 얼림 아니오.
- `FEATURES_DO_NOT_DEPEND_ON_ADMIN`: `admin` 밖의 클래스가 `admin` 의 클래스를 쓰지 않는다. 메시지: `<전체 이름> 가 admin 을 쓴다. 기능은 admin 을 모른다. 의존을 거꾸로 하거나 공통 값은 기능의 application.dto 에 둔다.` 얼림 아니오.
- `ADMIN_USES_ONLY_FEATURE_APPLICATION`: `admin` 의 클래스는 다른 최상위 패키지의 클래스를 쓸 때 `application`, `application.dto`, `domain.type` 의 것만 쓴다. `shared` 와 `admin` 안은 자유다. 이동이 끝나기 전의 옛 이름인 `application.model` 과 `application.toolset` 도 허용하고, phase 10 이 이 두 허용을 지운다. 메시지: `<admin 클래스> 가 <기능> 의 <패키지> 에 있는 <클래스> 를 쓴다. admin 은 기능의 application 과 application.dto 만 부른다. 필요한 값은 그 기능의 application 이 dto 로 내놓게 한다. 응답 모양은 admin 의 *Dtos 에 둔다.` 얼림: 예. phase 03 이 13줄을 더하고, `agent` 를 옮기는 PR 이 한 줄(`AgentAdminService` 가 port 를 쓴다)을 더해 14줄이 남는다. 기능의 `*Dtos` 를 쓰는 13줄은 phase 02 가 admin 전용 `*Dtos` 를 만들어 없앤다. 줄 목록은 phase 03 에 있다.
- `ADMIN_ENDPOINTS_REQUIRE_ADMIN`: `admin..presentation` 의 `@RequestMapping` 계열 메서드는 모두 `CurrentUserProvider.requireAdmin` 을 부른다. 메시지: `<컨트롤러>.<메서드> 가 requireAdmin 을 부르지 않는다. admin 의 엔드포인트는 첫 줄에서 requireAdmin 을 부른다.` 얼림 아니오(기존 `AGENT_ADMIN_HTTP_CONTRACT` 가 이미 같은 검사를 한다. 그 규칙은 이 규칙이 서면 해당 부분을 지운다).

**규칙 10. `REQUIRE_ADMIN_ONLY_IN_ADMIN`**

- 판정: `CurrentUserProvider.requireAdmin` 을 부르는 호출은 `admin` 과 `shared.auth` 에서만 있다. 서비스 안에서 요청자의 역할을 다시 확인하는 private 도우미는 보지 않는다.
- 메시지: `<전체 이름>.<메서드> 가 CurrentUserProvider.requireAdmin 을 부른다. 관리자 전용 API 는 admin.<하위 기능>.presentation 에 두고 requireAdmin 은 거기서만 부른다. 사용자 기능 안에서 권한에 따라 범위만 다르면 CurrentUser 의 역할로 판정한다.`
- 얼림: 예. 정리 전 17개 컨트롤러의 호출이다. phase 03 이 그 컨트롤러를 `admin` 으로 옮기면 0이 된다.

**규칙 11. `SIMPLE_CLASS_NAMES_ARE_UNIQUE`**

- 판정: 운영 코드의 최상위 클래스의 단순 이름이 겹치지 않는다. 기준 대조가 단순 이름에 기대므로 이 규칙이 그 전제를 지킨다.
- 메시지: `<이름> 가 <전체 이름 A> 와 <전체 이름 B> 에 있다. 기준 파일 대조가 단순 이름에 기대므로 클래스 이름은 운영 코드 전체에서 유일해야 한다. 한쪽의 이름을 바꾼다.`
- 얼림: 아니오. 지금 겹치는 이름이 없다.

### 3. 순서 상수

`SubFeatureOrder` 를 `TopLevelPackageOrder` 옆에 둔다. 기능마다 하위 기능의 순서를 아래에서 위로 적는다. 값은 ADR 의 표와 같다.

| 기능 | 하위 기능(아래에서 위로) |
| --- | --- |
| `chat` | `conversation`, `delivery`, `modelchoice`, `attachment`, `artifact`, `contentdraft`, `starter`, `turn`, `nextturn`, `recovery`, `purge` |
| `connector` | `connection`, `action`, `financial`, `contentdraft`, `approval` |
| `proactive` | `check`, `evaluation`, `autonomy`, `loop`, `surface`, `feedback` |
| `usage` | `pricing`, `execution`, `subagent`, `tree`, `summary` |
| `skill` | `usage`, `library`, `package` |

`TopLevelPackageOrder.ORDER` 는 아래에서 위로 적은 목록이다. 마지막 원소(`workspace` 뒤)에 `admin` 을 더한다. 어느 패키지도 `admin` 을 import 하지 않는다는 서술은 규칙 9 의 `FEATURES_DO_NOT_DEPEND_ON_ADMIN` 이 지킨다.

### 4. 기존 규칙의 처리

| 기존 규칙 | 처리 |
| --- | --- |
| `LAYER_DIRECTION` | 규칙 5 로 개정한다 |
| `FEATURE_CONFIG_PLACEMENT` | 유지한다. `agent.config` 의 위치가 바뀌지 않는다. 나머지 설정은 규칙 4 가 맡는다 |
| `AGENT_ADMIN_APPLICATION_PLACEMENT`, `AGENT_ADMIN_HTTP_CONTRACT` | phase 03 이 전체 이름을 `admin.agent.*` 로 고친다. `AGENT_ADMIN_HTTP_CONTRACT` 의 requireAdmin 검사는 규칙 9 의 `ADMIN_ENDPOINTS_REQUIRE_ADMIN` 이 서면 그 부분을 지운다 |
| `AGENT_PURGE_CONTRACT_PLACEMENT` | phase 05 가 전체 이름을 `agent.application.*`, `agent.application.port.*`, `agent.application.dto.*` 로 고친다. 정리 실행이 구현 기능을 import 하지 않는 방향 검사는 유지한다 |
| `ENTITIES_IN_OWNED_ENTITY_PACKAGE`, `EMBEDDED_IDS_WITH_ENTITIES` | 유지한다. `crypto` 와 `memory` 는 위치가 바뀌지 않는다 |
| `ENUMERATED_FIELDS_USE_DOMAIN_TYPE`, `DOMAIN_TYPE_DEPENDS_ON_NOTHING_ABOVE` | 유지한다. 패턴이 `..domain.type..` 이라 하위 기능 아래에서도 맞는다 |
| `CONTROLLERS_HAVE_NO_NESTED_RECORDS`, `SERVICES_DO_NOT_EXPOSE_NESTED_TYPES` | 유지한다 |
| `TOP_LEVEL_PACKAGES_FOLLOW_LAYER_ORDER`, `TOP_LEVEL_PACKAGES_FREE_OF_CYCLES` | `admin` 을 순서에 더한다 |
| `LIVE_SETTINGS_ONLY_THROUGH_LIVE_PROPERTIES`, `HERMES_TIMEOUTS_ONLY_THROUGH_LIVE_PROPERTIES` | `ArchitectureRules.java` 의 import 가 컴파일로 따라간다 |
| `VIRTUAL_THREAD_EXECUTOR_ONLY_IN_RESEARCH_AND_BUILD_FLOW` | 정리 PR 이 지운다 |

### 5. 문서

`revision-docs.md` 의 개정안을 이 PR 에서 `backend/AGENTS.md`, `backend/docs/code-architecture.md`, `.github/workflows/code-review-prompt.txt` 에 적용한다. 규칙이 코드에 있어야 문서가 이름으로 가리킬 수 있어서 이 PR 에서 함께 한다.
이동이 끝나기 전에는 문서가 목표 구조를 말하고 코드는 옛 구조가 남아 있다. 그래서 개정한 `backend/AGENTS.md` 「패키지 배치」 첫머리에 「이동 진행 중」 알림 문단을 둔다. phase 10 이 그것을 지운다.

### 6. 이 phase 를 검증하는 테스트

- `ViolationLineMatcher` 구현의 단위 테스트(작업 항목 1).
- 규칙마다 위반하는 fixture 와 통과하는 fixture 의 테스트. 기존 `AgentPurgerPlacementTest` 가 쓰는 방식(컴파일한 fixture 에 규칙을 돌린다)을 따른다. 메시지에 「어디에 두라」 가 들었는지도 확인한다.
- `./gradlew archTest` 가 기준 파일과 함께 통과한다. 기준 파일의 줄 수가 이 phase 의 「얼림」 건수와 맞는다.
