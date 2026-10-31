# Phase 01. backend 테스트를 JVM 두 개로 나눠 돌린다

**Execution profile**: standard

## 목표

`maxParallelForks = 2` 로 unit 과 MySQL 테스트를 JVM 두 개에 나눈다. 지금은 JVM 하나에서 직렬로 돌아 4 vCPU runner 의 CPU 대부분이 논다.
앞선 plan 으로 줄인 뒤 남은 약 6.5분이 4분 안팎이 될 것으로 추정하며 실측이 필요하다.

**범위 외**: hermes-tests job(phase 02, 03).

## 컨텍스트

**근거 문서**: `backend/docs/adr/ADR-20261007-test-context-base.md`

- 테스트가 함께 쓰는 `build/test-*` 디렉터리가 JVM 사이에 겹친다. JVM 마다 다른 디렉터리를 주어야 한다.
- 공용 컨텍스트 기동이 JVM 마다 한 번 늘어난다(약 15초).
- MySQL 은 `MysqlTestDatabase.shared()` 가 JVM 마다 DB 하나를 만들어 JVM 마다 데이터가 겹치지 않는다.

## 의도 메모

- 병렬화는 앞선 plan 으로 단계를 줄인 뒤에 재야 효과가 보인다.
- 측정은 성공한 실행에도 짧은 보존 기간으로 보고서를 올려 CI 에서 한다.

## 작업 항목

### 1. 병렬 설정

`backend/build.gradle.kts` 의 `tasks.test` 와 `mysqlMigrationTest` 에 `maxParallelForks` 를 주고, 공유 디렉터리를 쓰는 검사를 찾아 포크 번호가 든 경로로 바꾼다.

### 2. 보고서 업로드

`.github/workflows/ci.yml` 의 보고서 업로드를 성공한 실행에도 1일 보존으로 올린다.

## 검증

```bash
cd backend && ./gradlew test
```

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/build.gradle.kts` | 수정 |
| `.github/workflows/ci.yml` | 수정 |
