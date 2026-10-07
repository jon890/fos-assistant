# Phase 02. Control Plane 이 아이콘과 링크를 다시 검증해 연결 목록에 싣는다

**Execution profile**: deep

## 목표

Control Plane 이 대시보드 카탈로그의 `icon` 과 `link` 를 같은 규칙으로 다시 검증하고, `GET /api/v1/connectors` 의 항목에 `icon`(data URL 이나 null)과 `link`(글이나 null)를 싣는다.
틀린 칸은 그 칸만 null 로 두고 커넥터는 그대로 낸다.

**범위 외**: 대시보드 쪽 검증(phase 01), 화면(phase 03), 에이전트 상세의 연결 목록(`GET /api/v1/agents/{code}/connections`). 그 응답은 바꾸지 않는다.

## 컨텍스트

- 카탈로그 읽기는 `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesConnectorClient.java` 의 `private static ConnectorManifest manifest(JsonNode item)` 다. **이 파일은 길이 기준 파일(614줄)이라 늘어나면 `node scripts/check-file-length.mjs` 가 실패한다.** palantir 포맷은 생성자 인자를 한 줄에 하나씩 둔다. 그래서 `String description = text(item, "description");` 줄을 지우고 생성자 인자 줄 `description == null ? "" : description,` 을 `text(item, "description"),` 로 바꾼 뒤 `ConnectorAppearances.read(item),` 한 줄을 더한다. 줄 수 변화는 0 이고 import 를 더하지 않는다. null 설명을 빈 글로 바꾸는 일은 `ConnectorManifest` 의 compact 생성자가 맡는다(작업 3)
- `backend/src/main/java/com/bifos/assistant/hermes/dto/ConnectorManifest.java` 는 record 이고 10인자, 11인자 생성자를 시험 27곳이 쓴다. 새 component 를 끝에 더하고 기존 두 생성자는 `ConnectorAppearance.NONE` 을 넘겨 유지한다
- 연결 목록은 `connector/application/ConnectorConnectionService.java` 의 `catalog(CurrentUser)` 가 `ConnectorSummary` 를 만들고 `connector/presentation/ConnectionDtos.java` 의 `ConnectorView.from` 이 응답으로 바꾼다
- 시험: `backend/src/test/java/com/bifos/assistant/hermes/HttpHermesConnectorClientTest.java`(카탈로그 파싱, `readsCatalogWithEnvOptionsVerifyToolAndMcpServer` 참고), `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionControllerTest.java`(`GET /api/v1/connectors` 의 JSON)

**근거 문서**: `docs/adr/ADR-20261008-connector-card.md`, `docs/connectors.md` 의 「아이콘과 링크」 와 「Control Plane API」, `docs/backend/connector-install.md` 의 「대시보드 plugin 계약」

## 의도 메모

- 대시보드와 같은 SVG 정규식을 쓴다. 규칙은 ADR 의 목록 하나가 정본이다
- 틀린 칸만 버리는 까닭: 장식 칸 하나 때문에 쓰던 연결이 화면에서 사라지면 안 된다
- 경고 로그에는 커넥터 번호와 칸 이름만 남긴다. 값은 남기지 않는다

## 작업 항목

### 1. 신규 `backend/src/main/java/com/bifos/assistant/hermes/dto/ConnectorAppearance.java`

```java
/** 커넥터 카드의 아이콘과 링크다. 검증을 통과한 값만 담고, 없거나 틀린 칸은 null 이다(ADR-20261008 connector-card). */
public record ConnectorAppearance(String icon, String link) {
    public static final ConnectorAppearance NONE = new ConnectorAppearance(null, null);
}
```

`icon` 은 `data:<media type>;base64,<data>` 다.

### 2. 신규 `backend/src/main/java/com/bifos/assistant/hermes/ConnectorAppearances.java`

- `static ConnectorAppearance read(JsonNode item)`. **어떤 입력에도 예외를 밖으로 던지지 않는다.** Base64 의 `IllegalArgumentException`, 디코딩 실패도 안에서 잡아 그 칸을 null 로 둔다
- `item.get("icon")` 이 없거나 JSON null 이면 경고 없이 null 이다. 새 대시보드는 선언하지 않은 커넥터에도 `"icon": null` 을 보낸다. `link` 도 같다
- `icon` 이 객체이고 `media_type` 이 `image/svg+xml` 이나 `image/png`, `data` 가 엄격한 base64(`Base64.getDecoder()`)이며 디코딩한 크기가 1 이상 `32 * 1024` 이하, PNG 는 서명 `89 50 4E 47 0D 0A 1A 0A` 로 시작, SVG 는 엄격한 UTF-8(`CharsetDecoder` 의 `CodingErrorAction.REPORT`)로 읽히고 아래 앞부분 검사와 금지 정규식을 지키면 `data:<media_type>;base64,<data>` 로 담는다. 아니면 null
- `link` 는 글이고 500자 이하, 공백과 제어 문자가 없고, `java.net.URI` 로 읽혀 `scheme` 이 정확히 `https`, `host` 가 있고 `userInfo` 가 없으면 그대로 담는다. 아니면 null
- 칸이 있고 JSON null 이 아니었는데 버렸으면 `log.warn("커넥터 카드 칸을 버렸다 connector={} field={}", id, "icon" 또는 "link")`. `id` 는 `item.get("id")` 의 글이다. 값은 로그에 싣지 않는다
- 앞부분 검사는 정규식이 아니라 한 번 훑는 반복이다. 대시보드의 `_svg_starts_with_root` 와 같다: 위치 0 의 BOM(`\uFEFF`) 하나를 건너뛰고, 더 나아가지 않을 때까지 ASCII 공백(` \t\r\n\f` 와 `\u000B`)을 건너뛰고, `<?xml` 이면 다음 `?>` 뒤로(없으면 거짓), `<!--` 면 다음 `-->` 뒤로(없으면 거짓) 간다. 멈춘 자리가 대소문자 무시로 `<svg` 이고 그다음 글자가 ASCII 공백, `>`, `/` 가운데 하나면 참. 정규식 `(<!--.*?-->\s*)*` 는 주석이 많은 입력에서 `StackOverflowError` 를 내 카탈로그 전체를 실패시킨다
- 금지 정규식은 대시보드와 같고 `find()` 로 쓴다(`matches()` 를 쓰면 모든 SVG 가 걸린다):

```java
private static final Pattern SVG_FORBIDDEN = Pattern.compile(
        "<!doctype|<!entity|<script|<foreignobject|<iframe|<embed|<object|<set|<animate|@import|javascript:|&#|\\\\"
                + "|\\son[a-z]+\\s*=|href\\s*=(?![\\s\"']*#)|url\\((?![\\s\"']*#)",
        Pattern.CASE_INSENSITIVE);
```

### 3. `ConnectorManifest` 에 `ConnectorAppearance appearance` 를 끝 component 로 더한다

compact 생성자에서 `appearance` 가 null 이면 `ConnectorAppearance.NONE` 으로, `description` 이 null 이면 `""` 로 둔다. 기존 10인자, 11인자 생성자는 `NONE` 을 넘긴다. javadoc `@param appearance` 를 더한다.

### 4. `HttpHermesConnectorClient.manifest(JsonNode)` 가 `ConnectorAppearances.read(item)` 을 넘긴다

줄 수는 늘지 않는다(위 「컨텍스트」). 기존 시험 가운데 카탈로그의 빠진 `description` 을 빈 글로 읽는 것이 그대로 통과해야 한다.

### 5. `ConnectorSummary` 와 `ConnectorView`

- `ConnectorSummary` 에 `ConnectorAppearance appearance` 를 `description` 다음 component 로 더한다. javadoc 에 「카탈로그에서 빠진 커넥터는 `NONE`」 을 적는다
- `ConnectorConnectionService.catalog` 는 카탈로그 항목에 `manifest.appearance()`, 빠진 연결에 `ConnectorAppearance.NONE` 을 넘긴다
- `ConnectionDtos.ConnectorView` 에 `String icon, String link` 를 `description` 다음에 더하고 `from` 이 `value.appearance().icon()`, `value.appearance().link()` 를 넣는다

### 6. 이 phase 를 검증하는 시험

- 신규 `backend/src/test/java/com/bifos/assistant/hermes/ConnectorAppearancesTest.java`: SVG 와 PNG 가 data URL 로 담긴다. `href="#a"`, `xlink:href='#a'`, `url(#g)`, `url('#g')`, `url( #g)` 는 받는다. 32 KiB 정확히는 받는다. `<script>`, `onload=`, `<foreignObject>`, `<!DOCTYPE`, `<set`, `<animate`, `&#`, `\`, `xlink:href="https://..."`, `url(http://...)`, svg 로 시작하지 않는 글, UTF-8 이 아닌 바이트, 잘못된 base64, 빈 data, 32 KiB 보다 1 바이트 큰 내용, 서명 없는 PNG, 모르는 `media_type`, 객체가 아닌 `icon`, `http://` 링크, `https://user@host/` 링크, 501자 링크, 글이 아닌 링크는 그 칸만 null 이다. 칸이 없거나 JSON null 이면 둘 다 null 이다. `<!---->` 2000개 뒤에 `<svg/>` 를 둔 SVG 는 받고 `X` 를 둔 SVG 는 null 이며 둘 다 예외 없이 1초 안에 끝난다(`assertTimeoutPreemptively`)
- `HttpHermesConnectorClientTest`: 카탈로그 항목에 `icon` 과 `link` 를 넣은 응답을 읽으면 `manifest.appearance()` 에 data URL 과 링크가 담기고, 칸이 없는 옛 응답은 `NONE` 이다
- `ConnectorConnectionControllerTest`: 첫 시험의 `ConnectorSummary` 에 아이콘과 링크를 넣고 `$[0].icon`, `$[0].link` 를 본다. 빠진 커넥터 시험은 두 칸이 JSON null 로 나오는지 본다. 응답 직렬화가 null 칸을 빼는 설정이면 그 설정에 맞춰 단언한다
- `ConnectorSummary` 를 직접 만드는 다른 시험(`grep -rn 'new ConnectorSummary(' backend/src/test`)도 새 인자를 넣는다

## 검증

모두 종료 코드 0 이어야 한다.

```bash
(cd backend && ./gradlew test --tests '*ConnectorAppearancesTest' --tests '*HttpHermesConnectorClientTest' --tests '*ConnectorConnectionControllerTest' --tests '*ConnectorConnectionServiceTest')
(cd backend && ./gradlew qualityCheck)
node scripts/check-file-length.mjs
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/hermes/dto/ConnectorAppearance.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/ConnectorAppearances.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/dto/ConnectorManifest.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesConnectorClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/model/ConnectorSummary.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorConnectionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/ConnectionDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/ConnectorAppearancesTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/hermes/HttpHermesConnectorClientTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/*.java` | 수정 |
