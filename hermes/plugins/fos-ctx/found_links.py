"""비서가 웹 검색과 페이지 읽기로 찾은 주소를 결과에서 뽑아 Control Plane 에 알린다.

계약의 정본은 fos-assistant `docs/adr/ADR-20261121-found-link-image-source.md` 와 `hermes/plugins/fos-ctx/README.md` 의
「찾은 주소를 알린다」 다. Control Plane 은 알린 주소의 해시를 그 대화에 남기고, 모델이 `image_generate` 에 `link:` 로 가리킨 주소가
사용자 메시지에 없으면 이 해시에 있을 때만 사진을 받는다.

여기서는 글자를 뽑기만 한다. 같은 주소인지, 상대 주소를 어떻게 푸는지는 Control Plane 이 정한다. 결과 글은 바꾸지 않고,
알리지 못해도 도구 결과는 그대로 간다. 두 도구의 결과 모양은 `hermes/docs/hermes-contract.md` 의 「웹 도구의 결과」 가 갖는다.
"""

from __future__ import annotations

import json
import re
import urllib.error
import urllib.parse
import urllib.request

from .context import _read_token, build_context, logger
from .tool_guard import guard_url

SEARCH_TOOL = "web_search"
EXTRACT_TOOL = "web_extract"
TOOLS = frozenset({SEARCH_TOOL, EXTRACT_TOOL})
# 호출 맥락을 서명할 때 넣는 도구 이름이다. Control Plane 의 `FoundLinkService.TOOL` 과 같다.
CTX_TOOL = "found_links"
REPORT_PATH = "found-links"
# Control Plane 의 `FoundLinkService.MAX_LINKS` 와 `FoundLinks.MAX_LINK_CHARS` 와 같다. 넘는 것은 보내지 않는다.
MAX_LINKS = 2000
MAX_LINK_CHARS = 2048
# Hermes 의 callback 제한(기본 30초)보다 훨씬 짧게 둔다. 웹 도구마다 한 번 부르므로 답을 늦추지 않아야 한다.
REPORT_TIMEOUT = 5.0
# 한 호출에서 보내는 「비서가 적어 넣은 주소」 의 수다. Control Plane 의 `FoundLinkService.MAX_TYPED` 와 같다.
MAX_TYPED = 50
# 검색 결과 항목에서 주소를 찾는 칸이다. 실패 응답의 `error` 는 남이 쓴 결과가 아니라 읽지 않는다.
SEARCH_FIELDS = ("url", "title", "description")

_ABSOLUTE = re.compile(r"https?://[^\s<>\"'\\]+", re.IGNORECASE)
# 마크다운 링크와 사진의 대상이다. `<` `>` 로 감싼 대상은 벗긴다.
_MARKDOWN_TARGET = re.compile(r"\]\(\s*<?([^)\s>]+)")
_ATTRIBUTE = re.compile(r"(?:src|href)\s*=\s*(?:\"([^\"]+)\"|'([^']+)')", re.IGNORECASE)
_TRAILING = ".,;:!?*"
# 주소에 쓸 수 있는 ASCII 문자(RFC 3986 의 unreserved, reserved 와 `%`)다. Control Plane 의 `LinkTarget.URL_CHARACTERS` 와 같다.
_URL_CHARACTERS = frozenset("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~:/?#[]@!$&'()*+,;=%")
# 다듬기 전의 덩어리가 이보다 길면 버린다. 닫는 괄호를 길게 이어 붙인 본문이 다듬기를 오래 붙잡지 못하게 한다.
MAX_FOUND_CHARS = MAX_LINK_CHARS * 2
_SKIPPED_PREFIXES = ("#", "data:", "javascript:", "mailto:", "tel:")


def _trim(link: str) -> str:
    """주소 끝에 붙은 문장 부호와, 여는 짝이 없는 닫는 괄호를 뗀다. 괄호 수는 한 번만 세고 줄여 간다."""
    opened = {")": link.count("("), "]": link.count("[")}
    closed = {")": link.count(")"), "]": link.count("]")}
    end = len(link)
    while end:
        last = link[end - 1]
        if last in _TRAILING:
            end -= 1
        elif last in closed and closed[last] > opened[last]:
            closed[last] -= 1
            end -= 1
        else:
            break
    return link[:end]


def _url_prefix(link: str) -> str:
    """주소에 쓸 수 없는 첫 문자(한글 조사, `|` 등) 앞까지다. Control Plane 의 `LinkTarget.urlPrefix` 와 같은 경계다."""
    for index, character in enumerate(link):
        if character not in _URL_CHARACTERS:
            return link[:index]
    return link


def absolute_links(text) -> list:
    """글에서 `http://`, `https://` 로 시작하는 주소를 나온 순서대로 뽑는다.

    주소 뒤에 한글 조사처럼 주소에 쓸 수 없는 글자가 바로 붙었으면 그 앞까지 자른 꼴을 먼저, 붙은 꼴을 그다음에 낸다.
    한글 경로를 그대로 적은 주소는 붙은 꼴이 맞는 주소다. 상한을 넘는 덩어리는 다듬지 않고 버린다.
    """
    if not isinstance(text, str):
        return []
    links = []
    for found in _ABSOLUTE.findall(text):
        if len(found) > MAX_FOUND_CHARS:
            continue
        whole = _trim(found)
        cut = _trim(_url_prefix(found))
        for link in (cut, whole) if cut != whole else (whole,):
            if link and _ABSOLUTE.fullmatch(link):
                links.append(link)
    return links


def relative_links(text) -> list:
    """마크다운 링크와 사진의 대상, `src` 와 `href` 속성 값을 나온 순서대로 뽑는다. 절대 주소도 함께 나온다."""
    if not isinstance(text, str):
        return []
    found = [match.group(1) for match in _MARKDOWN_TARGET.finditer(text)]
    found += [match.group(1) or match.group(2) for match in _ATTRIBUTE.finditer(text)]
    return [link for link in (value.strip() for value in found)
            if link and not link.lower().startswith(_SKIPPED_PREFIXES)]


def _search_page(parsed) -> list:
    if parsed.get("success") is not True:
        return []
    data = parsed.get("data")
    web = data.get("web") if isinstance(data, dict) else None
    if not isinstance(web, list):
        return []
    links = []
    for item in web:
        if isinstance(item, dict):
            for field in SEARCH_FIELDS:
                links += absolute_links(item.get(field))
    return [{"base": None, "links": links}]


def _extract_pages(parsed) -> list:
    results = parsed.get("results")
    if not isinstance(results, list):
        return []
    found = []
    for item in results:
        if not isinstance(item, dict) or item.get("error"):
            continue
        url, content = item.get("url"), item.get("content")
        if not isinstance(url, str) or not isinstance(content, str):
            continue
        # 항목의 `url` 은 비서가 적어 넣은 주소라 후보가 아니다. 상대 주소를 푸는 기준으로만 보낸다.
        found.append({"base": url, "links": absolute_links(content) + relative_links(content)})
    return found


def typed_urls(tool_name, args) -> list:
    """비서가 `web_extract` 에 적어 넣은 주소다. Control Plane 이 찾은 주소에서 뺀다(fos-assistant 스펙 agent-found-image DECISION25).

    읽기 서비스에 따라 결과 항목의 `url` 이 넘어간 뒤의 최종 주소일 수 있어, 적어 넣은 원래 주소를 따로 보낸다.
    """
    if tool_name != EXTRACT_TOOL or not isinstance(args, dict):
        return []
    urls = args.get("urls")
    if isinstance(urls, str):
        urls = [urls]
    if not isinstance(urls, list):
        return []
    return [url for url in urls if isinstance(url, str) and 0 < len(url) <= MAX_LINK_CHARS][:MAX_TYPED]


def pages(tool_name, result) -> list:
    """도구 결과에서 `[{"base": 기준 주소나 None, "links": [후보]}]` 를 만든다. 뽑을 것이 없거나 모양이 다르면 빈 목록이다."""
    if tool_name not in TOOLS or not isinstance(result, str):
        return []
    try:
        parsed = json.loads(result)
    except ValueError:
        return []
    if not isinstance(parsed, dict):
        return []
    raw = _search_page(parsed) if tool_name == SEARCH_TOOL else _extract_pages(parsed)
    kept, total = [], 0
    for page in raw:
        seen, links = set(), []
        for link in page["links"]:
            if total >= MAX_LINKS:
                break
            if link in seen or len(link) > MAX_LINK_CHARS:
                continue
            seen.add(link)
            links.append(link)
            total += 1
        if links:
            kept.append({"base": page["base"], "links": links})
    return kept


def report(tool_name, result, session_id, tool_call_id, args=None) -> None:
    """웹 도구의 결과에서 뽑은 주소를 Control Plane 에 알린다. 알릴 것이 없거나 맥락이 없으면 아무것도 하지 않는다.

    주소와 결과 글은 로그에 남기지 않는다. 옛 Control Plane 은 이 경로를 몰라 거절로 답하고, 그때도 넘어간다.
    """
    if not session_id or not tool_call_id:
        return
    found = pages(tool_name, result)
    if not found:
        return
    guard = guard_url()
    url = urllib.parse.urljoin(guard, REPORT_PATH) if guard else ""
    token = _read_token()
    ctx = build_context(CTX_TOOL, session_id, tool_call_id)
    if not url or not token or not ctx:
        return
    payload = {"_fos_ctx": ctx, "pages": found}
    typed = typed_urls(tool_name, args)
    if typed:
        payload["typed"] = typed
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url, data=body, method="POST",
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + token},
    )
    try:
        with urllib.request.urlopen(request, timeout=REPORT_TIMEOUT) as response:
            response.read()
    except urllib.error.HTTPError as exc:
        exc.close()
        logger.debug("fos-ctx: 찾은 주소를 알리지 못했다: HTTP %d", exc.code)
    except OSError as exc:
        logger.debug("fos-ctx: 찾은 주소를 알리지 못했다: %s", type(exc).__name__)
