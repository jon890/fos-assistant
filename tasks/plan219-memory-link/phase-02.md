# Phase 02. 자동 RELATED (PR 2b)

**Execution profile**: standard

## 목표

임베딩 코사인 상위 k 안에 서로 드는 같은 범위의 쌍을 `ACTIVE` `RELATED`(`origin=AUTO`)로 맺는다. 항목마다 링크 수에 상한을 둔다. 정밀도를 운영 집계로 표본 확인한다.

**범위 외**: 조립에 쓰는 것(phase 03).

## 컨텍스트

**근거 문서**: ADR 「결정」 의 「`RELATED` 는 자동으로 맺고 바로 `ACTIVE` 다」 와 「감당할 것」(틀린 자동 링크), `docs/features/memory.md` 의 「운영 집계」.

## 작업 항목

### 1. `RelatedLinker.java` 신규

- `memory/application/link/RelatedLinker.java` 신규: phase 01 과 같은 훅에서, 새로 쓴 항목의 같은 범위 후보 가운데 코사인 상위 `related-top-k`(기본 3)이고 상대의 상위 k 에도 드는 쌍만 맺는다. `related-min-similarity`(기본 0.7) 아래는 맺지 않는다. 항목마다 `ACTIVE` `RELATED` 는 `related-max-per-item`(기본 5)까지

### 2. 맺지 않는 쌍

- 대체 후보 쌍(phase 01)과 이미 `REVOKED` 된 쌍은 맺지 않는다

### 3. 운영 집계

- `backend/src/test/resources/memory-eval/ops-metrics.sql` 에 `memory_links`(종류, origin, 상태별 수)와 `auto_link_revoked`(자동 링크 가운데 사람이 끊은 비율의 분자와 분모)를 더하고 `MemoryOpsMetricsSqlTest` 에 합성 줄로 확인한다

### 4. 이 phase 를 검증하는 테스트

- `RelatedLinkerTest`: 서로 상위 k 인 쌍만 맺는다. 한쪽만 상위면 맺지 않는다. 상한을 넘지 않는다. 다른 범위는 맺지 않는다. 끊은 쌍은 다시 맺지 않는다
- `MemoryOpsMetricsSqlTest` 의 새 두 지표

## 검증

```bash
cd backend && ./gradlew test --tests '*RelatedLinker*' --tests '*MemoryOpsMetricsSqlTest'
scripts/checks/check-mysql-migration.sh --tests '*MemoryOpsMetricsSqlMysqlTest'
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/memory/application/link/RelatedLinker.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/link/MemoryLinkProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/embedding/MemoryEmbeddingRefresher.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/resources/memory-eval/ops-metrics.sql` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/link/RelatedLinkerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryOpsMetricsSqlTest.java` | 수정 |
