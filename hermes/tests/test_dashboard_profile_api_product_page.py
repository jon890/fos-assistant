"""상품 페이지 toolset 이 웹 검색을 켠 profile 에서만 켜지는지 본다(운영 R7).

`fake_platform_tools` 는 plugin toolset 을 다루지 않는다. 여기서는 Hermes 의 `_enabled_plugin_toolsets` 규칙,
곧 그 platform 의 `known_plugin_toolsets` 에 없는 plugin toolset 은 허용 목록과 상관없이 켜진다는 규칙을 더해 계산한다.
규칙 자체는 `test_found_links_contract.py` 가 Hermes 소스와 대조한다.
"""

import yaml
from unittest.mock import patch

from dashboard_profile_api_support import fake_platform_tools
import dashboard_profile_api_support as support


PRODUCT_PAGE = "fos-product-page"
PLUGIN_TOOLSETS = {"fos-attachments", PRODUCT_PAGE}


def hermes_platform_tools(config, platform):
    """허용 목록 계산 뒤 알려지지 않은 plugin toolset 을 켜고, 마지막에 disabled_toolsets 를 뺀다.

    문자열로 저장된 옛 목록도 Hermes 처럼 목록으로 푼다.
    """
    listed = (config.get("platform_toolsets") or {}).get(platform)
    if isinstance(listed, str):
        listed = yaml.safe_load(listed)
        config = {**config, "platform_toolsets": {**config["platform_toolsets"], platform: listed}}
    enabled = fake_platform_tools(config, platform)
    if isinstance(listed, list):
        enabled |= set(listed) & PLUGIN_TOOLSETS
    known = set(((config.get("known_plugin_toolsets") or {}).get(platform)) or [])
    enabled |= PLUGIN_TOOLSETS - known
    return enabled - set((config.get("agent") or {}).get("disabled_toolsets") or [])


class ProductPageToolsetTest(support.ProfileApiRouteTest):
    def setUp(self):
        super().setUp()
        self.tools._get_platform_tools = hermes_platform_tools
        keys = patch.object(self.tools, "_get_plugin_toolset_keys", return_value=set(PLUGIN_TOOLSETS))
        keys.start()
        self.addCleanup(keys.stop)

    def body(self, allowed):
        # 원본 조회 toolset 은 기록에 없어 늘 켜진다. Control Plane 처럼 목록에 함께 보낸다.
        return {"profile": "owner", "config": {"platform_toolsets": {"api_server": allowed + ["fos-attachments"]}}}

    def write_owner(self, config):
        (self.root / "owner/config.yaml").write_text(yaml.safe_dump(config), encoding="utf-8")

    def test_the_rule_opens_unknown_plugin_toolsets(self):
        """흉내가 실제 위험을 재현한다. 기록이 없으면 웹 검색을 허락하지 않은 목록에도 상품 페이지가 켜진다."""
        config = {"platform_toolsets": {"api_server": ["delegation", "fos-assistant"]}}
        self.assertIn(PRODUCT_PAGE, hermes_platform_tools(config, "api_server"))

    def test_template_profile_keeps_web_and_product_page_off(self):
        """새 profile 은 웹 검색도 상품 페이지도 켜지 않는다."""
        template = yaml.safe_load(self.template.read_text(encoding="utf-8"))
        enabled = hermes_platform_tools(template, "api_server")
        self.assertNotIn("web", enabled)
        self.assertNotIn(PRODUCT_PAGE, enabled)
        self.assertIn("fos-attachments", enabled)

    def test_save_without_web_keeps_both_off(self):
        """기록이 없던 profile 도 웹 검색을 빼고 저장하면 기록이 생겨 상품 페이지가 꺼진다."""
        self.assertNotIn("known_plugin_toolsets", self.saved_config())
        self.assertEqual(self.request("/api/config", "PUT", token="valid",
                                      body=self.body(["delegation", "fos-assistant"])), 200)
        saved = self.saved_config()
        self.assertEqual(saved["known_plugin_toolsets"]["api_server"], [PRODUCT_PAGE])
        enabled = hermes_platform_tools(saved, "api_server")
        self.assertNotIn("web", enabled)
        self.assertNotIn(PRODUCT_PAGE, enabled)

    def test_save_with_web_turns_on_both(self):
        """웹 검색과 상품 페이지를 함께 저장하면 둘 다 켜진다."""
        self.assertEqual(self.request("/api/config", "PUT", token="valid",
                                      body=self.body(["delegation", "web", "fos-assistant", PRODUCT_PAGE])), 200)
        enabled = hermes_platform_tools(self.saved_config(), "api_server")
        self.assertIn("web", enabled)
        self.assertIn(PRODUCT_PAGE, enabled)

    def test_product_page_without_web_is_dropped(self):
        """웹 검색 없이 상품 페이지만 든 목록은 상품 페이지를 빼고 저장한다."""
        self.assertEqual(self.request("/api/config", "PUT", token="valid",
                                      body=self.body(["delegation", "fos-assistant", PRODUCT_PAGE])), 200)
        saved = self.saved_config()
        self.assertNotIn(PRODUCT_PAGE, saved["platform_toolsets"]["api_server"])
        self.assertNotIn(PRODUCT_PAGE, hermes_platform_tools(saved, "api_server"))

    def test_old_control_plane_web_request_keeps_product_page(self):
        """새 이름을 모르는 이전 Control Plane 이 web 만 보내도 상품 페이지가 함께 켜지고, 다시 저장해도 남는다."""
        for _ in range(2):
            self.assertEqual(self.request("/api/config", "PUT", token="valid",
                                          body=self.body(["delegation", "web", "fos-assistant"])), 200)
            saved = self.saved_config()
            self.assertIn(PRODUCT_PAGE, saved["platform_toolsets"]["api_server"])
            enabled = hermes_platform_tools(saved, "api_server")
            self.assertIn("web", enabled)
            self.assertIn(PRODUCT_PAGE, enabled)

    def test_save_keeps_other_known_plugin_toolsets(self):
        """다른 platform 과 다른 이름의 기록은 그대로 두고 상품 페이지만 더한다."""
        config = self.saved_config()
        config["known_plugin_toolsets"] = {"api_server": ["web"], "discord": ["fos-attachments"]}
        self.write_owner(config)
        self.assertEqual(self.request("/api/config", "PUT", token="valid",
                                      body=self.body(["delegation", "fos-assistant"])), 200)
        self.assertEqual(self.saved_config()["known_plugin_toolsets"],
                         {"api_server": ["fos-product-page", "web"], "discord": ["fos-attachments"]})

    def test_migration_follows_web_and_is_idempotent(self):
        """기동 이관은 웹 검색을 켠 profile 에만 상품 페이지를 두고, 다시 돌려도 바뀌지 않는다."""
        self.write_owner({"platform_toolsets": {"api_server": ["delegation", "fos-assistant"]}})
        for name, allowed in (("guest", ["fos-assistant", PRODUCT_PAGE]), ("reader", ["web", "fos-assistant"])):
            (self.root / name).mkdir()
            (self.root / name / "config.yaml").write_text(
                yaml.safe_dump({"platform_toolsets": {"api_server": allowed}}), encoding="utf-8")
        (self.root / "wide").mkdir()
        (self.root / "wide/config.yaml").write_text(yaml.safe_dump({"model": {"default": "m"}}), encoding="utf-8")
        # 옛 버전이 문자열로 저장한 목록과, 목록에는 web 이 있어도 disabled_toolsets 가 끈 profile 이다.
        for name, config in (
                ("legacy", {"platform_toolsets": {"api_server": "[delegation, fos-assistant]"}}),
                ("disabled", {"platform_toolsets": {"api_server": ["web", "fos-assistant", PRODUCT_PAGE]},
                              "agent": {"disabled_toolsets": ["web"]}}),
                ("narrow", {"agent": {"disabled_toolsets": ["web"]}})):
            (self.root / name).mkdir()
            (self.root / name / "config.yaml").write_text(yaml.safe_dump(config), encoding="utf-8")
        for name in ("legacy", "disabled", "narrow"):
            self.assertIn(PRODUCT_PAGE, hermes_platform_tools(
                yaml.safe_load((self.root / name / "config.yaml").read_text(encoding="utf-8")), "api_server"))

        self.assertEqual(self.plugin._migrate_product_page_toolset(), {"changed": 6, "kept": 1, "unreadable": 0})

        def load(name):
            return yaml.safe_load((self.root / name / "config.yaml").read_text(encoding="utf-8"))

        for name in ("owner", "guest", "legacy", "disabled", "narrow"):
            enabled = hermes_platform_tools(load(name), "api_server")
            self.assertNotIn("web", enabled)
            self.assertNotIn(PRODUCT_PAGE, enabled)
        reader = load("reader")
        self.assertEqual(reader["platform_toolsets"]["api_server"], ["web", "fos-assistant", PRODUCT_PAGE])
        self.assertIn(PRODUCT_PAGE, hermes_platform_tools(reader, "api_server"))
        # 허용 목록이 없는 profile 은 Hermes 기본 묶음으로 웹 검색이 켜지므로 그대로 둔다.
        self.assertEqual(load("wide"), {"model": {"default": "m"}})

        before = {name: (self.root / name / "config.yaml").read_bytes()
                  for name in ("owner", "guest", "reader", "legacy", "disabled", "narrow")}
        self.assertEqual(self.plugin._migrate_product_page_toolset(), {"changed": 0, "kept": 7, "unreadable": 0})
        self.assertEqual({name: (self.root / name / "config.yaml").read_bytes() for name in before}, before)
