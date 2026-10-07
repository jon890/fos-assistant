# Phase 02. 검사가 설정을 @OverrideProperties 로 바꾸고 설정 변형 컨텍스트를 없앤다

**Execution profile**: deep

## 목표

검사가 컨텍스트를 새로 띄우지 않고 `LiveProperties` 의 값을 바꾸게 한다.
`@TestPropertySource` 와 설정 변형 주석으로 갈라지던 컨텍스트를 기반 하나로 모은다. 검사가 확인하는 것은 바꾸지 않는다.

**범위 외**: 검사 하나만 쓰는 mock 과 `@Import` 대역의 이전, 모델 tier 검사, 구조 규칙(phase 03). 측정과 보존 상한(phase 04).

## 컨텍스트

**근거 문서**: `docs/backend/testing.md` 「설정 바꾸기」, `docs/adr/ADR-20261007-test-context-base.md`, `docs/adr/ADR-20261007-live-properties.md`.

phase 01 이 `shared/config/LiveProperties` 와 `LivePropertiesConfig` 를 만들었다.
기반은 `backend/src/test/java/com/bifos/assistant/testsupport/` 의 `BackendIntegrationTest`, `IntegrationTestDoubles`, `IntegrationTestIsolation` 이다.
지금 변형 주석 `DelegationWakeEnabled`, `SmallExecutionLimit`, `MemoryEncryptionDisabled`, `LongProactiveCheckTimeouts`, `MemoryProposeEnabled` 는 `@TestPropertySource` 를 메타 주석으로 갖고, `SamplePriceCatalog` 는 `@Import(SamplePriceCatalogProperties.class)` 다.

## 의도 메모

- 값은 `application.yml` 키 문자열(`"assistant.user-execution.max-running=2"`)로 받는다. 지금 `@TestPropertySource` 와 같은 모양이라 검사 수정이 작다.
- 바꾼 record 는 Spring `Binder` 로 만든다. 덮어쓸 값의 `MapConfigurationPropertySource` 를 앞에 두고 컨텍스트 `Environment` 의 속성 출처를 뒤에 두어 그 record 의 prefix 로 바인딩한다. 생성자 검증이 그대로 돈다.
- `assistant.proactive-check.max-duration` 이나 `hermes.run-timeout` 을 바꾸면 운영의 기동 검사와 같은 조건(`requireShorterThan`)을 다시 확인한다.
- 어떤 `LiveProperties` 의 prefix 에도 속하지 않는 키를 적으면 검사를 실패로 둔다. 조용히 무시하면 검사가 바뀌지 않은 설정으로 통과한다.
- `hermes.run-timeout` 과 `hermes.poll-interval` 외의 `hermes.*` 키도 실패로 둔다(phase 01 이 그 두 값만 `LiveProperties` 로 읽게 했다).
- `spring.jpa.properties.hibernate.generate_statistics` 는 Hibernate 기동 때만 읽는다. `application-test.yml` 에서 늘 `true` 로 두고 `UsageControllerTest` 의 속성을 지운다.

## 작업 항목

### 1. `testsupport/OverridableLiveProperties.java` 와 빈 후처리기

`LiveProperties` 를 구현한다. 기동 값을 쥐고 `override(T)` 와 `reset()` 을 갖는다.
`IntegrationTestDoubles` 에 `static` `BeanPostProcessor` 를 두어 `LiveProperties` 빈을 모두 `OverridableLiveProperties` 로 감싼다.

### 2. `testsupport/OverrideProperties.java`

`@Target({TYPE, ANNOTATION_TYPE})`, `@Retention(RUNTIME)`, `@Inherited`, `@Repeatable` 이고 `String[] value()` 다.
변형 주석 다섯을 `@OverrideProperties(...)` 를 메타 주석으로 갖게 바꾼다. 값은 지금 각 주석의 `@TestPropertySource` 값과 같다.
`SamplePriceCatalog` 는 `assistant.pricing.catalog-path` 를 바꾸는 주석으로 바꾼다. 파일 경로는 클래스패스 `/pricing/models-dev-sample.json` 의 실제 경로이고, 적용할 때 그 파일의 수정 시각을 `2026-09-17T04:00:00Z` 로 고정한다(지금 `SamplePriceCatalogProperties` 가 하는 일). `SamplePriceCatalogProperties` 는 지운다.

### 3. `IntegrationTestIsolation` 이 적용하고 되돌린다

검사 전: 검사 클래스와 그 상위, 메타 주석에서 `@OverrideProperties` 값을 모두 모아(`MergedAnnotations`, `SearchStrategy.TYPE_HIERARCHY`) prefix 별로 묶어 바인딩하고 해당 `OverridableLiveProperties` 에 적용한다.
검사 뒤: 백그라운드 작업 join 다음에 모든 `OverridableLiveProperties` 를 `reset()` 한다. 지금의 대역과 시계 되돌리기와 같은 `finally` 안에 둔다.

### 4. 검사를 옮긴다

`backend/src/test/java/com/bifos/assistant/` 아래 `@TestPropertySource` 를 `@OverrideProperties` 로 바꾼다. 값은 그대로 둔다.
`UsageControllerTest` 는 위 의도 메모대로 속성을 지운다. `UserExecutionLimiterTest` 의 `hermes.poll-interval=10ms` 는 test profile 의 값과 같으면 지운다.
`ModelTierFallbackIntegrationTest`, `ModelTierSeedImportIntegrationTest` 는 이 phase 에서 건드리지 않는다(phase 03).

### 5. 이 phase 를 검증하는 검사

`backend/src/test/java/com/bifos/assistant/testsupport/OverridePropertiesTest.java` 를 만든다. `@BackendIntegrationTest` 와 `@OverrideProperties("assistant.user-execution.max-running=2")` 를 단 검사다.
- 정상: `LiveProperties<UserExecutionProperties>.current().maxRunning()` 이 2 다
- 되돌리기: `IntegrationTestIsolation` 의 정리 메서드를 직접 부른 뒤 기동 값(test profile 의 1000)으로 돌아온다
- 실패: 어느 prefix 에도 속하지 않는 키로 적용을 부르면 그 키 이름을 담은 예외가 난다
- 기존 `VariantAnnotationsTest` 는 `@OverrideProperties` 병합을 확인하도록 고친다

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test --tests '*OverridePropertiesTest' --tests '*VariantAnnotationsTest' --tests '*IntegrationTestIsolationTest')
(cd backend && ./gradlew test)
(cd backend && ./gradlew qualityCheck)
scripts/quality.sh check
git grep -ln "@TestPropertySource" -- backend/src/test
```

- 모두 종료 코드 0
- 마지막 줄의 결과가 비어 있거나 `ModelTier*IntegrationTest.java` 둘과 MySQL 기준 클래스뿐이다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/test/java/com/bifos/assistant/testsupport/OverridableLiveProperties.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/testsupport/OverrideProperties.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/testsupport/OverridePropertiesTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/testsupport/SamplePriceCatalogProperties.java` | 삭제 |
| `backend/src/test/java/com/bifos/assistant/testsupport/*.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/**/*Test.java` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
