# 코드 규칙 목록

번호는 고정하며 폐지한 번호를 다시 쓰지 않는다.
규칙의 까닭과 예외는 각 파일이, 정확한 실행 조건과 범위는 연결한 검사 코드가 갖는다.
`리뷰`는 자동 검사로 확인하지 못하는 조건을 사람이 판단한다는 뜻이다.
자동 검사가 연결돼 있어도 모든 예외와 권한을 증명하지는 않는다.
기존 위반 기준과 설계상 예외를 구별하고 기준 갱신 절차는 모듈 아키텍처 문서에서 찾는다.

소유권은 [ADR-20261016 / code-rules](../adr/ADR-20261016-code-rules.md)가 정한다.
새 규칙은 문서와 검사를 같은 PR에 넣고, 리뷰 전용 조건이면 그 한계를 밝힌다.
목록 자체는 `test/unit/rule-index.test.ts`가 파일, 번호, 문장, 검사 연결과 코드의 역참조를 확인한다.

| 번호 | 규칙 한 문장 | 검사 |
| --- | --- | --- |
| [rule-1](rule-1.md) | auditing은 허용된 필드와 기존 Clock provider만 쓴다. | [AUDITING_LISTENERS_AND_CLOCK](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-2](rule-2.md) | 최상위 기능 사이에 순환을 만들지 않는다. | [TOP_LEVEL_PACKAGES_FREE_OF_CYCLES](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-3](rule-3.md) | 최상위 패키지는 정해진 층 순서의 아래쪽만 쓴다. | [TOP_LEVEL_PACKAGES_FOLLOW_LAYER_ORDER](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-4](rule-4.md) | shared는 다른 기능에 의존하지 않는다. | [SHARED_DOES_NOT_DEPEND_ON_DOMAINS](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-5](rule-5.md) | 기능 안의 층은 허용된 방향으로만 의존한다. | [LAYER_DIRECTION](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-6](rule-6.md) | domain은 웹 타입에 의존하지 않는다. | [DOMAIN_DOES_NOT_DEPEND_ON_WEB](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-7](rule-7.md) | 관리자 서비스·명령은 지정된 소유 경계에 둔다. | [AGENT_ADMIN_APPLICATION_PLACEMENT](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-8](rule-8.md) | 관리자 HTTP 타입의 위치와 requireAdmin 호출을 지킨다. | [AGENT_ADMIN_HTTP_CONTRACT](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-9](rule-9.md) | 에이전트 정리 실행과 port·결과는 소유 경계를 지킨다. | [AGENT_PURGE_CONTRACT_PLACEMENT](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-10](rule-10.md) | orchestration은 mcp에 의존하지 않는다. | [ORCHESTRATION_DOES_NOT_DEPEND_ON_MCP](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-11](rule-11.md) | mcp는 Hermes 호출 타입을 직접 쓰지 않는다. | [MCP_DOES_NOT_CALL_HERMES](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-12](rule-12.md) | orchestration은 ChatService에 의존하지 않는다. | [ORCHESTRATION_DOES_NOT_CALL_CHAT_SERVICE](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-13](rule-13.md) | hermes는 people에 의존하지 않는다. | [HERMES_DOES_NOT_DEPEND_ON_PEOPLE](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-14](rule-14.md) | Jackson 2 core·databind를 쓰지 않는다. | [NO_JACKSON_2_DATABIND](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-15](rule-15.md) | 컨트롤러 안에 record를 두지 않는다. | [CONTROLLERS_HAVE_NO_NESTED_RECORDS](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-16](rule-16.md) | 테스트 문장은 메서드의 DisplayName으로 쓴다. | [TEST_METHODS_HAVE_DISPLAY_NAME](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-17](rule-17.md) | 공유 테스트 기반 밖에서 Spring 컨텍스트를 나누지 않는다. | [TESTS_DO_NOT_SPLIT_CONTEXT](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-18](rule-18.md) | 트랜잭션 경계는 application에 둔다. | [TRANSACTIONAL_ONLY_IN_APPLICATION](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-19](rule-19.md) | Instant.now() 대신 주입된 Clock을 쓴다. | [NO_DIRECT_INSTANT_NOW](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-20](rule-20.md) | MessageDigest 생성은 Sha256만 맡는다. | [MESSAGE_DIGEST_ONLY_IN_SHA256](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-21](rule-21.md) | 요청 밖 가상 스레드는 BackgroundTasks로 시작한다. | [VIRTUAL_THREADS_ONLY_THROUGH_BACKGROUND_TASKS](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-22](rule-22.md) | 관리되지 않는 가상 스레드 실행기를 직접 만들지 않는다. | [NO_UNMANAGED_VIRTUAL_THREAD_EXECUTORS](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-23](rule-23.md) | 에이전트 설정 두 타입은 지정한 config 역할을 지킨다. | [FEATURE_CONFIG_PLACEMENT](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-24](rule-24.md) | ConfigurationProperties에는 Validated를 단다. | [CONFIGURATION_PROPERTIES_ARE_VALIDATED](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-25](rule-25.md) | 서비스·infra의 중첩 구현 타입은 private으로 둔다. | [SERVICES_DO_NOT_EXPOSE_NESTED_TYPES](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-26](rule-26.md) | 저장 enum은 domain.type에 둔다. | [ENUMERATED_FIELDS_USE_DOMAIN_TYPE](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-27](rule-27.md) | domain.type은 위 역할에 의존하지 않는다. | [DOMAIN_TYPE_DEPENDS_ON_NOTHING_ABOVE](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-28](rule-28.md) | 실행 중 설정 record는 LiveProperties로 읽는다. | [LIVE_SETTINGS_ONLY_THROUGH_LIVE_PROPERTIES](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-29](rule-29.md) | Hermes timeout 설정은 허용된 경로로만 읽는다. | [HERMES_TIMEOUTS_ONLY_THROUGH_LIVE_PROPERTIES](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-30](rule-30.md) | 엔티티는 소유 domain.entity에 하나씩 둔다. | [ENTITIES_IN_OWNED_ENTITY_PACKAGE](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-31](rule-31.md) | 실제 영속 ID는 엔티티와 함께 두고 entity의 역할을 섞지 않는다. | [EMBEDDED_IDS_WITH_ENTITIES](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-32](rule-32.md) | 빈 private utility 생성자는 Lombok으로 만든다. | [privateEmptyConstructor](../../backend/config/checkstyle/checkstyle.xml) |
| [rule-33](rule-33.md) | 엔티티 접근자는 Getter와 fluent Accessors로 통일한다. | [entityHandwrittenAccessor](../../backend/config/checkstyle/checkstyle.xml) |
| [rule-34](rule-34.md) | 단순한 생성자 주입은 RequiredArgsConstructor를 쓴다. | [requiredArgsConstructor](../../backend/config/checkstyle/checkstyle.xml), [리뷰](../../.github/workflows/code-review-prompt.txt) |
| [rule-35](rule-35.md) | 의존은 명시 import로 보이고 wildcard·불필요 import를 두지 않는다. | [UnusedImports](../../backend/config/checkstyle/checkstyle.xml), [AvoidStarImport](../../backend/config/checkstyle/checkstyle.xml), [RedundantImport](../../backend/config/checkstyle/checkstyle.xml), [IllegalImport](../../backend/config/checkstyle/checkstyle.xml), [fullyQualifiedName](../../backend/config/checkstyle/checkstyle.xml) |
| [rule-36](rule-36.md) | 로거는 Slf4j로 선언한다. | [lombokLogger](../../backend/config/checkstyle/checkstyle.xml) |
| [rule-37](rule-37.md) | 의도적으로 삼키는 예외는 ignored 또는 expected로 표시한다. | [EmptyCatchBlock](../../backend/config/checkstyle/checkstyle.xml) |
| [rule-38](rule-38.md) | 빈 문장·불완전한 equals/hashCode·문자열 ==·누락된 override·실수한 fallthrough를 막는다. | [EmptyStatement](../../backend/config/checkstyle/checkstyle.xml), [EqualsHashCode](../../backend/config/checkstyle/checkstyle.xml), [StringLiteralEquality](../../backend/config/checkstyle/checkstyle.xml), [FallThrough](../../backend/config/checkstyle/checkstyle.xml), [MissingOverride](../../backend/config/checkstyle/checkstyle.xml) |
| [rule-39](rule-39.md) | 같은 표현은 단순한 boolean·표준 modifier·배열·long 표기를 쓴다. | [SimplifyBooleanExpression](../../backend/config/checkstyle/checkstyle.xml), [SimplifyBooleanReturn](../../backend/config/checkstyle/checkstyle.xml), [ModifierOrder](../../backend/config/checkstyle/checkstyle.xml), [UpperEll](../../backend/config/checkstyle/checkstyle.xml), [ArrayTypeStyle](../../backend/config/checkstyle/checkstyle.xml) |
| [rule-40](rule-40.md) | Java 식별자는 타입·메서드·값 종류별 표준 이름 모양을 따른다. | [PackageName](../../backend/config/checkstyle/checkstyle.xml), [TypeName](../../backend/config/checkstyle/checkstyle.xml), [MethodName](../../backend/config/checkstyle/checkstyle.xml), [MemberName](../../backend/config/checkstyle/checkstyle.xml), [ParameterName](../../backend/config/checkstyle/checkstyle.xml), [LocalVariableName](../../backend/config/checkstyle/checkstyle.xml), [LocalFinalVariableName](../../backend/config/checkstyle/checkstyle.xml), [StaticVariableName](../../backend/config/checkstyle/checkstyle.xml), [LambdaParameterName](../../backend/config/checkstyle/checkstyle.xml), [RecordComponentName](../../backend/config/checkstyle/checkstyle.xml), [ClassTypeParameterName](../../backend/config/checkstyle/checkstyle.xml), [MethodTypeParameterName](../../backend/config/checkstyle/checkstyle.xml), [ConstantName](../../backend/config/checkstyle/checkstyle.xml) |
| [rule-41](rule-41.md) | 문장·중괄호·메서드 구분은 정해진 형식을 따른다. | [EmptyLineSeparator](../../backend/config/checkstyle/checkstyle.xml), [OneStatementPerLine](../../backend/config/checkstyle/checkstyle.xml), [LeftCurly](../../backend/config/checkstyle/checkstyle.xml), [RightCurly](../../backend/config/checkstyle/checkstyle.xml), [NeedBraces](../../backend/config/checkstyle/checkstyle.xml) |
| [rule-42](rule-42.md) | 긴 함수는 분리 후보로 표시한다. | [MethodLength](../../backend/config/checkstyle/checkstyle.xml), [max-lines-per-function](../../web/eslint.config.mjs) |
| [rule-43](rule-43.md) | 포맷은 고정된 formatter와 바뀐 파일 범위로 적용한다. | [spotlessCheck](../../backend/build.gradle.kts), [format:check](../../web/package.json), [포맷 회귀](../../test/spotless-jdt.test.py) |
| [rule-44](rule-44.md) | 파일 길이 상한과 기존 위반 기준을 지킨다. | [파일 길이](../../scripts/checks/check-file-length.mjs) |
| [rule-45](rule-45.md) | 기준 파일은 기존 위반을 줄이는 목록으로만 쓴다. | [기준 파일 관리](../../scripts/checks/quality.sh), [리뷰](../../.github/workflows/code-review-prompt.txt) |
| [rule-46](rule-46.md) | 공개 저장소에는 환경 운영값과 비밀을 꺼내는 방법을 적지 않는다. | [공개 정보](../../scripts/checks/check-public-safe.sh), [리뷰](../../.github/workflows/code-review-prompt.txt) |
| [rule-47](rule-47.md) | 실행 profile은 요청자의 바인딩에서만 고른다. | [요청자 바인딩](../../test/e2e/scenarios/binding.ts), [대화의 profile 유지](../../test/e2e/scenarios/chat.ts), [리뷰](../../.github/workflows/code-review-prompt.txt) |
| [rule-48](rule-48.md) | Memory 권한은 Control Plane이 정하고 비밀 원문은 DB에 두지 않는다. | [기억 비밀값 회귀](../../test/unit/memory-secrecy.test.ts), [리뷰](../../.github/workflows/code-review-prompt.txt) |
| [rule-49](rule-49.md) | 남의 자료는 없는 것과 같은 응답을 주며 실패 실행도 남긴다. | [남의 대화 접근 거절](../../test/e2e/scenarios/chat.ts), [리뷰](../../.github/workflows/code-review-prompt.txt) |
| [rule-50](rule-50.md) | 엔티티와 Flyway를 같이 바꾸고 적용된 마이그레이션은 수정하지 않는다. | [MySQL 마이그레이션](../../backend/src/test/java/com/bifos/assistant/MysqlMigrationTest.java), [적용 파일 불변](../../test/unit/migration-immutable.test.ts), [마이그레이션 버전](../../scripts/checks/check-migration-versions.mjs), [리뷰](../../.github/workflows/code-review-prompt.txt) |
| [rule-51](rule-51.md) | 저장소 쿼리는 실제 MySQL에서도 실행한다. | [MySQL 저장소 쿼리](../../backend/src/test/java/com/bifos/assistant/RepositoryQueryMysqlTest.java), [리뷰](../../.github/workflows/code-review-prompt.txt) |
| [rule-52](rule-52.md) | 주석·문서·도메인 용어는 저장소 언어 규칙을 따른다. | [문서 참조](../../test/unit/doc-code-references.test.ts), [리뷰](../../.github/workflows/code-review-prompt.txt) |
| [rule-53](rule-53.md) | 기능·ADR·구현 계획은 지정한 자리에 두고 끝난 계획은 지운다. | [문서 배치](../../test/unit/doc-files.test.ts), [ADR 목록](../../test/unit/adr-index.test.ts), [리뷰](../../.github/workflows/code-review-prompt.txt) |
| [rule-54](rule-54.md) | 서버 HTTP 클라이언트는 timeout을 정하고 테스트 설정 파일 이름을 분리한다. | [리뷰](../../.github/workflows/code-review-prompt.txt) |
| [rule-55](rule-55.md) | 브라우저의 API 호출은 서버 라우트와 lib 도우미를 거친다. | [no-restricted-globals](../../web/eslint.config.mjs), [no-restricted-syntax](../../web/eslint.config.mjs), [리뷰](../../.github/workflows/code-review-prompt.txt) |
| [rule-56](rule-56.md) | 색·간격·움직임은 테마 토큰을 쓰고 예외 inline에는 이유를 단다. | [테마 토큰](../../test/unit/design-tokens.test.ts), [리뷰](../../.github/workflows/code-review-prompt.txt) |
| [rule-57](rule-57.md) | 외부 글은 신뢰하지 않는 Markdown으로 그린다. | [리뷰](../../.github/workflows/code-review-prompt.txt) |
| [rule-58](rule-58.md) | 사용자 문구는 해요체와 제품 용어를 쓰고 내부 값은 관리자 영역에서만 보인다. | [리뷰](../../.github/workflows/code-review-prompt.txt) |
| [rule-59](rule-59.md) | web import는 별칭을 쓰되 Node 테스트가 읽는 런타임 모듈은 상대 import를 쓴다. | [no-restricted-imports](../../web/eslint.config.mjs), [리뷰](../../.github/workflows/code-review-prompt.txt) |
| [rule-60](rule-60.md) | 브라우저 테스트는 빌드·실행 환경을 분리하고 타이밍 가정을 두지 않는다. | [리뷰](../../.github/workflows/code-review-prompt.txt) |
| [rule-61](rule-61.md) | 수가 정해지지 않은 목록과 비용 큰 탭은 자동 prefetch를 끈다. | [대화 요청 수](../../test/browser/conversation-requests.spec.ts), [사용량 탭](../../test/browser/usage.spec.ts), [리뷰](../../.github/workflows/code-review-prompt.txt) |
| [rule-62](rule-62.md) | 운영 코드에 테스트 전용 인증·상태 우회를 만들지 않는다. | [리뷰](../../.github/workflows/code-review-prompt.txt) |
| [rule-63](rule-63.md) | Hermes core를 고치기 전에 plugin·profile·API 경계를 쓴다. | [리뷰](../../.github/workflows/code-review-prompt.txt) |
| [rule-64](rule-64.md) | 새 Hermes 내부 의존은 같은 변경에 계약 테스트로 선언한다. | [Hermes 계약](../../hermes/tests/hermes_contract.py), [리뷰](../../.github/workflows/code-review-prompt.txt) |
| [rule-65](rule-65.md) | plugin은 서비스 이름을 모르고 connector만 서비스별 동작을 갖는다. | [서비스 중립](../../test/unit/connector-neutral.test.ts) |
| [rule-66](rule-66.md) | plugin은 전환 배포 동안 이전 Control Plane 호출을 받는다. | [리뷰](../../.github/workflows/code-review-prompt.txt) |
| [rule-67](rule-67.md) | 운영값은 설치 묶음과 환경에서 받고 코드에 고정하지 않는다. | [공개 정보](../../scripts/checks/check-public-safe.sh), [리뷰](../../.github/workflows/code-review-prompt.txt) |
| [rule-68](rule-68.md) | 동시 변경은 조건부 갱신으로 보호하고 오래된 엔티티를 통째로 덮어쓰지 않는다. | [위임 경합 회귀](../../backend/src/test/java/com/bifos/assistant/orchestration/AgentDelegationServiceRaceTest.java), [리뷰](../../.github/workflows/code-review-prompt.txt) |
| [rule-69](rule-69.md) | 화면 갱신은 입력 상태와 레이아웃을 불필요하게 다시 만들지 않는다. | [입력 상태 회귀](../../test/browser/composer-send-flow.spec.ts), [리뷰](../../.github/workflows/code-review-prompt.txt) |
| [rule-70](rule-70.md) | application 직하에는 Spring 빈만 둔다. | [APPLICATION_ROOT_HOLDS_ONLY_BEANS](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-71](rule-71.md) | application 아래 하위 패키지는 dto와 port뿐이다. | [APPLICATION_SUBPACKAGES_ARE_DTO_AND_PORT](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-72](rule-72.md) | port 패키지에는 인터페이스와 그 예외만 둔다. | [PORT_PACKAGE_HOLDS_ONLY_INTERFACES](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-73](rule-73.md) | dto 패키지에는 값만 둔다. | [DTO_PACKAGE_HOLDS_ONLY_VALUES](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-74](rule-74.md) | ConfigurationProperties는 config 패키지에 둔다. | [CONFIGURATION_PROPERTIES_IN_CONFIG](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-75](rule-75.md) | application 직하의 클래스는 25개를 넘지 않는다. | [APPLICATION_PACKAGE_SIZE](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-76](rule-76.md) | 하위 기능은 같은 기능의 아래 하위 기능만 쓴다. | [SUB_FEATURES_FOLLOW_ORDER](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-77](rule-77.md) | 하위 기능으로 나눈 기능은 루트에 역할 패키지를 두지 않는다. | [SPLIT_FEATURES_HAVE_NO_ROOT_ROLES](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-78](rule-78.md) | 운영 클래스의 단순 이름은 겹치지 않는다. | [SIMPLE_CLASS_NAMES_ARE_UNIQUE](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-79](rule-79.md) | admin은 하위 기능 아래에 application, application.dto, presentation만 둔다. | [ADMIN_PACKAGE_SHAPE](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-80](rule-80.md) | 기능은 admin을 쓰지 않는다. | [FEATURES_DO_NOT_DEPEND_ON_ADMIN](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-81](rule-81.md) | admin은 기능의 application과 application.dto만 쓴다. | [ADMIN_USES_ONLY_FEATURE_APPLICATION](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-82](rule-82.md) | admin의 엔드포인트는 requireAdmin을 부른다. | [ADMIN_ENDPOINTS_REQUIRE_ADMIN](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-83](rule-83.md) | requireAdmin은 admin 안에서만 부른다. | [REQUIRE_ADMIN_ONLY_IN_ADMIN](../../backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java) |
| [rule-84](rule-84.md) | 하위 기능과 admin의 경계, dto와 도우미의 소속은 리뷰가 판단한다. | [리뷰](../../.github/workflows/code-review-prompt.txt) |
