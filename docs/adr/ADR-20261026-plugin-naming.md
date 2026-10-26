## ADR-20261026 / plugin-naming: 「커넥터」 는 화면과 문서에서 「플러그인」 이고, 코드 식별자는 `connector` 를 그대로 쓴다

- **status**: `accepted`
- Date: 2026-10-26
- 형식은 [ADR-20261026 / plugin-standard-format](../../hermes/docs/adr/ADR-20261026-plugin-standard-format.md), 옮기는 순서는 [ADR-20261026 / remote-mcp-plugin](../../hermes/docs/adr/ADR-20261026-remote-mcp-plugin.md) 의 「옮기는 순서」 가 갖는다. 이름 변경은 그 순서의 셋째 단계이고, 그때까지 화면과 문서는 「커넥터」 를 쓴다.

### 결정

**사용자가 보는 화면과 문서는 에이전트에게 도구를 쥐어 주는 묶음을 「플러그인」 이라 부른다. DB, API 경로, 코드 식별자, 디렉터리 이름은 `connector` 를 그대로 둔다.**

| 대상 | 정한 것 |
| --- | --- |
| 화면 문구 | 「커넥터」 를 「플러그인」 으로 바꾼다. 「커넥터 README」 는 「플러그인 README」 다 |
| 「외부 서비스 연결」 화면과 메뉴 | 이름과 주소(`/connections`, `/admin/connections`)를 그대로 둔다(사용자 결정 2026-10-26). 화면 안의 목록과 카드가 「플러그인」 을 쓴다. 사용자가 계정을 잇는 동작은 지금처럼 「연결」 이다 |
| 문서 | `docs/features/connector*.md` 와 `<id>-connector.md` 의 본문 말을 바꾼다. 파일 이름은 옮기지 않는다. 다른 문서와 코드 주석의 링크가 많고, 이름이 바뀌어도 내용은 그대로라서다 |
| 용어표 | 「에이전트에게 쥐어 주는 도구 묶음. 플러그인 root 의 `assistant.json` 이 assistant 쪽 정보를 선언한다」 를 **플러그인**, 코드는 `connector` 로 둔다. 「커넥터」 는 쓰지 않는 말로 옮긴다 |
| Hermes 쪽 확장 | `hermes/plugins/` 의 것은 「Hermes plugin」 이나 이름(대시보드 plugin, `fos-ctx`)으로 쓴다. 맨 「plugin」 만 쓰면 플러그인과 헷갈린다 |
| DB | 테이블과 컬럼(`connector_connection`, `agent_connector_binding` 등)과 이미 적용된 마이그레이션은 바꾸지 않는다 |
| 코드 식별자 | Java 패키지, 클래스, Python 모듈, TypeScript 이름, API 경로(`/api/connectors`), 오류 코드(`CONNECTOR_*`), 디렉터리(`hermes/connectors/`)는 옮기지 않는다. 새 코드도 `connector` 를 쓴다 |
| 선언 파일 | `connector.json` 을 `assistant.json` 으로 옮긴다. 이것은 플러그인 형식의 파일 이름이라 코드 식별자가 아니다 |

### 맥락

사용자는 커넥터를 따로 부르지 말고 Claude Code 와 Codex 의 플러그인을 그대로 쓰기를 원했다(2026-10-26).
「커넥터」 는 화면, 문서, DB, API, 코드 식별자에 모두 퍼져 있다.
DB 테이블과 컬럼은 바꾸지 않고, 이미 적용된 마이그레이션은 주석도 고치지 않는다(`AGENTS.md` 「용어」).

### 대안 기각

- **코드 식별자도 `plugin` 으로 모두 옮긴다**: DB 이름은 옮기지 못해서 엔티티와 저장소 계층에 두 이름이 남는다. 이동만 하는 변경이 수백 파일에 이르고 그동안 다른 작업과 충돌한다. 동작은 바뀌지 않는다.
- **새 코드만 `plugin` 으로 짓는다**: 한 기능 안에 두 이름이 섞여 무엇이 같은 것인지 코드만 보고 알 수 없다.
- **화면 이름도 「플러그인」 으로 바꾼다**: 메뉴와 목록이 같은 낱말을 쓰게 되지만, 사용자는 그 화면에서 하는 일이 외부 서비스에 계정을 잇는 것이라 지금 이름을 두기로 했다.
- **문서 파일 이름도 `plugin*.md` 로 옮긴다**: 링크를 모두 고치는 이동이 생기고 얻는 것은 파일 이름뿐이다.

### 결과

- 얻는 것: 사용자는 Claude Code 와 Codex 에서 쓰던 말 그대로 플러그인을 고른다. 코드와 DB 는 움직이지 않는다.
- 감당할 것:
  - 코드를 읽는 사람은 `connector` 가 문서의 플러그인임을 용어표에서 안다. 용어표 한 줄이 그 대응을 갖는다.
  - 「plugin」 이 Hermes plugin 과 플러그인 두 가지를 가리킬 수 있다. 문서는 Hermes 쪽을 늘 「Hermes plugin」 이나 이름으로 쓴다.
