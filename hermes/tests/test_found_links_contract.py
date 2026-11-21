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
        self.assertEqual(found_links.TOOLS, frozenset(contract.WEB_RESULT_TOOLS))
        for tool in contract.WEB_RESULT_TOOLS:
            self.assertIn(tool, contract.HERMES_BUILTIN_TOOLS)
        self.assertEqual(found_links.SEARCH_FIELDS, contract.WEB_SEARCH_ITEM_FIELDS)


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
