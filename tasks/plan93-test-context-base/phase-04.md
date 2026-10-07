# Phase 04. 컨텍스트 수와 힙을 측정해 보존 상한을 정한다

**Execution profile**: standard

## 목표

옮긴 뒤의 컨텍스트 수, 기동 시간, 전체 검사 시간, 힙을 측정하고, 그 값으로 `spring.test.context.cache.maxSize` 를 정한다.
backend 전체 검사가 세 번 연속 통과하는 것을 확인한다.

**범위 외**: 검사 코드 변경. 측정 중 실패가 나오면 원인 검사를 고치는 것은 이 phase 안에서 하되, 기반이나 변형의 설계를 바꾸지 않는다.

## 컨텍스트

**근거 문서**: `docs/backend/testing.md` 의 「보존 상한과 측정」, `docs/adr/ADR-095-backend-통합-검사는-공통-기반-컨텍스트-하나를-함께-쓰고-기동-설정이-다른-검사만-변형을-둔다.md`.

옮기기 전 기준값은 2026-10-07 에 측정했다. `@SpringBootTest` 검사 141개, 컨텍스트 기동 77번, 기동 합계 78.2초, 검사 클래스 시간 합계 161.7초, 검사 JVM 실행 163초,
young GC 뒤 힙 중앙값 798MB(1g 힙), Full GC 2번이었다. 보존 상한은 16 이었다.

성공 기준(사용자 합의, 2026-10-07 측정 뒤 고침): 서로 다른 컨텍스트 키 37개를 모두 보존해 다시 뜨는 컨텍스트가 없고(상한 24 측정에서 missCount 37 = 키 수), Full GC 는 옮기기 전 수준(실행마다 2~3번), 전체 검사 세 번 연속 통과.
상한은 측정으로 24 로 정했다. 키 37 을 30 아래로 줄이는 것은 기동 설정을 주입 지점으로 바꾸는 다음 작업이 맡는다.

## 의도 메모

- 상한은 「밀려났다 다시 뜨는 컨텍스트가 없고 Full GC 가 없는 가장 작은 값」 으로 정한다. 컨텍스트가 서로 다른 키보다 많이 뜨면 밀려났다 다시 뜬 것이다.
- 힙 크기 `maxHeapSize = "1g"` 는 이 phase 에서 바꾸지 않는다. 바꿔야 할 측정이 나오면 PR 본문에 근거를 적고 멈춘다.

## 작업 항목

### 1. 측정

GC 로그와 Spring 컨텍스트 캐시 통계를 켜는 Gradle init 스크립트(Groovy, `.gradle`)를 저장소 밖 임시 디렉터리에 만든다.

```groovy
allprojects {
  tasks.withType(Test).configureEach {
    jvmArgs "-Xlog:gc:file=<GC 로그 경로>"
    systemProperty "logging.level.org.springframework.test.context.cache", "DEBUG"
  }
}
```
`cd backend && ./gradlew test --rerun-tasks --init-script <그 파일>` 을 돌리고 아래를 센다.

| 값 | 읽는 곳 |
| --- | --- |
| 컨텍스트 기동 수 | `backend/build/test-results/test/*.xml` 의 `HikariPool-N` 가장 큰 N, `Started <클래스> in <초> seconds` 의 줄 수 |
| 기동 합계 | 위 `Started` 줄의 초 합 |
| 검사 클래스 시간 합계 | 각 xml 의 `<testsuite time=...>` 합 |
| 서로 다른 컨텍스트 키 수 | 상한을 64 로 둔 실행에서, 모든 결과 xml 의 Spring 캐시 통계 줄(`Spring test ApplicationContext cache statistics`) 가운데 `missCount` 의 최댓값. 그 값이 같은 줄의 `size` 와 같아야 한다. xml 파일 순서는 실행 순서가 아니므로 최댓값을 쓴다 |
| 힙 | GC 로그의 young GC 뒤 크기 최댓값과 중앙값, `Pause Full` 줄 수, 마지막 시각(검사 JVM 실행 시간) |

### 2. 보존 상한

먼저 `backend/build.gradle.kts` 의 상한을 잠시 64 로 두고 전체 검사를 돌려 키 수를 정한다(위 표).
그다음 후보 「키 수」 와 「16」 으로 한 번씩 돌린다. 키 수가 16 이하면 키 수 하나만 돈다.
각 실행의 `missCount` 최댓값이 키 수와 같고 `Pause Full` 이 0 인 후보 가운데 가장 작은 값을 고른다. 둘 다 조건을 못 맞추면 측정값과 함께 멈추고 보고한다.
`backend/build.gradle.kts` 의 `systemProperty("spring.test.context.cache.maxSize", ...)` 를 그 값으로 둔다.
그 위 주석을 측정 날짜, 컨텍스트 수, 힙 값으로 고쳐 쓴다. 16 을 유지해도 주석의 근거는 새 측정으로 바꾼다.

### 3. 세 번 연속 통과

`cd backend && ./gradlew test --rerun-tasks` 를 세 번 연속 돌려 모두 통과하는지 본다. 하나라도 실패하면 원인을 고치고 세 번을 처음부터 다시 돈다.

### 4. 문서

`docs/backend/testing.md` 의 「보존 상한과 측정」 은 값을 갖지 않는다. 값은 `build.gradle.kts` 주석이 갖는다. 문서가 값을 적고 있으면 지운다.

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test --rerun-tasks)
(cd backend && ./gradlew test --rerun-tasks)
(cd backend && ./gradlew test --rerun-tasks)
(cd backend && ./gradlew qualityCheck)
scripts/check-local.sh
```

- 모두 종료 코드 0. 세 번의 전체 검사는 연달아 돌리고, 하나라도 실패하면 고친 뒤 세 번을 처음부터 다시 돈다
- 마지막 실행의 `backend/build/test-results/test/*.xml` 에서 `HikariPool-N` 의 가장 큰 N 이 서로 다른 키 수(37) 이하다
- 작업 항목 1 의 GC 로그에 `Pause Full` 이 3번 이하다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/build.gradle.kts` | 수정 |
| `docs/backend/testing.md` | 수정 |
