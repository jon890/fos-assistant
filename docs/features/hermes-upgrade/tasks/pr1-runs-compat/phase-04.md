# Phase 04. 가짜 Hermes 의 v0.23.2 모양과 문서

**Execution profile**: standard

## 목표

E2E 의 가짜 Hermes 가 v0.23.2 모양을 낼 수 있게 하고, 재기동 뒤 다음 turn 이 도는 장면 하나를 E2E 로 본다. ADR-061 과 계약 문서의 Runs 절을 v0.23.2 에 맞춘다.

**범위 외**: 승인과 fos-ctx 절(PR2), 기준 버전 절(PR3).

## 컨텍스트

**요구사항**: REQ2, REQ9

**근거 문서**: `docs/features/hermes-upgrade/spec.md` 의 「상류 소스로 확인한 사실」, `docs/features/hermes-upgrade/design.md` 의 「가짜 Hermes」, 「결정 기록 연결」

- 가짜 Hermes: `test/e2e/fake-hermes/run-routes.ts`(실행 제출, 조회, 중지, steer), `test/e2e/fake-hermes.ts`(기동과 상태). 시나리오는 `test/e2e/scenarios/`.
- 조회 응답 계약 문서: `hermes/docs/hermes-contract.md` 의 「Runs API」, 「실행 조회가 답하는 기간」, 「실행 이벤트가 실제로 오는 형태」, 「취소한 실행의 조회 응답」.
- 재기동 정리 결정: `backend/docs/adr/ADR-061-재기동-때-남은-실행은-hermes-에-물어-정하고-도는-실행에는-다시-붙는다.md`.
- 공개 저장소다. 운영 값(주소, 포트, 컨테이너 이름, 경로)을 문서와 시나리오에 쓰지 않는다.

## 의도 메모

- 가짜 Hermes 는 기본을 v0.21.6 모양으로 두고 시나리오가 제어 경로로 v0.23.2 모양을 켠다. 기본을 바꾸면 PR3 전까지 기존 시나리오가 운영과 다른 버전을 본다.
- 계약 문서의 버전 기준 절(「v0.21.6의 연동 기준」)은 PR3 이 고친다. 이 phase 는 Runs 관련 절에 v0.23.2 차이를 더하는 것만 한다.

## 작업 항목

### 1. 가짜 Hermes 의 v0.23.2 모양

- 제어 경로 `POST /__test/hermes-shape`(본문 `{"shape": "v0.23"}`)로 켜고, 기존 `/__test/reset-controls` 가 기본(v0.21.6 모양)으로 되돌린다. E2E 는 가짜 Hermes 하나로 모든 시나리오를 돌리므로 환경 값으로는 한 시나리오만 바꿀 수 없다(`test/e2e/run.ts`). 켜져 있으면: 조회 응답에서 `error`, `pending_steer` 를 빼고 `admission_id`, `execution_generation` 을 넣는다. 종료 사건에는 그 칸을 그대로 둔다.
- 제어 경로 하나(`test/e2e/fake-hermes/control-routes.ts` 의 기존 방식)로 「재기동 흉내」 를 연다. 도는 실행을 `unknown` 으로 바꾸고 사건 스트림을 닫는다. 같은 session 의 다음 실행은 해제 전까지 `queued` 다.
- `POST /v1/runs/{id}/resolve-unknown`: 본문 두 칸을 검사하고 그 실행을 끝 상태(`cancelled`)로 바꾼 뒤 대기를 푼다.
- 끝난 실행에 `GET /v1/runs/{id}/events` 를 열면 보관한 종료 사건을 보내고 닫는다(`test/e2e/fake-hermes/event-routes.ts`).

### 2. E2E 시나리오 하나

`test/e2e/scenarios/` 에 v0.23.2 모양으로 띄운 가짜 Hermes 에서: 대화 turn 을 도는 중에 재기동 흉내를 보내면 그 turn 이 실패로 보이고, 같은 대화에 다음 글을 보내면 답이 끝까지 온다. 기존 시나리오의 기동 도우미(`test/e2e/harness.ts`)를 따르고 `test/e2e/run.ts` 의 `SCENARIOS` 에 등록한다. 가짜 Hermes 의 v0.23.2 경로(재기동 흉내, 해제, 대기 풀림, 끝난 실행의 사건 보관분)는 `test/e2e/unit/fake-hermes-controls.test.ts` 에 단위 테스트를 더한다.

### 3. 문서

- ADR-061 의 Hermes 답 표에 `queued`(도는 중으로 본다)와 `unknown`(해제하고 `FAILED`, `REMOTE_RUN_LOST`) 줄을 더하고, 「잃은 실행 미리 풀기」 와 「`cancelled` 구분」 을 결과 절에 한 줄씩 적는다. 결정의 뜻은 바꾸지 않는다.
- `hermes/docs/hermes-contract.md`: Runs API 표에 `POST /v1/runs/{run_id}/resolve-unknown` 줄. 「실행 조회가 답하는 기간」 에 v0.23.2 의 디스크 기록, 재기동 뒤 `unknown`, 조회 응답 칸 차이를 표로 더한다. 「실행 이벤트가 실제로 오는 형태」 에 오지 않는 사건(DECISION2)과 `tool_call_id`, `duration` 0, `run.queued`, 정상 종료의 `run.interrupted` 를 더한다. 「운영 왕복으로 확인하지 않았다」 를 함께 적는다.
- `docs/features/hermes-upgrade.md`: 「아직 구현하지 않았다」 를 PR1 이 된 범위에 맞게 고친다.

## 검증

```bash
node --test test/e2e/unit
node --test test/repo/doc-files.test.ts
scripts/dev/check-local.sh --fast
```

E2E 전체(`node test/e2e/run.ts`)는 무거워 로컬에서 기다리지 않는다. `test/e2e/` 를 바꾸면 PR CI 의 경로 규칙이 E2E 를 돌리므로 새 시나리오는 Draft CI 결과로 본다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `test/e2e/fake-hermes/run-routes.ts` | 수정 |
| `test/e2e/fake-hermes/control-routes.ts` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/fake-hermes/event-routes.ts` | 수정 |
| `test/e2e/fake-hermes/state.ts` | 수정 (모양 상태) |
| `test/e2e/scenarios/hermes-v023-restart.ts` | 신규 |
| `test/e2e/run.ts` | 수정 (시나리오 등록) |
| `test/e2e/unit/fake-hermes-controls.test.ts` | 수정 |
| `backend/docs/adr/ADR-061-재기동-때-남은-실행은-hermes-에-물어-정하고-도는-실행에는-다시-붙는다.md` | 수정 |
| `hermes/docs/hermes-contract.md` | 수정 |
| `docs/features/hermes-upgrade.md` | 수정 |
