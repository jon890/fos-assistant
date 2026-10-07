# Phase 03. 변형을 이름 있는 주석으로 모아 컨텍스트 수를 줄인다

**Execution profile**: standard

## 목표

여럿이 같은 기동 설정을 쓰는 검사를 이름 있는 변형 주석으로 묶고, 검사 클래스마다 따로 둔 설정을 공유 설정으로 바꿔 컨텍스트 수를 줄인다.
검사가 확인하는 것은 바꾸지 않는다.

**범위 외**: 보존 상한과 측정(phase 04). 모델 tier 검사 둘을 단위 검사로 옮기는 일.

## 컨텍스트

**근거 문서**: `docs/backend/testing.md` 의 「변형」 표, `docs/adr/ADR-095-backend-통합-검사는-공통-기반-컨텍스트-하나를-함께-쓰고-기동-설정이-다른-검사만-변형을-둔다.md`.

Spring 은 `@TestPropertySource` 의 값 배열을 순서까지 견줘 컨텍스트 키를 만든다. 같은 값을 다른 순서나 다른 묶음으로 적으면 컨텍스트가 따로 뜬다.
클래스마다 둔 `@DynamicPropertySource` 메서드는 값이 같아도 메서드가 달라 컨텍스트가 따로 뜬다.

## 의도 메모

- 변형 주석은 `@BackendIntegrationTest` 와 함께 다는 보조 주석이다. 변형 주석에 `@BackendIntegrationTest` 를 넣지 않는다. 두 변형을 함께 다는 검사가 있다.
- 변형 주석은 `@TestPropertySource` 를 메타 주석으로 갖는다. 여러 변형을 단 검사의 값은 Spring 이 합친다.
- **변형 주석은 아래 표의 순서대로 달고, 남는 값은 그 뒤 하나의 `@TestPropertySource` 에 둔다.** 같은 묶음을 쓰는 검사의 컨텍스트 키가 순서까지 같아야 한다.
- 가격표를 기반에 넣지 않는다. 기본 설정은 가격표가 없어 금액이 빈다. 그 상태를 전제로 하는 검사의 의미가 바뀐다.
- `UsageCostRecordingTest` 는 가격표 파일의 수정 시각을 `2026-09-17T04:00:00Z` 로 고정한다. 공유 설정이 그 시각을 고정한다. 가격표를 쓰는 다른 검사는 그 시각을 단언하지 않는다.

## 작업 항목

### 1. 변형 주석 넷

`backend/src/test/java/com/bifos/assistant/testsupport/` 에 둔다.

| 주석 | 메타 주석 |
| --- | --- |
| `DelegationWakeEnabled` | `@TestPropertySource(properties = "assistant.delegation-wake.enabled=true")` |
| `SmallExecutionLimit` | `@TestPropertySource(properties = "assistant.user-execution.max-running=2")` |
| `MemoryEncryptionDisabled` | `@TestPropertySource(properties = {"assistant.memory.encryption.active-key-id=", "assistant.memory.encryption.keys="})` |
| `SamplePriceCatalog` | `@Import(SamplePriceCatalogProperties.class)` |

`SamplePriceCatalogProperties` 는 `@TestConfiguration` 이고 `DynamicPropertyRegistrar` 빈 하나로 `assistant.pricing.catalog-path` 를 클래스패스의 `/pricing/models-dev-sample.json` 실제 경로로 넣는다. 그 파일의 수정 시각을 위 시각으로 고정한다.

### 2. 검사에 단다

- `assistant.delegation-wake.enabled=true` 만 둔 검사와 그 값을 다른 값과 함께 둔 검사는 `@DelegationWakeEnabled` 를 단다. 남는 값만 `@TestPropertySource` 에 둔다
- `assistant.user-execution.max-running=2` 도 같은 방식으로 `@SmallExecutionLimit` 를 단다
- 암호화를 끈 검사 셋(`MemoryDocumentEncryptionDisabledTest`, `MemoryEncryptionDisabledTest`, `MemoryImportEncryptionDisabledTest`)은 `@MemoryEncryptionDisabled` 를 단다
- `@DynamicPropertySource` 로 표본 가격표를 가리키던 검사(`ModelSelectionTest`, `RecoveredRunRecorderTest`, `RestartReconcilerTest`, `ResearchAndBuildFlowTest`, `UsageCostRecordingTest`, `SubagentUsageLedgerTest`)는 그 메서드를 지우고 `@SamplePriceCatalog` 를 단다
- `AttentionServiceTest` 와 `AttentionControlServiceTest` 의 후보 출처 대역 둘을 `backend/src/test/java/com/bifos/assistant/attention/AttentionTestCandidates.java` 공유 설정 하나로 옮겨 두 검사가 함께 `@Import` 한다. 두 대역 모두 꺼 두면 후보를 내지 않는다. 각 검사는 `@BeforeEach` 에서 자기 대역을 꺼 둔 상태로 되돌린다

### 3. 이 phase 를 검증하는 검사

`backend/src/test/java/com/bifos/assistant/testsupport/VariantAnnotationsTest.java` 를 만든다. 컨텍스트를 띄우지 않고 `TestContextAnnotationUtils` 나 `MergedAnnotations` 로 확인한다.

- 정상: `@DelegationWakeEnabled` 와 `@SmallExecutionLimit` 를 함께 단 클래스의 병합된 `@TestPropertySource` 값에 두 속성이 모두 있다
- 실패에 해당하는 경계: 변형 주석만 단 클래스에는 `@SpringBootTest` 가 없다. 변형 주석이 기반을 끌어오지 않는다

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test --tests '*VariantAnnotationsTest')
(cd backend && ./gradlew test --tests '*Attention*' --tests '*Memory*EncryptionDisabledTest' --tests '*UsageCostRecordingTest' --tests '*SubagentUsageLedgerTest' --tests '*ModelSelectionTest')
(cd backend && ./gradlew test)
(cd backend && ./gradlew qualityCheck)
scripts/quality.sh check
git grep -ln "@DynamicPropertySource" -- backend/src/test
```

- 모두 종료 코드 0
- 마지막 줄의 결과가 파일 이름에 `Mysql` 이 든 검사뿐이다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/test/java/com/bifos/assistant/testsupport/DelegationWakeEnabled.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/testsupport/SmallExecutionLimit.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/testsupport/MemoryEncryptionDisabled.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/testsupport/SamplePriceCatalog.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/testsupport/SamplePriceCatalogProperties.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/testsupport/VariantAnnotationsTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/attention/AttentionTestCandidates.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/**/*Test.java` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `docs/backend/testing.md` | 수정 |
