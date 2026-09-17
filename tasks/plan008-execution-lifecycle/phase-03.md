# Phase 03. 고아 실행을 정리하고 화면이 돌고 있는 실행을 보인다

**Execution profile**: standard

## 목표

기동할 때 `RUNNING` 으로 남아 있는 실행을 `FAILED` 로 정리한다.
사용량 화면이 돌고 있는 실행을 끝난 실행과 구분해 보인다.

**범위 외**: 실행을 취소하는 경로는 만들지 않는다. `CANCELLED` 는 상태값으로만 둔다.

## 컨텍스트

phase-02 가 `RUNNING` 상태를 만들었다.
그 상태로 남은 줄이 생기는 경로가 둘이다.
서버가 실행 도중에 죽는 것과 배포로 재시작되는 것이다.
둘 다 정리하지 않으면 그 줄이 영원히 `RUNNING` 으로 남는다.

이 Control Plane 은 한 대만 돈다.
그래서 기동 시점에 돌고 있는 실행이 없다는 것이 확실하다.
여러 대로 늘리면 이 방식은 다른 대가 돌리고 있는 실행을 끊게 되므로 그때 바꿔야 한다.

**근거 문서**: `docs/adr/ADR-011-실행은-시작할-때-기록하고-끝날-때-갱신한다.md` 의
「적용 범위」 와 「감당할 것」, `docs/data-schema.md` 의 「끝나지 않은 실행」 절

## 의도 메모

- 시간이 지나면 정리하는 안을 버렸다. 스케줄러가 필요하고, 한 대만 도는 지금은 기동 시점으로 충분하다.
  여러 대로 늘릴 때 이 결정을 다시 본다.
- `error_code` 를 `ORPHANED` 로 못 박는다.
  Hermes 가 돌려준 실패와 우리가 정리한 것을 나중에 구분할 수 있어야 한다.

## 작업 항목

### 1. `usage/application/OrphanedExecutionSweeper.java` 신규

기동이 끝난 뒤 한 번 돈다.

```java
@Component
@RequiredArgsConstructor
public class OrphanedExecutionSweeper {

    @EventListener(ApplicationReadyEvent.class)
    public void sweep() { ... }
}
```

`RUNNING` 인 줄을 모두 찾아 `FAILED` 로 바꾸고 `error_code` 를 `ORPHANED` 로 적는다.
`finished_at` 은 정리하는 지금 시각으로 적는다.
몇 줄을 정리했는지 로그로 남긴다. 0 줄이면 로그를 남기지 않는다.

`ApplicationReadyEvent` 를 쓰는 이유는 Flyway 가 끝난 뒤에 돌아야 하기 때문이다.
`@PostConstruct` 는 마이그레이션보다 먼저 돌 수 있다.

### 2. `usage/infra/AgentExecutionRepository.java` 에 조회를 더한다

```java
List<AgentExecution> findByStatus(ExecutionStatus status);
```

### 3. 화면이 돌고 있는 실행을 구분해 보인다

`web/src/components/usage/` 아래다.
실행 목록의 상태 칸이 지금 `SUCCEEDED` 와 `FAILED` 를 그린다.
`RUNNING` 을 더한다.

| 상태 | 화면 |
| --- | --- |
| `RUNNING` | 「도는 중」. 소요 시간과 금액 자리는 비워 둔다 |
| `SUCCEEDED` | 지금 그대로 |
| `FAILED` | 지금 그대로. `error_code` 가 `ORPHANED` 면 「중간에 끊김」 으로 보인다 |

**금액 자리를 0 으로 채우지 않는다.** 비워 둔다.
0 은 공짜라는 뜻으로 읽힌다. 기존 규칙이 그렇다.

### 4. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/usage/OrphanedExecutionSweeperTest.java` 를 새로 만든다.

- `RUNNING` 인 줄이 둘 있을 때 `sweep` 이 둘 다 `FAILED` 와 `ORPHANED` 로 바꾼다
- 이미 `SUCCEEDED` 인 줄은 건드리지 않는다
- `RUNNING` 이 없으면 아무것도 바꾸지 않는다

`test/browser/` 에 더한다.

- 사용량 화면이 `RUNNING` 인 실행을 「도는 중」 으로 보이고 금액 자리가 비어 있다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
```

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/usage/application/OrphanedExecutionSweeper.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `web/src/components/usage/` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/OrphanedExecutionSweeperTest.java` | 신규 |
| `test/browser/` | 수정 |

## 끝낸 뒤

`tasks/plan008-execution-lifecycle/index.json` 의 이 phase 를 `completed` 로 바꾸고
plan 의 `status` 를 `completed` 로 바꾼다.
