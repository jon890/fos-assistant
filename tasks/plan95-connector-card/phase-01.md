# Phase 01. 대시보드 plugin 이 `icon` 과 `link` 를 검증해 카탈로그에 싣는다

**Execution profile**: deep

## 목표

`connector.json` 의 선택 칸 `icon` 과 `link` 를 대시보드 plugin 이 검증하고, 카탈로그 응답에 `icon: {media_type, data}` 와 `link` 로 싣는다.
이 저장소의 커넥터(gmail, naver-blog)와 시험 커넥터에 직접 그린 아이콘과 링크를 채운다.

**범위 외**: Control Plane 의 재검증(phase 02), 화면(phase 03).

## 컨텍스트

- manifest 읽기는 `hermes/plugins/dashboard-profile-api/connector_manifest.py` 의 `_load_connector(connector_id, entry)` 다. 틀리면 `ValueError` 를 던지고 `_connector_manifest` 가 그 커넥터를 카탈로그에서 뺀다
- 카탈로그 응답은 같은 파일의 `_connector_catalog_response()` 다
- 형식 상수는 `connector_schema.py` 가 갖는다. 파일 길이 상한은 `hermes/**/*.py` 400줄이다(`scripts/check-file-length.mjs`). `connector_manifest.py` 는 357줄이라 검사 본문은 새 모듈에 둔다
- plugin 경로 안 파일 검사의 선례는 `_read_connector_json` 과 `.mcp.json` 인자 검사(`target.resolve() != target or not target.is_relative_to(root)`)다
- 시험은 `hermes/tests/test_connector_manifest.py` 의 `ConnectorGateCase` 를 쓴다. `self.rewrite("connector.json", change)` 로 사본을 고치고 `self.catalog()` 로 읽는다
- 저장소 커넥터 계약 시험은 `hermes/tests/test_connectors_contract.py` 다

**근거 문서**: `docs/adr/ADR-20261008-connector-card.md`, `docs/connectors.md` 의 「connector.json」 과 「아이콘과 링크」, `hermes/README.md` 의 「카탈로그 응답」, `docs/connector-authoring.md` 의 「공통 검사」

## 의도 메모

- SVG 를 고쳐 쓰지 않고 거절한다. 규칙은 ADR 의 목록 그대로이고 Java 쪽(phase 02)과 같은 정규식을 쓴다
- 대시보드는 틀린 `icon`·`link` 의 커넥터를 카탈로그에서 뺀다. 다른 칸과 같은 규칙이다
- 아이콘은 상표 로고를 복사하지 않는다. gmail 은 봉투, naver-blog 는 연필과 종이 같은 단순 도형이다

## 작업 항목

### 1. `hermes/plugins/dashboard-profile-api/connector_schema.py` 에 상수를 더한다

```python
ICON_PATH_RE = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9_./-]{0,127}$")
ICON_MAX_BYTES = 32 * 1024
ICON_MEDIA_TYPES = {".svg": "image/svg+xml", ".png": "image/png"}
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
LINK_MAX_CHARS = 500
# 이 금지 목록은 `<img>` 앞의 두 번째 선이다(ADR-20261008 connector-card). Control Plane 의 같은 식과 같아야 한다.
SVG_FORBIDDEN_RE = re.compile(
    r"<!doctype|<!entity|<script|<foreignobject|<iframe|<embed|<object|<set|<animate|@import|javascript:|&#|\\"
    r"|\son[a-z]+\s*=|href\s*=(?![\s\"']*#)|url\((?![\s\"']*#)",
    re.IGNORECASE | re.ASCII,
)
```

- `href\s*=(?![\s"']*#)` 는 `href="#a"`, `xlink:href='#a'`, `href= "#a"` 를 받고 `href="https://..."`, `href=""` 를 거절한다. `url\((?![\s"']*#)` 는 `url(#g)`, `url('#g')`, `url( #g)` 를 받고 `url(http://...)` 를 거절한다
- `re.ASCII` 로 `\s` 와 대소문자 무시를 ASCII 기준으로 둔다. Java 의 기본 동작과 같게 하기 위해서다
- 앞부분(`<svg` 로 시작하는지)은 정규식으로 보지 않는다. 아래 2항의 반복으로 본다. `(<!--.*?-->\s*)*` 같은 식은 주석이 많은 입력에서 되추적으로 시간이 지수적으로 는다

### 2. 새 모듈 `hermes/plugins/dashboard-profile-api/connector_appearance.py`

- `_connector_icon(root: pathlib.Path, declared) -> dict | None`. `declared` 가 None 이면 None. 문자열이 아니거나 `ICON_PATH_RE` 불일치, `..` 조각, 확장자가 `ICON_MEDIA_TYPES` 밖이면 `ValueError`. `path = root / declared` 가 `path.resolve() != path` 이거나 `not path.is_relative_to(root)` 이거나 파일이 아니면 `ValueError`. 크기가 0 이거나 `ICON_MAX_BYTES` 를 넘으면 `ValueError`. PNG 는 `PNG_SIGNATURE` 로 시작, SVG 는 `_svg_is_safe(data)` 가 참이어야 한다. 돌려주는 값은 `{"media_type": ..., "data": base64.b64encode(data).decode("ascii")}`
- `_svg_starts_with_root(text: str) -> bool`. 정규식 없이 한 번 훑는다. 위치 0 에서 BOM(`\ufeff`) 하나를 건너뛰고, 다음을 더 나아가지 않을 때까지 되풀이한다: ASCII 공백(` \t\r\n\f\v`)을 건너뛴다, `<?xml` 로 시작하면 다음 `?>` 뒤로 간다(없으면 거짓), `<!--` 로 시작하면 다음 `-->` 뒤로 간다(없으면 거짓). 멈춘 자리가 대소문자 무시로 `<svg` 이고 그다음 글자가 ASCII 공백, `>`, `/` 가운데 하나면 참
- `_svg_is_safe(data: bytes) -> bool`. 엄격한 UTF-8 로 읽히고(`data.decode("utf-8")` 가 예외 없이 끝남) `_svg_starts_with_root` 가 참이고 `SVG_FORBIDDEN_RE.search` 가 찾지 못하면 참
- `_connector_link(declared) -> str | None`. None 이면 None. 문자열, `LINK_MAX_CHARS` 이하, 공백·제어 문자 없음, `urllib.parse.urlsplit` 의 `scheme == "https"`, `hostname` 있음, `username` 과 `password` 없음이 아니면 `ValueError`

### 3. `connector_manifest.py` 가 두 칸을 읽고 낸다

- `_load_connector` 에서 `icon = _connector_icon(root, declared.get("icon"))`, `link = _connector_link(declared.get("link"))` 를 부르고 반환 dict 에 `"icon"`, `"link"` 를 더한다
- `_connector_catalog_response` 의 항목에 `"icon": manifest["icon"], "link": manifest["link"]` 를 더한다

### 4. 아이콘 파일과 `connector.json`

- 신규 `hermes/connectors/gmail/icon.svg`(봉투), `hermes/connectors/naver-blog/icon.svg`(연필과 종이), `hermes/tests/fixtures/demo-connector/icon.svg`(메모지). `xmlns="http://www.w3.org/2000/svg"` 와 `viewBox="0 0 24 24"` 를 갖고, `<path>`·`<rect>`·`<circle>` 만 쓰며, 1KB 안팎이다. `xmlns` 가 없으면 `<img>` 가 아무것도 그리지 않는다. 색은 `currentColor` 를 쓰지 않는다. `<img>` 안의 SVG 는 글자색을 물려받지 않아 검정으로 그려지고 어두운 테마에서 보이지 않는다. 둥근 사각형 바탕(고정 색)을 깔고 그 위에 고정 색 도형을 그려 두 테마에서 모두 보이게 한다
- `hermes/connectors/gmail/connector.json` 에 `"icon": "icon.svg", "link": "https://mail.google.com/"`
- `hermes/connectors/naver-blog/connector.json` 에 `"icon": "icon.svg", "link": "https://blog.naver.com/"`
- `hermes/tests/fixtures/demo-connector/connector.json` 은 바꾸지 않는다. 시험이 사본에서 칸을 넣는다

### 5. 이 phase 를 검증하는 `hermes/tests/test_connector_manifest.py`

- `test_catalog_lists_the_validated_connector_without_operator_values` 의 키 집합에 `"icon", "link"` 를 더하고 둘이 None 인지 본다
- 새 클래스 `ConnectorAppearanceTest(ConnectorGateCase)`:
  - 선언한 SVG 와 PNG(서명 뒤에 임의 바이트) 아이콘이 `media_type` 과 원본 base64 로 나온다. `link` 가 그대로 나온다
  - 아래가 하나라도 있으면 카탈로그가 빈 목록이다: 절대 경로, `../icon.svg`, `.gif` 확장자, 없는 파일, 빈 파일, plugin 밖을 가리키는 심볼릭 링크, 32 KiB 보다 1 바이트 큰 파일, 서명 없는 PNG, UTF-8 이 아닌 SVG, `<script>`, `onload=`, `<foreignObject>`, `<!DOCTYPE`, `<set`, `<animate`, `&#`, `\`, `href="https://..."`, `xlink:href="javascript:..."`, `url(http...)`, svg 로 시작하지 않는 글, `http://` 링크, `https://user@host/` 링크, 501자 링크, 문자열이 아닌 `link`
  - `href="#a"`, `xlink:href='#a'`, `url(#g)`, `url('#g')`, `url( #g)` 는 받는다
  - 32 KiB 정확히인 아이콘은 받는다
  - `<!---->` 2000개 뒤에 `<svg/>` 를 둔 SVG 는 받고, 2000개 뒤에 `X` 를 둔 SVG 는 거절한다. 둘 다 1초 안에 끝난다(`time.monotonic()` 으로 측정한다)

### 6. `hermes/tests/test_connectors_contract.py`

- `test_every_connector_keeps_the_contract` 가 쓰는 검사 함수에 「`icon` 과 `link` 를 선언했고, 아이콘이 SVG 면 `xmlns="http://www.w3.org/2000/svg"` 를 선언했다」 를 더한다. 어긋나면 그 커넥터 이름과 함께 보고한다
- `ContractCatchesViolationsTest` 에 「gmail 사본에서 `icon` 을 지우면 위반으로 보고된다」 시험을 더한다. 기존 `test_write_tool_without_title_is_reported` 의 사본 만들기를 따른다

## 검증

모두 종료 코드 0 이어야 한다.

```bash
python3 -m unittest discover -s hermes/tests -p 'test_connector*.py'
node scripts/check-file-length.mjs
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/connector_schema.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_appearance.py` | 신규 |
| `hermes/plugins/dashboard-profile-api/connector_manifest.py` | 수정 |
| `hermes/connectors/gmail/icon.svg` | 신규 |
| `hermes/connectors/gmail/connector.json` | 수정 |
| `hermes/connectors/naver-blog/icon.svg` | 신규 |
| `hermes/connectors/naver-blog/connector.json` | 수정 |
| `hermes/tests/fixtures/demo-connector/icon.svg` | 신규 |
| `hermes/tests/test_connector_manifest.py` | 수정 |
| `hermes/tests/test_connectors_contract.py` | 수정 |
