# Phase 03. 관리자 화면과 e2e 대역을 재시작 없는 반영에 맞춘다

**Execution profile**: standard

## 목표

관리자 「연결 반영 확인」 은 재시작 대기 바인딩에만 반영 완료 단추와 재시작 안내를 보인다.
재시작 대기가 아닌 `PENDING` 바인딩은 「반영 중」 으로 보이고 단추가 없다. Control Plane 이 스스로 확인하기 때문이다.
e2e 의 가짜 대시보드가 실제 plugin 처럼 `reload_pending` 을 답하고, 붙이기 시나리오가 관리자 반영 완료 없이 `READY` 가 되는 것을 확인한다.

**범위 외**: plugin 응답은 phase 01, backend 예약 확인은 phase 02 가 만든다. 사용자의 연결 화면 문구 「준비 중」 은 그대로 둔다.

## 컨텍스트

- 관리자 화면은 `web/src/components/connector/connector-admin-panel.tsx` 다. `statusText` 가 `restartRequired` 면 「반영 대기」, `PENDING` 이면 「준비 중」 을 낸다. `waiting` 목록은 `restartRequired`, `PENDING`, `undeclaredTools > 0` 인 줄이다. 단추와 「공유 gateway 를 재시작한 뒤 눌러 주세요.」 는 `restartRequired || status === "PENDING"` 일 때 보인다
- 화면 타입과 문구는 `web/src/lib/connection.ts` 에 있다. 3행의 `BindingStatus` 주석이 「붙인 직후는 관리자 반영 완료 전까지 `PENDING` 이다」 라고 적는다
- 브라우저 시험은 `test/browser/connector-connection.spec.ts` 의 `adminBinding()` 대역과 「관리자는 바인딩마다 한 줄을 보고 …」 시험이다
- e2e 대역은 `test/e2e/fake-hermes.ts` 의 `bindingInstall` 이다. 지금은 붙이기에서 `answer(changed, changed)` 로 바뀌면 재시작이 필요하다고 답한다. 실제 plugin 은 phase 01 뒤로 새 서버면 `restart_required: false, reload_pending: true`, 이미 붙은 서버의 값이 바뀌면 `restart_required: true` 로 답한다(`docs/backend/connector-install.md` 의 「바인딩 설치」)
- e2e 공용 보조 `test/e2e/connector-support.ts` 의 `attach()` 는 붙인 직후 `restartRequired` 가 참이라고 단언하고 `confirm()` 한다. `ConnectorSetup` 과 connector-policy, proactive-check, delivery-retry, notifications, scheduled-task 시나리오가 이 함수를 쓴다
- 브라우저 시험 `test/browser/admin-area.spec.ts` 190-224행은 실제 Control Plane 과 가짜 대시보드로 붙인 뒤 `restartRequired: true`, 「반영 대기」, 「반영 완료」 단추를 단언한다. 브라우저용 backend 환경(`test/browser/fixtures.ts`)은 반영 지연과 일정을 정하지 않아 기본값(150초, 30초 주기)이 쓰인다
- phase 02 가 `ConnectorPolicyService.BINDING_PENDING_MESSAGE` 를 「이 에이전트에 붙인 연결이 아직 반영되지 않았다. 대개 몇 분 안에 저절로 반영되니 사용자에게 잠시 뒤 다시 시도하라고 알린다.」 로 바꿨다
- e2e 시나리오는 `test/e2e/scenarios/connector-binding.ts` 다. 붙인 뒤 `restartRequired` 를 단언하고, 반영 전 호출이 막히는지 본 다음 `confirm()` 으로 `READY` 를 만든다. backend 설정은 `test/e2e/run.ts` 의 환경 값(`ASSISTANT_CONNECTOR_POLICY_EXPIRE_CRON` 등)으로 준다
- phase 02 가 `assistant.connector.binding.apply-delay`, `assistant.connector.binding.apply-cron` 을 더했다

**근거 문서**: `docs/adr/ADR-20261007-connector-live-reload.md`, `docs/connectors.md` 의 「관리자 반영 완료」

## 의도 메모

- e2e 의 지연은 10초로 두고 일정은 매초 돈다. 반영 전 호출이 막히는지 보는 단계를 붙인 직후로 옮기고, 그 뒤 `READY` 를 40초까지 폴링한다
- 값 교체가 재시작 대기로 남는 흐름은 관리자 반영 완료로 계속 확인한다. 붙인 연결의 값을 다시 등록해 재시작 대기를 만든 뒤 `confirm()` 하는 단계를 connector-binding 시나리오에 더한다
- 브라우저 `admin-area.spec.ts` 는 기본 지연 150초 안에 끝나므로, 붙인 직후 줄이 「반영 중」 이고 「반영 완료」 단추가 없다고 바꿔 단언한다

## 작업 항목

### 1. `web/src/components/connector/connector-admin-panel.tsx`

- `statusText`: `restartRequired` 면 「반영 대기」, `PENDING` 이면 「반영 중」, 그 밖은 「붙음」
- 단추와 재시작 안내는 `restartRequired` 일 때만 보인다
- `CardDescription` 을 「재시작이 필요한 연결만 공유 gateway 를 재시작한 뒤 확인해요. 나머지는 1-2분 안에 저절로 반영돼요.」 로 바꾼다

### 2. `web/src/lib/connection.ts`

`BindingStatus` 와 바인딩 타입 주석을 새 흐름으로 고친다. 동작은 바꾸지 않는다.

### 3. `test/browser/connector-connection.spec.ts`

기존 시험은 그대로 두고, 재시작 대기가 아닌 `PENDING` 줄은 「반영 중」 으로 보이고 「반영 완료」 단추가 없다는 시험을 더한다.

### 4. `test/e2e/fake-hermes.ts` 와 `test/e2e/run.ts`

- `bindingInstall` 의 붙이기 답에 `reload_pending` 을 더한다. 붙기 전이던 커넥터면 `restart_required: false`, `reload_pending: changed` 이고, 이미 붙어 있던 커넥터의 값이 바뀌면 `restart_required: true` 다. 떼기는 `reload_pending: changed` 다. 주석을 새 판정으로 고친다
- `run.ts` 의 backend 환경 값에 반영 지연 10초와 매초 일정을 더한다. 이름은 같은 파일의 `ASSISTANT_CONNECTOR_POLICY_*` 와 같은 모양으로 짓는다

### 4-1. `test/e2e/connector-support.ts`

`attach()` 를 「붙인 직후 `PENDING` 이고 `restartRequired` 가 거짓 → `READY` 가 될 때까지 40초 폴링」 으로 바꾼다. 파일 머리 주석(1-7행)과 `attach` 주석도 고친다. `confirm()` 보조는 남긴다.

### 4-2. `test/browser/admin-area.spec.ts`

190-224행의 단언을 의도 메모대로 바꾼다.

### 5. `test/e2e/scenarios/connector-binding.ts`

- `BINDING_PENDING` 상수를 phase 02 의 새 글로 바꾼다
- 붙인 직후 단언을 `PENDING` 이고 `restartRequired` 가 거짓인 것으로 바꾼다. 단계 이름과 실패 글도 고친다
- 「관리자가 반영 완료를 누르면 READY 다」 단계를 「관리자 반영 완료 없이 READY 가 된다」 로 바꾸고 폴링한다
- 값 교체의 재시작 대기와 관리자 반영 완료 확인은 의도 메모대로 남긴다

### 6. 문서

`docs/connectors.md` 의 관리자 화면 설명이 phase 02 에서 고친 흐름과 다르면 같이 고친다. phase 02 에서 이미 맞췄으면 건드리지 않는다.

## 검증

```bash
# cwd: 저장소 root
(cd web && pnpm lint && pnpm typecheck)
(cd web && pnpm test:browser connector-connection.spec.ts admin-area.spec.ts)
lockf /tmp/fos-assistant-heavy.lock node test/e2e/run.ts
```

- 모두 종료 코드 0. 브라우저 검사 전에 `/Users/nhn/personal/fos-assistant/.omc/scripts/wait-browser.sh` 를 실행한다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/components/connector/connector-admin-panel.tsx` | 수정 |
| `web/src/lib/connection.ts` | 수정 |
| `test/browser/connector-connection.spec.ts` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/run.ts` | 수정 |
| `test/e2e/connector-support.ts` | 수정 |
| `test/browser/admin-area.spec.ts` | 수정 |
| `test/e2e/scenarios/connector-binding.ts` | 수정 |
| `docs/connectors.md` | 수정 |
