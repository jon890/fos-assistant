# Phase 01. 표본 패키지의 도메인 로직 검사를 단위 테스트로 내린다

**Execution profile**: standard

## 목표

`@BackendIntegrationTest` 로 도는 검사 가운데 도메인 로직만 확인하는 것을 Spring 과 DB 없는 단위 테스트로 내린다. 표본 패키지 하나(`memory` 권장)로 시작해 줄어드는 시간과 덜 잡게 되는 것을 재고, 효과가 있으면 다음 패키지로 넓힌다.

**범위 외**: 실제 DB 동작(행 잠금, 정렬 규칙, 마이그레이션 DDL)에 기대는 검사. 그것은 MySQL 검사로 남긴다.

## 컨텍스트

**근거 문서**: `backend/docs/adr/ADR-20261007-test-context-base.md` 의 「MySQL 검사」 원칙, `backend/docs/code-architecture.md` 의 「통합 검사」

- 검사 하나가 공용 컨텍스트를 쓰므로 기동 비용은 이미 한 번뿐이다. 줄어드는 것은 검사마다 드는 트랜잭션과 JPA 비용, 그리고 속도에 따른 흔들림이다.
- 단위로 내릴 수 있는 기준: 저장소를 거치지 않고 서비스·도메인 객체의 입력과 출력만 단언한다.

## 의도 메모

- 저장소 쿼리와 트랜잭션 경계를 확인하는 검사는 내리지 않는다. 내려서 덜 잡는 것은 PR 본문에 목록으로 둔다.

## 작업 항목

### 1. 표본 선정과 분류

`memory` 패키지의 `@BackendIntegrationTest` 클래스를 모아 검사마다 저장소 의존 여부를 분류한다.

### 2. 단위 테스트로 이동

저장소에 기대지 않는 검사를 순수 단위 테스트로 옮기고 통합 쪽 중복을 지운다.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.memory.*'
cd backend && ./gradlew archTest
```

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryServiceTest.java` | 수정 |
