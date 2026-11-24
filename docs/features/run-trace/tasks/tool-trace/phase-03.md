# Phase 03. plugin 이 도구 기록을 기다리지 않고 보낸다

**Execution profile**: standard

## 목표

fos-ctx 가 `post_tool_call` 에서 숫자와 오류 글만 뽑아 대기열에 넣고, 데몬 스레드가 모아 Control Plane 에 보낸다.

**범위 외**: 모델 호출 hook(PR3).

## 컨텍스트

**요구사항**: REQ6, REQ7

**근거 문서**: `docs/features/run-trace/design.md` 의 「plugin 에서 Control Plane 으로」, 「실패와 일관성」, `hermes/plugins/fos-ctx/README.md`, `hermes/docs/hermes-contract.md` 의 「plugin hook」

- 주소는 `tool_guard.guard_url` 처럼 `FOS_CTX_POLICY_URL` 의 마지막 조각을 바꿔 만든다. 서명 key 는 `context.signing_key`.
- Hermes 는 `post_tool_call` 을 제한 시간 worker 에서 부르지만 그동안 도구 루프가 기다린다. 그래서 hook 은 대기열에 넣기만 한다.

## 의도 메모

- 다시 보내지 않는다. 기록은 관측용이고 실행보다 중요하지 않다(스펙 REQ7).
- 인자, 결과, `middleware_trace` 는 본문에 넣지 않는다. 테스트가 본문 칸 목록을 고정한다.

## 작업 항목

### 1. `hermes/plugins/fos-ctx/run_trace.py` (신규)

`post_tool_call(**kwargs)` 가 기록을 만들어 `queue.Queue(maxsize=1000)` 에 `put_nowait` 한다. 가득 차면 버리고 센다. 처음 부를 때 데몬 스레드를 띄운다. 스레드는 0.5초 모은 뒤 100개까지 한 번에 보낸다. 루트 session 은 스레드가 `context.root_session` 으로 찾는다. 실패는 종류만 `logger.warning` 으로 남긴다.

### 2. `__init__.py`, `plugin.yaml`, `README.md`

hook 등록, `hooks` 에 `post_tool_call`, 버전 1.19.0, README 에 「도구 기록을 보낸다」 계약 절(주소, 인증, 본문, 서명할 글, 답, 확인 값).

### 3. `hermes/tests/hermes_contract.py`, `hermes/tests/test_hermes_contract.py`

`post_tool_call` 이 `model_tools.py` 에서 `tool_name`, `duration_ms`, `status`, `error_type`, `error_message` 를 넘기고, 식별 칸이 `_CallIds` 의 `session_id`, `tool_call_id` 에서 오는지 소스로 확인한다.

### 4. `hermes/tests/test_fos_ctx_run_trace.py` (신규)

- hook 이 보내기를 기다리지 않는다(5초 늦는 가짜 서버에서도 바로 돌아온다). 500 이어도 예외가 없다.
- 본문 칸이 정해진 목록뿐이고 인자와 결과의 표식 글이 본문에 없다.
- 서명이 README 의 확인 값과 같다. backend `RunTraceEndpointTest` 도 같은 값을 쓴다.

## 검증

```bash
cd hermes && python3 -m unittest tests.test_fos_ctx_run_trace tests.test_fos_ctx tests.test_hermes_contract
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/fos-ctx/run_trace.py` | 신규 |
| `hermes/plugins/fos-ctx/__init__.py` | 수정 |
| `hermes/plugins/fos-ctx/plugin.yaml` | 수정 |
| `hermes/plugins/fos-ctx/README.md` | 수정 |
| `hermes/tests/hermes_contract.py` | 수정 |
| `hermes/tests/test_hermes_contract.py` | 수정 |
| `hermes/tests/test_fos_ctx_run_trace.py` | 신규 |
