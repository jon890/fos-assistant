#!/usr/bin/env python3
"""고정 IntelliJ 설치를 확인하거나 Linux 공식 배포본을 검증해 캐시한다."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import stat
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
    runtime = home / ("jbr/Contents/Home" if (home / "MacOS").is_dir() else "jbr")
    required = [runtime / "bin/java", runtime / "lib/modules", home / "lib/app.jar", home / "plugins/java/lib/java-impl.jar"]
    for file in required:
        if not file.is_file() or file.stat().st_size == 0:
            raise RuntimeError(f"IntelliJ 필수 엔진 파일이 없다: {file}")
    if not os.access(runtime / "bin/java", os.X_OK):
        raise RuntimeError("IntelliJ JBR 실행 권한이 없다.")
    return binary


def inventory(home):
    # 경로, 권한, 링크와 내용을 함께 식별한다. 사용자 설치에는 파일을 쓰지 않는다.
    result = {}
    for file in sorted(Path(home).rglob("*")):
        relative = file.relative_to(home).as_posix()
        if relative == ".verified-sha256":
            continue
        mode = stat.S_IMODE(file.lstat().st_mode)
        if file.is_symlink():
            result[relative] = ("link", 0, os.readlink(file))
        elif file.is_file():
            with file.open("rb") as source:
                result[relative] = ("file", mode, hashlib.file_digest(source, "sha256").hexdigest())
        elif file.is_dir():
            result[relative] = ("dir", 0, "")
        else:
            raise RuntimeError(f"지원하지 않는 설치 파일: {file}")
    return result


def engine_identity(binary):
    home = binary.parent.parent
    # 실제 실행에 쓰는 launcher, 플랫폼, Java plugin, JBR 전체를 한 번만 읽는다.
    files = {name: inventory(home / name) for name in ("lib", "plugins/java", "jbr")}
    for name in ("bin", "MacOS", "Resources"):
        if (home / name).is_dir():
            files[name] = inventory(home / name)
    info = home / "Resources/product-info.json" if (home / "MacOS").is_dir() else home / "product-info.json"
    files["product-info"] = hashlib.sha256(info.read_bytes()).hexdigest()
    files["home"] = str(home)
    return hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()


def verified_archive(archive, checksum):
    with archive.open("rb") as source:
        actual = hashlib.file_digest(source, "sha256").hexdigest()
    if actual != checksum:
        raise RuntimeError(f"IntelliJ SHA256 불일치: {actual}; 캐시 archive를 지우고 다시 설치한다.")


def archive_inventory(archive):
    expected = {}
    roots, hardlinks = set(), {}
    with tempfile.TemporaryDirectory(prefix="intellij-metadata-") as target, tarfile.open(archive, mode="r|gz") as contents:
        for item in contents:
            roots.add(Path(item.name).parts[0])
            relative = Path(*Path(item.name).parts[1:]).as_posix()
            if relative == ".":
                continue
            if item.isfile():
                with contents.extractfile(item) as source:
                    value = ("file", tarfile.data_filter(item, target).mode, hashlib.file_digest(source, "sha256").hexdigest())
            elif item.issym():
                value = ("link", 0, item.linkname)
            elif item.islnk():
                hardlinks[relative] = Path(*Path(item.linkname).parts[1:]).as_posix()
                continue
            elif item.isdir():
                value = ("dir", 0, "")
            else:
                raise RuntimeError(f"지원하지 않는 배포본 항목: {item.name}")
            expected[relative] = value
    for relative, linked in hardlinks.items():
        expected[relative] = expected[linked]
    if len(roots) != 1:
        raise RuntimeError("IntelliJ 배포본 최상위 디렉터리가 하나가 아니다.")
    return expected


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
    archive = cache / f"{version}-{arch}-{checksum[:12]}.tar.gz"
    # stamp나 캐시 manifest는 신뢰하지 않는다. 매 소비 때 공식 고정 checksum에 연결한다.
    if not archive.is_file():
        with tempfile.TemporaryDirectory(prefix="download-", dir=cache) as temporary:
            staged = Path(temporary) / "idea.tar.gz"
            url = f"https://download.jetbrains.com/idea/idea-{version}{suffix}.tar.gz"
            with urllib.request.urlopen(url, timeout=60) as response, staged.open("wb") as output:
                shutil.copyfileobj(response, output)
            verified_archive(staged, checksum)
            staged.rename(archive)
    verified_archive(archive, checksum)
    expected = archive_inventory(archive)
    if destination.is_dir() and inventory(destination) == expected:
        return installation(destination, version, build)
    # 손상된 설치는 보관한 공식 archive에서 복구한다. 재다운로드하지 않는다.
    with tempfile.TemporaryDirectory(prefix="install-", dir=cache) as temporary:
        temporary = Path(temporary)
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
    parser.add_argument("--identity", action="store_true")
    args = parser.parse_args()
    binary = resolve(args.catalog, args.home, args.cache)
    print(binary)
    if args.identity:
        print(engine_identity(binary))
