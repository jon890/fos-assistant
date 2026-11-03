#!/usr/bin/env python3
"""hermes/tests 의 test_*.py 를 모듈마다 프로세스 하나로 나눠 동시에 돌린다.

`python3 -m unittest discover -s hermes/tests` 와 같은 모듈을 같은 방식으로 돌리되,
테스트 대부분이 자식 프로세스(Bun 서버, MCP 도구)를 띄우고 기다리는 시간이라
프로세스를 나누면 벽시계 시간이 줄어든다. 환경 변수와 작업 디렉터리를 바꾸는 테스트도
모듈마다 별도 프로세스라 서로 건드리지 않는다.

사용법:
  python3 scripts/checks/run-hermes-python-tests.py [-j 동시수] [모듈 파일 이름...]

- 동시 수의 기본값은 CPU 수와 4 중 작은 값이다. `-j 1` 이면 차례로 돈다.
- 모듈 이름을 주면(예: test_fos_ctx.py) 그것만 돈다.
- 통과한 모듈은 한 줄만 보이고, 실패한 모듈은 그 출력을 모두 보인다.
- 하나라도 실패하면 종료 코드 1 이다. 돈 테스트가 없는 모듈도 실패다(unittest 의 종료 코드 5).
"""
import argparse
import os
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TESTS = ROOT / "hermes" / "tests"


def modules(selected: list[str]) -> list[Path]:
    found = sorted(TESTS.glob("test*.py"))
    if selected:
        names = {Path(name).name for name in selected}
        missing = names - {path.name for path in found}
        if missing:
            sys.exit(f"없는 모듈이다: {', '.join(sorted(missing))}")
        found = [path for path in found if path.name in names]
    # 큰 파일을 먼저 시작해야 마지막에 긴 모듈 하나만 남는 일이 줄어든다.
    return sorted(found, key=lambda path: path.stat().st_size, reverse=True)


def run(module: Path) -> tuple[Path, int, float, str]:
    started = time.monotonic()
    done = subprocess.run(
        [sys.executable, "-m", "unittest", "discover", "-s", str(TESTS), "-p", module.name],
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        errors="replace",
    )
    return module, done.returncode, time.monotonic() - started, done.stdout


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("-j", "--jobs", type=int, default=min(4, os.cpu_count() or 1))
    parser.add_argument("modules", nargs="*")
    args = parser.parse_args()
    targets = modules(args.modules)
    if not targets:
        sys.exit("돌릴 모듈이 없다")
    started = time.monotonic()
    failed: list[Path] = []
    with ThreadPoolExecutor(max_workers=max(1, args.jobs)) as pool:
        for future in as_completed([pool.submit(run, path) for path in targets]):
            module, code, seconds, output = future.result()
            if code == 0:
                print(f"통과 {module.name} ({seconds:.1f}s)", flush=True)
            else:
                failed.append(module)
                print(f"실패 {module.name} (종료 코드 {code}, {seconds:.1f}s)\n{output}", flush=True)
    elapsed = time.monotonic() - started
    print(f"모듈 {len(targets)}개를 {min(max(1, args.jobs), len(targets))}개 프로세스로 {elapsed:.1f}초에 돌렸다. 실패 {len(failed)}개", flush=True)
    if failed:
        print("실패한 모듈: " + ", ".join(path.name for path in failed), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
