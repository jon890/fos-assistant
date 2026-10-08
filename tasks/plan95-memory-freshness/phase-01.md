# Phase 01. Memory 신선도를 수정 시각으로 판정한다

**Execution profile**: standard

## 목표

Memory 문맥 항목의 고정 FRESH를 실제 나이 판정으로 바꾸고 실행 출처 기록까지 검증한다.

**범위 외**: Hermes core, 화면, DB 스키마와 기존 실행 기록, Memory 주입 문구와 선택 및 권한.

## 컨텍스트

**근거 문서**: `docs/backend/context-bundle.md`의 「신선도」.
`ContextAssembler`는 `MemoryService`로 권한을 거른 뒤 묶음을 만들고 `ContextSourceRefs`는 그 값을 실행 기록으로 옮긴다.
`ResultHeader.freshnessOf`는 시각이 비면 UNKNOWN, 기간을 초과하면 STALE, 같으면 FRESH를 내므로 재사용한다.
`Clock` 빈과 통합 시험의 `TestClock`이 이미 있다. ContextProperties는 LiveProperties가 아니므로
OverrideProperties를 쓰지 않는다. 시험별 설정은 실제 MemoryService와 시계로 ContextAssembler를 직접 구성한다.

## 의도 메모

- 승인된 기억도 낡을 수 있으므로 trust와 freshness를 독립 판정한다.
- 기본 180일로 운영에서도 판정한다. 새 의존성이나 마이그레이션은 필요 없다.
- 같은 실행의 모든 항목은 같은 시각을 쓴다. 로그에 제목과 본문을 추가하지 않는다.

## 작업 항목

### 1. ContextProperties와 ContextAssembler

`ContextProperties`에 `Duration memoryStaleAfter`(설정 없으면 180일)와 `Map<String, Duration> memoryCollectionStaleAfter`(설정 없으면 빈 맵)를 추가한다.
collection별 기준이 있으면 기본값보다 우선한다. 기준 0 이하는 UNKNOWN으로 판정하는 명시적 끄기다.
맵은 방어 복사하여 불변으로 둔다. ContextAssembler에 Clock을 주입하고 assemble마다 now를 한 번 읽는다.
always/index/omitted 항목 모두 updatedAt으로 판정하며 기준 없음 또는 수정 시각 없음은 UNKNOWN이다.
예산 계산에서 불필요한 문맥 항목 생성이 있으면 제목 기반 길이 계산으로 정리한다.
현재 주입 글, 예산, 지문, 권한, 암호문 건너뛰기를 유지한다.

### 2. ContextAssemblerTest, MemoryFreshnessTest, ContextPropertiesTest 테스트

기존 ContextAssemblerTest의 새 기억은 기본 180일 안이므로 FRESH가 유지된다.
수정 시각을 통제하는 시험으로 오래된 항상/색인/생략 항목의 STALE, 새 항목과 경계 시각의 FRESH,
collection 덮어쓰기, 기준 0 및 수정 시각 없음의 UNKNOWN을 검증한다.
설정 누락 시 180일과 빈 맵, 원본 맵 변경이 영향을 주지 않는 것, Spring 설정 바인딩을 검증한다.
`MemoryFreshnessTest`는 통합 하네스에서 실제 Memory를 저장하고 `ContextAssembler` → `ContextSourceRefs` →
`ExecutionRecorder.start`로 기록해 `execution_context_source`의 STALE와 UNKNOWN을 모두 단언한다.
고정 FRESH를 되돌리면 이 시험이 실패해야 한다. 실제 개인정보를 쓰지 않는다.

## 검증

```bash
cd backend && ./gradlew test --tests '*ContextAssemblerTest' --tests '*MemoryFreshnessTest' --tests '*ResultHeaderTest' --tests '*ExecutionContextSourceTest' --tests '*ConnectorActionDeliveryTest' --tests '*ResultDeliveryRetryTest'
cd backend && ./gradlew qualityCheck
node scripts/check-file-length.mjs
scripts/check-public-safe.sh
```

무거운 검사는 공통 지시의 heavy-lock으로 감싼다. backend 전체와 e2e 및 브라우저 전체는 CI에서 확인한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/context/ContextProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/MemoryFreshnessTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/context/ContextPropertiesTest.java` | 신규 |
| `docs/backend/context-bundle.md` | 수정 |
