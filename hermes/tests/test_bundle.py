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
        for name in ("__init__.py", "plugin.yaml"):
            self.assertEqual((self.out / name).read_bytes(), (source / name).read_bytes())
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
