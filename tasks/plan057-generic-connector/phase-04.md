# Phase 04. 두 번째 커넥터가 코드 변경 없이 붙는지 e2e 로 보고 서비스 이름이 들어오지 않게 막는다

**Execution profile**: standard

## 목표

이 plan 의 성공 기준 두 가지를 검사로 남긴다.
1. 시험 커넥터 하나를 manifest 만으로 붙여 카탈로그부터 READY 와 해제까지 전체 흐름이 돈다
2. `backend/src/main` 과 `web/src` 에 특정 서비스의 이름이 다시 들어오지 않는다

**범위 외**: 각 층의 단위 검사(phase 01~03).

## 컨텍스트

- `test/e2e/` 는 Hermes 대역(`test/e2e/fake-hermes.ts`)을 같은 프로세스에 띄워 Control Plane 과 함께 돌린다. 시나리오는 `test/e2e/scenarios/` 에 하나씩 있고 `test/e2e/run.ts` 가 차례로 부른다. 지금 대역에는 대시보드의 `/api/env` 가 있고 커넥터 경로는 없다
- 단위 검사는 `node --test 'test/unit/**/*.test.ts'` 로 돈다. 다른 검사 모양은 `test/unit/control-plane-result.test.ts` 를 본다

**근거 문서**: `docs/connectors.md` 의 「Control Plane API」, 「대시보드 plugin 계약」, 「설치와 실패 처리」, `docs/code-architecture.md` 의 `connector` 설명

## 의도 메모

- e2e 대역은 대시보드 plugin 의 커넥터 경로를 응답 모양만 흉내 낸다. 실제 MCP 자식 실행은 phase 01 의 Python 검사가 이미 본다
- 대역의 시험 커넥터는 이름도 칸도 가계부와 다르게 둔다(`demo-notes`, 칸 `token`, `scope`). 가계부와 같은 모양이면 범용화가 실제로 됐는지 보이지 않는다
- 서비스 이름 검사는 이름 목록을 검사 파일 안에 둔다. 마이그레이션 파일(`V36`, `V38`)과 옛 경로를 넘기는 페이지는 제외한다

## 작업 항목

### 1. `test/e2e/fake-hermes.ts` 에 커넥터 경로를 더한다

`GET /api/connectors/catalog`(시험 커넥터 하나), `POST /api/connectors/{id}/call`(토큰 `demo_ok` 면 `{ok: true, result: {"scopes": [{"id": "a", "name": "A"}]}}`, `demo_bad` 면 `credential_rejected`), `GET PUT /api/connectors`, 커넥터 key 의 `PUT DELETE /api/env`, `POST /api/mcp/servers/demo/test`. 응답 모양은 `docs/connectors.md` 표와 같게 한다. 받은 요청을 기록해 시나리오가 순서를 단언할 수 있게 한다.

### 2. `test/e2e/scenarios/connector.ts` (신규)

`test/e2e/run.ts` 에 등록한다.
- 카탈로그에 `demo-notes` 가 보인다
- 선택지 조회 → `[{value: "a", label: "A"}]`
- `demo_bad` 로 등록 → 400 `CONNECTOR_CREDENTIAL_REJECTED`, 연결 행 없음
- `demo_ok` 로 등록 → `PENDING`, 대역이 받은 순서가 확인 → env → 도구 목록 → 설치
- 연결 확인 → `READY`, 상태 응답의 `secretPrefixes.token` 이 앞 8자이고 원문이 응답 어디에도 없음
- 해제 → `DISCONNECTED`
- 다른 사용자는 이 연결을 읽지 못한다

### 3. `test/unit/connector-neutral.test.ts` (신규)

`git ls-files backend/src/main web/src` 의 파일을 읽어 금지 낱말(`accountbook`, `ACCOUNTBOOK_`, `fab_`, `가계부`)이 대소문자 무시로 없는지 본다. 제외: `backend/src/main/resources/db/migration/V36__accountbook_connection.sql`, `backend/src/main/resources/db/migration/V38__connector_connection.sql`, `web/src/app/connections/accountbook/page.tsx`.
- 정상: 지금 트리에서 통과
- 실패 갈래: 금지 낱말이 든 가짜 파일 목록을 검사 함수에 넘기면 그 파일 이름을 돌려준다(검사 함수를 따로 export 해 시험한다)

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test && cd ..
node test/e2e/run.ts
node --test test/unit/connector-neutral.test.ts
node --test 'test/unit/**/*.test.ts'
scripts/quality.sh check
scripts/check-public-safe.sh
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/connector.ts` | 신규 |
| `test/e2e/run.ts` | 수정 |
| `test/unit/connector-neutral.test.ts` | 신규 |
