"""로컬 검사 실행 순서와 purge 변경 판정을 격리한 checkout에서 확인한다."""

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
INPUTS = (
    "backend/src/main/resources/db/migration/probe.sql",
    "backend/src/main/java/db/migration/Probe.java",
    "backend/src/test/resources/purge/tables.tsv",
)


class LocalCheckTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="fos-local-check-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.log = self.root / "commands.log"
        self.script = self.root / "scripts/dev/check-local.sh"
        self.script.parent.mkdir(parents=True)
        shutil.copyfile(REPO / "scripts/dev/check-local.sh", self.script)
        for name in INPUTS:
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("baseline")
        for name in ("scripts/checks/check-public-safe.sh", "scripts/checks/quality.sh",
                     "scripts/checks/check-mysql-migration.sh", "scripts/checks/check-connectors.sh"):
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("#!/bin/bash\nexit 0\n")
            target.chmod(0o755)
        self.git("init", "-b", "main")
        self.git("config", "user.email", "test@example.test")
        self.git("config", "user.name", "검사")
        self.git("add", ".")
        self.git("commit", "-m", "baseline")
        self.git("update-ref", "refs/remotes/origin/main", "HEAD")
        self.git("checkout", "-b", "topic")
        binaries = self.root / "bin"
        binaries.mkdir()
        stub = """#!/usr/bin/env python3
import os
from pathlib import Path
import sys
with open(os.environ["COMMAND_LOG"], "a") as log:
    log.write(Path(sys.argv[0]).name + " " + " ".join(sys.argv[1:]) + "\\n")
if Path(sys.argv[0]).name == "node" and "-p" in sys.argv:
    print("24.0.0")
"""
        for name in ("node", "pnpm", "bun", "python3", "gradlew"):
            target = self.root / "backend/gradlew" if name == "gradlew" else binaries / name
            # python3 스텁도 실제 Python으로 실행해 재귀 호출을 피한다.
            target.write_text(stub.replace("#!/usr/bin/env python3", "#!" + sys.executable))
            target.chmod(0o755)
        self.env = {**os.environ, "PATH": str(binaries) + os.pathsep + os.environ["PATH"],
                    "COMMAND_LOG": str(self.log)}

    def git(self, *arguments):
        return subprocess.run(["git", "-C", str(self.root), *arguments],
                              check=True, capture_output=True, text=True)

    def run_check(self, *arguments):
        self.log.unlink(missing_ok=True)
        result = subprocess.run(["bash", str(self.script), *arguments], env=self.env,
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result.stdout, self.log.read_text().splitlines()

    def test_fast_skips_purge_and_connector_install_without_changes(self):
        output, commands = self.run_check("--fast")
        self.assertIn("purge-classification: 변경이 없어 건너뛴다.", output)
        self.assertFalse(any(command.startswith(("gradlew test", "bun install")) for command in commands))

    def test_fast_runs_only_purge_for_committed_staged_unstaged_and_new_inputs(self):
        for name in INPUTS:
            for state in ("committed", "staged", "unstaged", "new", "deleted"):
                with self.subTest(name=name, state=state):
                    self.git("reset", "--hard", "origin/main")
                    target = self.root / name
                    if state == "new":
                        target = target.with_name("new-" + target.name)
                    if state == "deleted":
                        target.unlink()
                    else:
                        target.write_text("changed")
                    if state in ("committed", "staged"):
                        self.git("add", name)
                    if state == "committed":
                        self.git("commit", "-m", "change")
                    output, commands = self.run_check("--fast")
                    self.assertIn("purge-classification", output)
                    self.assertEqual([command for command in commands if command.startswith("gradlew test")],
                                     ["gradlew test --tests *PurgeClassificationTest"])
                    self.assertFalse(any(command.startswith("bun install") for command in commands))
                    if state == "new":
                        target.unlink()

    def test_missing_base_runs_purge_conservatively(self):
        self.git("update-ref", "-d", "refs/remotes/origin/main")
        output, commands = self.run_check("--fast")
        self.assertIn("비교 기준을 구할 수 없어", output)
        self.assertIn("gradlew test --tests *PurgeClassificationTest", commands)

    def test_full_installs_connector_before_backend_and_e2e(self):
        _, commands = self.run_check("--skip-browser")
        install = "bun install --frozen-lockfile --cwd " + str(self.root / "hermes/connectors/tossinvest")
        self.assertLess(commands.index(install), commands.index("gradlew test"))
        e2e = next(index for index, command in enumerate(commands) if "test/e2e/run.ts" in command)
        self.assertLess(commands.index(install), e2e)


if __name__ == "__main__":
    unittest.main()
