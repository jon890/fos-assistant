"""에이전트용 스킬 가운데 설치할 것과 빠질 것을 정하는 판정을 검사한다(ADR-20261120 plugin-agent-skills)."""

import pathlib
import tempfile

from test_connector_manifest import ConnectorGateCase

RUNNER = "demo-runner"
REPORT = "demo-report"
MANIFEST = {"skills": {"demo": {}, REPORT: {}, RUNNER: {}},
            "agent_skills": {REPORT: frozenset(), RUNNER: frozenset({"CLAUDE_PLUGIN_ROOT", "CAREER_BACKEND_URL"})}}


def shell(env=None, terminal=True, docker=True):
    """셸 조건을 갖춘 profile 설정이다. 인자로 하나씩 뺀다."""
    config = {"platform_toolsets": {"api_server": ["fos-assistant", "skills"] + (["terminal"] if terminal else [])}}
    if docker:
        config["terminal"] = {"backend": "docker"}
        if env is not None:
            config["terminal"]["docker_env"] = env
    else:
        config["terminal"] = {"backend": "local"}
    return config


FULL_ENV = {"CLAUDE_PLUGIN_ROOT": "/plugin", "CAREER_BACKEND_URL": "http://backend.test"}


class AgentSkillPlanTest(ConnectorGateCase):
    def setUp(self):
        super().setUp()
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.profile = pathlib.Path(tmp.name).resolve() / "alice"
        (self.profile / "skills").mkdir(parents=True)

    def plan(self, config, owned=()):
        return self.plugin._agent_skill_plan(self.profile, config, MANIFEST, list(owned))

    def test_skill_without_required_env_ignores_the_shell(self):
        """요구 env 가 없는 스킬은 터미널이 꺼져 있어도 설치한다. 플러그인 스킬은 판정하지 않는다."""
        install, skipped = self.plan(shell(terminal=False, docker=False))
        self.assertEqual(install, {REPORT})
        self.assertNotIn("demo", skipped)

    def test_runner_is_installed_only_when_the_shell_passes_every_name(self):
        install, skipped = self.plan(shell(env=FULL_ENV))
        self.assertEqual(install, {REPORT, RUNNER})
        self.assertEqual(skipped, {})

    def test_reasons_come_in_order_and_all_at_once(self):
        """터미널, 실행 공간, env 순서로 해당하는 까닭을 모두 낸다."""
        cases = (
            (shell(env=FULL_ENV, terminal=False), ["terminal_off"]),
            (shell(env=None), ["sandbox_env_missing"]),
            (shell(env={"CLAUDE_PLUGIN_ROOT": "/plugin"}), ["sandbox_env_missing"]),
            (shell(terminal=False, docker=False),
             ["terminal_off", "sandbox_unregistered", "sandbox_env_missing"]),
        )
        for config, reasons in cases:
            with self.subTest(reasons=reasons):
                install, skipped = self.plan(config)
                self.assertEqual(install, {REPORT})
                self.assertEqual(skipped, {RUNNER: reasons})

    def test_name_taken_by_a_profile_skill_the_plugin_does_not_own(self):
        """profile `skills/` 의 같은 이름은 이 플러그인이 설치한 것이면 겹침이 아니다."""
        (self.profile / "skills" / REPORT).mkdir()
        self.assertEqual(self.plan(shell(env=FULL_ENV))[1], {REPORT: ["name_taken"]})
        self.assertEqual(self.plan(shell(env=FULL_ENV), owned=[REPORT])[1], {})

    def test_name_taken_by_an_uploaded_skill_regardless_of_ownership(self):
        """`external_dirs` 의 같은 이름은 소유 기록과 상관없이 겹침이다. 문자열 하나로 둔 경로도 읽는다."""
        uploaded = self.profile.parent / "uploaded" / "alice" / "v1"
        (uploaded / RUNNER).mkdir(parents=True)
        (uploaded / RUNNER / "SKILL.md").write_text("---\nname: %s\n---\n올린 스킬\n" % RUNNER, encoding="utf-8")
        for external in ([str(uploaded)], str(uploaded)):
            with self.subTest(external=type(external).__name__):
                config = {**shell(env=FULL_ENV), "skills": {"external_dirs": external}}
                install, skipped = self.plan(config, owned=[RUNNER])
                self.assertEqual(install, {REPORT})
                self.assertEqual(skipped, {RUNNER: ["name_taken"]})

    def test_name_taken_by_a_skill_under_a_category_folder(self):
        """분류 폴더 아래 스킬도 디렉터리 이름이나 앞머리 `name` 이 같으면 겹침이다. Hermes 의 `skill_view` 가 둘 다 고르지 못한다."""
        nested = self.profile / "skills" / "local" / "my-report"
        nested.mkdir(parents=True)
        (nested / "SKILL.md").write_text("---\nname: %s\ndescription: 모델이 만든 스킬\n---\n본문\n" % REPORT,
                                         encoding="utf-8")
        self.assertEqual(self.plan(shell(env=FULL_ENV), owned=[REPORT])[1], {REPORT: ["name_taken"]})
        (nested / "SKILL.md").write_text("본문\n", encoding="utf-8")
        nested.rename(self.profile / "skills" / "local" / RUNNER)
        self.assertEqual(self.plan(shell(env=FULL_ENV))[1], {RUNNER: ["name_taken"]})

    def test_only_the_shell_backend_is_missing(self):
        """터미널과 env 가 있어도 셸 설정이 실행 공간이 아니면 그 까닭 하나만 낸다."""
        config = shell(env=FULL_ENV)
        config["terminal"]["backend"] = "local"
        self.assertEqual(self.plan(config)[1], {RUNNER: ["sandbox_unregistered"]})

    def test_malformed_config_does_not_raise(self):
        """설정 칸의 모양이 틀려도 예외 없이 조건을 못 채운 것으로 본다."""
        for config in ({}, {"platform_toolsets": "x", "terminal": [], "skills": {"external_dirs": [1, None]}}, None):
            with self.subTest(config=config):
                install, skipped = self.plan(config)
                self.assertEqual(install, {REPORT})
                self.assertIn("terminal_off", skipped[RUNNER])
