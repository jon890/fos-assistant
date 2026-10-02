# Phase 02. ConfigurationProperties 클래스에 Validated 를 단다

**Execution profile**: fast

## 목표

`@ConfigurationProperties` 인데 `@Validated` 가 없는 record 열넷에 `@Validated` 를 달아
`CONFIGURATION_PROPERTIES_ARE_VALIDATED` 의 기준을 0 줄로 만든다. 잘못된 설정이 기동에서 멈추게 하는 자리를 만들기 위해서다.

**범위 외**: 제약 애너테이션(`@NotBlank`, `@Min` 등)을 새로 다는 것. 제약을 달면 지금 운영 설정이 기동에서 거절될 수 있어 동작이 바뀐다.

## 컨텍스트

- 먼저 `tasks/plan66-archunit-easy-rules/README.md` 를 읽는다.
- 이미 `@Validated` 가 붙은 본보기: `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorProperties.java`.
  import 는 `org.springframework.validation.annotation.Validated` 다.
- 열넷 모두 지금 제약 애너테이션이 없다. 그래서 `@Validated` 만 달면 바인딩 결과가 달라지지 않는다.

**근거 문서**: `docs/adr/ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`

## 작업 항목

### 1. 아래 열넷에 `@Validated` 를 단다

`backend/src/main/java/com/bifos/assistant/` 아래다. `@ConfigurationProperties` 바로 위나 아래에 본보기와 같은 순서로 단다.

- `agent/application/AgentProperties.java`
- `agent/application/StarterProperties.java`
- `chat/application/ArtifactProperties.java`
- `chat/application/ArtifactSourceProperties.java`
- `chat/application/AttachmentProperties.java`
- `chat/application/DelegationWakeProperties.java`
- `context/ContextProperties.java`
- `hermes/HermesProperties.java`
- `memory/application/MemoryProposalProperties.java`
- `orchestration/application/DelegationProperties.java`
- `people/application/PeopleProperties.java`
- `shared/auth/AuthProperties.java`
- `skill/application/SkillProperties.java`
- `usage/infra/PricingProperties.java`

### 2. 이 phase 를 검증하는 `ValidatedPropertiesBindingTest.java`

`backend/src/test/java/com/bifos/assistant/shared/ValidatedPropertiesBindingTest.java` 를 새로 만든다. `@SpringBootTest` 다.

- 정상: 위 열넷의 빈을 `ApplicationContext.getBean(<타입>.class)` 로 꺼낼 수 있고, 꺼낸 객체의 `getClass()` 가 그 record 타입 자체다.
  `@Validated` 가 붙어도 프록시로 감싸지지 않고 바인딩이 그대로라는 뜻이다
- 실패: 열넷의 타입 가운데 `@Validated` 가 없는 것이 있으면 이 테스트가 그 타입 이름을 내며 실패한다.
  `AnnotatedElementUtils.hasAnnotation(type, Validated.class)` 로 단언한다

열넷의 목록은 테스트 안의 상수로 둔다.

## 검증

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
./gradlew test
test "$(wc -l < config/archunit/store/f875bef7-581f-41c7-8e85-cf4b3d36f833)" -eq 0
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/StarterProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ArtifactProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ArtifactSourceProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/DelegationWakeProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/ContextProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryProposalProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/DelegationProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/people/application/PeopleProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/auth/AuthProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/PricingProperties.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/shared/ValidatedPropertiesBindingTest.java` | 신규 |
| `backend/config/archunit/store/f875bef7-581f-41c7-8e85-cf4b3d36f833` | 수정 |
