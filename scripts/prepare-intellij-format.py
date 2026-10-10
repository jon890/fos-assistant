#!/usr/bin/env python3
"""Java 사본을 IntelliJ 로 한 번에 포맷한다. 성공한 전체 결과만 Spotless 에 공개한다."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time


def digest(file):
    return hashlib.sha256(file.read_bytes()).hexdigest()


def isolate_plugins(binary, config):
    # 배포본의 Java 엔진만 사용한다. Spring/원격 개발 같은 IDE 플러그인은 포맷에 필요하지 않다.
    home = Path(binary).resolve().parent.parent
    info = home / "Resources/product-info.json" if (home / "Resources/product-info.json").is_file() else home / "product-info.json"
    if info.is_file():
        data = json.loads(info.read_text())
        plugins = {item["name"] for item in data["layout"] if item["kind"] == "plugin"}
        disabled = plugins - {"com.intellij", "com.intellij.java", "com.intellij.java.ide"}
        config.mkdir(parents=True)
        (config / "disabled_plugins.txt").write_text("\n".join(sorted(disabled)) + "\n")


def prepare(backend, destination, settings, binary, timeout):
    backend, destination, settings = map(lambda p: Path(p).resolve(), (backend, destination, settings))
    if not destination.is_relative_to(backend / "build") or destination == backend / "build":
        raise ValueError("포맷 결과는 backend/build 아래 별도 디렉터리에만 쓴다.")
    settings_before = digest(settings)
    # 실패한 실행 뒤에 이전 성공 결과가 남지 않게 한다.
    if destination.exists():
        shutil.rmtree(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    sources = sorted(p for folder in ("src/main/java", "src/test/java") for p in (backend / folder).rglob("*.java"))
    before = {str(p.relative_to(backend)): digest(p) for p in sources}
    started = time.monotonic()
    # 프로젝트의 .idea 와 .editorconfig 를 상속하지 않는 위치에서 포맷한다.
    with tempfile.TemporaryDirectory(prefix="fos-intellij-") as temporary:
        temporary = Path(temporary)
        result = temporary / "result"
        for file in sources:
            relative = file.relative_to(backend)
            for tree in ("original", "formatted"):
                copy = result / tree / relative
                copy.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(file, copy)
        properties = temporary / "idea.properties"
        isolate_plugins(binary, temporary / "config")
        properties.write_text("\n".join(f"idea.{key}.path={temporary / key}" for key in ("config", "system", "plugins", "log")) + "\n")
        isolated_settings = temporary / "code-style.xml"
        shutil.copyfile(settings, isolated_settings)
        if sources:
            command = [str(binary), "format", "-s", str(isolated_settings), "-charset", "UTF-8", "-r", "-m", "*.java", str(result / "formatted")]
            process = subprocess.run(command, env=dict(os.environ, IDEA_PROPERTIES=str(properties)), capture_output=True, text=True, timeout=timeout)
            log = process.stdout + process.stderr
            if process.returncode or "ERROR:" in log:
                raise RuntimeError(f"IntelliJ 포맷 실패 ({process.returncode}):\n{log[-6000:]}")
            # exit 0 만으로는 플러그인 부재나 일부 파일 건너뛰기를 판정할 수 없다.
            scanned = re.findall(r"(?m)^(\d+) file\(s\) scanned\.$", process.stdout)
            formatted = re.findall(r"(?m)^(\d+) file\(s\) formatted\.$", process.stdout)
            completed = re.findall(r"(?m)^Formatting .+\.java\.\.\.OK$", process.stdout)
            if scanned != [str(len(sources))] or formatted != [str(len(sources))] or len(completed) != len(sources):
                raise RuntimeError(f"IntelliJ 가 모든 Java 파일을 처리하지 않았다:\n{log[-6000:]}")
            (result / "formatter.log").write_text(log)
        else:
            (result / "original").mkdir(parents=True)
            (result / "formatted").mkdir(parents=True)
        after = {str(p.relative_to(backend)): digest(p) for folder in ("src/main/java", "src/test/java") for p in (backend / folder).rglob("*.java")}
        if before != after:
            raise RuntimeError("포맷하는 동안 원본 Java 입력이 바뀌었다. 다시 실행한다.")
        if settings_before != digest(settings):
            raise RuntimeError("포맷하는 동안 Java 설정이 바뀌었다. 다시 실행한다.")
        actual = {str(p.relative_to(result / "formatted")) for p in (result / "formatted").rglob("*.java")}
        if actual != set(before):
            raise RuntimeError("IntelliJ 포맷 결과의 Java 파일 목록이 입력과 다르다.")
        manifest = {"files": before, "settings": settings_before, "seconds": round(time.monotonic() - started, 3)}
        (result / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        # 임시 폴더와 build 가 서로 다른 파일시스템이어도 성공 결과만 같은 볼륨에서 게시한다.
        with tempfile.TemporaryDirectory(prefix="intellij-publish-", dir=destination.parent) as publish:
            staged = Path(publish) / "result"
            shutil.copytree(result, staged)
            staged.rename(destination)
    print(f"IntelliJ batch: {len(sources)}개, {manifest['seconds']}초")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--settings", required=True)
    parser.add_argument("--binary", required=True)
    parser.add_argument("--timeout", type=float, default=180)
    args = parser.parse_args()
    prepare(args.backend, args.output, args.settings, args.binary, args.timeout)
