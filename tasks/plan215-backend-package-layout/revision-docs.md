# 문서 개정안

phase 01 이 `ArchitectureRules.java` 에 규칙을 넣을 때 이 개정안을 적용한다. 규칙이 코드에 있어야 문서가 이름으로 가리킬 수 있다.
적용할 문서는 `backend/AGENTS.md`(= `backend/CLAUDE.md`), `backend/docs/code-architecture.md`, `.github/workflows/code-review-prompt.txt` 셋이다.
아래 블록은 그 문서에 들어갈 본문이고, 블록 밖의 글은 적용 위치다.

## 1. backend/AGENTS.md

`## 패키지 배치` 절의 첫 부분(`application` 의 서비스는 목적에 따라 하위 패키지로 묶는다 부터 `agent.config` 문단 앞까지)을 아래로 바꾼다. 나머지 소절(「crypto의 영속 타입」, 「memory의 영속 타입」)은 그대로 둔다.

```markdown
## 패키지 배치

**이동 진행 중이다.** 아직 옮기지 않은 기능은 옛 구조(`application.model` 같은 이름)로 남아 있고, 규칙은 그 위반을 기준 파일에 얼려 두었다. 이동이 끝나면 이 문단을 지운다.

기능은 `<기능>[.<하위 기능>].<역할>` 로 나눈다. 결정과 근거는 [ADR-20261015 / package-layout](docs/adr/ADR-20261015-package-layout.md) 이 갖는다.
층 방향, 하위 기능의 순서와 뜻, 규칙의 이름과 판정은 [`backend/docs/code-architecture.md`](docs/code-architecture.md) 의 「패키지 배치 규칙」 이 갖는다.

| 역할 | 패키지 | 두는 것 |
| --- | --- | --- |
| presentation | `presentation` | 컨트롤러와 `*Dtos`, 웹 계층의 어댑터 |
| application | `application` | Spring 빈만. 빈이 소유한 package-private 도우미는 허용한다 |
| dto | `application.dto` | 서비스가 주고받는 record, enum, 값 클래스 |
| port | `application.port` | 구현을 바깥에 두는 인터페이스 |
| config | `config` | `@ConfigurationProperties` 와 그 값 record |
| domain | `domain` | 엔티티, 영속 ID, 도메인 정책, 저장 enum(`domain.type`) |
| infra | `infra` | 저장소와 외부 시스템의 어댑터 |

- 새 클래스는 이 표로 자리를 정한다. 인터페이스는 `application.port`, 서비스가 주고받는 값은 `application.dto`, 설정 값은 `config` 다.
- `application` 직하 클래스가 25개를 넘으면 하위 기능으로 나눈다. 나눈 기능은 루트에 역할 패키지를 두지 않는다. 하위 기능은 순서의 아래쪽만 쓴다.
- 관리자 전용 화면과 API 는 `admin.<하위 기능>` 에 둔다. `admin` 은 기능의 `application` 과 `application.dto`(와 `domain.type`)만 부르고, 기능은 `admin` 을 모른다. `CurrentUserProvider.requireAdmin` 은 `admin` 안에서만 부른다. 사용자 기능 안에서 권한에 따라 보이는 범위만 다른 것은 그 기능에 둔다.
- 관리자 컨트롤러의 응답 모양은 `admin` 의 `*Dtos` 가 갖는다. 기능의 `*Dtos` 를 가져다 쓰지 않는다.
- 어느 하위 기능에 둘지, 한 서비스의 일이 관리자 전용인지 권한 범위만 다른 것인지는 규칙으로 판정하지 못한다. 리뷰에서 본다.

검사: `ArchitectureRules.LAYER_DIRECTION`, `APPLICATION_ROOT_HOLDS_ONLY_BEANS`, `APPLICATION_SUBPACKAGES_ARE_DTO_AND_PORT`, `PORT_PACKAGE_HOLDS_ONLY_INTERFACES`, `DTO_PACKAGE_HOLDS_ONLY_VALUES`, `CONFIGURATION_PROPERTIES_IN_CONFIG`, `APPLICATION_PACKAGE_SIZE`, `SUB_FEATURES_FOLLOW_ORDER`, `SPLIT_FEATURES_HAVE_NO_ROOT_ROLES`, `SIMPLE_CLASS_NAMES_ARE_UNIQUE`, `ADMIN_PACKAGE_SHAPE`, `FEATURES_DO_NOT_DEPEND_ON_ADMIN`, `ADMIN_USES_ONLY_FEATURE_APPLICATION`, `ADMIN_ENDPOINTS_REQUIRE_ADMIN`, `REQUIRE_ADMIN_ONLY_IN_ADMIN`
```

`### 관리자 등록 업무` 소절은 `### 관리자 패키지` 로 바꾼다.

```markdown
### 관리자 패키지

`admin.<하위 기능>` 은 `application`(필요할 때 `application.dto`)과 `presentation` 만 둔다. 엔티티와 저장소와 설정은 갖지 않는다.
수동 profile 등록과 변경은 `admin.agent.application.AgentAdminService` 가 맡고, 등록과 변경 명령은 그 아래 `application.dto` 에 둔다.
각 관리자 컨트롤러의 mapped 메서드는 첫 줄에서 `CurrentUserProvider.requireAdmin` 을 부른다.
검사: `ArchitectureRules.ADMIN_ENDPOINTS_REQUIRE_ADMIN`, `ArchitectureRules.REQUIRE_ADMIN_ONLY_IN_ADMIN`

`agent` 의 소유 타입을 이름으로 찾아 기대 위치를 강제하는 `AGENT_ADMIN_APPLICATION_PLACEMENT` 는 `admin.agent.*` 위치를 계속 지킨다.
```

`### 에이전트 설정` 소절의 문장 `서비스는 application, 공개 서비스 값은 application.model, ...` 에서 `application.model` 을 `application.dto` 로 바꾼다.

`### 에이전트 지연 정리` 소절의 위치를 평평한 `agent` 에 맞춘다.

```markdown
### 에이전트 지연 정리

정리 실행과 writer 는 `agent.application` 에, 참여 port 는 `agent.application.port` 에, 정리 결과 enum 은 `agent.application.dto` 에 둔다.
참여 구현은 각 기능에 남기고 정리 실행이 구현 기능을 import하지 않는다.
`AgentProperties` 는 `agent.config` 에, profile 회수 port 는 `agent.application.port` 에 둔다.
검사: `ArchitectureRules.AGENT_PURGE_CONTRACT_PLACEMENT`
```

`### 데이터 클래스는 컨트롤러 안에 두지 않는다` 소절의 마지막 문단(`한 패키지에 컨트롤러가 여럿이어도 ...`)은 다음으로 바꾼다.

```markdown
한 패키지에 컨트롤러가 여럿이어도 `*Dtos.java` 는 하나다. 다만 관리자 컨트롤러의 모양은 같은 기능의 `*AdminDtos` 가 아니라 `admin.<하위 기능>.presentation` 의 `*AdminDtos` 가 갖는다.
```

`### enum 은 저장 여부로 둘 곳을 정한다` 소절의 마크다운 표에서 `<기능>.application.model` 을 `<기능>.application.dto` 로 바꾼다.

## 2. backend/docs/code-architecture.md

- `## 패키지와 책임` 의 표를 하위 기능을 반영해 다시 쓴다. 나눈 기능(`chat`, `connector`, `proactive`, `usage`, `skill`)은 하위 기능마다 한 줄이고 책임은 ADR 의 「하위 기능」 표를 옮긴다. 나누지 않은 기능은 지금처럼 한 줄이다. `admin` 줄을 더한다.
- `### 최상위 패키지의 층 순서` 에 `admin` 을 맨 위로 적는다: 「`admin` 은 맨 위다. 관리자 전용 화면과 API 가 기능의 `application` 을 부르고, 어느 패키지도 `admin` 을 import 하지 않는다.」
- `### 에이전트 도구`, `### 에이전트 관리자 등록`, `### 에이전트 지연 정리`, `### 스킬`, `### 첨부 원본 조회`, `### 첨부 관찰`, `### 에이전트 설정`, `### proactive`, `### connector` 소절의 클래스 위치(`agent.application.toolset`, `agent.admin.*`, `chat.application.*`, `connector.application.*` 같은 경로 표기)는 이동 PR 이 `test/unit/doc-code-references.test.ts` 가 알려 주는 대로 새 경로로 고친다.
- `### 구조 규칙의 기준 파일` 에 한 문단을 더한다.

```markdown
기준 대조는 위반 줄의 전체 이름에서 패키지를 지우고 단순 클래스 이름으로 한다(`archunit.properties` 의 `freeze.lineMatcher`). 클래스를 다른 패키지로 옮겨도 기준이 흔들리지 않는다. 이 대조는 운영 클래스의 단순 이름이 유일하다는 것에 기대고, `SIMPLE_CLASS_NAMES_ARE_UNIQUE` 가 그것을 지킨다.
```

- 새 절 `## 패키지 배치 규칙` 을 `## 패키지와 책임` 앞에 더한다. 본문은 ADR 의 「결정」 의 역할 표와 「층 방향의 개정」 표, 하위 기능의 순서 표, 그리고 phase 01 의 규칙 목록(이름, 판정, 메시지, 얼림)이다. 규칙의 메시지 전문은 `ArchitectureRules.java` 의 Javadoc 이 갖는다고 적고 이 문서에는 이름과 판정만 둔다.

## 3. .github/workflows/code-review-prompt.txt

`### 지침에서 특히 볼 곳` 의 표에 한 줄을 더한다.

```markdown
| 하위 기능의 경계와 `admin` 의 경계. 한 서비스의 일이 관리자 전용인지 권한 범위만 다른 것인지, `application.dto` 의 타입이 서비스의 입출력인지, package-private 도우미가 빈 하나에 딸려 있는지 | `backend/AGENTS.md` 「패키지 배치」, ADR-20261015 / package-layout |
```

이동 PR 을 리뷰할 때는 기존 규칙대로 `git diff --color-moved` 로 이동한 줄을 가르고, 이동만 있는지와 `package`, `import`, 전체 이름 문자열의 누락을 본다는 문구가 이 프롬프트의 1단계에 이미 있다. 더하지 않는다.
