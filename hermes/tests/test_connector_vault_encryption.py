"""보관 파일을 별도 key 로 암호화해 쓰고 읽는지 검사한다(ADR-20261103 connector-vault-key)."""

import base64
import contextlib
import json
import logging
import os
import shutil
import types
from unittest import mock

from plugin_loading import patch_plugin
from dashboard_profile_api_support import DEMO, OTHER, OTHER_VALUE
import dashboard_profile_api_support as support


KEY_FILE_ENV = "FOS_ASSISTANT_CONNECTOR_VAULT_KEY_FILE"
ACTIVE_ENV = "FOS_ASSISTANT_CONNECTOR_VAULT_ACTIVE_KEY_ID"
REQUIRED_ENV = "FOS_ASSISTANT_CONNECTOR_VAULT_KEY_REQUIRED"
# 칸 값이다. 파일, 응답, 로그 어디에도 원문이 나오면 안 된다.
SECRET = "demo_ok_9876543210"
OLD_KEY = bytes(range(32))
NEW_KEY = bytes(range(32, 64))


def encoded(key: bytes) -> str:
    return base64.b64encode(key).decode("ascii")


class VaultKeyTest(support.ProfileApiRouteTest):
    """key 파일과 환경 변수를 검사마다 따로 둔다."""

    def setUp(self):
        super().setUp()
        self.key_file = self.root.parent / "vault-keys"
        environ = mock.patch.dict(os.environ)
        environ.start()
        self.addCleanup(environ.stop)
        for name in (KEY_FILE_ENV, ACTIVE_ENV, REQUIRED_ENV):
            os.environ.pop(name, None)

    def use_keys(self, active="new", lines=None, required=None):
        """key 파일을 쓰고 환경 변수를 준다. `lines` 가 없으면 옛 key 와 새 key 를 둔다."""
        if lines is None:
            lines = ["# 보관 파일 key", "", "old:%s" % encoded(OLD_KEY), "new:%s" % encoded(NEW_KEY)]
        self.key_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
        self.key_file.chmod(0o600)
        os.environ[KEY_FILE_ENV] = str(self.key_file)
        os.environ[ACTIVE_ENV] = active
        if required is not None:
            os.environ[REQUIRED_ENV] = required

    def stored(self, vault="c1"):
        return (self.hermes_root / "connector-vault" / ("%s.json" % vault)).read_text(encoding="utf-8")

    def put(self, vault="c1", connector=DEMO, token=SECRET):
        return self.vault("PUT", vault=vault, connector=connector, values={"token": token})

    def call_env(self, vault="c1", tool="list_scopes"):
        """그 vault 로 `call` 을 불러 자식 프로세스에 넘긴 env 와 응답을 돌려준다."""
        seen = []

        async def verify(manifest, name, env, timeout):
            seen.append(env)
            return types.SimpleNamespace(structured_content={"scopes": []}, is_error=False, content=[])

        with patch_plugin(self.plugin, "_mcp_sdk_problem", return_value=None), \
                patch_plugin(self.plugin, "_run_connector_tool", verify):
            response = self.request("/api/connectors/%s/call" % DEMO, "POST", token="valid", full_response=True,
                                    body={"tool": tool, "vault": vault})
        return response, (seen[0] if seen else None)

    @contextlib.contextmanager
    def captured(self):
        """plugin 로거의 모든 줄을 모은다. 아무것도 남지 않아도 실패하지 않는다."""
        lines = []

        class Collect(logging.Handler):
            def emit(self, record):
                lines.append("%s:%s" % (record.levelname, record.getMessage()))

        handler = Collect(level=logging.DEBUG)
        logger = self.plugin.logger
        previous = logger.level
        # 테스트 기반이 로그를 모두 끈다(`logging.disable`). 모으는 동안만 켠다.
        disabled = logging.root.manager.disable
        logging.disable(logging.NOTSET)
        logger.addHandler(handler)
        logger.setLevel(logging.DEBUG)
        try:
            yield lines
        finally:
            logger.removeHandler(handler)
            logger.setLevel(previous)
            logging.disable(disabled)

    def assert_quiet(self, logs, *extra):
        """로그와 덧붙인 글에 칸 값과 key 의 base64, key 파일 경로가 없다."""
        text = "\n".join(list(logs) + [json.dumps(item, ensure_ascii=False, default=str) for item in extra])
        for secret in (SECRET, encoded(OLD_KEY), encoded(NEW_KEY), str(self.key_file)):
            self.assertNotIn(secret, text)


class ConnectorVaultEncryptionTest(VaultKeyTest):
    def test_put_writes_v2_and_readers_get_original_values(self):
        """REQ1: key 가 있으면 PUT 뒤 파일이 v2 이고 글에 칸 값이 없다. call 과 바인딩 설치가 원래 값을 받는다."""
        self.use_keys(required="true")
        self.bind_fixture()
        with self.captured() as logs:
            self.assertEqual(self.put().status_code, 200)
        stored = json.loads(self.stored())
        self.assertEqual(set(stored), {"v", "connector", "key_id", "sealed"})
        self.assertEqual((stored["v"], stored["connector"], stored["key_id"]), (2, DEMO, "new"))
        self.assertTrue(stored["sealed"].startswith("v1."))
        self.assertNotIn(SECRET, self.stored())
        self.assert_quiet(logs)
        mode = (self.hermes_root / "connector-vault" / "c1.json").stat().st_mode & 0o777
        self.assertEqual(mode, 0o600)
        self.assertEqual([name for name in os.listdir(self.hermes_root / "connector-vault")
                          if name.startswith(".connector-")], [])

        response, env = self.call_env()
        self.assertEqual(response.status_code, 200, response.body)
        self.assertEqual(env["DEMO_TOKEN"], SECRET)

        # `tools` 는 `call` 과 같은 함수의 같은 `_read_vault` 로 값을 읽는다(`connector_run.py`).
        installed = self.bind(vault="c1")
        self.assertEqual(installed.status_code, 200, installed.body)
        self.assertIn("DEMO_TOKEN=%s" % SECRET, (self.root / "alice/.env").read_text(encoding="utf-8"))

    def test_moved_file_does_not_open(self):
        """REQ2: 다른 연결 이름으로 복사한 파일과 `connector` 만 고친 파일은 풀리지 않고, 값이 새지 않는다."""
        self.use_keys()
        self.bind_fixture()
        self.assertEqual(self.put().status_code, 200)
        directory = self.hermes_root / "connector-vault"
        shutil.copy(directory / "c1.json", directory / "c5.json")
        with self.captured() as logs:
            with self.assertRaises(ValueError) as raised:
                self.plugin._read_vault("c5")
            response, env = self.call_env(vault="c5")
        self.assertEqual(response.body, {"ok": False, "error": "unavailable"})
        self.assertIsNone(env)
        self.assert_quiet(logs, str(raised.exception), response.body)

        # 같은 이름의 파일에서 `connector` 만 다른 플러그인으로 고친다. 그 플러그인으로 읽거나 붙이지 못한다.
        changed = json.loads(self.stored())
        changed["connector"] = OTHER
        (directory / "c1.json").write_text(json.dumps(changed), encoding="utf-8")
        with self.captured() as logs:
            with self.assertRaises(ValueError) as raised:
                self.plugin._read_vault("c1")
            installed = self.bind(plugin=OTHER, vault="c1")
        self.assertEqual(installed.status_code, 503)
        self.assert_quiet(logs, str(raised.exception), installed.body)

    def test_old_key_still_opens_and_unknown_key_does_not(self):
        """활성 key 가 아니어도 파일에 있는 key 의 v2 는 풀리고, 파일에 없는 key 의 v2 는 풀리지 않는다."""
        self.use_keys(active="old")
        self.bind_fixture()
        self.assertEqual(self.put().status_code, 200)
        self.assertEqual(json.loads(self.stored())["key_id"], "old")
        self.use_keys(active="new")
        self.assertEqual(self.plugin._read_vault("c1")["values"], {"token": SECRET})
        self.use_keys(active="new", lines=["new:%s" % encoded(NEW_KEY)])
        with self.assertRaises(ValueError):
            self.plugin._read_vault("c1")

    def test_required_without_usable_key_refuses_put_but_reads_keep_working(self):
        """REQ4: 「key 필수」 에서 key 가 없거나 틀리면 PUT 이 503 이고 파일이 그대로다. 읽기와 call 은 돈다."""
        self.bind_fixture()  # key 없이 c1, c2 를 v1 로 쓴다.
        before = self.stored()
        cases = {
            "없음": lambda: None,
            "파일만": lambda: os.environ.__setitem__(KEY_FILE_ENV, str(self.key_file)),
            "모양 틀림": lambda: self.use_keys(lines=["new:%s" % encoded(NEW_KEY[:16])]),
            "id 겹침": lambda: self.use_keys(lines=["new:%s" % encoded(NEW_KEY), "new:%s" % encoded(OLD_KEY)]),
            "활성 id 없음": lambda: self.use_keys(active="gone"),
            "파일 없음": lambda: self.use_keys() or self.key_file.unlink(),
        }
        for label, prepare in cases.items():
            with self.subTest(label):
                for name in (KEY_FILE_ENV, ACTIVE_ENV):
                    os.environ.pop(name, None)
                prepare()
                os.environ[REQUIRED_ENV] = "yes-please"  # 모르는 값은 켠 것으로 본다.
                with self.captured() as logs:
                    response = self.put()
                self.assertEqual(response.status_code, 503)
                self.assertEqual(self.stored(), before)
                self.assert_quiet(logs, response.body)
                self.assertTrue(any("key 를 쓸 수 없어" in line for line in logs))
                called, env = self.call_env()
                self.assertEqual(called.status_code, 200, called.body)
                self.assertEqual(env["DEMO_TOKEN"], support.DEMO_VALUE)

    def test_without_required_missing_key_writes_plaintext_with_warning(self):
        """REQ4: 「key 필수」 를 끄면 key 가 없을 때 v1 로 쓰고 경고를 남긴다."""
        self.bind_fixture()
        for value in (None, "false", "FALSE"):
            with self.subTest(value):
                os.environ.pop(REQUIRED_ENV, None)
                if value is not None:
                    os.environ[REQUIRED_ENV] = value
                with self.captured() as logs:
                    self.assertEqual(self.put().status_code, 200)
                self.assertEqual(json.loads(self.stored()), {"v": 1, "connector": DEMO, "values": {"token": SECRET}})
                self.assertTrue(any("평문으로 쓴다" in line for line in logs))
                self.assert_quiet(logs)

    def test_invalid_active_id_still_reads_existing_v2(self):
        """활성 id 가 틀려도 파일의 key 로 풀어 call 이 돈다. 쓰기만 거절한다."""
        self.use_keys()
        self.bind_fixture()
        self.assertEqual(self.put().status_code, 200)
        self.use_keys(active="typo", required="true")
        called, env = self.call_env()
        self.assertEqual(env["DEMO_TOKEN"], SECRET)
        self.assertEqual(self.put(token="demo_ok_1111111111").status_code, 503)

    def test_ownership_check_does_not_need_the_key(self):
        """key 를 잃은 v2 파일도 같은 플러그인의 새 값으로 덮어쓰고, 다른 플러그인은 409 다. 깨진 파일은 덮어쓴다."""
        self.use_keys()
        self.bind_fixture()
        self.assertEqual(self.put().status_code, 200)
        self.use_keys(active="other", lines=["other:%s" % encoded(OLD_KEY)])
        self.assertEqual(self.put(connector=OTHER, token=OTHER_VALUE).status_code, 409)
        self.assertEqual(self.put(token="demo_ok_2222222222").status_code, 200)
        self.assertEqual(self.plugin._read_vault("c1")["values"], {"token": "demo_ok_2222222222"})

        (self.hermes_root / "connector-vault" / "c1.json").write_text("{깨진", encoding="utf-8")
        with self.captured() as logs:
            self.assertEqual(self.put(connector=OTHER, token=OTHER_VALUE).status_code, 200)
        self.assertTrue(any("모양을 읽지 못하는" in line for line in logs))
        self.assertEqual(self.plugin._read_vault("c1")["connector"], OTHER)

    def test_tampered_v2_shapes_are_rejected(self):
        """v2 의 키 집합, key id, 암호문 모양이 틀리면 읽기 실패다."""
        self.use_keys()
        self.bind_fixture()
        self.assertEqual(self.put().status_code, 200)
        good = json.loads(self.stored())
        path = self.hermes_root / "connector-vault" / "c1.json"
        pieces = good["sealed"].split(".")
        flipped = pieces[2][:-2] + ("AA" if pieces[2][-2:] != "AA" else "BA")
        for label, changed in (
            ("extra key", {**good, "values": {}}),
            ("bad key id", {**good, "key_id": "Bad!"}),
            ("not sealed", {**good, "sealed": "plain"}),
            ("short iv", {**good, "sealed": "v1.AAAA.%s" % pieces[2]}),
            ("flipped tag", {**good, "sealed": ".".join(pieces[:2] + [flipped])}),
            ("version 3", {**good, "v": 3}),
        ):
            with self.subTest(label):
                path.write_text(json.dumps(changed), encoding="utf-8")
                with self.assertRaises(ValueError) as raised:
                    self.plugin._read_vault("c1")
                self.assert_quiet([str(raised.exception)])
