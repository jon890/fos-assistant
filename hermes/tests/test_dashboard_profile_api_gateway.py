"""dashboard-profile-api 의 공유 gateway 기동 시각 조회를 검사한다(ADR-20261120 / connector-auto-apply)."""

import os
import pathlib
import sys
import tempfile
from unittest import mock

import dashboard_profile_api_support as support

PID = 4242
BOOT = 1_763_600_000
TICKS = os.sysconf("SC_CLK_TCK")


class ProfileApiGatewayTest(support.ProfileApiRouteTest):
    def setUp(self):
        super().setUp()
        self.calls = []
        self.proc_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.proc_dir.cleanup)
        self.proc = pathlib.Path(self.proc_dir.name)
        self.pid = PID
        self.gateway_status.get_running_pid = self.running_pid
        self.addCleanup(setattr, self.gateway_status, "get_running_pid",
                        lambda pid_path=None, cleanup_stale=True: None)

    def running_pid(self, pid_path=None, cleanup_stale=True):
        self.calls.append((pid_path, cleanup_stale))
        return self.pid

    def write_proc(self, start_ticks, comm="hermes"):
        (self.proc / "stat").write_text("cpu  1 2 3\nbtime %d\nprocesses 9\n" % BOOT, encoding="ascii")
        (self.proc / str(PID)).mkdir(exist_ok=True)
        # comm 뒤 칸은 state(0) 부터 센다. 시작 tick 은 그 19번째 칸이다.
        rest = ["S"] + ["0"] * 18 + [str(start_ticks), "0", "0"]
        (self.proc / str(PID) / "stat").write_text("%d (%s) %s\n" % (PID, comm, " ".join(rest)), encoding="utf-8")

    def gateway(self, token="valid", method="GET", path="/api/gateway", cookie=False):
        module = sys.modules[self.plugin.__name__ + ".gateway_status"]
        with mock.patch.object(module, "PROC_ROOT", self.proc):
            return self.request(path, method, token=token, cookie=cookie, full_response=True)

    def test_started_at_is_boot_time_plus_start_ticks(self):
        """기동 시각은 `/proc/stat` 의 btime 에 그 pid 의 시작 tick 을 초로 더한 정수다."""
        self.write_proc(start_ticks=TICKS * 125 + TICKS // 2)
        response = self.gateway()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.body, {"started_at": BOOT + 125})

    def test_reads_pid_without_cleanup_from_default_root(self):
        """기본 root 의 pid 파일을 정리 없는 부름으로만 읽는다. 인자 없는 부름은 낡은 파일을 지울 수 있다."""
        self.write_proc(start_ticks=TICKS)
        self.gateway()
        self.assertEqual(self.calls, [(pathlib.Path("/opt/data") / "gateway.pid", False)])

    def test_comm_with_parentheses_and_spaces_is_read(self):
        """프로세스 이름에 공백과 괄호가 들어가도 마지막 괄호 뒤부터 칸을 센다."""
        self.write_proc(start_ticks=TICKS * 7, comm="hermes ) (gateway")
        self.assertEqual(self.gateway().body, {"started_at": BOOT + 7})

    def test_started_at_is_null_without_gateway_pid(self):
        """gateway 가 떠 있지 않으면 200 에 null 이다."""
        self.pid = None
        self.write_proc(start_ticks=TICKS)
        response = self.gateway()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.body, {"started_at": None})

    def test_started_at_is_null_when_proc_is_unreadable(self):
        """`/proc` 를 읽지 못하면 null 이다. 예외를 응답으로 내지 않는다."""
        response = self.gateway()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.body, {"started_at": None})

    def test_requires_control_plane_token(self):
        self.write_proc(start_ticks=TICKS)
        self.assertEqual(self.gateway(token=None).status_code, 401)
        self.assertEqual(self.calls, [])

    def test_other_gateway_routes_are_not_intercepted(self):
        """Hermes 대시보드의 다른 gateway 경로와 다른 메서드는 가로채지 않고 사람의 쿠키 검사로 넘긴다."""
        self.write_proc(start_ticks=TICKS)
        for method, path in (("POST", "/api/gateway"), ("POST", "/api/gateway/start")):
            response = self.gateway(token=None, method=method, path=path, cookie=True)
            self.assertEqual(response.status_code, 200)
            self.assertFalse(hasattr(response, "body"))
        self.assertEqual(self.calls, [])
