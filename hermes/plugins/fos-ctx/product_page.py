"""상품 페이지를 한 번 읽어 대표 사진을 고를 때 필요한 정보만 돌려준다."""

from __future__ import annotations

import json
import re
from html.parser import HTMLParser
from urllib.parse import urlsplit

from .context import logger
from .next_step import alternative

TOOL = "product_page_read"
# 이 도구만 담는 자기 toolset 이다. Hermes 기본 이름(`web`)으로 등록하면 그 이름이 plugin toolset 이 되어,
# `known_plugin_toolsets` 에 없는 profile 에서 웹 검색까지 기본으로 켜진다. Control Plane 은 `web` 을 켠 profile 에만 이것을 켠다.
TOOLSET = "fos-product-page"
MAX_URL_CHARS = 2048
MAX_IMAGES = 24
MAX_HTML_CHARS = 2_000_000
MAX_IMAGE_AREA = 10_000_000_000
MAX_RESPONSE_BYTES = 4 * 1024 * 1024
_SRCSET_URL = re.compile(r"[\s,]*([^\s,]\S*)")
_DENSITY = re.compile(r"[0-9]{0,5}(?:\.[0-9]{1,5})?x")
_LAYOUT_TAGS = frozenset({"header", "nav", "footer", "aside"})
SCHEMA = {
    "name": TOOL,
    "description": "웹 검색에서 찾은 상품 페이지를 한 번 읽고 상품 사진 후보를 확인한다. "
                   "그림에 참고할 때는 읽은 상품 페이지 주소를 그대로 link: 뒤에 쓴다.",
    "parameters": {
        "type": "object",
        "properties": {"url": {"type": "string", "maxLength": MAX_URL_CHARS}},
        "required": ["url"],
    },
}


def _size(text):
    return int(text) if text and text.isascii() and text.isdecimal() and len(text) <= 5 else 0


def _srcset(value):
    """주소 안의 쉼표는 보존하고, 표기 뒤의 쉼표에서 다음 후보로 넘어간다."""
    offset = 0
    while match := _SRCSET_URL.match(value, offset):
        url, offset = match.group(1), match.end()
        if url.endswith(","):
            yield url.rstrip(","), ""
            continue
        end = value.find(",", offset)
        end = len(value) if end < 0 else end
        yield url, value[offset:end].strip()
        offset = end


def _srcset_size(descriptor, width, height):
    area = width * height or max(width, height)**2
    if not descriptor:
        return area
    if descriptor.endswith("w") and (candidate_width := _size(descriptor[:-1])):
        pixels = candidate_width**2
        return pixels * height // width if width and height else pixels
    if descriptor != "x" and _DENSITY.fullmatch(descriptor):
        return int(area * float(descriptor[:-1])**2)
    return None


def _nodes(value):
    """JSON-LD의 배열, 그래프와 중첩된 상품을 순서대로 읽는다."""
    pending = [value]
    while pending:
        node = pending.pop()
        if isinstance(node, dict):
            yield node
            pending.extend(reversed(tuple(node.values())))
        elif isinstance(node, list):
            pending.extend(reversed(node))


def _product_images(text):
    try:
        data = json.loads(text)
    except (ValueError, RecursionError):
        return
    for node in _nodes(data):
        kinds = node.get("@type", [])
        kinds = kinds if isinstance(kinds, list) else [kinds]
        if not any(kind in ("Product", "https://schema.org/Product", "http://schema.org/Product") for kind in kinds):
            continue
        values = node.get("image")
        for image in values if isinstance(values, list) else [values]:
            if isinstance(image, str):
                yield image
            elif isinstance(image, dict):
                yield image.get("contentUrl") or image.get("url") or image.get("@id")


class _Photos(HTMLParser):
    def __init__(self):
        super().__init__()
        self.images = {"product": {}, "og": {}, "body": {}}
        self.script = None
        self.layout = []

    def add(self, kind, url, size=0):
        if not isinstance(url, str):
            return
        url = url.strip()
        if not url or len(url) > MAX_URL_CHARS or url.lower().startswith(("#", "data:", "javascript:")):
            return
        entries = self.images[kind]
        entries[url] = min(MAX_IMAGE_AREA, max(size, entries.get(url, 0)))

    def handle_starttag(self, tag, attrs):
        fields = dict(attrs)
        if tag in _LAYOUT_TAGS:
            self.layout.append(tag)
        if tag == "script" and (fields.get("type") or "").lower() == "application/ld+json":
            self.script = []
        if tag == "meta" and (fields.get("property") or fields.get("name") or "").lower() in {
            "og:image", "og:image:url", "og:image:secure_url",
        }:
            self.add("og", fields.get("content"))
        if not self.layout and tag in ("img", "source"):
            self._body_image(tag, fields)

    def handle_data(self, data):
        if self.script is not None:
            self.script.append(data)

    def handle_endtag(self, tag):
        if self.layout and tag == self.layout[-1]:
            self.layout.pop()
        if tag == "script" and self.script is not None:
            for url in _product_images("".join(self.script)):
                self.add("product", url)
            self.script = None

    def _body_image(self, tag, fields):
        width, height = _size(fields.get("width")), _size(fields.get("height"))
        for key in ("data-srcset", "srcset"):
            for url, descriptor in _srcset(fields.get(key) or ""):
                area = _srcset_size(descriptor, width, height)
                if area is not None:
                    self.add("body", url, area)
        if tag == "img":
            for key in ("data-src", "src"):
                self.add("body", fields.get(key), width * height or max(width, height)**2)


def photos(html):
    parser = _Photos()
    parser.feed(html[:MAX_HTML_CHARS])
    parser.close()
    return [
        {"kind": kind, "url": url, "size": size}
        for kind, entries in parser.images.items()
        for url, size in sorted(entries.items(), key=lambda entry: -entry[1])[:MAX_IMAGES // len(parser.images)]
    ]


def _valid_https_url(url):
    if not isinstance(url, str) or not 0 < len(url) <= MAX_URL_CHARS or any(ch.isspace() for ch in url):
        return False
    try:
        parsed = urlsplit(url)
        return (parsed.scheme == "https" and bool(parsed.hostname) and parsed.port in (None, 443)
                and parsed.username is None and parsed.password is None)
    except ValueError:
        return False


async def _read_html(url):
    """Hermes와 같은 무료 읽기 주소와 웹 주소 검사를 쓴다."""
    from plugins.web.firecrawl.provider import _FIRECRAWL_CLOUD_API_URL, _extract_scrape_payload, _final_url
    from plugins.web.keyless_mcp import keyless_enabled, provider_tier
    from tools.url_safety import async_is_safe_url
    from tools.web_tools_extract import _validate_extract_urls
    from tools.website_policy import check_website_access

    urls, _, _, blocked = _validate_extract_urls([url])
    if blocked or not urls or not keyless_enabled() or provider_tier("firecrawl") == "paid":
        return None
    requested = urls[0]
    if not _valid_https_url(requested) or not await async_is_safe_url(requested) or check_website_access(requested):
        return None
    response = await _scrape(_FIRECRAWL_CLOUD_API_URL, requested)
    if response.get("success") is not True:
        return None
    payload = _extract_scrape_payload(response)
    metadata = payload.get("metadata") or {}
    final = _final_url(metadata, requested)
    status = metadata.get("statusCode")
    if (type(status) is not int or status != 200 or not _valid_https_url(final)
            or not await async_is_safe_url(final) or check_website_access(final)):
        return None
    html = payload.get("rawHtml")
    return (final, html) if isinstance(html, str) else None


async def _scrape(api_url, page):
    """원본 HTML이 큰 페이지로 읽기 응답의 메모리 사용이 늘지 않게 크기와 시간을 제한한다."""
    import asyncio
    import httpx

    body = bytearray()
    async with asyncio.timeout(15), httpx.AsyncClient(follow_redirects=False) as client:
        async with client.stream("POST", f"{api_url}/v2/scrape", headers={"Accept-Encoding": "identity"},
                                 json={"url": page, "formats": ["rawHtml"], "timeout": 12_000}) as response:
            if response.status_code != 200 or response.headers.get("Content-Encoding", "identity").lower() != "identity":
                return {}
            async for chunk in response.aiter_raw():
                if len(body) + len(chunk) > MAX_RESPONSE_BYTES:
                    return {}
                body.extend(chunk)
    parsed = json.loads(body)
    return parsed if isinstance(parsed, dict) else {}


async def handle(args, **_):
    url = args.get("url") if isinstance(args, dict) else None
    if not _valid_https_url(url):
        return _failure("상품 페이지 주소가 올바르지 않다", "검색 결과에 나온 상품 페이지의 전체 주소를 넣는다")
    try:
        read = await _read_html(url)
        if read:
            base, html = read
            return json.dumps({"base": base, "images": photos(html)}, ensure_ascii=False)
    except Exception as exc:
        logger.warning("fos-ctx: 상품 페이지를 읽지 못했다: %s", type(exc).__name__)
    return _failure("상품 페이지에서 사진을 읽지 못했다", "검색 결과의 다른 판매처나 사진 주소를 쓴다")


def _failure(reason, next_step):
    return json.dumps({"error": alternative(reason, next_step)}, ensure_ascii=False)
