# Phase 03. 주문 내역을 실행 공간의 파일로 낸다

**Execution profile**: deep

## 목표

`list_orders` 에 `output: "file"` 을 더한다. 기간 전체의 주문을 JSON Lines 파일 하나로 쓰고 경로, 건수, 기간, 칸 목록만 돌려준다. 계산은 에이전트의 `execute_code` 스크립트가 한다.

**범위 외**: 다른 도구의 파일 출력. 계산 도구는 만들지 않는다.

## 컨텍스트

**근거 문서**: `docs/connectors/tossinvest.md` 의 「주문 내역」, `docs/adr/ADR-20261008-connector-output-files.md`, `docs/connector-authoring.md` 의 「계산할 목록을 파일로 내는 커넥터」, `docs/connectors.md` 의 `owner_output_env` 줄

- 디렉터리는 바인딩 설치가 `owner_output_env` 로 선언한 env 에 넣는다. 그 디렉터리는 그 에이전트의 실행 공간에 같은 경로로 읽기 전용으로 붙는다. 확인 도구, 선택지 호출, 승인한 실행에는 빈 값이 온다
- `.mcp.json` 의 서버 env 는 `fields[].env`, `operator_env`, `owner_attachments_env`, `owner_output_env` 의 합과 같아야 한다. 대시보드 plugin 이 다르면 카탈로그에서 뺀다(`hermes/plugins/dashboard-profile-api/connector_manifest.py`)
- 이 저장소에 파일 출력을 하는 다른 커넥터는 없다. 규칙은 근거 문서가 갖는다
- 끝난 주문(`CLOSED`)의 페이지는 `GET /api/v1/orders` 의 `cursor` 와 `limit`(최대 100), 답의 `nextCursor`, `hasNext` 다. `OPEN` 은 한 번에 전량이다. 호출 한도는 `ORDER_HISTORY` 그룹 초당 5회다

## 의도 메모

- 파일 이름은 커넥터가 정한다. 최종 이름은 `orders-<UTC yyyyMMddTHHmmssZ>-<무작위 8자 hex>.jsonl` 이고, 쓰는 동안의 임시 이름은 `.orders-<같은 꼬리>.tmp` 다. 모델이 준 이름이나 경로를 쓰지 않는다
- 임시 파일은 `wx` 플래그로 연다. 다 쓴 뒤 `link(임시, 최종)` 으로 최종 이름을 만들고 임시 파일을 지운다. `link` 는 대상이 있으면 실패하므로 기존 파일을 덮지 않는다(`rename` 은 같은 이름의 파일을 바꿔 쓴다). `link` 가 실패하면 임시 파일을 지우고 `TOSSINVEST_OUTPUT_UNAVAILABLE` 로 끝낸다
- 기간 전체를 한 파일에 쓴다. `CLOSED` 는 100건씩 최대 20쪽(2,000건)이다. 21쪽째가 필요하면 임시 파일을 지우고 `TOSSINVEST_TOO_MANY_ORDERS` 로 끝낸다. 실패하면 어떤 경우든 임시 파일을 지우고, 일부만 쓴 최종 파일을 남기지 않는다. 프로세스가 죽어 남은 임시 파일은 아래 24시간 정리와 운영의 정기 정리가 지운다
- 쪽 사이에 250ms 를 쉰다. 429 를 받으면 1초 쉬고 그 쪽을 한 번만 다시 부른다
- 디렉터리 env 가 비었으면 요청 없이 `TOSSINVEST_OUTPUT_UNAVAILABLE`
- 쓸 때마다 그 디렉터리에서 `orders-*.jsonl` 과 `.orders-*.tmp` 가운데 수정 시각이 24시간 지난 정규 파일을 지운다. 다른 이름과 링크는 건드리지 않는다
- 디렉터리가 링크이거나 디렉터리가 아니면 `TOSSINVEST_OUTPUT_UNAVAILABLE` 다. 커넥터는 실행 공간 밖에서 돌고, 이 디렉터리는 실행 공간에서 읽기 전용이다
- 파일에는 자격 증명과 서비스의 오류 원문을 쓰지 않는다. 계좌 순번도 쓰지 않는다
- 한 줄의 칸은 phase 02 의 `list_orders` 결과 항목과 같다. 금액과 수량은 10진수 글 그대로다

## 작업 항목

### 1. `hermes/connectors/tossinvest/connector.json` 와 `.mcp.json`

- `connector.json` 에 `"owner_output_env": "TOSSINVEST_OUTPUT_DIR"` 를 더한다
- `errors` 에 `"TOSSINVEST_TOO_MANY_ORDERS": { "category": "invalid_input", "recovery": "fix_input" }` 과 `"TOSSINVEST_OUTPUT_UNAVAILABLE": "unavailable"` 을 더한다
- `.mcp.json` 의 서버 env 에 `"TOSSINVEST_OUTPUT_DIR": "${TOSSINVEST_OUTPUT_DIR}"` 를 더한다

### 2. `hermes/connectors/tossinvest/src/order-file.ts`

- `writeOrdersFile(client, directory, query, timing = { now: () => new Date(), sleep: (ms) => Bun.sleep(ms), random: () => <crypto.getRandomValues 로 만든 4바이트의 8자 hex> }): Promise<{file, count, from, to, fields}>`. `from` 과 `to` 는 받은 값이고 없으면 null 이다. `fields` 는 한 줄의 칸 이름 배열이다
- 위 「의도 메모」 의 이름, 쪽 상한, 쉼, 429 한 번 재시도, 임시 파일과 `link`, 24시간 정리를 담는다
- 한 줄의 칸 만들기는 phase 02 의 `list_orders` 결과 항목을 만드는 함수를 `account-tools.ts` 에서 내보내 함께 쓴다

### 3. `hermes/connectors/tossinvest/src/account-tools.ts` 와 `src/tool-registration.ts`

- `account-tools.ts`: `list_orders` 의 입력에 `output`(선택, `"file"` 만)을 더한다. 있으면 `writeOrdersFile` 을 부르고 그 결과만 돌려준다. 디렉터리는 `TossinvestOptions.env` 의 `TOSSINVEST_OUTPUT_DIR` 이다
- `tool-registration.ts`: `descriptions` 표의 `list_orders` 설명에 「output 에 file 을 주면 기간 전체를 실행 공간의 파일로 쓰고 경로만 돌려줍니다. 합계는 그 파일을 스크립트로 읽어 계산합니다.」 를 더한다

### 4. `hermes/connectors/tossinvest/skills/tossinvest/SKILL.md`

「끝난 주문을 기간 전체로 세거나 더할 때는 `list_orders` 에 `output: "file"` 을 주고, 받은 경로를 `execute_code` 스크립트로 읽어 10진수(`decimal`)로 계산한다. 모델이 직접 더한 값으로 답하지 않는다. 코드 실행 도구가 없거나 `TOSSINVEST_OUTPUT_UNAVAILABLE` 이면 할 수 없다고 말한다」 를 더한다.

### 5. 묶음 파일

`bun run build` 로 `dist/tossinvest-mcp.js` 를 다시 만든다.

### 6. 시험 `hermes/connectors/tossinvest/tests/order-file.test.ts`

임시 디렉터리를 만들어 `TOSSINVEST_OUTPUT_DIR` 로 준다. 정상 경로 하나는 MCP 도구로 부르고, 쪽 상한, 429, 정리 시험은 `writeOrdersFile` 을 바로 불러 `sleep` 을 즉시 끝나는 함수로, `now` 를 고정 시각으로 준다. 쉼을 실제로 기다리면 bun test 의 기본 제한 시간 5초에 걸린다.

- 정상: `CLOSED` 세 쪽(100, 100, 7건)을 이어 207줄 파일 하나를 쓰고, 결과에 경로, 건수 207, 기간, 칸 목록만 있고 주문 내용이 없다. 파일의 각 줄이 JSON 이고 금액이 글이다
- 21쪽이 필요하면 `TOSSINVEST_TOO_MANY_ORDERS` 이고 디렉터리에 `orders-` 파일과 `.orders-` 임시 파일이 남지 않는다
- 같은 최종 이름의 파일이 이미 있으면(`timing.random` 과 `timing.now` 를 고정해 만든다) `TOSSINVEST_OUTPUT_UNAVAILABLE` 이고, 기존 파일 내용이 그대로이며 임시 파일이 남지 않는다
- env 가 비었으면 요청 없이 `TOSSINVEST_OUTPUT_UNAVAILABLE`. 디렉터리가 링크면 같은 코드
- 24시간 지난 `orders-*.jsonl` 과 `.orders-*.tmp` 는 지우고, 23시간 된 파일과 다른 이름의 파일, 링크는 남긴다
- 한 쪽이 429 면 한 번 다시 불러 이어 쓴다. 다시 부른 쪽도 429 면 `TOSSINVEST_RATE_LIMITED` 이고 파일이 남지 않는다
- 파일과 결과 어디에도 토큰, secret, 계좌 순번이 없다

## 검증

```bash
cd hermes/connectors/tossinvest && bun install --frozen-lockfile && bun run typecheck && bun test ./tests && bun run check:bundle
python3 -m unittest discover -s hermes/tests -p 'test_connectors_contract.py'
python3 -m unittest discover -s hermes/tests -p 'test_connector_manifest.py'
node scripts/check-file-length.mjs
```

넷 다 실패 없이 끝난다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/connectors/tossinvest/connector.json` | 수정 |
| `hermes/connectors/tossinvest/.mcp.json` | 수정 |
| `hermes/connectors/tossinvest/src/order-file.ts` | 신규 |
| `hermes/connectors/tossinvest/src/account-tools.ts` | 수정 |
| `hermes/connectors/tossinvest/src/tool-registration.ts` | 수정 |
| `hermes/connectors/tossinvest/skills/tossinvest/SKILL.md` | 수정 |
| `hermes/connectors/tossinvest/dist/tossinvest-mcp.js` | 수정 |
| `hermes/connectors/tossinvest/tests/order-file.test.ts` | 신규 |
