"""분리한 plugin 의 적재와 mock 복원 경계를 검사한다."""

import pathlib
import sys
import tempfile
import types
import unittest

from plugin_loading import load_plugin, patch_plugin, set_plugin


class PluginLoadingTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = pathlib.Path(temporary.name)
        (self.directory / "part.py").write_text("VALUE = object()\n", encoding="utf-8")
        self.entrypoint = self.directory / "__init__.py"
        self.entrypoint.write_text("from .part import VALUE\n", encoding="utf-8")

    def test_package_and_submodule_are_restored(self):
        previous = types.ModuleType("loading_test")
        previous_part = types.ModuleType("loading_test.part")
        sys.modules.update({"loading_test": previous, "loading_test.part": previous_part})
        self.addCleanup(sys.modules.pop, "loading_test")
        self.addCleanup(sys.modules.pop, "loading_test.part")
        cleanups = []
        plugin = load_plugin("loading_test", self.entrypoint, cleanups.append)
        part = sys.modules["loading_test.part"]
        original = plugin.VALUE
        with patch_plugin(plugin, "VALUE") as replacement:
            self.assertIs(plugin.VALUE, replacement)
            self.assertIs(part.VALUE, replacement)
        self.assertIs(plugin.VALUE, original)
        self.assertIs(part.VALUE, original)
        replacement = object()
        set_plugin(plugin, "VALUE", replacement)
        self.assertIs(plugin.VALUE, replacement)
        self.assertIs(part.VALUE, replacement)
        cleanups.pop()()
        self.assertIs(sys.modules["loading_test"], previous)
        self.assertIs(sys.modules["loading_test.part"], previous_part)

    def test_failed_import_removes_partial_package(self):
        self.entrypoint.write_text("from .part import VALUE\nraise RuntimeError('failed')\n", encoding="utf-8")
        with self.assertRaises(RuntimeError):
            load_plugin("failed_loading_test", self.entrypoint, self.addCleanup)
        self.assertNotIn("failed_loading_test", sys.modules)
        self.assertNotIn("failed_loading_test.part", sys.modules)
