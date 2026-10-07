"""dashboard-profile-api 의 sandbox_attachments 분기를 검사한다."""

import hashlib
import pathlib
import shutil
import subprocess
import tempfile
from plugin_loading import patch_plugin
import dashboard_profile_api_support as support


class ProfileApiSandboxAttachmentsTest(support.ProfileApiRouteTest):
    def test_sandbox_terminal_mounts_only_the_execution_owners_attachments(self):
        """사용자 A와 B의 실행 공간에는 각각의 해시 디렉터리만 읽기 전용으로 붙는다."""
        self.save_sandbox_key(owner="user-a")
        first = self.saved_config()["terminal"]["docker_volumes"]
        self.save_sandbox_key(owner="user-b")
        second = self.saved_config()["terminal"]["docker_volumes"]

        first_key = hashlib.sha256(b"user-a").hexdigest()
        second_key = hashlib.sha256(b"user-b").hexdigest()
        first_mount = "%s/users/%s:%s/users/%s:ro" % (
            self.attachment_root, first_key, self.attachment_agent_root, first_key)
        second_mount = "%s/users/%s:%s/users/%s:ro" % (
            self.attachment_root, second_key, self.attachment_agent_root, second_key)

        self.assertIn(first_mount, first)
        self.assertNotIn(second_key, "\n".join(first))
        self.assertIn(second_mount, second)
        self.assertNotIn(first_key, "\n".join(second))
        self.assertNotIn(str(self.attachment_root) + ":", "\n".join(first + second))
        first_attachment_mounts = [
            volume for volume in first if volume.startswith(str(self.attachment_root) + "/")
        ]
        second_attachment_mounts = [
            volume for volume in second if volume.startswith(str(self.attachment_root) + "/")
        ]
        self.assertEqual(first_attachment_mounts, [first_mount])
        self.assertEqual(second_attachment_mounts, [second_mount])
        self.assertTrue(pathlib.Path(self.attachment_agent_root, "users", first_key).is_dir())
        self.assertTrue(pathlib.Path(self.attachment_agent_root, "users", second_key).is_dir())

    def test_sandbox_attachment_key_matches_the_control_plane_golden_vector(self):
        """Control Plane과 Hermes가 같은 UTF-8 SHA-256 사용자 디렉터리 키를 만든다."""
        self.assertEqual(
            self.plugin._sandbox_attachment_key("u11"),
            "92ec86fa88925dabc1026bfc9335f5f6848886c98586c005f4a0c02716e3bbab",
        )

    def test_sandbox_attachment_directory_is_not_created_by_the_plugin(self):
        """첨부 디렉터리가 없는 주인은 거절하고, plugin 은 어느 루트에도 디렉터리를 만들지 않는다."""
        original = (self.root / "owner/config.yaml").read_bytes()

        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.file_body("user-new")), 409)

        self.assertEqual((self.root / "owner/config.yaml").read_bytes(), original)
        key = hashlib.sha256(b"user-new").hexdigest()
        self.assertFalse((self.attachment_root / "users" / key).exists())
        self.assertFalse(pathlib.Path(self.attachment_agent_root, "users", key).exists())

    def test_sandbox_terminal_is_saved_with_read_only_attachment_roots(self):
        """첨부 루트를 쓸 수 없어도 Control Plane 이 만든 디렉터리가 있으면 저장한다."""
        roots = [self.attachment_root, pathlib.Path(self.attachment_agent_root)]
        for root in roots:
            for directory in (root, root / "users"):
                directory.chmod(0o555)
        try:
            self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.file_body()), 200)
        finally:
            for root in roots:
                for directory in (root / "users", root):
                    directory.chmod(0o755)
        self.assertEqual(self.saved_config()["terminal"], self.expected_terminal(
            "user-1", ["/srv/shared:/opt/shared", "/srv/owner-skills:/opt/owner-skills"]))

    def test_sandbox_attachment_directory_does_not_follow_a_users_symlink(self):
        """agent root의 users가 링크이면 거절하고 대상 밖에 사용자 디렉터리를 만들지 않는다."""
        agent_root = pathlib.Path(self.attachment_agent_root)
        shutil.rmtree(agent_root / "users")
        outside = pathlib.Path(self.tmp.name) / "outside"
        outside.mkdir()
        (agent_root / "users").symlink_to(outside, target_is_directory=True)

        original = (self.root / "owner/config.yaml").read_bytes()
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.file_body("user-a")), 409)
        self.assertEqual((self.root / "owner/config.yaml").read_bytes(), original)

        key = hashlib.sha256(b"user-a").hexdigest()
        self.assertFalse((outside / key).exists())

    def test_sandbox_attachment_source_user_symlink_preserves_config(self):
        """같은 첨부 루트의 다른 사용자로 연결한 링크도 설정 저장 전에 거절한다."""
        self.set_sandbox_policy(self.sandbox_policy(attachment_agent_root=str(self.attachment_root)))
        users = self.attachment_root / "users"
        other = users / hashlib.sha256(b"user-b").hexdigest()
        (users / hashlib.sha256(b"user-a").hexdigest()).rmdir()
        (users / hashlib.sha256(b"user-a").hexdigest()).symlink_to(other, target_is_directory=True)
        original = (self.root / "owner/config.yaml").read_bytes()

        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.file_body("user-a")), 409)

        self.assertEqual((self.root / "owner/config.yaml").read_bytes(), original)

    def test_sandbox_attachment_source_intermediate_symlink_preserves_config(self):
        outside = self.attachment_root.parent / "other-users"
        (self.attachment_root / "users").rename(outside)
        (self.attachment_root / "users").symlink_to(outside, target_is_directory=True)
        original = (self.root / "owner/config.yaml").read_bytes()

        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.file_body()), 409)

        self.assertEqual((self.root / "owner/config.yaml").read_bytes(), original)

    def test_sandbox_attachment_root_parent_symlink_preserves_config(self):
        alias = self.attachment_root.parent / "alias"
        alias.symlink_to(self.attachment_root.parent, target_is_directory=True)
        self.set_sandbox_policy(self.sandbox_policy(attachment_root=str(alias / self.attachment_root.name)))
        original = (self.root / "owner/config.yaml").read_bytes()

        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.file_body()), 409)

        self.assertEqual((self.root / "owner/config.yaml").read_bytes(), original)

    def test_sandbox_attachment_preparation_failure_preserves_config(self):
        original = (self.root / "owner/config.yaml").read_bytes()
        with patch_plugin(self.plugin, "_sandbox_verify_attachment_directories", side_effect=OSError("denied")):
            self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.file_body()), 409)
        self.assertEqual((self.root / "owner/config.yaml").read_bytes(), original)

    def test_sandbox_attachment_target_user_symlink_preserves_config(self):
        agent_root = pathlib.Path(self.attachment_agent_root)
        users = agent_root / "users"
        other = users / hashlib.sha256(b"user-b").hexdigest()
        (users / hashlib.sha256(b"user-a").hexdigest()).rmdir()
        (users / hashlib.sha256(b"user-a").hexdigest()).symlink_to(other, target_is_directory=True)
        original = (self.root / "owner/config.yaml").read_bytes()

        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.file_body("user-a")), 409)

        self.assertEqual((self.root / "owner/config.yaml").read_bytes(), original)

    def test_sandbox_attachment_unavailable_source_preserves_config(self):
        self.set_sandbox_policy(self.sandbox_policy(attachment_root=str(self.attachment_root / "not-visible")))
        original = (self.root / "owner/config.yaml").read_bytes()

        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.file_body()), 409)

        self.assertEqual((self.root / "owner/config.yaml").read_bytes(), original)

    def test_sandbox_attachment_change_after_mount_generation_preserves_config(self):
        """mount 문자열을 만든 뒤 경로가 바뀌어도 실제 설정 쓰기 전에 거절한다."""
        original = (self.root / "owner/config.yaml").read_bytes()
        build_terminal = self.plugin._sandbox_terminal

        def replace_source(*args, **kwargs):
            terminal = build_terminal(*args, **kwargs)
            directory = self.attachment_root / "users" / hashlib.sha256(b"user-1").hexdigest()
            directory.rename(directory.with_name("old-user"))
            directory.symlink_to(directory.with_name("old-user"), target_is_directory=True)
            return terminal

        with patch_plugin(self.plugin, "_sandbox_terminal", side_effect=replace_source):
            self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.file_body()), 409)
        self.assertEqual((self.root / "owner/config.yaml").read_bytes(), original)

    def test_sandbox_attachment_change_before_config_write_preserves_config(self):
        """같은 이름의 일반 디렉터리로 바꾼 경우도 inode를 대조해 거절한다."""
        original = (self.root / "owner/config.yaml").read_bytes()
        write_config = self.plugin._write_checked_config

        def replace_target(*args, **kwargs):
            directory = pathlib.Path(self.attachment_agent_root, "users", hashlib.sha256(b"user-1").hexdigest())
            directory.rename(directory.with_name("old-user"))
            directory.mkdir()
            return write_config(*args, **kwargs)

        with patch_plugin(self.plugin, "_write_checked_config", side_effect=replace_target):
            self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.file_body()), 409)
        self.assertEqual((self.root / "owner/config.yaml").read_bytes(), original)

    def test_sandbox_attachment_change_after_preparation_preserves_config(self):
        original = (self.root / "owner/config.yaml").read_bytes()
        prepare = self.plugin._sandbox_verify_attachment_directories

        def replace_source(*args, **kwargs):
            snapshot = prepare(*args, **kwargs)
            directory = self.attachment_root / "users" / hashlib.sha256(b"user-1").hexdigest()
            directory.rename(directory.with_name("old-user"))
            directory.symlink_to(directory.with_name("old-user"), target_is_directory=True)
            return snapshot

        with patch_plugin(self.plugin, "_sandbox_verify_attachment_directories", side_effect=replace_source):
            self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.file_body()), 409)
        self.assertEqual((self.root / "owner/config.yaml").read_bytes(), original)

    def test_sandbox_attachment_mounts_block_other_users_in_docker(self):
        """Docker가 있으면 A와 B의 bind mount가 상대 파일과 상위 경로 탐색을 모두 막는지 확인한다."""
        if shutil.which("docker") is None:
            self.skipTest("docker 명령이 없다")
        available = subprocess.run(
            ["docker", "image", "inspect", "alpine:latest"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        if available.returncode != 0:
            self.skipTest("검사에 쓸 Docker 이미지가 없다")

        # Docker Desktop은 macOS의 시스템 임시 디렉터리를 파일 공유 대상으로 두지 않을 수 있다.
        # `/tmp`는 Docker가 보는 `/private/tmp`와 이어진다. context manager가 끝나면 시험 원본을 지운다.
        with tempfile.TemporaryDirectory(dir="/tmp") as shared_root:
            attachment_root = pathlib.Path(shared_root).resolve() / "attachments"
            attachment_root.mkdir()
            self.set_sandbox_policy(self.sandbox_policy(
                attachment_root=str(attachment_root), attachment_agent_root=self.attachment_agent_root))
            # Control Plane 이 만드는 사용자 디렉터리다. plugin 은 만들지 않는다.
            for owner in (b"user-a", b"user-b"):
                (attachment_root / "users" / hashlib.sha256(owner).hexdigest()).mkdir(parents=True)
            self.save_sandbox_key(owner="user-a")
            first_mount = self.saved_config()["terminal"]["docker_volumes"][1]
            self.save_sandbox_key(owner="user-b")
            second_mount = self.saved_config()["terminal"]["docker_volumes"][1]
            first_key = hashlib.sha256(b"user-a").hexdigest()
            second_key = hashlib.sha256(b"user-b").hexdigest()
            seeded = subprocess.run(
                ["docker", "run", "--rm", "--volume", "%s:/seed" % attachment_root,
                 "alpine:latest", "sh", "-ec",
                 "mkdir -p /seed/users/%s /seed/users/%s /seed/123; "
                 "printf A-only > /seed/users/%s/a.txt; "
                 "printf B-only > /seed/users/%s/b.txt; "
                 "printf old > /seed/123/old.txt"
                 % (first_key, second_key, first_key, second_key)],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                check=False,
            )
            self.assertEqual(seeded.returncode, 0, seeded.stderr)

            def remove_seeded_files():
                subprocess.run(
                    ["docker", "run", "--rm", "--volume", "%s:/seed" % attachment_root,
                     "alpine:latest", "sh", "-ec",
                     "rm /seed/users/%s/a.txt /seed/users/%s/b.txt /seed/123/old.txt; "
                     "rmdir /seed/users/%s /seed/users/%s /seed/123 /seed/users"
                     % (first_key, second_key, first_key, second_key)],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    check=False,
                )

            try:
                commands = [
                    (first_mount, "test \"$(cat /agent/attachments/users/%s/a.txt)\" = A-only; "
                     "test ! -e /agent/attachments/users/%s/b.txt; "
                     "test ! -e /agent/attachments/users/%s/../%s/b.txt; "
                     "test ! -e /agent/attachments/users/%s/../../%s/b.txt; "
                     "test ! -e /agent/attachments/123/old.txt; "
                     "! sh -c 'printf changed > /agent/attachments/users/%s/a.txt'; "
                     "test \"$(cat /agent/attachments/users/%s/a.txt)\" = A-only"
                     % (first_key, second_key, first_key, second_key, first_key, second_key, first_key, first_key)),
                    (second_mount, "test \"$(cat /agent/attachments/users/%s/b.txt)\" = B-only; "
                     "test ! -e /agent/attachments/users/%s/a.txt; "
                     "test ! -e /agent/attachments/users/%s/../%s/a.txt; "
                     "test ! -e /agent/attachments/users/%s/../../%s/a.txt; "
                     "test ! -e /agent/attachments/123/old.txt; "
                     "! sh -c 'printf changed > /agent/attachments/users/%s/b.txt'; "
                     "test \"$(cat /agent/attachments/users/%s/b.txt)\" = B-only"
                     % (second_key, first_key, second_key, first_key, second_key, first_key, second_key, second_key)),
                ]
                for mount, command in commands:
                    with self.subTest(mount=mount):
                        completed = subprocess.run(
                            ["docker", "run", "--rm", "--volume", mount, "alpine:latest", "sh", "-ec",
                             command.replace("/agent/attachments", self.attachment_agent_root)],
                            stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE,
                            text=True,
                            check=False,
                        )
                        self.assertEqual(completed.returncode, 0, completed.stderr)
            finally:
                remove_seeded_files()
