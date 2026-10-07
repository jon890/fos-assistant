"""dashboard-profile-api 의 profiles 분기를 검사한다."""

import json
import yaml
from plugin_loading import set_plugin
from dashboard_profile_api_support import MCP_URL, ROOT, fake_platform_tools
import dashboard_profile_api_support as support


class ProfileApiProfilesTest(support.ProfileApiRouteTest):
    def test_model_defaults_only_returns_public_fields(self):
        path = self.root / "owner/config.yaml"
        config = yaml.safe_load(path.read_text(encoding="utf-8"))
        config["agent"]["reasoning_effort"] = "medium"
        config["secret"] = "must-not-return"
        path.write_text(yaml.safe_dump(config), encoding="utf-8")
        response = self.request("/api/profiles/owner/model-defaults", "GET", token="valid", full_response=True)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.body, {"provider": "openai-codex", "model": "gpt-5.6-sol", "reasoningEffort": "medium"})

    def test_decision_readiness_rejects_normal_profile_and_auth_failure(self):
        route = "/api/profiles/owner/decision-readiness"
        self.assertEqual(self.request(route, "GET", cookie=True), 401)
        self.assertEqual(self.request(route, "GET", token="invalid"), 401)
        response = self.request(route, "GET", token="valid", full_response=True)
        self.assertEqual(response.body, {"version": 1, "ready": False})
        self.assertEqual(self.request("/api/profiles/missing/decision-readiness", "GET", token="valid"), 404)

    def test_decision_readiness_requires_no_tools_no_memory_and_no_fallback(self):
        config_path = self.root / "owner/config.yaml"
        template = yaml.safe_load((ROOT / "decision-profile/config.yaml.template").read_text())
        template["secret"] = "must-not-return"
        # 전역 MCP 설정이 있어도 no_mcp 를 통해 이 API 실행에서는 도구가 없다.
        template["mcp_servers"] = {"demo": {"enabled": True}}
        config_path.write_text(yaml.safe_dump(template), encoding="utf-8")
        route = "/api/profiles/owner/decision-readiness"
        self.assertEqual(self.request(route, "GET", token="valid", full_response=True).body,
                         {"version": 1, "ready": True})
        for section, key, unsafe in (("memory", "user_profile_enabled", True),
                                     ("memory", "memory_enabled", True),
                                     ("memory", "provider", "external"),
                                     ("platform_toolsets", "api_server", []),
                                     ("platform_toolsets", "api_server", ["no_mcp", "web"])):
            changed = json.loads(json.dumps(template))
            changed[section][key] = unsafe
            config_path.write_text(yaml.safe_dump(changed), encoding="utf-8")
            self.assertFalse(self.request(route, "GET", token="valid", full_response=True).body["ready"])
        template["fallback_providers"] = [{"provider": "other"}]
        config_path.write_text(yaml.safe_dump(template), encoding="utf-8")
        self.assertFalse(self.request(route, "GET", token="valid", full_response=True).body["ready"])

    def test_model_defaults_requires_control_plane_token(self):
        self.assertEqual(self.request("/api/profiles/owner/model-defaults", "GET", cookie=True), 401)
        self.assertEqual(self.request("/api/profiles/owner/model-defaults", "GET", token="invalid"), 401)

    def test_model_defaults_reads_default_without_allowing_writes(self):
        self.make_profile("default")
        response = self.request("/api/profiles/default/model-defaults", "GET", token="valid", full_response=True)
        self.assertEqual(response.status_code, 200)
        self.assertIsNone(response.body["reasoningEffort"])
        self.assertEqual(self.request("/api/profiles/default", "DELETE", token="valid"), 401)
        self.assertEqual(self.request("/api/profiles/missing/model-defaults", "GET", token="valid"), 404)

    def test_create_writes_template_and_keeps_new_model(self):
        """profile 을 만들면 템플릿을 쓰고 새로 정한 model 을 유지한다."""
        # 틀을 쓰기 전에는 넓게 열린다. 흉내가 실제로 차이를 내는지 먼저 본다.
        self.assertIn("terminal", fake_platform_tools({}, "api_server"))

        self.assertEqual(self.create(), 200)

        config = yaml.safe_load((self.root / "alice/config.yaml").read_text(encoding="utf-8"))
        template = yaml.safe_load(self.template.read_text(encoding="utf-8"))
        self.assertEqual(config["model"], {"default": "gpt-5.6-sol", "provider": "openai-codex"})
        self.assertNotIn("__MODEL__", (self.root / "alice/config.yaml").read_text(encoding="utf-8"))
        self.assertEqual(config["platform_toolsets"], template["platform_toolsets"])
        self.assertEqual(config["agent"], template["agent"])
        self.assertEqual(
            fake_platform_tools(config, "api_server"), {"delegation", "fos-assistant"}
        )
        self.assertTrue((self.root / "alice/.no-bundled-skills").is_file())
        self.assertEqual(self.deleted, [])

    def test_create_registers_control_plane_mcp_and_signing_plugin(self):
        """profile 을 만들면 control plane MCP 와 서명 plugin 을 등록한다."""
        self.assertEqual(self.create(), 200)
        profile = self.root / "alice"
        config = yaml.safe_load((profile / "config.yaml").read_text(encoding="utf-8"))
        server = config["mcp_servers"]["fos-assistant"]
        self.assertEqual(server["url"], MCP_URL)
        # 토큰 원문은 틀에 없다. Control Plane 이 PUT /api/env 로 넣는다.
        self.assertEqual(server["headers"], {"Authorization": "Bearer ${MCP_FOS_ASSISTANT_API_KEY}"})
        self.assertEqual(config["plugins"]["enabled"], ["fos-ctx"])
        self.assertEqual(config["plugins"]["entries"]["fos-ctx"], {"allow_tool_override": False})
        source = self.profile_plugins / "fos-ctx"
        copied = profile / "plugins/fos-ctx"
        self.assertEqual(sorted(p.name for p in copied.iterdir()),
                         sorted(p.name for p in source.iterdir() if p.name != "__pycache__"))
        for name in ("__init__.py", "plugin.yaml"):
            self.assertEqual((copied / name).read_bytes(), (source / name).read_bytes())
            self.assertEqual((copied / name).stat().st_mode & 0o777, 0o644)
        self.assertEqual(copied.stat().st_mode & 0o777, 0o755)
        marker = json.loads((profile / ".fos-assistant-managed").read_text(encoding="utf-8"))
        self.assertEqual(marker["created_by"], "fos-assistant-control-plane")
        self.assertEqual(self.reloads, [("/opt/data", str(profile))])

    def test_create_succeeds_when_gateway_does_not_reload(self):
        """gateway 가 다시 읽지 않아도 profile 생성은 성공한다."""
        type(self).reload_fails = True
        self.assertEqual(self.create(), 200)
        self.assertTrue((self.root / "alice/.fos-assistant-managed").is_file())

    def test_create_removes_profile_when_profile_plugin_is_missing(self):
        """profile 용 plugin 이 없으면 만든 profile 을 지운다."""
        set_plugin(self.plugin, "PROFILE_PLUGIN_DIR", self.root / "missing-plugins")
        self.assert_rolled_back(self.create())

    def test_create_rejects_body_keys_before_handler(self):
        """허용하지 않는 본문 키와 형식은 handler 를 부르기 전에 거절한다."""
        for label, body in (
            ("clone_from", {"name": "alice", "clone_from": "owner"}),
            ("clone_all", {"name": "alice", "clone_all": True}),
            ("no name", {"no_skills": True}),
            ("no_skills type", {"name": "alice", "no_skills": "yes"}),
            ("not object", ["alice"]),
        ):
            with self.subTest(label=label):
                self.assertEqual(self.create(body), 400)
                self.assertEqual(sorted(p.name for p in self.root.iterdir()), ["owner"])
        self.assertEqual(self.create({"name": "alice", "no_skills": True, "description": "x"}), 200)

    def test_create_with_two_new_names_removes_both(self):
        """새 이름 둘로 만들면 두 profile 을 모두 지운다."""
        self.created_by_handler = ["alice", "carol"]
        self.assert_rolled_back(self.create(), deleted=("alice", "carol"))

    def test_create_without_template_removes_profile(self):
        """템플릿이 없으면 만든 profile 을 지운다."""
        set_plugin(self.plugin, "TEMPLATE_PATH", self.root / "missing.yaml.template")
        self.assert_rolled_back(self.create())

    def test_create_removes_profile_when_forbidden_toolset_remains(self):
        """금지된 toolset 이 남아 있으면 만든 profile 을 지운다."""
        self.tools._get_platform_tools = lambda config, platform: {"delegation", "terminal"}
        self.assert_rolled_back(self.create())

    def test_create_removes_profile_when_calculation_fails(self):
        """유효 toolset 계산이 실패하면 만든 profile 을 지운다."""
        def broken(config, platform):
            raise KeyError("platform")

        self.tools._get_platform_tools = broken
        self.assert_rolled_back(self.create())

    def test_create_removes_profile_when_calculator_cannot_be_imported(self):
        """계산기를 불러오지 못하면 만든 profile 을 지운다."""
        # Hermes 를 올려 내부 함수 이름이 바뀐 경우다. 넓게 열린 profile 을 남기지 않는다.
        del self.tools._get_platform_tools
        self.assert_rolled_back(self.create())

    def test_create_is_refused_before_handler_when_list_fails(self):
        """기존 profile 목록을 읽지 못하면 handler 를 부르기 전에 생성을 거절한다."""
        type(self).list_fails = True
        self.assertEqual(self.create(), 500)
        self.assertEqual(sorted(p.name for p in self.root.iterdir()), ["owner"])
        self.assertEqual(self.deleted, [])

    def test_create_passes_handler_rejection_through(self):
        """handler 가 생성을 거절하면 그 응답을 그대로 돌려준다."""
        self.handler_status = 400
        self.assertEqual(self.create(), 400)
        self.assertEqual(self.deleted, [])
        self.assertEqual(self.reloads, [])

    def test_cookie_create_is_left_to_dashboard(self):
        """쿠키 요청의 생성은 dashboard 원래 동작에 맡긴다."""
        # 사람이 대시보드에서 만드는 길은 plugin 이 손대지 않는다.
        self.assertEqual(self.request("/api/profiles", "POST", cookie=True), 200)
        self.assertEqual(self.request("/api/profiles", "POST"), 401)
        self.assertNotIn("platform_toolsets", yaml.safe_load(
            (self.root / "alice/config.yaml").read_text(encoding="utf-8")
        ))
        self.assertFalse((self.root / "alice/.fos-assistant-managed").exists())

    def test_delete_needs_the_managed_marker(self):
        """관리 표지가 있는 profile 만 지울 수 있다."""
        self.assertEqual(self.create(), 200)
        # 사람이 만든 profile 과 기본 profile 에는 표식이 없다.
        self.assertEqual(self.request("/api/profiles/owner", "DELETE", token="valid"), 401)
        self.assertEqual(self.request("/api/profiles/default", "DELETE", token="valid"), 401)
        self.assertEqual(self.request("/api/profiles/..", "DELETE", token="valid"), 401)
        self.assertEqual(self.request("/api/profiles/missing", "DELETE", token="valid"), 404)
        self.assertEqual(self.request("/api/profiles/alice/soul", "DELETE", token="valid"), 401)
        self.assertEqual(self.request("/api/profiles/alice", "DELETE"), 401)
        self.assertEqual(self.request("/api/profiles/alice", "DELETE", token="wrong"), 401)
        self.assertEqual(self.request("/api/profiles/alice", "DELETE", token="valid"), 204)
        # 사람의 쿠키 요청은 표식과 무관하게 대시보드가 판정한다.
        self.assertEqual(self.request("/api/profiles/owner", "DELETE", cookie=True), 200)
