"""dashboard-profile-api 의 sandbox_terminal 분기를 검사한다."""

import hashlib
import json
import os
import pathlib
import yaml
from plugin_loading import patch_plugin
import dashboard_profile_api_support as support


class ProfileApiSandboxTerminalTest(support.ProfileApiRouteTest):
    def test_vision_connector_uses_the_trusted_owners_sandbox_terminal(self):
        """사진 도구를 여는 connector는 요청한 주인에게만 보이는 Docker 실행 공간을 쓴다."""
        root = self.connector_fixture()
        manifest = root / "connector.json"
        declared = json.loads(manifest.read_text(encoding="utf-8"))
        declared["toolsets"] = ["vision"]
        manifest.write_text(json.dumps(declared), encoding="utf-8")

        response = self.connector()

        self.assertEqual(response.status_code, 200)
        self.assertNotIn("sandbox_owner", response.body)
        self.assertEqual(self.alice_config()["terminal"], self.expected_terminal(
            "user-1", ["/srv/shared:/opt/shared", "/srv/alice-skills:/opt/alice-skills"], profile="alice"))
        attachment_key = hashlib.sha256(b"user-1").hexdigest()
        self.assertTrue(pathlib.Path(self.attachment_agent_root, "users", attachment_key).is_dir())

    def test_vision_connector_requires_a_trusted_sandbox_owner_and_policy(self):
        """사진 도구는 주인이나 실행 공간 정책이 없으면 설치하지 않는다."""
        root = self.connector_fixture()
        manifest = root / "connector.json"
        declared = json.loads(manifest.read_text(encoding="utf-8"))
        declared["toolsets"] = ["vision"]
        manifest.write_text(json.dumps(declared), encoding="utf-8")
        before = (self.root / "alice/config.yaml").read_bytes()

        self.assertEqual(self.connector(sandbox_owner=None).status_code, 400)
        self.assertEqual((self.root / "alice/config.yaml").read_bytes(), before)
        os.environ.pop("FOS_ASSISTANT_SANDBOX")
        unavailable = self.connector()
        self.assertEqual(unavailable.status_code, 409)
        self.assertEqual(unavailable.body["code"], "sandbox_unavailable")
        self.assertEqual((self.root / "alice/config.yaml").read_bytes(), before)

        self.set_sandbox_policy(self.sandbox_policy(profiles={"owner": {}}))
        unlisted = self.connector()
        self.assertEqual(unlisted.status_code, 409)
        self.assertEqual(unlisted.body["code"], "sandbox_unavailable")
        self.assertEqual((self.root / "alice/config.yaml").read_bytes(), before)

    def test_saved_terminal_fingerprint_matches_the_key_for_empty_and_filled_env(self):
        """저장된 terminal 에서 키를 뺀 나머지로 감사 규칙대로 다시 계산한 지문이 키의 지문과 같다."""
        self.make_profile("blog")
        self.register_memory("blog")
        self.set_sandbox_policy(self.sandbox_policy(profiles={
            "owner": {},
            "blog": {"env": {"CAREER_BACKEND_URL": "http://backend.test"}},
        }))
        for profile, has_env in (("owner", False), ("blog", True)):
            with self.subTest(profile=profile):
                self.save_sandbox_key(profile=profile)
                saved = yaml.safe_load((self.root / profile / "config.yaml").read_text(encoding="utf-8"))
                terminal = self.hermes_save_shape(saved["terminal"])
                self.assertEqual("docker_env" in terminal, has_env)
                key = terminal.pop("docker_shared_container_key")
                fingerprint = hashlib.sha256(json.dumps(terminal, sort_keys=True).encode("utf-8")).hexdigest()[:12]
                self.assertEqual(key, "%s-user-1-%s" % (profile, fingerprint))

    def test_sandbox_container_key_changes_with_owner_and_policy(self):
        """같은 입력이면 키가 같고, 주인이나 마운트나 이미지가 바뀌면 키가 바뀌며, 다른 profile 과 겹치지 않는다."""
        first = self.save_sandbox_key()
        self.assertEqual(self.save_sandbox_key(), first, "같은 입력인데 키가 달라졌다")
        self.assertNotEqual(self.save_sandbox_key(owner="user-2"), first, "주인이 바뀌었는데 키가 같다")
        self.set_sandbox_policy(self.sandbox_policy(read_only_mounts=["/srv/other:/opt/shared"]))
        self.assertNotEqual(self.save_sandbox_key(), first, "read_only_mounts 가 바뀌었는데 키가 같다")
        self.set_sandbox_policy(self.sandbox_policy(image="sandbox-image:next"))
        self.assertNotEqual(self.save_sandbox_key(), first, "image 가 바뀌었는데 키가 같다")
        self.set_sandbox_policy(self.sandbox_policy())
        self.assertEqual(self.save_sandbox_key(), first, "설정을 되돌렸는데 키가 처음과 다르다")
        self.make_profile("blog")
        self.register_memory("blog")
        self.assertNotEqual(self.save_sandbox_key(profile="blog"), first, "다른 profile 과 키가 겹친다")

    def test_shell_toolset_writes_the_sandbox_terminal(self):
        """셸 도구를 켜면 profile 의 terminal: 을 실행 공간 설정으로 통째로 바꾼다."""
        path = self.root / "owner/config.yaml"
        config = yaml.safe_load(path.read_text(encoding="utf-8"))
        # 운영자가 남긴 local 설정과 값 전달 칸이 남지 않아야 한다.
        config["terminal"] = {"backend": "local", "env_passthrough": ["OPENAI_API_KEY"], "timeout": 60}
        path.write_text(yaml.safe_dump(config), encoding="utf-8")
        body = self.toolset_body()
        body["config"]["platform_toolsets"]["api_server"] = ["delegation", "fos-assistant", "terminal"]
        body["sandbox_owner"] = "user-1"
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 200)
        saved = self.saved_config()
        self.assertEqual(saved["terminal"], self.expected_terminal(
            "user-1", ["/srv/shared:/opt/shared", "/srv/owner-skills:/opt/owner-skills"]))
        # 빈 목록 칸이 저장된 YAML 에 그대로 남아야 Hermes 기본값(값 전달)으로 돌아가지 않는다.
        raw = path.read_text(encoding="utf-8")
        for key in ("docker_forward_env: []", "env_passthrough: []", "credential_files: []"):
            self.assertIn(key, raw)
        self.assertTrue((self.sandbox_root / "user-1").is_dir())
        attachment_key = hashlib.sha256(b"user-1").hexdigest()
        self.assertTrue(pathlib.Path(self.attachment_agent_root, "users", attachment_key).is_dir())
        self.assertTrue((self.attachment_root / "users" / attachment_key).is_dir())

    def test_shell_toolset_writes_unattended_approval_and_keeps_other_approvals(self):
        """셸 도구를 docker 실행 공간으로 쓰면 승인 없는 실행을 켜고, 운영자가 정한 다른 승인 키는 그대로 둔다."""
        path = self.root / "owner/config.yaml"
        config = yaml.safe_load(path.read_text(encoding="utf-8"))
        config["approvals"] = {"mode": "manual", "deny": ["*curl*"]}
        path.write_text(yaml.safe_dump(config, sort_keys=False), encoding="utf-8")

        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.file_body()), 200)

        self.assertEqual(self.saved_config()["approvals"],
                         {"mode": "manual", "deny": ["*curl*"], "unattended_mode": "approve"})

    def test_resaving_an_unchanged_sandbox_terminal_writes_the_missing_approval(self):
        """terminal 이 그대로여도 승인 값이 빠졌으면 같은 저장이 다시 넣는다."""
        self.save_sandbox_key()
        path = self.root / "owner/config.yaml"
        config = self.saved_config()
        terminal = config["terminal"]
        config.pop("approvals")
        path.write_text(yaml.safe_dump(config, sort_keys=False), encoding="utf-8")

        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.file_body()), 200)

        saved = self.saved_config()
        self.assertEqual(saved["terminal"], terminal, "다시 저장했는데 terminal 이 바뀌었다")
        self.assertEqual(saved["approvals"], {"unattended_mode": "approve"})

    def test_vision_connector_install_writes_unattended_approval(self):
        """사진 도구 커넥터 설치가 docker 실행 공간을 쓸 때 승인 없는 실행도 함께 켠다."""
        root = self.connector_fixture()
        manifest = root / "connector.json"
        declared = json.loads(manifest.read_text(encoding="utf-8"))
        declared["toolsets"] = ["vision"]
        manifest.write_text(json.dumps(declared), encoding="utf-8")

        self.assertEqual(self.connector().status_code, 200)

        self.assertEqual(self.alice_config()["approvals"], {"unattended_mode": "approve"})

    def test_non_object_approvals_rejects_the_shell_save(self):
        """approvals 가 객체가 아니면 셸 저장을 500 으로 멈추고 설정 파일을 그대로 둔다."""
        path = self.root / "owner/config.yaml"
        config = yaml.safe_load(path.read_text(encoding="utf-8"))
        config["approvals"] = "approve"
        path.write_text(yaml.safe_dump(config), encoding="utf-8")
        original = path.read_bytes()

        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.file_body()), 500)
        self.assertEqual(path.read_bytes(), original)

    def test_connector_output_root_mounts_the_profile_directory_read_only_at_the_same_path(self):
        """정책에 출력 루트가 있으면 그 profile 의 출력 디렉터리를 만들고 같은 경로에 읽기 전용으로 붙인다."""
        self.set_sandbox_policy(self.sandbox_policy(connector_output_root=self.connector_output_root))
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.file_body()), 200)

        self.assertEqual(self.saved_config()["terminal"], self.expected_terminal(
            "user-1", ["/srv/shared:/opt/shared", "/srv/owner-skills:/opt/owner-skills"], connector_output=True))
        directory = pathlib.Path(self.connector_output_directory("user-1", "owner"))
        self.assertTrue(directory.is_dir())
        self.assertEqual(directory.stat().st_mode & 0o777, 0o700)
        # 같은 사용자의 다른 profile 디렉터리는 붙지 않는다.
        volumes = self.saved_config()["terminal"]["docker_volumes"]
        self.assertFalse(any(self.connector_output_directory("user-1", "blog") in volume for volume in volumes))

    def test_connector_output_root_change_changes_the_container_key(self):
        """출력 루트를 넣으면 셸 설정이 바뀌므로 키도 바뀐다. 빼면 처음 키로 돌아간다."""
        first = self.save_sandbox_key()
        self.set_sandbox_policy(self.sandbox_policy(connector_output_root=self.connector_output_root))
        self.assertNotEqual(self.save_sandbox_key(), first)
        self.set_sandbox_policy(self.sandbox_policy())
        self.assertEqual(self.save_sandbox_key(), first)

    def test_connector_output_directory_through_a_link_is_unavailable(self):
        """출력 디렉터리 경로에 링크가 섞이면 409 로 거절하고 설정을 그대로 둔다."""
        elsewhere = pathlib.Path(self.connector_output_root).parent / "elsewhere"
        elsewhere.mkdir()
        users = pathlib.Path(self.connector_output_root, "users")
        users.mkdir(parents=True)
        (users / hashlib.sha256(b"user-1").hexdigest()).symlink_to(elsewhere)
        self.set_sandbox_policy(self.sandbox_policy(connector_output_root=self.connector_output_root))
        path = self.root / "owner/config.yaml"
        original = path.read_bytes()

        response = self.request("/api/config", "PUT", token="valid", body=self.file_body(), full_response=True)

        self.assertEqual(response.status_code, 409)
        self.assertEqual(response.body["code"], "sandbox_unavailable")
        self.assertEqual(path.read_bytes(), original)

    def test_vision_connector_is_not_installed_without_the_owners_attachment_directory(self):
        """사진 커넥터 설치도 Control Plane 이 만든 디렉터리가 없으면 거절하고 만들지 않는다."""
        root = self.connector_fixture()
        manifest = root / "connector.json"
        declared = json.loads(manifest.read_text(encoding="utf-8"))
        declared["toolsets"] = ["vision"]
        manifest.write_text(json.dumps(declared), encoding="utf-8")
        path = self.root / "alice/config.yaml"
        original = path.read_bytes()

        self.assertEqual(self.connector(sandbox_owner="user-new").status_code, 409)

        self.assertEqual(path.read_bytes(), original)
        key = hashlib.sha256(b"user-new").hexdigest()
        self.assertFalse((self.attachment_root / "users" / key).exists())
        self.assertFalse(pathlib.Path(self.attachment_agent_root, "users", key).exists())

    def test_vision_connector_attachment_change_before_write_preserves_config(self):
        root = self.connector_fixture()
        manifest = root / "connector.json"
        declared = json.loads(manifest.read_text(encoding="utf-8"))
        declared["toolsets"] = ["vision"]
        manifest.write_text(json.dumps(declared), encoding="utf-8")
        path = self.root / "alice/config.yaml"
        original = path.read_bytes()
        install = self.plugin._connector_config

        def replace_source(*args, **kwargs):
            directory = self.attachment_root / "users" / hashlib.sha256(b"user-1").hexdigest()
            directory.rename(directory.with_name("old-user"))
            directory.symlink_to(directory.with_name("old-user"), target_is_directory=True)
            return install(*args, **kwargs)

        with patch_plugin(self.plugin, "_connector_config", side_effect=replace_source):
            self.assertEqual(self.connector().status_code, 409)
        self.assertEqual(path.read_bytes(), original)
        self.assertFalse((path.parent / self.plugin.CONNECTOR_STATE).exists())

    def test_sandbox_terminal_mounts_profile_entries_only_on_that_profile(self):
        """profile 별 마운트와 label 은 그 실행 공간에만 붙고, network 가 없으면 망 인자가 없다."""
        self.make_profile("blog")
        self.register_memory("blog")
        self.set_sandbox_policy(self.sandbox_policy(network=None, cpu=None, memory_mb=None))
        body = self.file_body(owner="user-2")
        body["profile"] = "blog"
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 200)
        saved = yaml.safe_load((self.root / "blog/config.yaml").read_text(encoding="utf-8"))
        self.assertEqual(saved["terminal"], self.expected_terminal(
            "user-2", ["/srv/shared:/opt/shared"], extra_args=(), cpu=1, memory=1024, profile="blog"))

    def test_sandbox_terminal_is_restored_when_handler_fails(self):
        """agent 가 그대로여도 terminal: 을 먼저 썼으면 처리기가 실패할 때 원래 바이트로 되돌린다."""
        path = self.root / "owner/config.yaml"
        original = path.read_bytes()
        self.handler_status = 500
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.file_body()), 500)
        self.assertEqual(path.read_bytes(), original)
