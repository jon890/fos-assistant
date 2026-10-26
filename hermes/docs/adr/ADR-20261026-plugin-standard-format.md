## ADR-20261026 / plugin-standard-format: 플러그인은 Claude Code·Codex 플러그인 형식을 그대로 읽고, assistant 전용 정보는 `assistant.json` 하나에 덧붙인다

- **status**: `accepted`
- Date: 2026-10-26
- [ADR-043](../../../backend/docs/adr/ADR-043-커넥터는-plugin-의-connector-json-으로-선언하고-control-plane-은-범용-흐름만-갖는다.md) 의 「커넥터는 plugin 이 선언하고 Control Plane 은 범용 흐름만 갖는다」 는 그대로 둔다. 선언 파일의 이름과 플러그인 manifest 를 읽는 규칙을 바꾼다.
- 이름은 [ADR-20261026 / plugin-naming](../../../docs/adr/ADR-20261026-plugin-naming.md), 원격 MCP 는 [ADR-20261026 / remote-mcp-plugin](ADR-20261026-remote-mcp-plugin.md) 이 갖는다.

### 결정

**대시보드 plugin 은 운영 목록의 디렉터리를 Claude Code 플러그인이나 Codex 플러그인으로 읽는다. assistant 만 쓰는 정보(제목, 아이콘, 입력 칸, 확인 도구, 도구별 위험도와 승인, 오류 계약)는 플러그인 root 의 `assistant.json` 에 둔다.**

| 항목 | 정한 것 |
| --- | --- |
| 플러그인 manifest | `.claude-plugin/plugin.json` 과 `.codex-plugin/plugin.json` 가운데 하나 이상이 있어야 한다. 둘 다 있으면 `name` 이 같아야 하고, 스킬 경로와 MCP 정의 경로는 `.claude-plugin` 쪽을 쓴다. 다르면 그 플러그인을 카탈로그에서 뺀다 |
| 이름 | manifest 의 `name` 이 운영 목록의 이름이다. 지금처럼 `CONNECTOR_ID_RE` 를 따른다 |
| 스킬 경로 | manifest 의 `skills`(문자열이나 문자열 목록). 없으면 `./skills` 다. 플러그인 안의 링크 없는 디렉터리여야 한다 |
| MCP 정의 | manifest 의 `mcpServers` 가 문자열이면 그 상대 경로의 파일, 없으면 root 의 `.mcp.json` 이다. manifest 안에 객체로 적은 정의는 받지 않는다 |
| MCP 서버 수 | 하나다. 둘 이상이면 카탈로그에서 뺀다 |
| 서버 정의 | 로컬 실행형(`command`, `args`, `env`, `type` 이 없거나 `stdio`)과 원격(`type: http`, `url`, `headers`)을 받는다. 원격의 규칙은 ADR-20261026 / remote-mcp-plugin 이 갖는다. `sse` 는 받지 않는다 |
| assistant 전용 정보 | 플러그인 root 의 `assistant.json`. 칸과 검증은 지금의 `connector.json` 과 같다. 원격 플러그인만 `headers` 칸을 더 받는다 |
| 옛 이름 | `assistant.json` 이 없으면 `connector.json` 을 같은 규칙으로 읽는다. 둘 다 있으면 카탈로그에서 뺀다 |
| manifest 의 나머지 | `hooks`, `commands`, `agents`, Codex 의 `interface` 같은 칸과 디렉터리는 읽지 않고 설치하지 않는다. Hermes 에 옮겨 갈 자리가 없다 |

`assistant.json` 이 없을 때의 기본값(도구는 모두 호출마다 승인, 입력 칸은 MCP 정의에서 추론, 확인 도구 없음)은 이 결정의 방향이지만 첫 구현에 넣지 않는다.
선언하지 않은 도구를 거절하는 지금의 판정(`default_tool_policy: deny`)을 「묻는다」 로 바꾸는 일이 Control Plane 과 화면에 걸쳐서다.
그때까지는 `assistant.json` 이나 `connector.json` 이 없으면 카탈로그에서 뺀다.

### 맥락

이 저장소의 커넥터는 이미 Claude Code 플러그인 형식(`.claude-plugin/plugin.json`, `.mcp.json`, `skills/`)에 `connector.json` 을 덧붙인 디렉터리였다.
사용자는 이것을 따로 부르지 않고 Claude Code 와 Codex 에서 쓰는 플러그인을 그대로 받기를 원했다(2026-10-26).
첫 대상인 금융 워크벤치는 자기 저장소에 `.claude-plugin`, `.codex-plugin`, 원격 MCP 를 가리키는 `.mcp.json`, 스킬을 둔 플러그인을 만들고 있다.
지금 검증은 `.claude-plugin` 만 읽고 `connector.json` 이 없으면 그 디렉터리를 버린다.

### 대안 기각

- **`connector.json` 이름을 그대로 둔다**: 바꾸는 비용은 없지만 화면과 문서에서 「커넥터」 를 「플러그인」 으로 바꾼 뒤에 파일 이름만 옛 말로 남는다. 다른 저장소의 플러그인 작성자가 그 파일이 무엇인지 이름으로 알 수 없다.
- **assistant 전용 정보를 plugin.json 의 사용자 칸에 넣는다**: Claude Code 와 Codex 가 모르는 칸을 그 manifest 에 넣으면 그 도구의 검증에서 경고나 실패가 날 수 있고, 두 manifest 에 같은 값을 두 벌 적어야 한다.
- **assistant 전용 정보를 이 저장소에 따로 둔다(덧씌우기 디렉터리)**: 도구 정책이 도구를 만든 저장소와 떨어져, 도구가 바뀔 때 함께 고쳐지지 않는다. [ADR-064](ADR-064-범용-커넥터는-이-저장소의-hermes-connectors-에-두고-저장소가-유지보수한다.md) 가 커넥터를 저장소마다 나누지 않은 까닭과 같다. 서비스 이름이 범용 plugin 쪽으로 들어오는 것도 [rule-65](../../../docs/code-rules/rule-65.md) 에 어긋난다.
- **MCP 서버 여럿을 받는다**: 카탈로그와 Control Plane 이 플러그인 하나에 서버 이름 하나(`mcp_server`)를 두고, 도구 정책도 서버 하나의 도구 이름을 키로 쓴다. 첫 대상은 서버 하나라 이번에는 열지 않는다.

### 결과

- 얻는 것: 다른 저장소의 플러그인을 고치지 않고 받는다. 그 저장소가 `assistant.json` 하나만 더하면 위험도와 승인을 선언할 수 있다. Codex 만 지원하는 플러그인도 받는다.
- 감당할 것:
  - `connector.json` 을 읽는 기간이 남는다. 이 저장소의 커넥터와 운영 목록의 다른 저장소 플러그인이 모두 `assistant.json` 으로 옮긴 뒤 옛 이름 읽기를 지운다. 지우기 전에 운영 목록의 항목을 `fos-home-infra` 에서 확인한다.
  - 두 manifest 의 차이를 정할 때 `.claude-plugin` 을 앞세운다. Codex 쪽에만 있는 스킬 경로는 쓰지 않는다.
  - 다른 저장소가 선언한 위험도와 승인도 그 저장소의 판단이다. 운영 목록에 올리기 전에 운영자가 `assistant.json` 을 읽어 본다. ADR-064 의 「분류가 맞는지는 리뷰가 본다」 와 같다.
