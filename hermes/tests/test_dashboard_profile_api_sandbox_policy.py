"""dashboard-profile-api 의 sandbox_policy 분기를 검사한다."""

import os
import pathlib
import yaml
import dashboard_profile_api_support as support


class ProfileApiSandboxPolicyTest(support.ProfileApiRouteTest):
    def test_shell_toolset_without_sandbox_policy_is_unavailable(self):
        """실행 공간 설정이 없으면 셸·파일 도구를 켜는 저장을 409 sandbox_unavailable 로 거절한다."""
        os.environ.pop("FOS_ASSISTANT_SANDBOX")
        path = self.root / "owner/config.yaml"
        original = path.read_bytes()
        response = self.request("/api/config", "PUT", token="valid", body=self.file_body(), full_response=True)
        self.assertEqual(response.status_code, 409)
        self.assertEqual(response.body["code"], "sandbox_unavailable")
        self.assertEqual(path.read_bytes(), original)

    def test_invalid_sandbox_policy_is_unavailable(self):
        """실행 공간 설정이 하나라도 틀리면 설정이 없는 것과 같이 409 로 거절한다."""
        root = str(self.sandbox_root)
        cases = [
            ("not json", "{"),
            ("not object", []),
            ("missing image", self.sandbox_policy(image=None)),
            ("missing attachment root", self.sandbox_policy(attachment_root=None)),
            ("missing attachment agent root", self.sandbox_policy(attachment_agent_root=None)),
            ("image with space", self.sandbox_policy(image="bad image")),
            ("relative root", self.sandbox_policy(workspace_root="sandbox")),
            ("dotdot root", self.sandbox_policy(workspace_root=root + "/../sandbox")),
            ("empty piece root", self.sandbox_policy(workspace_root=root + "//x")),
            ("colon root", self.sandbox_policy(workspace_root=root + ":x")),
            ("relative attachment root", self.sandbox_policy(attachment_root="attachments")),
            ("dotdot attachment root", self.sandbox_policy(attachment_root=root + "/../attachments")),
            ("empty piece attachment agent root", self.sandbox_policy(attachment_agent_root="/agent//attachments")),
            ("attachment root is workspace root", self.sandbox_policy(attachment_root=root)),
            ("attachment root under workspace root", self.sandbox_policy(attachment_root=root + "/attachments")),
            ("attachment agent root is workspace", self.sandbox_policy(attachment_agent_root="/workspace/attachments")),
            ("attachment agent root is root", self.sandbox_policy(attachment_agent_root="/root/attachments")),
            ("bad network", self.sandbox_policy(network="-net")),
            ("zero cpu", self.sandbox_policy(cpu=0)),
            ("too much cpu", self.sandbox_policy(cpu=8.5)),
            ("small memory", self.sandbox_policy(memory_mb=255)),
            ("large memory", self.sandbox_policy(memory_mb=16385)),
            ("float memory", self.sandbox_policy(memory_mb=1024.5)),
            ("relative mount source", self.sandbox_policy(read_only_mounts=["srv:/opt/x"])),
            ("dotdot mount", self.sandbox_policy(read_only_mounts=["/srv/../etc:/opt/x"])),
            ("three pieces", self.sandbox_policy(read_only_mounts=["/srv:/opt/x:rw"])),
            ("workspace mount", self.sandbox_policy(read_only_mounts=["/srv:/workspace"])),
            ("under workspace", self.sandbox_policy(read_only_mounts=["/srv:/workspace/x"])),
            ("under root", self.sandbox_policy(profiles={"owner": {"read_only_mounts": ["/srv:/root/.hermes"]}})),
            ("mounts not list", self.sandbox_policy(read_only_mounts="/srv:/opt/x")),
            ("mount source is workspace root", self.sandbox_policy(read_only_mounts=[root + ":/opt/x"])),
            ("mount source under workspace root",
             self.sandbox_policy(read_only_mounts=[root + "/user-2:/opt/x"])),
            ("mount source above workspace root",
             self.sandbox_policy(profiles={"owner": {"read_only_mounts": [str(self.sandbox_root.parent) + ":/opt/x"]}})),
            ("mount source is attachment root",
             self.sandbox_policy(read_only_mounts=[str(self.attachment_root) + ":/opt/x"])),
            ("mount source under attachment root",
             self.sandbox_policy(profiles={"owner": {"read_only_mounts": [str(self.attachment_root / "users") + ":/opt/x"]}})),
            ("mount source above attachment root",
             self.sandbox_policy(read_only_mounts=[str(self.attachment_root.parent) + ":/opt/x"])),
            ("mount target is attachment agent root",
             self.sandbox_policy(read_only_mounts=["/srv/x:%s" % self.attachment_agent_root])),
            ("mount target under attachment agent root",
             self.sandbox_policy(profiles={"owner": {"read_only_mounts": ["/srv/x:%s/users" % self.attachment_agent_root]}})),
            ("mount target above attachment agent root",
             self.sandbox_policy(read_only_mounts=["/srv/x:%s" % pathlib.Path(self.attachment_agent_root).parent])),
            ("unknown top-level key", dict(self.sandbox_policy(), docker_extra_args=["--privileged"])),
        ]
        path = self.root / "owner/config.yaml"
        original = path.read_bytes()
        for label, policy in cases:
            with self.subTest(label=label):
                self.set_sandbox_policy(policy)
                response = self.request("/api/config", "PUT", token="valid", body=self.file_body(), full_response=True)
                self.assertEqual(response.status_code, 409)
                self.assertEqual(response.body["code"], "sandbox_unavailable")
                self.assertEqual(path.read_bytes(), original)

    def test_sandbox_policy_accepts_paths_that_only_share_a_prefix(self):
        """경로 조각이 다른 prefix는 workspace, 첨부 원본, 첨부 실행 경로와 겹치지 않는다."""
        self.set_sandbox_policy(self.sandbox_policy(read_only_mounts=[
            "/srv/a:/rootfs", "/srv/b:/workspaces/x", str(self.sandbox_root) + "-other:/opt/other",
            str(self.attachment_root) + "-other:/agent/attachments-other",
        ]))
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.file_body()), 200)
        self.assertEqual(self.saved_config()["terminal"]["docker_volumes"][2:6], [
            "/srv/a:/rootfs:ro",
            "/srv/b:/workspaces/x:ro",
            str(self.sandbox_root) + "-other:/opt/other:ro",
            str(self.attachment_root) + "-other:/agent/attachments-other:ro",
        ])

    def test_shell_toolset_needs_a_valid_sandbox_owner(self):
        """셸 도구를 켜는데 sandbox_owner 가 없거나 형식이 틀리면 400 이다."""
        path = self.root / "owner/config.yaml"
        original = path.read_bytes()
        for label, owner in [("missing", None), ("upper", "User"), ("digit first", "1user"), ("slash", "a/b"),
                             ("dotdot", ".."), ("too long", "a" * 65), ("number", 1)]:
            with self.subTest(label=label):
                self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.file_body(owner)), 400)
                self.assertEqual(path.read_bytes(), original)
        # 형식이 틀린 sandbox_owner 는 셸 도구가 없어도 거절한다.
        body = self.toolset_body()
        body["sandbox_owner"] = "User"
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 400)
        self.assertEqual(path.read_bytes(), original)
        # 64자까지는 받는다.
        self.prepare_attachment_directory("a" * 64)
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.file_body("a" * 64)), 200)

    def test_unlisted_profile_keeps_local_terminal_and_needs_no_sandbox_owner(self):
        """유효한 정책에 없는 profile 은 셸 저장을 허용하고 기존 local 설정을 그대로 둔다."""
        path = self.root / "owner/config.yaml"
        config = self.saved_config()
        config["terminal"] = {"backend": "local", "cwd": "/tmp", "timeout": 90}
        path.write_text(yaml.safe_dump(config), encoding="utf-8")
        self.set_sandbox_policy(self.sandbox_policy(profiles={"alice": {}}))

        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.file_body(owner=None)), 200)
        self.assertEqual(self.saved_config()["terminal"], config["terminal"])
        self.assertFalse((self.sandbox_root / "user-1").exists())

    def test_unlisted_profile_cannot_enable_media_file_tools_locally(self):
        """사진과 영상 파일 도구는 정책 profile 밖에서 host 실행으로 돌아가지 않고 409로 거절한다."""
        self.set_sandbox_policy(self.sandbox_policy(profiles={"alice": {}}))
        for toolset in ("vision", "image_gen", "video_gen"):
            with self.subTest(toolset=toolset):
                body = self.file_body()
                body["config"]["platform_toolsets"]["api_server"].append(toolset)
                response = self.request("/api/config", "PUT", token="valid", body=body, full_response=True)

                self.assertEqual(response.status_code, 409)
                self.assertEqual(response.body["code"], "sandbox_unavailable")

    def test_video_gen_alone_uses_the_sandbox_terminal(self):
        """다른 실행 도구 없이 video_gen만 켜도 사용자별 Docker 실행 공간을 쓴다."""
        body = self.toolset_body()
        body["config"]["platform_toolsets"]["api_server"] = ["delegation", "fos-assistant", "video_gen"]
        body["sandbox_owner"] = "user-1"

        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 200)
        self.assertEqual(self.saved_config()["terminal"], self.expected_terminal(
            "user-1", ["/srv/shared:/opt/shared", "/srv/owner-skills:/opt/owner-skills"]))

    def test_empty_profiles_policy_keeps_all_profiles_local(self):
        """profiles 가 비었으면 정책 없음과 구분해 모든 profile 의 기존 실행을 유지한다."""
        self.set_sandbox_policy(self.sandbox_policy(profiles={}))
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.file_body(owner=None)), 200)
        self.assertEqual(self.saved_config()["terminal"], {"backend": "local"})

    def test_profile_removed_from_policy_returns_to_local_on_shell_save(self):
        """정책에서 profile 을 빼면 다음 셸 저장에서 이전 docker 설정을 제거한다."""
        self.save_sandbox_key()
        self.set_sandbox_policy(self.sandbox_policy(profiles={}))

        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.file_body(owner=None)), 200)
        self.assertEqual(self.saved_config()["terminal"], {"backend": "local"})

    def test_profile_policy_does_not_change_another_profile_or_default_config(self):
        """도구를 저장한 profile 만 바뀌고 기본 profile 과 다른 profile 의 설정은 그대로다."""
        self.make_profile("alice")
        self.register_memory("alice")
        default = self.root / "config.yaml"
        default.write_text("terminal:\n  backend: local\n", encoding="utf-8")
        alice_path = self.root / "alice/config.yaml"
        alice_before = alice_path.read_bytes()
        default_before = default.read_bytes()
        self.set_sandbox_policy(self.sandbox_policy(profiles={"owner": {}}))

        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.file_body()), 200)
        self.assertEqual(self.saved_config()["terminal"]["backend"], "docker")
        self.assertEqual(default.read_bytes(), default_before)
        self.assertEqual(alice_path.read_bytes(), alice_before)

    def test_profile_env_and_network_are_only_taken_from_policy(self):
        """지정한 profile 에만 정책의 경로와 Backend 주소, 망을 넣고 요청 본문으로는 바꾸지 못한다."""
        env = {
            "CAREER_BACKEND_URL": "http://backend.test",
            "CAREER_BACKEND_TOKEN_FILE": "/run/secrets/backend-token",
            "CLAUDE_PLUGIN_ROOT": "/opt/plugin",
            "CAREER_EVIDENCE_DIR": "/opt/evidence",
            "CAREER_WORKSPACE_ROOT": "/workspace/career",
            "CAREER_DART_API_KEY_FILE": "/run/secrets/dart-key",
        }
        self.set_sandbox_policy(self.sandbox_policy(profiles={
            "owner": {"env": env, "network": "backend-test-net"},
            "alice": {},
        }))
        self.save_sandbox_key()
        terminal = self.saved_config()["terminal"]
        self.assertEqual(terminal["docker_env"], env)
        self.assertEqual(terminal["docker_extra_args"], ["--network=backend-test-net", "--label=fos-sandbox-profile=owner"])
        self.assertEqual(terminal["docker_forward_env"], [])
        self.assertEqual(terminal["credential_files"], [])
        self.make_profile("alice")
        self.register_memory("alice")
        self.save_sandbox_key(profile="alice")
        alice = yaml.safe_load((self.root / "alice/config.yaml").read_text(encoding="utf-8"))["terminal"]
        self.assertNotIn("docker_env", alice)
        self.assertEqual(alice["docker_extra_args"], ["--network=sandbox-net", "--label=fos-sandbox-profile=alice"])

        original = (self.root / "owner/config.yaml").read_bytes()
        for key, value in (("env", env), ("network", "other-net"), ("read_only_mounts", []),
                           ("profiles", {"owner": {}}), ("docker_extra_args", ["--label=fos-sandbox-profile=alice"])):
            with self.subTest(key=key):
                body = self.file_body()
                body["config"][key] = value
                self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 400)
                self.assertEqual((self.root / "owner/config.yaml").read_bytes(), original)

    def test_profile_env_and_network_changes_replace_the_container_key(self):
        """profile 의 경로와 망을 바꾸면 옛 컨테이너를 재사용하지 않는다."""
        first = self.save_sandbox_key()
        self.set_sandbox_policy(self.sandbox_policy(profiles={"owner": {"env": {"CLAUDE_PLUGIN_ROOT": "/opt/new"}}}))
        env_key = self.save_sandbox_key()
        self.assertNotEqual(env_key, first)
        self.set_sandbox_policy(self.sandbox_policy(profiles={
            "owner": {"env": {"CLAUDE_PLUGIN_ROOT": "/opt/new"}, "network": "new-net"},
        }))
        self.assertNotEqual(self.save_sandbox_key(), env_key)

    def test_invalid_profile_policy_is_unavailable_even_for_unlisted_profiles(self):
        """정책 전체가 잘못됐으면 목록에 없는 profile 도 셸 저장을 거절한다."""
        cases = [
            ("missing profiles", self.sandbox_policy(profiles=None)),
            ("not object", self.sandbox_policy(profiles=[])),
            ("default profile", self.sandbox_policy(profiles={"default": {}})),
            ("bad name", self.sandbox_policy(profiles={"../owner": {}})),
            ("bad settings", self.sandbox_policy(profiles={"alice": []})),
            ("unknown key", self.sandbox_policy(profiles={"alice": {"docker_extra_args": []}})),
            ("bad network", self.sandbox_policy(profiles={"alice": {"network": "--host"}})),
            ("bad env", self.sandbox_policy(profiles={"alice": {"env": []}})),
            ("secret env", self.sandbox_policy(profiles={"alice": {"env": {"CAREER_BACKEND_TOKEN": "secret"}}})),
            ("relative path", self.sandbox_policy(profiles={"alice": {"env": {"CLAUDE_PLUGIN_ROOT": "plugin"}}})),
            ("dot path", self.sandbox_policy(profiles={"alice": {"env": {"CLAUDE_PLUGIN_ROOT": "/opt/./plugin"}}})),
            ("newline path", self.sandbox_policy(profiles={"alice": {"env": {"CLAUDE_PLUGIN_ROOT": "/opt/plugin\n"}}})),
            ("authenticated url", self.sandbox_policy(profiles={"alice": {"env": {"CAREER_BACKEND_URL": "http://user:pass@backend.test"}}})),
            ("url query", self.sandbox_policy(profiles={"alice": {"env": {"CAREER_BACKEND_URL": "http://backend.test?token=value"}}})),
            ("bad url port", self.sandbox_policy(profiles={"alice": {"env": {"CAREER_BACKEND_URL": "http://backend.test:bad"}}})),
            ("url scheme", self.sandbox_policy(profiles={"alice": {"env": {"CAREER_BACKEND_URL": "file:///opt/backend"}}})),
        ]
        path = self.root / "owner/config.yaml"
        original = path.read_bytes()
        for label, policy in cases:
            with self.subTest(label=label):
                self.set_sandbox_policy(policy)
                self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.file_body()), 409)
                self.assertEqual(path.read_bytes(), original)

    def test_toolset_update_without_shell_keeps_terminal_without_policy(self):
        """셸 도구가 없는 저장은 실행 공간 설정이 없어도 되고 terminal: 을 건드리지 않는다."""
        os.environ.pop("FOS_ASSISTANT_SANDBOX")
        path = self.root / "owner/config.yaml"
        config = yaml.safe_load(path.read_text(encoding="utf-8"))
        config["terminal"] = {"backend": "docker", "docker_image": "kept"}
        path.write_text(yaml.safe_dump(config), encoding="utf-8")
        body = self.toolset_body()
        body["sandbox_owner"] = "user-1"
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 200)
        self.assertEqual(self.saved_config()["terminal"], {"backend": "docker", "docker_image": "kept"})
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.toolset_body()), 200)
        self.assertEqual(self.saved_config()["terminal"], {"backend": "docker", "docker_image": "kept"})
