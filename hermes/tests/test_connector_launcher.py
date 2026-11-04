"""로컬 플러그인 서버를 실행기로 띄우는 바인딩을 검사한다(docs/features/connector/spec.md 의 REQ6, REQ8).

실행기는 실제 자식 프로세스로 띄운다. Hermes 처럼 몇 기본 env 에 서버 정의의 `env` 만 얹어 넘기고,
실제 서버 대신 받은 env 를 파일로 내는 셸 스크립트를 둔다.
"""

import json
import os
import pathlib
import shutil
import subprocess

from dashboard_profile_api_support import DEMO, DEMO_BASE, DEMO_VALUE, OTHER
import test_connector_vault_encryption as encryption


LAUNCHER_PREFIX = "FOS_ASSISTANT_CONNECTOR_VAULT_"


class ConnectorLauncherTest(encryption.VaultKeyTest):
    def bound(self):
        """key 를 준 상태로 두 보관 파일을 v2 로 쓰고 시험 플러그인을 붙인다. 서버 정의를 돌려준다."""
        self.use_keys()
        self.bind_fixture()
        response = self.bind()
        self.assertEqual(response.status_code, 200, response.body)
        return self.alice_config()["mcp_servers"]["demo"]

    def env_dump_server(self):
        """실제 서버 자리의 실행 파일을 받은 env 를 파일로 내는 스크립트로 바꾼다. 그 파일 경로를 돌려준다."""
        out = self.root.parent / "child-env.txt"
        pathlib.Path(self.connector_command).write_text("#!/bin/sh\n/usr/bin/env > '%s'\n" % out, encoding="utf-8")
        return out

    def run_definition(self, definition, environ=None):
        """Hermes 가 하듯 기본 env 에 정의의 `env` 만 얹어 정의의 command 와 args 로 띄운다."""
        env = {"PATH": "/usr/bin:/bin", "HOME": str(self.root.parent), **(environ or {}), **definition["env"]}
        return subprocess.run([definition["command"], *definition["args"]], env=env, capture_output=True,
                              text=True, timeout=30)

    def assert_no_secret(self, text):
        self.assertNotIn(DEMO_VALUE, text)
        self.assertNotIn(str(self.root.parent), text)
        self.assertNotIn(encryption.encoded(encryption.NEW_KEY), text)

    def test_installed_definition_starts_the_server_with_vault_values_only_in_its_env(self):
        """설치한 정의대로 띄우면 실제 서버가 보관 파일 값을 받는다. 정의와 `.env` 에는 값이 없고 실행기 env 는 넘어가지 않는다."""
        definition = self.bound()
        config_text = (self.root / "alice/config.yaml").read_text(encoding="utf-8")
        self.assert_no_secret(self.env_text())
        self.assertNotIn(DEMO_VALUE, config_text)
        self.assertNotIn(encryption.encoded(encryption.NEW_KEY), config_text)
        self.assertEqual(definition["args"][0], "-I")
        out = self.env_dump_server()

        # 활성 key id 는 정의에 없다. 실행기는 key 파일만으로 v2 를 푼다.
        self.assertNotIn(encryption.ACTIVE_ENV, definition["env"])
        finished = self.run_definition(definition)

        self.assertEqual(finished.returncode, 0, finished.stderr)
        child = dict(line.split("=", 1) for line in out.read_text(encoding="utf-8").splitlines() if "=" in line)
        self.assertEqual((child["DEMO_TOKEN"], child["DEMO_SCOPE"], child["DEMO_BASE"]), (DEMO_VALUE, "", DEMO_BASE))
        self.assertEqual([name for name in child if name.startswith(LAUNCHER_PREFIX)], [])
        self.assertEqual(child["PATH"].split(os.pathsep)[0], os.path.dirname(self.connector_command))

    def test_launcher_failures_exit_without_values_or_paths(self):
        """풀지 못하거나 다른 플러그인의 파일이거나 띄울 수 없으면 78 로 끝나고 stderr 에 이유 단어만 남는다."""
        definition = self.bound()
        self.env_dump_server()
        vault = self.hermes_root / "connector-vault" / "c1.json"
        original = vault.read_bytes()

        def failure(prepare, word, args=None, environ=None):
            prepare()
            finished = self.run_definition(definition if args is None else {**definition, "args": args}, environ)
            vault.write_bytes(original)
            self.assertEqual((finished.returncode, finished.stderr), (78, "connector-launcher: %s\n" % word), word)
            self.assert_no_secret(finished.stderr + finished.stdout)

        failure(vault.unlink, "vault_missing")
        failure(lambda: shutil.copyfile(vault.parent / "c2.json", vault), "connector_mismatch")
        failure(lambda: vault.write_text("{", encoding="utf-8"), "vault_unreadable")
        # 다른 연결 이름으로 옮긴 v2 파일은 AAD 가 맞지 않아 풀리지 않는다.
        failure(lambda: vault.write_bytes((vault.parent / "c2.json").read_bytes().replace(
            OTHER.encode(), DEMO.encode())), "vault_unreadable")
        key_text = self.key_file.read_text(encoding="utf-8")
        failure(lambda: self.key_file.write_text("", encoding="utf-8"), "vault_unreadable")
        self.key_file.write_text(key_text, encoding="utf-8")
        args = definition["args"]
        failure(lambda: None, "arguments", args=args[:args.index("--")])
        failure(lambda: None, "arguments", args=[*args[:4], "token=BAD-NAME", *args[args.index("--"):]])
        os.unlink(self.connector_command)
        failure(lambda: None, "exec_failed")

    def test_definition_comparison_knows_launcher_and_legacy_definitions(self):
        """실행기 정의와 옛 정의를 모두 맞다고 보고, 경로나 칸이 바뀐 실행기 정의는 맞지 않다고 본다."""
        definition = self.bound()
        manifest = self.plugin._connector_manifest(DEMO)
        matches = lambda server: self.plugin._server_matches(manifest, server)
        legacy = self.plugin._connector_server(manifest)
        legacy["env"]["DEMO_SCOPE"] = ""
        self.assertTrue(matches(definition))
        self.assertTrue(matches(legacy))
        moved = lambda **env: {**definition, "env": {**definition["env"], **env}}
        self.assertFalse(matches({**definition, "command": "/elsewhere/python"}))
        self.assertFalse(matches(moved(FOS_ASSISTANT_CONNECTOR_VAULT_KEY_FILE="/elsewhere/keys")))
        self.assertFalse(matches(moved(FOS_ASSISTANT_CONNECTOR_VAULT_DIR="/elsewhere/vault")))
        self.assertFalse(matches(moved(FOS_ASSISTANT_CONNECTOR_VAULT_REVISION="x")))
        self.assertFalse(matches(moved(DEMO_TOKEN="literal")))
        args = list(definition["args"])
        args[args.index("token=DEMO_TOKEN")] = "token=DEMO_OTHER"
        self.assertFalse(matches({**definition, "args": args}))
        # 다른 보관 파일을 여는 실행기 정의는 소유 기록으로 받지 않는다.
        state_path = self.root / "alice" / self.plugin.CONNECTOR_STATE
        state = json.loads(state_path.read_text(encoding="utf-8"))
        args = list(definition["args"])
        args[2] = "c2"
        state[DEMO]["server"] = {**definition, "args": args}
        with self.assertRaises(ValueError):
            self.plugin._connector_state(state)

    def test_same_values_keep_the_revision_and_changed_values_require_restart(self):
        """같은 값으로 다시 등록하면 보관 파일과 정의가 그대로이고, 값을 바꾸면 다시 설치가 재시작 대기를 답한다(REQ8)."""
        self.bound()
        vault = self.hermes_root / "connector-vault" / "c1.json"
        before = vault.read_bytes()
        self.assertEqual(self.put(token=DEMO_VALUE).status_code, 200)
        self.assertEqual(vault.read_bytes(), before)
        repeated = self.bind()
        self.assertEqual({key: repeated.body[key] for key in ("changed", "restart_required")},
                         {"changed": False, "restart_required": False})

        self.assertEqual(self.put(token="demo_ok_1111111111").status_code, 200)
        changed = self.bind()
        self.assertEqual({key: changed.body[key] for key in ("changed", "restart_required", "reload_pending")},
                         {"changed": True, "restart_required": True, "reload_pending": False})
        self.assertEqual(self.launched()[1]["env"]["DEMO_TOKEN"], "demo_ok_1111111111")
        self.assertIs(self.status_of()["connectors"][0]["configured"], True)
