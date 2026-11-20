# Phase 03. fos-ctx 가 찾은 주소를 뽑아 알린다

**Execution profile**: standard

## 목표

fos-ctx 의 결과 hook 이 `web_search` 와 `web_extract` 의 결과에서 주소 후보를 뽑아 Control Plane 의 `POST /internal/hermes/found-links` 에 알린다. 막는 글을 찾은 주소까지 넓히고 계약 문서를 쓴다.

**범위 외**: 그림 규칙의 나머지 줄과 화면 표시(뒤 PR). Control Plane 쪽(phase 2).

## 컨텍스트

- 결과 hook: `hermes/plugins/fos-ctx/hooks.py` 의 `transform_tool_result`. `image_generate` 분기 다음에 플러그인 도구 결과를 감싸는 분기가 있다
- 본보기: `hermes/plugins/fos-ctx/image_source.py` 의 `prepare` 가 `build_context(TOOL, session_id, tool_call_id)`, `guard_url()`, `_read_token()` 으로 맥락과 주소, 토큰을 만든다
- 두 도구의 결과 모양(Hermes v0.23.2 `tools/web_tools.py`): `web_search` 는 `{"success": true, "data": {"web": [{"title", "url", "description", "position"}]}}`, 실패는 `{"success": false, "error": "..."}`. `web_extract` 는 `{"results": [{"url", "title", "content", "error"}]}` 이고 본문은 마크다운이며 사진 주소는 링크로 남는다
- 테스트 본보기: `hermes/tests/test_image_source.py`(가짜 HTTP), 계약 테스트 본보기: `hermes/tests/test_generated_image_contract.py`(`HERMES_SOURCE` 가 있을 때만 상류 소스를 읽는다)

**요구사항**: REQ2, REQ9

**근거 문서**: `docs/features/agent-found-image/design.md` 의 「fos-ctx 가 뽑는 후보」, 「알리는 경로」, `docs/adr/ADR-20261121-found-link-image-source.md`

## 의도 메모

- hook 은 이 두 도구에서 늘 None 을 돌려준다. 결과 글을 바꾸지 않는다
- 어떤 예외도 밖으로 내지 않는다. 로그에는 예외 종류만 남기고 주소와 결과 글은 남기지 않는다
- `web_extract` 항목의 `url` 은 후보가 아니라 기준 주소다(스펙 DECISION25)
- 같은 주소인지, 상대 주소를 어떻게 푸는지는 Control Plane 이 정한다. fos-ctx 는 글자를 뽑기만 한다

## 작업 항목

### 1. `hermes/plugins/fos-ctx/found_links.py` 를 만든다

- 상수: `TOOLS = frozenset({"web_search", "web_extract"})`, `CTX_TOOL = "found_links"`, `REPORT_PATH = "found-links"`, `MAX_LINKS = 2000`, `MAX_LINK_CHARS = 2048`, `REPORT_TIMEOUT = 5.0`
- `absolute_links(text)`: 정규식 `https?://[^\s<>"'\\]+`(대소문자 무시)로 찾고, 끝의 `.,;:!?` 를 떼고, 닫는 `)` 와 `]` 는 여는 짝보다 많을 때만 뗀다
- `relative_links(text)`: 마크다운 대상 `](대상)`(대상은 공백과 `)` 앞까지, `<` `>` 로 감싼 것은 벗긴다)과 `src`, `href` 속성 값(따옴표 두 종류)을 모은다. `#` 로 시작하거나 `data:`, `javascript:`, `mailto:` 로 시작하는 것은 버린다
- `pages(tool_name, result)`: 결과 글을 JSON 으로 읽어 설계 표대로 `[{"base": ..., "links": [...]}]` 를 만든다. `web_search` 는 `success` 가 참이고 `data.web` 이 목록일 때만 항목의 `url`, `title`, `description` 글에서 `absolute_links` 를 모아 `base` 가 None 인 페이지 하나로 만든다. `web_extract` 는 `results` 의 항목마다 `error` 가 비어 있고 `url` 과 `content` 가 글일 때 `absolute_links(content) + relative_links(content)` 를 `base=url` 로 묶는다. 후보는 순서를 지켜 중복을 빼고, 2,048자를 넘는 것은 버리며, 모든 페이지 합쳐 2,000개에서 멈춘다. 링크가 없는 페이지는 뺀다. JSON 이 아니거나 모양이 다르면 빈 목록이다
- `report(tool_name, result, session_id, tool_call_id)`: 도구가 `TOOLS` 에 없거나 결과가 글이 아니거나 session 이나 `tool_call_id` 가 없으면 끝낸다. `pages` 가 비면 끝낸다. `build_context(CTX_TOOL, session_id, tool_call_id)`, `guard_url()` 에 `REPORT_PATH` 를 `urljoin` 한 주소, `_read_token()` 가운데 하나라도 없으면 끝낸다. 본문 `{"_fos_ctx": ctx, "pages": pages}` 를 POST 한다(`Authorization: Bearer`, `Content-Type: application/json`, 제한 시간 5초). HTTP 오류와 연결 오류는 debug 로그 한 줄(상태 코드나 예외 종류)만 남긴다

### 2. `hermes/plugins/fos-ctx/hooks.py` 의 `transform_tool_result`

`image_generate` 분기 다음, 플러그인 결과 분기 앞에 둔다. 도구 이름이 `found_links.TOOLS` 에 있으면 `report(...)` 를 try 로 감싸 부르고 None 을 돌려준다. 함수 설명에 한 줄을 더한다.

### 3. `hermes/plugins/fos-ctx/image_source.py` 의 막는 글

- `NOT_REFERENCE`: `link:<사용자가 보낸 주소>` 를 `link:<사용자가 보낸 주소나 검색·읽기 결과에 나온 주소>` 로
- `UNKNOWN`: 「이 대화에 보낸 사진, 링크나 이 대화에서 만든 그림이 아니다」 뒤에 「검색이나 페이지 읽기 결과에 나온 주소도 아니다. 결과에 나온 주소를 글자 그대로 적었는지 본다」 를 더한다
- 파일 머리 설명에 ADR-20261121 / found-link-image-source 를 더한다

### 4. 문서

- `hermes/plugins/fos-ctx/README.md`: 「그림을 고칠 사진을 받는다」 다음에 「찾은 주소를 알린다」 절을 쓴다. 언제 도는지, 무엇을 뽑는지, 본문 모양, 응답, 실패해도 결과가 그대로라는 것, 근거 ADR. 「그림을 고칠 사진을 받는다」 의 `link:` 설명에 찾은 주소도 받는다고 적는다
- `hermes/docs/hermes-contract.md`: 「웹 검색 서비스」 다음에 「웹 도구의 결과」 절을 쓴다. 두 도구의 결과 JSON 모양, 결과 hook 이 결과 전체를 받는다는 것, 근거 소스 파일. Hermes 를 올릴 때 다시 볼 항목 표에 「두 도구의 결과 모양이 바뀌면 찾은 주소가 뽑히지 않는다」 줄을 더한다
- `docs/features/agent-found-image.md` 의 `covers:` 에 `hermes/plugins/fos-ctx/found_links.py` 를 더한다
- `docs/features/agent-found-image/tasks/found-link-fetch/` 폴더를 지운다. 구현이 끝난 계획서는 구현 PR 에서 지운다(루트 `AGENTS.md`). 운영 왕복(REQ9)은 PR 본문 「남은 범위」 에 적는다

### 5. 이 phase 를 검증하는 테스트

- `hermes/tests/test_found_links.py`(신규): `web_search` 표본에서 `url` 과 설명 안의 주소를 뽑는다. `success` 가 거짓인 응답의 `error` 글에 든 주소는 뽑지 않는다. `web_extract` 표본에서 본문의 절대 주소, 마크다운 사진 대상(`![a](/img/a.jpg)`), `//cdn` 꼴, `src` 속성을 뽑고 `base` 가 항목의 `url` 이며 그 `url` 자체는 후보에 없다. `error` 가 있는 항목은 뺀다. 마크다운 링크 `[a](https://x.example/p)` 에서 닫는 괄호가 주소에 붙지 않는다. 2,000개 상한과 2,048자 상한. `report` 가 가짜 서버에 보낸 본문에 `_fos_ctx` 와 `pages` 가 있고 맥락의 도구 이름이 `found_links` 다. 가짜 서버가 401, 500 으로 답하거나 연결이 실패해도 `transform_tool_result` 가 None 을 돌려주고 예외가 없다. `tool_call_id` 가 없으면 보내지 않는다. 다른 도구 이름에는 보내지 않는다
- `hermes/tests/test_found_links_contract.py`(신규): `HERMES_SOURCE` 가 있을 때 상류 `tools/web_tools.py` 에서 `web_search_tool` 의 설명에 `"data": {"web"` 꼴과 `url` 칸이, `web_extract_tool` 이 `{"results":` 로 직렬화하는 줄이 있는지 본다. 기존 계약 테스트가 소스를 읽고 건너뛰는 방식을 그대로 쓴다
- `hermes/tests/test_image_source.py`: 막는 글을 글자로 보는 단언이 있으면 새 글에 맞춘다

## 변경 파일

| 파일 | 종류 |
| --- | --- |
| `hermes/plugins/fos-ctx/found_links.py` | 신규 |
| `hermes/plugins/fos-ctx/hooks.py` | 수정 |
| `hermes/plugins/fos-ctx/image_source.py` | 수정 |
| `hermes/plugins/fos-ctx/README.md` | 수정 |
| `hermes/docs/hermes-contract.md` | 수정 |
| `docs/features/agent-found-image.md` | 수정 |
| `hermes/tests/test_found_links.py` | 신규 |
| `hermes/tests/test_found_links_contract.py` | 신규 |
| `hermes/tests/test_image_source.py` | 수정 |
| `hermes/tests/*.py` | 신규·수정 |
| `docs/features/agent-found-image/tasks/found-link-fetch/**` | 삭제 |

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_found_links*.py'
python3 -m unittest discover -s hermes/tests -p 'test_image_source.py'
python3 -m unittest discover -s hermes/tests -p 'test_fos_ctx.py'
node --test test/repo/doc-links.test.ts test/repo/doc-files.test.ts
scripts/dev/check-local.sh --fast
```

모두 통과한다. `check-local.sh --fast` 는 무거운 검사 대기열(`orch slot heavy --`)로 감싼다.
