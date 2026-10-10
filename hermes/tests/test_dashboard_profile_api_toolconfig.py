"""dashboard-profile-api 의 toolconfig 분기를 검사한다."""

import os
import yaml
from unittest.mock import patch
from dashboard_profile_api_support import fake_platform_tools
import dashboard_profile_api_support as support


class ProfileApiToolconfigTest(support.ProfileApiRouteTest):
    def test_registered_original_inspection_is_saved_and_unknown_one_is_rejected(self):
        """실제로 등록한 원본 조회 toolset만 설정에 저장할 수 있다."""
        body = self.toolset_body()
        body["config"]["platform_toolsets"]["api_server"].append("fos-attachments")
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 400)
        with patch.object(self.tools, "_get_plugin_toolset_keys", return_value={"fos-attachments"}):
            self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 200)
        saved = yaml.safe_load((self.root / "owner/config.yaml").read_text(encoding="utf-8"))
        self.assertIn("fos-attachments", fake_platform_tools(saved, "api_server"))

    def test_toolset_update_saves_only_checked_lists(self):
        """toolset 갱신은 검사를 통과한 목록만 저장한다."""
        body = self.toolset_body()
        before = yaml.safe_load((self.root / "owner/config.yaml").read_text(encoding="utf-8"))
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 200)
        saved = yaml.safe_load((self.root / "owner/config.yaml").read_text(encoding="utf-8"))
        self.assertEqual(saved["platform_toolsets"]["api_server"],
                         body["config"]["platform_toolsets"]["api_server"])
        self.assertEqual(saved["platform_toolsets"]["discord"], before["platform_toolsets"]["discord"])
        self.assertEqual(saved["agent"], before["agent"])
        # GET /p/<profile>/v1/toolsets 가 표시하는 API 경로의 실제 켜짐 상태를 계산한다.
        self.assertEqual(fake_platform_tools(saved, "api_server"),
                         {"delegation", "web", "fos-assistant"})
        self.assertEqual(self.request("/api/tools/toolsets", "GET", token="valid"), 204)
        self.assertEqual(self.request("/api/tools/toolsets", "POST", token="valid"), 401)
        self.assertEqual(self.request("/api/config", "GET", token="valid"), 401)
        self.assertEqual(self.request("/api/config", "PUT", cookie=True, body=body), 200)

    def test_unregistered_memory_mcp_needs_builtin_toolset(self):
        """등록되지 않은 memory MCP 는 내장 toolset 이 있어야 다룬다."""
        self.make_profile("blog")
        body = self.toolset_body()
        body["profile"] = "blog"
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 200)
        saved = yaml.safe_load((self.root / "blog/config.yaml").read_text(encoding="utf-8"))
        self.assertEqual(fake_platform_tools(saved, "api_server"),
                         {"delegation", "web", "fos-assistant"})

        body["config"]["platform_toolsets"]["api_server"] = ["fos-assistant"]
        original = (self.root / "blog/config.yaml").read_bytes()
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 400)
        self.assertEqual((self.root / "blog/config.yaml").read_bytes(), original)

    # 옛 이름 fos-assistant-memory 는 더 받지 않는다. 그 이름으로 등록된 profile 이 남아도 거절한다.
    def test_legacy_mcp_name_is_refused_even_if_registered(self):
        """옛 MCP 이름은 등록돼 있어도 거절한다."""
        self.register_memory("owner", "fos-assistant-memory")
        body = self.toolset_body()
        body["config"]["platform_toolsets"]["api_server"] = ["delegation", "web", "fos-assistant-memory"]
        original = (self.root / "owner/config.yaml").read_bytes()
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 400)
        self.assertEqual((self.root / "owner/config.yaml").read_bytes(), original)

    # 새 이름으로 옮긴 profile 에 옛 이름만 보내면 등록된 이름이 목록에 없어 등록된 MCP 가 모두 켜진다.
    def test_legacy_mcp_name_is_refused_after_the_rename(self):
        """이름을 바꾼 뒤에도 옛 MCP 이름은 거절한다."""
        body = self.toolset_body()
        body["config"]["platform_toolsets"]["api_server"] = ["delegation", "web", "fos-assistant-memory"]
        original = (self.root / "owner/config.yaml").read_bytes()
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 400)
        self.assertEqual((self.root / "owner/config.yaml").read_bytes(), original)

    # 공유 gateway 는 multiplex 로 돌아, 계산 함수가 profile scope 밖에서 비밀값을 읽으면 예외가 난다.
    def test_toolset_update_computes_inside_the_profile_scope(self):
        """toolset 갱신은 대상 profile 범위 안에서 유효 목록을 계산한다."""
        seen = []

        def scoped(config, platform):
            seen.append(self.scope["profile"])
            return fake_platform_tools(config, platform)

        self.tools._get_platform_tools = scoped
        self.assertEqual(self.request("/api/config", "PUT", token="valid",
                                      body=self.toolset_body()), 200)
        self.assertTrue(seen)
        self.assertEqual(set(seen), {"owner"})

    def test_toolset_update_rejects_effective_memory_if_hermes_changes(self):
        """Hermes 가 바뀌어 memory 가 유효해지면 toolset 갱신을 거절한다."""
        self.tools._get_platform_tools = lambda config, platform: {"memory", "delegation"}
        original = (self.root / "owner/config.yaml").read_bytes()
        self.assertEqual(self.request("/api/config", "PUT", token="valid",
                                      body=self.toolset_body()), 400)
        self.assertEqual((self.root / "owner/config.yaml").read_bytes(), original)

    def test_unregistered_memory_mcp_does_not_open_other_mcp(self):
        """등록되지 않은 memory MCP 를 다루는 길이 다른 MCP 를 열지 않는다."""
        self.make_profile("blog")
        path = self.root / "blog/config.yaml"
        config = yaml.safe_load(path.read_text(encoding="utf-8"))
        config["mcp_servers"] = {"other-mcp": {"command": "other"}}
        path.write_text(yaml.safe_dump(config), encoding="utf-8")
        body = self.toolset_body()
        body["profile"] = "blog"
        original = path.read_bytes()
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 400)
        self.assertEqual(path.read_bytes(), original)

    def test_toolset_update_rejects_auto_enabled_plugin_toolset(self):
        """plugin 이 자동으로 켠 toolset 이 있으면 갱신을 거절한다."""
        self.tools._get_platform_tools = lambda config, platform: {"delegation", "spotify"}
        original = (self.root / "owner/config.yaml").read_bytes()
        self.assertEqual(self.request("/api/config", "PUT", token="valid",
                                      body=self.toolset_body()), 400)
        self.assertEqual((self.root / "owner/config.yaml").read_bytes(), original)

    def test_toolset_update_rejects_other_platform_change(self):
        """다른 platform 의 toolset 을 바꾸는 갱신은 거절한다."""
        def changed(config, platform):
            if platform == "discord":
                return {"terminal"} if "api_server" in config.get("platform_toolsets", {}) else {"web"}
            return {"delegation"}

        self.tools._get_platform_tools = changed
        original = (self.root / "owner/config.yaml").read_bytes()
        self.assertEqual(self.request("/api/config", "PUT", token="valid",
                                      body=self.toolset_body()), 400)
        self.assertEqual((self.root / "owner/config.yaml").read_bytes(), original)

    def test_toolset_update_lifts_disabled_names_only_for_api(self):
        """켠 도구를 disabled_toolsets 에서 빼고, 목록 없는 다른 platform 은 지금 계산 결과로 고정한다."""
        path = self.disable_code_execution()
        before = yaml.safe_load(path.read_text(encoding="utf-8"))
        self.assertNotIn("code_execution", fake_platform_tools(before, "api_server"))
        others = {name: fake_platform_tools(before, name) for name in ("cli", "discord")}

        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.code_execution_body()), 200)
        saved = self.saved_config()
        self.assertEqual(fake_platform_tools(saved, "api_server"), {"code_execution", "delegation", "fos-assistant"})
        self.assertEqual({name: fake_platform_tools(saved, name) for name in others}, others)
        # 요청하지 않은 이름과 memory 는 그대로 남는다.
        self.assertEqual(saved["agent"]["disabled_toolsets"], ["memory", "terminal"])
        self.assertEqual(saved["platform_toolsets"]["cli"], sorted(others["cli"]))
        self.assertEqual(saved["platform_toolsets"]["discord"], ["web"])

        # 끌 때는 허용 목록만 바뀐다. disabled_toolsets 에 다시 넣지 않는다.
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.toolset_body()), 200)
        saved = self.saved_config()
        self.assertEqual(fake_platform_tools(saved, "api_server"), {"delegation", "web", "fos-assistant"})
        self.assertEqual(saved["agent"]["disabled_toolsets"], ["memory", "terminal"])

    def test_toolset_update_keeps_memory_disabled(self):
        """memory 를 요청하면 disabled_toolsets 를 건드리지 않고 거절한다."""
        path = self.disable_code_execution()
        original = path.read_bytes()
        self.assertEqual(self.request("/api/config", "PUT", token="valid",
                                      body=self.code_execution_body("memory")), 400)
        self.assertEqual(path.read_bytes(), original)

    def test_toolset_update_refuses_to_open_listed_platform(self):
        """목록이 있는 platform 에 그 도구가 열리게 되면 고정하지 않고 거절한다."""
        path = self.disable_code_execution(discord=("web", "code_execution"))
        original = path.read_bytes()
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.code_execution_body()), 400)
        self.assertEqual(path.read_bytes(), original)

    def test_toolset_update_restores_config_when_handler_fails(self):
        """plugin 이 먼저 쓴 설정은 처리기가 실패하면 원래 바이트로 되돌린다."""
        path = self.disable_code_execution()
        original = path.read_bytes()
        self.handler_status = 500
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.code_execution_body()), 500)
        self.assertEqual(path.read_bytes(), original)

    def test_skill_publish_with_skills_toolset_in_one_request(self):
        """skills toolset 과 skill 발행을 한 요청에 담아도 처리한다."""
        version = self.make_skill_version("owner", "v1")
        body = self.skills_body([version], ["delegation", "fos-assistant", "skills"])
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 200)
        saved = self.saved_config()
        self.assertEqual(saved["skills"]["external_dirs"], [version])
        self.assertIn("skills", fake_platform_tools(saved, "api_server"))
        # 다음 게시는 목록을 바꾸고, 빈 목록은 게시를 거둔다.
        version2 = self.make_skill_version("owner", "20260929T101500Z-2")
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.skills_body([version2])), 200)
        self.assertEqual(self.saved_config()["skills"]["external_dirs"], [version2])
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.skills_body([])), 200)
        self.assertEqual(self.saved_config()["skills"]["external_dirs"], [])

    def test_skill_publish_rejects_bad_paths_without_writing(self):
        """잘못된 경로의 skill 발행은 아무것도 쓰지 않고 거절한다."""
        good = self.make_skill_version("owner", "v1")
        self.make_skill_version("alice", "v1")
        linked = self.make_skill_version("owner", "vlink", link=str(self.root / "owner/config.yaml"))
        os.symlink("v1", self.skill_root / "owner/valias")
        root = str(self.skill_root)
        cases = [
            ("two entries", [good, good]),
            ("not a list", good),
            ("relative", ["owner/v1"]),
            ("dotdot", [root + "/owner/../owner/v1"]),
            ("dot", [root + "/owner/./v1"]),
            ("double slash", [root + "/owner//v1"]),
            ("tilde", ["~" + root + "/owner/v1"]),
            ("variable", ["${HOME}/owner/v1"]),
            ("other profile", [root + "/alice/v1"]),
            ("profile root", [root + "/owner"]),
            ("too deep", [good + "/note"]),
            ("bad version", [root + "/owner/-v1"]),
            ("missing dir", [root + "/owner/v9"]),
            ("symlinked version", [root + "/owner/valias"]),
            ("symlink inside", [linked]),
            ("not a string", [1]),
        ]
        body = self.skills_body([good], ["delegation", "fos-assistant", "skills"])
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 200)
        original = (self.root / "owner/config.yaml").read_bytes()
        for label, dirs in cases:
            with self.subTest(label=label):
                self.assertEqual(self.request("/api/config", "PUT", token="valid",
                                              body=self.skills_body(dirs)), 400)
                self.assertEqual((self.root / "owner/config.yaml").read_bytes(), original)

    def test_skill_publish_needs_skills_toolset(self):
        """skill 발행은 skills toolset 이 있어야 한다."""
        version = self.make_skill_version("owner", "v1")
        original = (self.root / "owner/config.yaml").read_bytes()
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.skills_body([version])), 400)
        self.assertEqual((self.root / "owner/config.yaml").read_bytes(), original)
        # 게시를 거두는 것은 skills 가 꺼져 있어도 된다.
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.skills_body([])), 200)

    def test_skill_publish_keeps_operator_dirs(self):
        """skill 발행이 운영자가 둔 디렉터리를 지우지 않는다."""
        version = self.make_skill_version("owner", "v1")
        path = self.root / "owner/config.yaml"
        config = yaml.safe_load(path.read_text(encoding="utf-8"))
        config["skills"] = {"external_dirs": ["/srv/operator/skills"]}
        path.write_text(yaml.safe_dump(config), encoding="utf-8")
        original = path.read_bytes()
        body = self.skills_body([version], ["delegation", "fos-assistant", "skills"])
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 409)
        self.assertEqual(path.read_bytes(), original)

    def test_skill_publish_refuses_other_skill_keys_and_missing_root(self):
        """다른 skill 키나 없는 root 를 가리키는 발행은 거절한다."""
        version = self.make_skill_version("owner", "v1")
        body = self.skills_body([version], ["delegation", "fos-assistant", "skills"])
        body["config"]["skills"]["create_dir"] = "/tmp"
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 400)
        del body["config"]["skills"]["create_dir"]
        os.environ.pop("FOS_ASSISTANT_SKILL_AGENT_ROOT")
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 500)
        # 스킬 루트가 없어도 도구 목록 쓰기는 그대로 된다.
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.toolset_body()), 200)

    def sandboxed_skills_body(self, version, toolsets=("delegation", "fos-assistant", "skills", "terminal")):
        """`scripts/` 가 든 스킬 게시처럼 지금 도구 목록과 `require_sandbox: true` 를 함께 보낸다."""
        body = self.skills_body([version], list(toolsets) if toolsets is not None else None)
        body["sandbox_owner"] = "user-1"
        body["require_sandbox"] = True
        return body

    def test_sandboxed_skill_publish_writes_skill_dirs_and_mounted_terminal(self):
        """셸 도구, 등록된 profile, `skill_root` 가 모두 있으면 스킬 경로와 스킬 마운트가 든 terminal 을 함께 쓴다."""
        self.set_sandbox_policy(self.sandbox_policy(skill_root=self.skill_host_root))
        version = self.make_skill_version("owner", "v1")

        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.sandboxed_skills_body(version)), 200)

        saved = self.saved_config()
        self.assertEqual(saved["skills"]["external_dirs"], [version])
        self.assertEqual(saved["terminal"], self.expected_terminal(
            "user-1", ["/srv/shared:/opt/shared", "/srv/owner-skills:/opt/owner-skills"], skill_mount=True))

    def test_sandboxed_skill_publish_is_unavailable_without_its_conditions(self):
        """`require_sandbox` 인 스킬 게시는 셸 도구, 등록된 profile, `skill_root` 가운데 하나라도 없으면 409 다."""
        version = self.make_skill_version("owner", "v1")
        with_skill_root = self.sandbox_policy(skill_root=self.skill_host_root)
        cases = [
            ("policy without skill_root", self.sandbox_policy(), self.sandboxed_skills_body(version)),
            ("no toolset list", with_skill_root, self.sandboxed_skills_body(version, toolsets=None)),
            ("no shell toolset", with_skill_root,
             self.sandboxed_skills_body(version, toolsets=("delegation", "fos-assistant", "skills"))),
            ("unlisted profile", self.sandbox_policy(skill_root=self.skill_host_root, profiles={"alice": {}}),
             self.sandboxed_skills_body(version)),
        ]
        path = self.root / "owner/config.yaml"
        original = path.read_bytes()
        for label, policy, body in cases:
            with self.subTest(label=label):
                self.set_sandbox_policy(policy)
                response = self.request("/api/config", "PUT", token="valid", body=body, full_response=True)
                self.assertEqual(response.status_code, 409)
                self.assertEqual(response.body["code"], "sandbox_unavailable")
                self.assertEqual(path.read_bytes(), original)

    def test_toolset_update_rejects_invalid_requests_without_writing(self):
        """잘못된 toolset 갱신 요청은 아무것도 쓰지 않고 거절한다."""
        cases = []
        different_key = self.toolset_body()
        different_key["config"]["model"] = {"default": "other"}
        cases.append(("different key", different_key, (), 400))
        cases.append(("empty config", {"profile": "owner", "config": {}}, (), 400))
        cases.append(("profile mismatch", self.toolset_body(), ("alice",), 400))
        cases.append(("duplicate query", self.toolset_body(), ("owner", "alice"), 400))
        memory_enabled = self.toolset_body()
        memory_enabled["config"]["platform_toolsets"]["api_server"].append("memory")
        cases.append(("memory enabled", memory_enabled, (), 400))
        no_memory_mcp = self.toolset_body()
        no_memory_mcp["config"]["platform_toolsets"]["api_server"].remove("fos-assistant")
        cases.append(("memory MCP missing", no_memory_mcp, (), 400))
        unknown = self.toolset_body()
        unknown["config"]["platform_toolsets"]["api_server"].append("unknown")
        cases.append(("unknown name", unknown, (), 400))
        default_profile = self.toolset_body()
        default_profile["profile"] = "default"
        cases.append(("default profile", default_profile, (), 400))
        missing_profile = self.toolset_body()
        missing_profile["profile"] = "missing"
        cases.append(("missing profile", missing_profile, (), 404))
        agent_key = self.toolset_body()
        agent_key["config"]["agent"] = {"disabled_toolsets": ["memory"]}
        cases.append(("agent key", agent_key, (), 400))
        wrong_type = self.toolset_body()
        wrong_type["config"]["platform_toolsets"]["api_server"] = "delegation"
        cases.append(("wrong list type", wrong_type, (), 400))
        extra_key = self.toolset_body()
        extra_key["sandbox"] = "user-1"
        cases.append(("extra body key", extra_key, (), 400))

        original = (self.root / "owner/config.yaml").read_bytes()
        for label, body, query_profiles, expected in cases:
            with self.subTest(label=label):
                self.assertEqual(self.request("/api/config", "PUT", token="valid",
                                              body=body, query_profiles=query_profiles), expected)
                self.assertEqual((self.root / "owner/config.yaml").read_bytes(), original)
