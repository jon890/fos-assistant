# Phase 01. 실행 속도에 흔들리는 시간 의존 테스트를 고친다

**Execution profile**: standard

## 목표

`hermes/connectors/naver-blog/tests/worker.test.ts` 의 「실제 Chrome의 제목 대기 중 작업 시간 상한」 처럼 CI 러너 속도에 따라 실패하는 시간 의존 테스트를 가짜 시계나 넉넉한 상한으로 고친다.

**범위 외**: 테스트 시간 단축 자체(병렬화 계획이 갖는다).

## 컨텍스트

**근거 문서**: `hermes/AGENTS.md`, `docs/features/naver-blog-connector.md`

- 실패하는 테스트가 확인하려는 것은 상한이 지나면 작업이 끝난다는 동작이다. 실제 시간을 재는 것이 아니다.
- 다른 시간 의존 테스트는 `git grep -n "setTimeout\|Date.now" -- hermes/connectors/*/tests` 로 후보를 모은다.

## 의도 메모

- 가짜 시계로 바꾸면 상한 계산을 빠르게 볼 수 있다. 실제 Chrome 을 쓰는 검사는 상한을 넉넉히 두고 상한 계산은 가짜 시계 검사가 맡는다.

## 작업 항목

### 1. 후보 분류

시간 의존 테스트를 모아 흔들린 이력이 있는 것부터 가짜 시계로 바꿀 수 있는지 분류한다.

### 2. 수정

바꿀 수 있는 것은 가짜 시계로, 아닌 것은 상한을 넉넉히 둔다.

## 검증

```bash
cd hermes/connectors/naver-blog && bun test ./tests/worker.test.ts
```

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `hermes/connectors/naver-blog/tests/worker.test.ts` | 수정 |
