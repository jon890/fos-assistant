"""fos-ctx 가 주소를 뽑는 두 웹 도구의 이름과 결과 모양이 Hermes 와 같은지 본다.

fos-ctx 는 `web_search` 와 `web_extract` 의 결과 JSON 에서 주소를 뽑아 Control Plane 에 알린다
(fos-assistant `docs/adr/ADR-20261121-found-link-image-source.md`). 결과 모양이 바뀌면 주소가 뽑히지 않아 비서가 찾은 사진을 쓰지 못하고,
오류도 나지 않는다. 그래서 Hermes 를 올릴 때 이 검사가 먼저 알린다. 모양의 설명은 `hermes/docs/hermes-contract.md` 의 「웹 도구의 결과」 가 갖는다.

Hermes 없이 도는 검사는 plugin 의 값이 계약과 같은지만 본다.
Hermes 소스 검사는 `HERMES_SOURCE` 를 줄 때만 돈다. `scripts/checks/check-hermes-contract.sh` 가 이 값을 준다.
"""

import os
import pathlib
import unittest

import hermes_contract as contract
from plugin_loading import load_plugin


ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE_ENV = "HERMES_SOURCE"
REQUIRED_ENV = "HERMES_CONTRACT_REQUIRED"


class PluginValuesTest(unittest.TestCase):
    def test_plugin_reads_the_contract_tools(self):
        """plugin 이 주소를 뽑는 도구가 계약의 두 웹 도구이고 Hermes 내장 도구 목록에 있다."""
        plugin = load_plugin("fos_ctx_found_links_contract", ROOT / "plugins/fos-ctx/__init__.py", self.addCleanup)
        found_links = __import__("sys").modules[plugin.__name__ + ".found_links"]
        self.assertEqual(found_links.WEB_TOOLS, frozenset(contract.WEB_RESULT_TOOLS))
        for tool in contract.WEB_RESULT_TOOLS:
            self.assertIn(tool, contract.HERMES_BUILTIN_TOOLS)
        self.assertEqual(found_links.SEARCH_FIELDS, contract.WEB_SEARCH_ITEM_FIELDS)

    def test_product_reader_is_registered_in_its_own_toolset(self):
        """상품 페이지 읽기는 자기 toolset 의 비동기 도구다. 내장 도구는 바꾸지 않는다.

        plugin 이 등록한 toolset 은 `known_plugin_toolsets` 에 없는 profile 에서 기본으로 켜진다.
        Hermes 기본 이름(`web` 등)으로 등록하면 그 내장 toolset 까지 허용 목록과 상관없이 켜진다(운영 R7).
        """
        plugin = load_plugin("fos_ctx_product_page_registration", ROOT / "plugins/fos-ctx/__init__.py", self.addCleanup)
        registered = {}

        class Context:
            def register_tool(self, **tool):
                registered[tool["name"]] = tool

            def register_hook(self, *args):
                pass

        plugin.register(Context())
        product = registered["product_page_read"]
        self.assertEqual(product["toolset"], "fos-product-page")
        for tool in registered.values():
            self.assertNotIn(tool["toolset"], contract.HERMES_TOOLSETS)
            self.assertFalse(tool["toolset"].startswith("hermes-"))
        self.assertIs(product["is_async"], True)
        self.assertFalse(product.get("override", False))


class HermesWebResultTest(unittest.TestCase):
    """`HERMES_SOURCE` 의 Hermes 소스에서 두 웹 도구의 결과 모양을 대조한다."""

    @classmethod
    def setUpClass(cls):
        if not os.environ.get(SOURCE_ENV):
            if os.environ.get(REQUIRED_ENV):
                raise AssertionError("%s 가 없다" % SOURCE_ENV)
            raise unittest.SkipTest("%s 가 없어 Hermes 의 웹 도구 결과 모양을 확인하지 않는다" % SOURCE_ENV)
        cls.root = pathlib.Path(os.environ[SOURCE_ENV]).resolve()

    def source(self, path):
        return (self.root / path).read_text(encoding="utf-8")

    def test_search_result_is_success_with_web_items(self):
        """`web_search` 는 `{"success", "data": {"web": [{"title", "url", "description", ...}]}}` 를 돌려준다."""
        tools = self.source("tools/web_tools.py")
        self.assertIn('name="web_search"', tools)
        self.assertIn('{"success": bool, "data": {"web": [{"title", "url", "description", "position"}', tools)
        self.assertIn('response_data.get("data", {}).get("web", [])', tools)
        self.assertIn('json.dumps({"success": False, "error": error_text}', tools)

    def test_extract_result_is_results_with_url_and_content(self):
        """`web_extract` 는 `{"results": [{"url", "title", "content", "error"}]}` 를 돌려주고 본문을 LLM 으로 요약하지 않는다."""
        tools = self.source("tools/web_tools.py")
        self.assertIn('name="web_extract"', tools)
        self.assertIn('json.dumps({"results": trimmed}', tools)
        self.assertIn("no LLM summarization", tools)
        extract = self.source("tools/web_tools_extract.py")
        self.assertIn('return {"url": url, "title": "", "content": "", "error": error}', extract)

    def test_result_hook_receives_the_whole_result(self):
        """결과 hook 은 자르지 않은 결과 글과 호출 번호를 받는다."""
        model_tools = self.source("model_tools.py")
        self.assertIn('invoke_hook("transform_tool_result", tool_name=function_name, args=function_args,', model_tools)
        self.assertIn("result=result, **ids.hook_kwargs()", model_tools)

    def test_product_reader_uses_hermes_web_url_guards_and_free_scrape_transport(self):
        """상품 전용 읽기는 원래 페이지와 이동한 주소를 확인하고 무료 스크레이프의 형식을 지정한다."""
        extract = self.source("tools/web_tools_extract.py")
        self.assertIn("def _validate_extract_urls(", extract)
        self.assertIn("async def async_is_safe_url(", self.source("tools/url_safety.py"))
        self.assertIn("def check_website_access(", self.source("tools/website_policy.py"))
        firecrawl = self.source("plugins/web/firecrawl/provider.py")
        for contract_part in ("_FIRECRAWL_CLOUD_API_URL", "class _KeylessFirecrawlClient:",
                              'return self._post("/v2/scrape", payload)', "def _extract_scrape_payload(",
                              "def _final_url("):
            self.assertIn(contract_part, firecrawl)
        self.assertIn("def keyless_enabled(", self.source("plugins/web/keyless_mcp.py"))
        self.assertIn("def provider_tier(", self.source("plugins/web/keyless_mcp.py"))
        self.assertIn("is_async: bool", self.source("hermes_cli/plugins_context.py"))
        self.assertIn("registry.get_tool_names_for_toolset(name)", self.source("toolsets.py"))

    def test_unknown_plugin_toolset_is_enabled_by_default(self):
        """plugin toolset 은 그 platform 의 `known_plugin_toolsets` 에 없으면 허용 목록과 상관없이 켜진다.

        그래서 profile 틀과 대시보드 plugin 이 `fos-product-page` 를 알려진 이름으로 적어 둔다.
        """
        tools = self.source("hermes_cli/tools_config.py")
        self.assertIn('known_for_platform = set((config.get("known_plugin_toolsets", {}) or {}).get(platform, []) or [])',
                      tools)
        self.assertIn("if pts in toolset_names or (pts not in _DEFAULT_OFF_TOOLSETS and pts not in known_for_platform)",
                      tools)
