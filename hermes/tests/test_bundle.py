"""설치 묶음을 만드는 `hermes/bundle.sh` 의 모양과 실패 갈래를 검사한다."""

import pathlib
import subprocess
import tempfile
import unittest

import yaml


ROOT = pathlib.Path(__file__).resolve().parents[1]
BUNDLE_SCRIPT = ROOT / "bundle.sh"
MCP_URL = "http://control-plane.test/mcp"
PLACEHOLDER = "__FOS_ASSISTANT_MCP_URL__"
# 인자 검사는 지나지만 `:` 로 끝나 채운 틀이 YAML 로 읽히지 않는 주소다. 채우기 시작한 뒤의 실패를 만든다.
UNREADABLE_URL = "http://control-plane.test/mcp:"


class BundleTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.base = pathlib.Path(tmp.name)
        self.out = self.base / "bundle"

    def bundle(self, *args):
        return subprocess.run([str(BUNDLE_SCRIPT), *args], capture_output=True, text=True)

    def test_bundle_has_plugin_filled_template_and_profile_plugins(self):
        """묶음에 대시보드 plugin, 주소를 채운 틀, 틀이 켜는 profile plugin 이 들어 있다."""
        result = self.bundle("--out", str(self.out), "--mcp-url", MCP_URL)
        self.assertEqual(result.returncode, 0, result.stderr)
        for relative in ("__init__.py", "plugin.yaml", "default-config.yaml.template",
                         "profile-plugins/fos-ctx/__init__.py", "profile-plugins/fos-ctx/plugin.yaml"):
            with self.subTest(relative):
                self.assertTrue((self.out / relative).is_file(), "%s 가 묶음에 없다" % relative)
        source = ROOT / "plugins/dashboard-profile-api"
        for name in [p.name for p in source.glob("*.py")] + ["plugin.yaml"]:
            self.assertEqual((self.out / name).read_bytes(), (source / name).read_bytes())
        for plugin in (ROOT / "plugins").iterdir():
            bundled = self.out / "profile-plugins" / plugin.name
            if bundled.is_dir():
                for module in plugin.glob("*.py"):
                    with self.subTest(plugin=plugin.name, module=module.name):
                        self.assertEqual((bundled / module.name).read_bytes(), module.read_bytes())
        text = (self.out / "default-config.yaml.template").read_text(encoding="utf-8")
        self.assertNotIn(PLACEHOLDER, text)
        template = yaml.safe_load(text)
        self.assertEqual(template["mcp_servers"]["fos-assistant"]["url"], MCP_URL)
        self.assertEqual([p.name for p in self.out.rglob("__pycache__")], [])

    def test_bundle_fills_an_existing_empty_directory(self):
        """비어 있는 디렉터리는 받는다. 경계는 파일이 하나라도 있는가다."""
        self.out.mkdir()
        result = self.bundle("--out", str(self.out), "--mcp-url", "https://control-plane.test/mcp")
        self.assertEqual(result.returncode, 0, result.stderr)
        template = yaml.safe_load((self.out / "default-config.yaml.template").read_text(encoding="utf-8"))
        self.assertEqual(template["mcp_servers"]["fos-assistant"]["url"], "https://control-plane.test/mcp")

    def test_source_template_keeps_the_placeholder(self):
        """저장소의 틀에는 주소가 아니라 자리 표시가 있다."""
        template = yaml.safe_load(
            (ROOT / "profile-template/config.yaml.template").read_text(encoding="utf-8"))
        self.assertEqual(template["mcp_servers"]["fos-assistant"]["url"], PLACEHOLDER)

    def test_missing_or_non_http_url_fails_and_leaves_no_output(self):
        """주소가 없거나 http(s) 가 아니면 실패하고 묶음 디렉터리를 남기지 않는다."""
        cases = [
            ("no url", ["--out", str(self.out)]),
            ("empty url", ["--out", str(self.out), "--mcp-url", ""]),
            ("ftp", ["--out", str(self.out), "--mcp-url", "ftp://control-plane.test/mcp"]),
            ("no scheme", ["--out", str(self.out), "--mcp-url", "control-plane.test/mcp"]),
        ]
        for label, args in cases:
            with self.subTest(label):
                result = self.bundle(*args)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(self.out.exists(), "실패했는데 %s 가 남았다" % self.out)

    def test_failure_leaves_an_existing_empty_directory_empty(self):
        """미리 만들어 둔 빈 디렉터리는 실패해도 지우지 않고 빈 채로 둔다."""
        self.out.mkdir()
        result = self.bundle("--out", str(self.out), "--mcp-url", UNREADABLE_URL)
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(self.out.is_dir(), "있던 디렉터리 %s 가 지워졌다" % self.out)
        self.assertEqual(sorted(p.name for p in self.out.iterdir()), [])

    def test_failure_after_filling_removes_a_directory_it_created(self):
        """채우다 실패하면 이 실행이 만든 디렉터리를 남기지 않는다."""
        result = self.bundle("--out", str(self.out), "--mcp-url", UNREADABLE_URL)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.out.exists(), "실패했는데 %s 가 남았다" % self.out)

    def test_relative_out_starting_with_dash_is_a_directory_name(self):
        """`-` 로 시작하는 상대 경로도 디렉터리 이름으로 다룬다. 실패하면 있던 디렉터리가 빈 채로 남는다."""
        out = self.base / "-bundle"
        result = subprocess.run([str(BUNDLE_SCRIPT), "--out", "-bundle", "--mcp-url", MCP_URL],
                                capture_output=True, text=True, cwd=self.base)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((out / "profile-plugins/fos-ctx/__init__.py").is_file())
        empty = self.base / "-empty"
        empty.mkdir()
        result = subprocess.run([str(BUNDLE_SCRIPT), "--out", "-empty", "--mcp-url", UNREADABLE_URL],
                                capture_output=True, text=True, cwd=self.base)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(sorted(p.name for p in empty.iterdir()), [])

    def test_failure_message_is_one_line_without_traceback(self):
        """실패 메시지는 원인을 적은 줄이고 Python traceback 을 싣지 않는다."""
        result = self.bundle("--out", str(self.out), "--mcp-url", UNREADABLE_URL)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("YAML 로 읽히지 않는다", result.stderr)
        self.assertNotIn("Traceback", result.stderr)

    def test_missing_out_fails(self):
        """--out 이 없으면 실패한다."""
        self.assertNotEqual(self.bundle("--mcp-url", MCP_URL).returncode, 0)

    def test_non_empty_out_fails_and_keeps_existing_files(self):
        """비어 있지 않은 디렉터리에는 만들지 않고 그 안의 파일을 건드리지 않는다."""
        self.out.mkdir()
        existing = self.out / "keep.txt"
        existing.write_text("keep", encoding="utf-8")
        result = self.bundle("--out", str(self.out), "--mcp-url", MCP_URL)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(existing.read_text(encoding="utf-8"), "keep")
        self.assertEqual(sorted(p.name for p in self.out.iterdir()), ["keep.txt"])


if __name__ == "__main__":
    unittest.main()
