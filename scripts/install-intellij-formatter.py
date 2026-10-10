#!/usr/bin/env python3
"""고정 IntelliJ 설치를 확인하거나 Linux 공식 배포본을 검증해 캐시한다."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import tarfile
import tempfile
import tomllib
import urllib.request


DOWNLOADS = {
    "x86_64": ("", "a6f049716da1d09d9e0ec1500c60bf01a5ff8a0fe2419178dd1ff2fdb2b77563"),
    "aarch64": ("-aarch64", "7659e791609233c3e6bf67c1bfcc86f5fa1176477ca5815ae6125b0eae84a88b"),
}


def installation(home, version, build):
    home = Path(home).expanduser().resolve()
    if (home / "Contents").is_dir():
        home = home / "Contents"
        info = home / "Resources/product-info.json"
        binary = home / "MacOS/idea"
    else:
        info = home / "product-info.json"
        binary = home / "bin/idea.sh"
    data = json.loads(info.read_text())
    if data["version"] != version or data["buildNumber"] != build:
        raise RuntimeError(f"IntelliJ {version} / {build} 가 필요하다: {data['version']} / {data['buildNumber']}")
    if not binary.is_file() or not os.access(binary, os.X_OK):
        raise RuntimeError(f"IntelliJ 실행 파일이 없다: {binary}")
    return binary


def resolve(catalog, home=None, cache=None):
    versions = tomllib.loads(Path(catalog).read_text())["versions"]
    version, build = versions["intellij-idea"], versions["intellij-idea-build"]
    if home:
        return installation(home, version, build)
    if platform.system() != "Linux":
        raise RuntimeError("고정 IntelliJ 설치 디렉터리를 INTELLIJ_FORMAT_HOME 으로 지정한다.")
    arch = platform.machine()
    if arch not in DOWNLOADS:
        raise RuntimeError(f"지원하지 않는 Linux 아키텍처: {arch}")
    suffix, checksum = DOWNLOADS[arch]
    cache = Path(cache or Path.home() / ".cache/fos-intellij")
    cache.mkdir(parents=True, exist_ok=True)
    destination = cache / f"{version}-{arch}-{checksum[:12]}"
    stamp = destination / ".verified-sha256"
    # 캐시는 설치 성공 뒤에만 게시한다. 중간 다운로드와 실패한 압축 해제는 재사용하지 않는다.
    if stamp.is_file() and stamp.read_text().strip() == checksum:
        return installation(destination, version, build)
    with tempfile.TemporaryDirectory(prefix="install-", dir=cache) as temporary:
        temporary = Path(temporary)
        archive = temporary / "idea.tar.gz"
        url = f"https://download.jetbrains.com/idea/idea-{version}{suffix}.tar.gz"
        with urllib.request.urlopen(url, timeout=60) as response, archive.open("wb") as output:
            shutil.copyfileobj(response, output)
        with archive.open("rb") as source:
            actual = hashlib.file_digest(source, "sha256").hexdigest()
        if actual != checksum:
            raise RuntimeError(f"IntelliJ SHA256 불일치: {actual}")
        extracted = temporary / "extracted"
        with tarfile.open(archive) as contents:
            contents.extractall(extracted, filter="data")
        roots = list(extracted.iterdir())
        if len(roots) != 1:
            raise RuntimeError("IntelliJ 배포본 최상위 디렉터리가 하나가 아니다.")
        installation(roots[0], version, build)
        (roots[0] / ".verified-sha256").write_text(checksum + "\n")
        if destination.exists():
            shutil.rmtree(destination)
        roots[0].rename(destination)
    return installation(destination, version, build)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", default="backend/gradle/libs.versions.toml")
    parser.add_argument("--home", default=os.environ.get("INTELLIJ_FORMAT_HOME"))
    parser.add_argument("--cache", default=os.environ.get("INTELLIJ_FORMAT_CACHE"))
    args = parser.parse_args()
    print(resolve(args.catalog, args.home, args.cache))
