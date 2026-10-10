"""설치 내용 무결성과 실제 Gradle/Spotless의 엔진 입력을 검증한다."""
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("installer", ROOT / "scripts/install-intellij-formatter.py")
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)
CATALOG = ROOT / "backend/gradle/libs.versions.toml"
ENGINE_FILES = ("bin/idea.sh", "plugins/java/lib/java-impl.jar", "jbr/bin/java", "jbr/lib/modules")


def make_home(home):
    for name in (*ENGINE_FILES, "lib/app.jar"):
        file = home / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text("engine")
        file.chmod(0o755 if name in ("bin/idea.sh", "jbr/bin/java") else 0o644)
    (home / "product-info.json").write_text(json.dumps({
        "version": "2026.1.3", "buildNumber": "261.25134.95", "layout": [],
    }))
    (home / "bin/idea.sh").write_text('''#!/usr/bin/env python3
import pathlib, sys
folder = pathlib.Path(sys.argv[-1])
files = list(folder.rglob('*.java'))
marker = (pathlib.Path(__file__).parent.parent / 'plugins/java/lib/java-impl.jar').read_text()
for file in files:
    file.write_text('class App {} // ' + marker + '\\n')
    print('Formatting ' + str(file) + '...OK')
print(str(len(files)) + ' file(s) scanned.')
print(str(len(files)) + ' file(s) formatted.')
# 원본
''')
    return home


class InstallationTests(unittest.TestCase):
    def test_official_archive_reuse_and_corruption_recovery(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            home = make_home(root / "original")
            os.link(home / "plugins/java/lib/java-impl.jar", home / "lib/java-copy.jar")
            (home / "lib/java-link.jar").symlink_to("java-copy.jar")
            archive = root / "official.tar.gz"
            with tarfile.open(archive, "w:gz") as contents:
                contents.add(home, arcname="idea")
            checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
            cache = root / "cache"
            cache.mkdir()
            saved = cache / f"2026.1.3-x86_64-{checksum[:12]}.tar.gz"
            shutil.copyfile(archive, saved)
            with patch.object(installer.platform, "system", return_value="Linux"), patch.object(
                installer.platform, "machine", return_value="x86_64"
            ), patch.dict(installer.DOWNLOADS, {"x86_64": ("", checksum)}), patch.object(
                installer.urllib.request, "urlopen", side_effect=AssertionError("재다운로드 금지")
            ):
                binary = installer.resolve(CATALOG, cache=cache)
                inode = binary.stat().st_ino
                self.assertEqual(installer.resolve(CATALOG, cache=cache).stat().st_ino, inode)
                installed = binary.parent.parent
                for name in ENGINE_FILES:
                    file = installed / name
                    original = file.read_bytes()
                    for change in ("delete", "replace"):
                        if change == "delete":
                            file.unlink()
                        else:
                            file.write_bytes(b"corrupted")
                        self.assertEqual(installer.resolve(CATALOG, cache=cache), binary)
                        self.assertEqual(file.read_bytes(), original)
                saved.write_bytes(b"corrupted archive")
                with self.assertRaisesRegex(RuntimeError, "SHA256 불일치"):
                    installer.resolve(CATALOG, cache=cache)

    def test_stamp_only_fake_cache_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            cache = Path(temporary)
            checksum = installer.DOWNLOADS["x86_64"][1]
            home = cache / f"2026.1.3-x86_64-{checksum[:12]}"
            home.mkdir()
            (home / ".verified-sha256").write_text(checksum)
            (home / "product-info.json").write_text('{"version":"2026.1.3","buildNumber":"261.25134.95"}')
            (home / "bin").mkdir()
            (home / "bin/idea.sh").write_text("#!/bin/sh\nexit 0\n")
            (home / "bin/idea.sh").chmod(0o755)
            with patch.object(installer.platform, "system", return_value="Linux"), patch.object(
                installer.platform, "machine", return_value="x86_64"
            ), patch.object(installer.urllib.request, "urlopen", return_value=io.BytesIO(b"fake")):
                with self.assertRaisesRegex(RuntimeError, "SHA256 불일치"):
                    installer.resolve(CATALOG, cache=cache)

    def test_user_installation_engine_identity_and_required_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = make_home(Path(temporary) / "user-ide")
            binary = installer.resolve(CATALOG, home=home)
            identity = installer.engine_identity(binary)
            for name in ENGINE_FILES:
                file = home / name
                original = file.read_bytes()
                file.write_bytes(original + b"changed")
                self.assertNotEqual(installer.engine_identity(binary), identity)
                file.unlink()
                with self.assertRaises((RuntimeError, FileNotFoundError)):
                    installer.resolve(CATALOG, home=home)
                file.write_bytes(original)
                file.chmod(0o755 if name in ("bin/idea.sh", "jbr/bin/java") else 0o644)
            other = Path(temporary) / "other"
            shutil.copytree(home, other)
            self.assertNotEqual(installer.engine_identity(installer.resolve(CATALOG, home=other)), identity)


def gradle_regression():
    with tempfile.TemporaryDirectory(prefix="fos-engine-gradle-") as temporary:
        root = Path(temporary)
        backend = root / "backend"
        backend.mkdir()
        for name in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties"):
            shutil.copyfile(ROOT / "backend" / name, backend / name)
        for name in ("gradle", "config/intellij"):
            shutil.copytree(ROOT / "backend" / name, backend / name)
        shutil.copytree(ROOT / "scripts", root / "scripts")
        source = backend / "src/main/java/App.java"
        source.parent.mkdir(parents=True)
        source.write_text("class App {} // engine\n")
        home = make_home(root / "ide")
        command = [str(ROOT / "backend/gradlew"), "-p", str(backend), "spotlessCheck", "-PintellijFormatAll=true", "--console=plain", "--build-cache"]

        def run(expected=0, selected=home):
            result = subprocess.run(command, env=dict(os.environ, INTELLIJ_FORMAT_HOME=str(selected)), capture_output=True, text=True)
            log = result.stdout + result.stderr
            assert (result.returncode == 0) == (expected == 0), log[-5000:]
            print(log, flush=True)
            return log

        run()
        assert "> Task :prepareIntellijFormat UP-TO-DATE" in run()
        for name in ENGINE_FILES:
            file = home / name
            original = file.read_bytes()
            file.unlink()
            run(expected=1)
            assert not (backend / "build/intellij-format").exists()
            file.write_bytes(original)
            file.chmod(0o755 if name in ("bin/idea.sh", "jbr/bin/java") else 0o644)
            run()
            metadata = file.stat()
            file.write_bytes(original.replace("# 원본".encode(), "# 변조".encode()) if name == "bin/idea.sh" else b"enginf")
            os.utime(file, ns=(metadata.st_atime_ns, metadata.st_mtime_ns))
            log = run(expected=1 if "java-impl" in name else 0)
            assert "> Task :prepareIntellijFormat UP-TO-DATE" not in log
            assert "> Task :spotlessJava UP-TO-DATE" not in log
            file.write_bytes(original)
            run()
        other = root / "other-ide"
        shutil.copytree(home, other)
        log = run(selected=other)
        assert "> Task :prepareIntellijFormat UP-TO-DATE" not in log
        assert "> Task :spotlessJava UP-TO-DATE" not in log
        assert source.read_text() == "class App {} // engine\n"
        print("Gradle engine regression: deletion/replacement/home/Spotless passed", flush=True)


if __name__ == "__main__":
    if "--gradle" in sys.argv:
        gradle_regression()
    else:
        unittest.main()
