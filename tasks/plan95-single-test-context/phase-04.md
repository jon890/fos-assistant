# Phase 04. 컨텍스트 수와 힙을 측정하고 보존 상한을 정한다

**Execution profile**: standard

## 목표

옮긴 뒤 `./gradlew test` 가 띄우는 컨텍스트가 3개 이하이고, Full GC 가 main 기준선 이하인지 측정한다.
보존 상한을 그 수에 맞추고, 전체 검사가 세 번 연속 통과하는 것을 확인한다.

**범위 외**: 검사 코드 변경. 측정 중 실패가 나면 원인 검사를 고치되 설계를 바꾸지 않는다.

## 컨텍스트

**근거 문서**: `docs/backend/testing.md` 「컨텍스트 수 확인」, `docs/adr/ADR-20261007-test-context-base.md`.

main 기준선(2026-10-07, main `916ed675`, 같은 계측): 컨텍스트 기동 79번, 기동 합계 102초, 검사 JVM 192초, Full GC 2번(GC 뒤 583MB, 685MB).
성공 기준(사용자 합의): `HikariPool-N` 의 가장 큰 N 이 3 이하, Full GC 2번 이하, 전체 검사 세 번 연속 통과.

## 작업 항목

### 1. 측정

GC 로그를 켜는 Gradle init 스크립트(Groovy, `.gradle`)를 저장소 밖 임시 디렉터리에 둔다.

```groovy
allprojects {
  tasks.withType(Test).configureEach {
    jvmArgs "-Xlog:gc:file=<GC 로그 경로>"
  }
}
```

`cd backend && ./gradlew test --rerun-tasks --init-script <그 파일>` 을 돌리고 아래를 표로 낸다.
`HikariPool-N` 최댓값, `Started <클래스> in <초> seconds` 줄 수와 초 합, 각 xml 의 `<testsuite time>` 합, GC 로그의 `Pause Full` 수와 GC 뒤 크기, 마지막 시각, 벽시계 시간.

### 2. 보존 상한

`backend/build.gradle.kts` 의 `spring.test.context.cache.maxSize` 를 측정한 컨텍스트 수보다 큰 가장 작은 값(예: 4)으로 둔다.
위 주석을 이 상한이 무엇을 정하는지, 측정 날짜와 컨텍스트 수와 Full GC 수로 고쳐 쓴다.

### 3. 세 번 연속 통과

`cd backend && ./gradlew test --rerun-tasks` 를 세 번 연속 돌린다. 하나라도 실패하면 원인을 고치고 처음부터 다시 돈다.

### 4. 문서의 수치

`docs/adr/ADR-20261007-test-context-base.md` 의 「결과 > 얻는 것」 첫 항목에 측정한 기동 수와 Full GC 수를 더한다.

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test --rerun-tasks)
(cd backend && ./gradlew test --rerun-tasks)
(cd backend && ./gradlew test --rerun-tasks)
(cd backend && ./gradlew qualityCheck)
scripts/quality.sh check
scripts/check-public-safe.sh
```

- 모두 종료 코드 0. 세 번의 전체 검사는 연달아 돌린다
- 마지막 실행의 `backend/build/test-results/test/*.xml` 에서 `HikariPool-N` 의 가장 큰 N 이 3 이하다
- 작업 항목 1 의 GC 로그에 `Pause Full` 이 2번 이하다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/build.gradle.kts` | 수정 |
| `docs/adr/ADR-20261007-test-context-base.md` | 수정 |
