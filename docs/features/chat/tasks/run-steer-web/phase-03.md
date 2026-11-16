# Phase 03. E2E 가짜 Hermes 의 steer 와 시나리오, 브라우저 검사, 문서

**Execution profile**: standard

## 목표

E2E 가짜 Hermes 가 steer 경로, `run.steered`, 끝난 실행의 `pending_steer` 를 낸다. 설정을 켠 E2E 와 브라우저 검사로 한 흐름을 본다. 기능 파일과 계약 문서를 지금 동작으로 고친다.

**범위 외**: 운영 왕복(REQ10).

## 컨텍스트

**요구사항**: REQ1, REQ3, REQ9

- 가짜 Hermes: `test/e2e/fake-hermes/`. 붙잡은 실행(`holdNextRun`)은 `running` 으로 남고, 풀면 `completed` 가 된다. Control Plane 은 끝을 `GET /v1/runs/{id}` 로 읽고 `pending_steer` 도 거기서 읽는다.
- E2E 와 브라우저 검사는 같은 가짜 Hermes 를 쓴다. 설정을 켜면 입력창 글이 모두 steer 를 부르므로, 지금 대기열 검사가 흔들리지 않게 가짜는 steer 검사 입력으로 연 실행만 받고 나머지는 409 `run_not_accepting_steer` 로 거절한다.
- 공개 저장소다. 운영 주소와 명령을 적지 않는다.

## 작업 항목

1. `scenario-fixtures.ts`: `STEER_PROBE`(도구 둘, 그 사이에 끼운 글을 넣는다), `STEER_UNREAD_PROBE`(도구 없이 답만 써 끼운 글을 `pending_steer` 로 돌려준다).
2. `state.ts`, `run-routes.ts`, `lifecycle.ts`, `event-routes.ts`, `control-routes.ts`, `fake-hermes.ts`: steer 경로, 슬롯, `run.steered`, 끝날 때 `pending_steer`, 중지 때 슬롯 비우기, `steerInputs()`.
3. `test/e2e/run.ts`: `ASSISTANT_CHAT_STEER_ENABLED=true`. `scenarios/chat-queue.ts` 에 steer 한 흐름(받음과 말풍선 자리, 돌아온 글로 바로 다음 turn, 중지 뒤 되돌림).
4. `web/test/browser/fixtures.ts` 에 같은 설정, `chat-steer.spec.ts`: 답 위 말풍선과 「전달됨」, 다시 생성 단추 없음.
5. `docs/features/chat.md` 의 「도는 실행에 끼워 넣기」 에 화면 동작, `hermes/docs/hermes-contract.md` 의 가짜 Hermes 줄, 스펙 머리의 「구현을 마친 변경의 기록」 한 줄. 계획서를 지운다.

## 검증

```bash
# cwd: 저장소 root
npx --yes tsx --test test/repo/doc-files.test.ts
# E2E 와 브라우저 검사는 무거운 검사 대기열로 돌린다
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `test/e2e/fake-hermes/*.ts`, `test/e2e/fake-hermes.ts`, `test/e2e/run.ts`, `test/e2e/scenarios/chat-queue.ts` | 수정 |
| `web/test/browser/fixtures.ts`, `web/test/browser/chat-steer.spec.ts` | 수정·신규 |
| `docs/features/chat.md`, `docs/features/chat/spec.md`, `hermes/docs/hermes-contract.md` | 수정 |
