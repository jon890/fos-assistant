# Phase 03. 가짜 Hermes 를 실제 모양으로 맞추고 e2e 가 새 경로로 자식 금액을 확인한다

**Execution profile**: standard

## 목표

가짜 Hermes 의 session 응답에서 provider 를 빼고, 대시보드 대역에 `GET /api/profiles/<이름>/sessions/<session id>/provider` 를 더한다.
지금 가짜는 session 응답에 `billing_provider` 를 실어 실제 Hermes 와 다르고, 그래서 e2e 는 운영에서 동작하지 않는 길로 통과한다.

**범위 외**: plugin(phase 01), backend(phase 02), 화면, 브라우저 검사.

## 컨텍스트

- 가짜는 `test/e2e/fake-hermes.ts` 다. 자식 session 은 `childUsages` Map 이 갖고, 값에 `profile`, `model`, `provider` 가 있다
- API server 쪽 session 응답은 `SESSION_PATH` 분기가 만든다. 지금 `child.provider` 가 있으면 `billing_provider` 를 싣는다
- 대시보드 대역은 `handleDashboard` 다. `isDashboardPath` 로 맡을 경로를 고르고 `dashboardAuthorized` 로 토큰을 본다. `MODEL_DEFAULTS_PATH` 분기가 본보기다
- `PROFILE_PATH` 의 `.+` 는 뒤의 조각까지 먹는다. 새 경로는 `SOUL_PATH` 처럼 `PROFILE_PATH` 분기보다 앞에서 처리한다
- 시나리오는 `test/e2e/scenarios/streaming.ts` 다. 「session 의 provider 와 모델로 자식 금액을 합계에 한 번만 더한다」 단계가 `SUBAGENT_PROVIDER_PROBE` 로 자식 금액을 확인하고, 그 앞 단계가 provider 없는 자식 3건이 가격 미확인으로 세어지는지 본다

**근거 문서**: `docs/hermes/delegation.md` 의 「자식 session 의 provider 는 저장소에만 있다」, `hermes/README.md` 의 「자식 session 의 provider」

## 의도 메모

- 가짜가 실제와 다른 모양을 보내면 테스트는 통과하고 운영에서 동작하지 않는다. 실제 v0.21.5 의 session 응답에는 provider 가 없다
- 금액 기대값(`PARENT_RUN_MICROS`, `CHILD_RUN_MICROS`)은 바꾸지 않는다. provider 와 모델이 같아 금액이 같다

## 작업 항목

### 1. `test/e2e/fake-hermes.ts` 를 고친다

- `SESSION_PATH` 분기의 자식 응답에서 `billing_provider` 를 싣는 줄을 지운다
- 상수 `SESSION_PROVIDER_PATH = /^\/api\/profiles\/([^/]+)\/sessions\/([^/]+)\/provider$/` 를 `MODEL_DEFAULTS_PATH` 옆에 더한다
- `handleDashboard` 가 이 경로를 대시보드 경로로 보게 하고, `PROFILE_PATH` 분기보다 앞에 `GET` 분기를 더한다

  | 상황 | 답 |
  | --- | --- |
  | `childUsages` 에 그 session 이 있고 `profile` 이 같다 | 200 `{ provider: child.provider ?? null, model: child.model ?? "example-fast" }` |
  | 없다, 또는 profile 이 다르다 | 404 `{ detail: "없는 session 이다" }` |

  토큰이 틀리면 지금의 공통 분기가 401 을 준다
- `childUsages` 의 주석을 고친다. provider 는 session 응답이 아니라 대시보드 경로가 준다

### 2. `test/e2e/scenarios/streaming.ts` 의 단계를 고친다

- 단계 이름을 「대시보드에서 읽은 provider 와 session 의 모델로 자식 금액을 합계에 한 번만 더한다」 로 바꾼다
- provider 없는 자식 3건의 실패 문구를 「대시보드가 provider 를 주지 않은 자식 3건이 가격 미확인으로 세어지지 않았다」 로 바꾼다
- 나머지 단언은 그대로 둔다. 가짜가 session 응답에서 provider 를 뺐으므로, 이 단계가 통과하면 금액이 대시보드 경로로 온 것이다

## 검증

```bash
# cwd: 저장소 root
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
! git grep -n "billing_provider: child.provider" -- test/e2e/fake-hermes.ts
```

`node test/e2e/run.ts` 는 backend 를 빌드해 띄우고 `test/e2e/scenarios/` 의 시나리오를 모두 돌린다. `streaming` 시나리오가 통과해야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/streaming.ts` | 수정 |
