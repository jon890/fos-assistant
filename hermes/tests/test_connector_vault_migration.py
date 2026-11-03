"""기동 이관이 보관 파일을 활성 key 로 다시 쓰는지 검사한다(ADR-20261103 connector-vault-key)."""

import json
import os
import shutil
import sys
import types
from unittest import mock

from plugin_loading import patch_plugin
from dashboard_profile_api_support import DEMO, DEMO_VALUE, OTHER, OTHER_VALUE
from test_connector_vault_encryption import NEW_KEY, OLD_KEY, REQUIRED_ENV, SECRET, VaultKeyTest, encoded


class ConnectorVaultMigrationTest(VaultKeyTest):
    def directory(self):
        return self.hermes_root / "connector-vault"

    def snapshot(self):
        return {path.name: path.read_bytes() for path in sorted(self.directory().iterdir())}

    def migrate(self):
        with self.captured() as logs:
            counts = self.plugin._migrate_vault_files()
        self.assert_quiet(logs)
        return counts, logs

    def test_plain_old_and_broken_files_are_sorted_and_rewritten(self):
        """REQ3: v1 과 옛 key 파일은 활성 key 의 v2 가 되고, 깨진 파일과 풀지 못하는 파일은 바이트가 그대로다."""
        self.bind_fixture()  # key 없이 c1(DEMO), c2(OTHER)를 v1 로 쓴다.
        self.use_keys(active="old")
        self.assertEqual(self.put(vault="c3").status_code, 200)  # 옛 key 의 v2
        directory = self.directory()
        (directory / "c4.json").write_text("{깨진", encoding="utf-8")
        # 다른 연결에서 복사해 온 활성 key 의 파일과, key 파일에 없는 key 의 파일이다.
        self.use_keys(active="new")
        self.assertEqual(self.put(vault="c7").status_code, 200)
        shutil.copy(directory / "c7.json", directory / "c5.json")
        self.use_keys(active="gone", lines=["gone:%s" % encoded(bytes(32))])
        self.assertEqual(self.put(vault="c6").status_code, 200)
        # 쓰다 죽은 임시 파일과 상관없는 파일이다.
        (directory / ".connector-abc").write_text(json.dumps({"values": {"token": SECRET}}), encoding="utf-8")
        (directory / "notes.txt").write_text("남겨 둔다", encoding="utf-8")
        self.use_keys(active="new")
        before = self.snapshot()

        counts, logs = self.migrate()

        self.assertEqual(counts, {"kept": 1, "from_plain": 2, "from_old_key": 1, "plain": 1, "old_key": 1,
                                  "unopened": 1, "temp_removed": 1})
        for vault, connector, values in (("c1", DEMO, {"token": DEMO_VALUE}),
                                         ("c2", OTHER, {"token": OTHER_VALUE, "scope": "a"}),
                                         ("c3", DEMO, {"token": SECRET})):
            stored = json.loads(self.stored(vault))
            self.assertEqual((stored["v"], stored["key_id"], stored["connector"]), (2, "new", connector))
            self.assertEqual(self.plugin._read_vault(vault)["values"], values)
            self.assertNotIn(values["token"], self.stored(vault))
        after = self.snapshot()
        for name in ("c4.json", "c5.json", "c6.json", "c7.json", "notes.txt"):
            self.assertEqual(after[name], before[name], name)
        self.assertNotIn(".connector-abc", after)
        summary = next(line for line in logs if "보관 파일 이관:" in line)
        self.assertIn("v1 에서 옮김 2, 옛 key 에서 옮김 1, 남은 평문 파일 1, 남은 옛 key 파일 1, 풀지 못한 파일 1", summary)
        self.assertIn("key=ready", summary)
        self.assertTrue(any("남은 보관 파일: c4, c5, c6" in line for line in logs))
        for name in ("c1.json", "c2.json", "c3.json"):
            self.assertEqual((directory / name).stat().st_mode & 0o777, 0o600)

        # 다시 돌리면 바뀌는 파일이 없다.
        again, _ = self.migrate()
        self.assertEqual(self.snapshot(), after)
        self.assertEqual((again["from_plain"], again["from_old_key"], again["kept"]), (0, 0, 4))

    def test_without_key_nothing_is_rewritten(self):
        """REQ3: key 가 없거나 활성 id 가 틀리면 다시 쓰지 않고 평문 파일 수만 남긴다."""
        self.use_keys()
        self.bind_fixture()  # c1, c2 를 v2 로 쓴다.
        (self.directory() / "c3.json").write_text(
            json.dumps({"v": 1, "connector": DEMO, "values": {"token": SECRET}}), encoding="utf-8")
        before = self.snapshot()
        for label, prepare in (("없음", lambda: [os.environ.pop(name, None) for name in
                                                 ("FOS_ASSISTANT_CONNECTOR_VAULT_KEY_FILE",
                                                  "FOS_ASSISTANT_CONNECTOR_VAULT_ACTIVE_KEY_ID")]),
                               ("활성 id 틀림", lambda: self.use_keys(active="typo"))):
            with self.subTest(label):
                prepare()
                counts, logs = self.migrate()
                self.assertEqual(self.snapshot(), before)
                self.assertEqual((counts["plain"], counts["kept"], counts["from_plain"]), (1, 2, 0))

    def test_rewrite_that_does_not_read_back_is_restored(self):
        """다시 쓴 파일을 다시 읽어 값이 다르면 원래 바이트로 되돌리고 남은 파일로 센다."""
        self.bind_fixture()
        self.use_keys()
        before = self.snapshot()
        original = self.plugin.connector_vault_migration._read_vault

        def broken(vault, keys=None):
            stored = original(vault, keys)
            return {**stored, "values": {"token": "다른 값"}} if stored["v"] == 2 else stored

        with mock.patch.object(self.plugin.connector_vault_migration, "_read_vault", broken):
            counts, _ = self.migrate()
        self.assertEqual(self.snapshot(), before)
        self.assertEqual((counts["plain"], counts["from_plain"]), (2, 0))

    def test_register_migrates_before_routes_open_and_survives_failure(self):
        """REQ3: register 가 경로를 감싸기 전에 이관을 돌리고, 이관이 실패해도 기동을 잇는다. 「key 필수」 면 error 를 남긴다."""
        self.bind_fixture()
        self.use_keys(required="true", lines=["new:%s" % encoded(NEW_KEY[:8])])
        drain = types.ModuleType("plugins.dashboard_auth.drain")
        drain.assess_secret_strength = lambda secret: None
        modules = {"plugins": types.ModuleType("plugins"),
                   "plugins.dashboard_auth": types.ModuleType("plugins.dashboard_auth"),
                   "plugins.dashboard_auth.drain": drain}
        order = []
        original = self.plugin._migrate_vault_files

        def migrate():
            order.append("migrate")
            return original()

        ctx = mock.Mock()
        secret = {"HERMES_DASHBOARD_PROFILE_API_SECRET": "s" * 64}
        with mock.patch.dict(sys.modules, modules), mock.patch.dict(os.environ, secret), self.captured() as logs:
            with patch_plugin(self.plugin, "_migrate_vault_files", migrate), \
                    patch_plugin(self.plugin, "_install_gate", side_effect=lambda: order.append("gate") or False):
                self.plugin.register(ctx)
            self.assertEqual(order, ["migrate", "gate"])
            self.assertTrue(any(line.startswith("ERROR:") and "key 필수" in line for line in logs))

            order.clear()
            with patch_plugin(self.plugin, "_migrate_vault_files", side_effect=OSError("/secret/path")), \
                    patch_plugin(self.plugin, "_install_gate", side_effect=lambda: order.append("gate") or False):
                self.plugin.register(ctx)
            self.assertEqual(order, ["gate"])
        self.assertTrue(any("이관이 멈췄다: OSError" in line for line in logs))
        self.assertFalse(any("/secret/path" in line for line in logs))
        self.assert_quiet(logs)

    def test_key_rotation_moves_old_files_and_reports_zero(self):
        """DECISION7: 활성 id 를 바꾸고 다시 띄우면 옛 key 파일이 새 key 로 옮겨지고, 그 뒤 옛 key 를 빼도 읽힌다."""
        self.use_keys(active="old")
        self.bind_fixture()
        self.use_keys(active="new")
        counts, _ = self.migrate()
        self.assertEqual((counts["from_old_key"], counts["old_key"]), (2, 0))
        self.use_keys(active="new", lines=["new:%s" % encoded(NEW_KEY)])
        self.assertEqual(self.plugin._read_vault("c1")["values"], {"token": DEMO_VALUE})
        self.assertNotIn(encoded(OLD_KEY), self.key_file.read_text(encoding="utf-8"))

    def test_shared_key_file_is_warned(self):
        """key 파일을 다른 사용자가 읽을 수 있으면 기동 때 경고한다. 경로는 남기지 않는다."""
        self.bind_fixture()
        self.use_keys()
        self.key_file.chmod(0o644)
        counts, logs = self.migrate()
        self.assertTrue(any("소유자가 아닌 사용자도" in line for line in logs))
        self.assertEqual(counts["from_plain"], 2)

    def test_required_key_env_default_is_off(self):
        """「key 필수」 를 주지 않으면 꺼져 있다."""
        os.environ.pop(REQUIRED_ENV, None)
        self.assertFalse(self.plugin._vault_keys().required)
