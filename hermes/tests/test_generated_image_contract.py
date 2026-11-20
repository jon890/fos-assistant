# 코드 규칙: rule-64. 까닭과 예외는 docs/code-rules/INDEX.md를 본다.
"""만든 그림 올리기가 기대는 Hermes 의 동작이 그대로인지 본다(fos-assistant ADR-20261119 / generated-image).

fos-ctx 는 Hermes 의 이름을 새로 import 하지 않지만 아래 동작에 기댄다. Hermes 를 올릴 때 바뀌면 그림이 조용히 보이지 않게 되거나
제목 줄이 그림에 그려진다.

- `image_generate` 의 성공 결과가 `success` 와 `image` 를 갖는다
- 그림을 `$HERMES_HOME/cache/generated/images/` 에 쓴다
- `pre_tool_call` 의 `modify` 가 돌려준 `args` 가 내장 도구의 원래 인자를 덮는다
- `transform_tool_result` 가 도구 인자를 `args` 로 받는다
- 이미지 서비스를 profile 설정의 `image_gen.provider` 에서 읽고, 대시보드 plugin 이 넣는 서비스가 Hermes 에 있다
- `image_generate` 가 바탕과 참고를 `image_url` 과 `reference_image_urls` 로 받고, 그 경로를 media cache(`cache/`) 안에서는 호스트에서
  읽으며, 이미지 서비스가 16장까지 받는다(ADR-20261120 / image-source)

Hermes 없이 도는 검사는 plugin 의 값이 계약과 같은지만 본다.
Hermes 소스 검사는 `HERMES_SOURCE` 를 줄 때만 돈다. `scripts/checks/check-hermes-contract.sh` 가 이 값을 준다.
"""

import ast
import os
import pathlib
import unittest

import hermes_contract as contract
from plugin_loading import load_plugin


ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE_ENV = "HERMES_SOURCE"
REQUIRED_ENV = "HERMES_CONTRACT_REQUIRED"


class PluginValuesTest(unittest.TestCase):
    def test_plugin_matches_contract(self):
        """plugin 이 읽는 캐시 폴더와 도구 이름이 계약의 값과 같다."""
        plugin = load_plugin("fos_ctx_image_contract", ROOT / "plugins/fos-ctx/__init__.py", self.addCleanup)
        self.assertEqual(plugin.generated_image.CACHE_PARTS, contract.GENERATED_IMAGE_CACHE_PARTS)
        self.assertEqual(plugin.generated_image.TOOL, contract.GENERATED_IMAGE_TOOL)
        self.assertIn(contract.GENERATED_IMAGE_TOOL, contract.HERMES_BUILTIN_TOOLS)
        self.assertEqual(plugin.image_source.TOOL, contract.GENERATED_IMAGE_TOOL)
        self.assertEqual(plugin.image_source.MAX_SOURCES, contract.IMAGE_SOURCE_LIMIT[2])

    def test_dashboard_plugin_writes_the_contract_image_service(self):
        """대시보드 plugin 이 넣는 이미지 서비스의 자리와 이름이 계약의 값과 같다."""
        source = (ROOT / "plugins/dashboard-profile-api/image_service.py").read_text(encoding="utf-8")
        values = {node.targets[0].id: node.value.value for node in ast.parse(source).body
                  if isinstance(node, ast.Assign) and isinstance(node.value, ast.Constant)}
        self.assertEqual(
            (values["IMAGE_SERVICE_SECTION"], values["IMAGE_SERVICE_KEY"], values["IMAGE_SERVICE_PROVIDER"]),
            contract.IMAGE_SERVICE)


class HermesImageSourceTest(unittest.TestCase):
    """`HERMES_SOURCE` 의 Hermes 소스에서 그림 결과의 모양과 저장 폴더, hook 의 인자 처리를 대조한다."""

    @classmethod
    def setUpClass(cls):
        if not os.environ.get(SOURCE_ENV):
            if os.environ.get(REQUIRED_ENV):
                raise AssertionError("%s 가 없다" % SOURCE_ENV)
            raise unittest.SkipTest("%s 가 없어 Hermes 의 그림 결과 모양을 확인하지 않는다" % SOURCE_ENV)
        cls.root = pathlib.Path(os.environ[SOURCE_ENV]).resolve()

    def source(self, path):
        return (self.root / path).read_text(encoding="utf-8")

    def test_success_result_has_image_path(self):
        """provider 의 성공 응답이 `success` 와 `image` 를 갖는다."""
        path, function, keys = contract.GENERATED_IMAGE_RESULT
        tree = ast.parse(self.source(path))
        found = next(node for node in ast.walk(tree) if isinstance(node, ast.FunctionDef) and node.name == function)
        literal = {key.value for node in ast.walk(found) if isinstance(node, ast.Dict)
                   for key in node.keys if isinstance(key, ast.Constant)}
        self.assertTrue(keys <= literal, "성공 응답에 %s 가 없다" % sorted(keys - literal))

    def test_host_image_is_the_host_path(self):
        """실행 공간이 있으면 `host_image` 에 호스트 경로를 둔다. plugin 이 `image` 보다 먼저 읽는다."""
        path, line = contract.GENERATED_IMAGE_HOST_PATH
        self.assertIn(line, self.source(path))

    def test_images_are_written_under_generated_cache(self):
        """그림은 `cache/generated/images` 에 쓴다. plugin 은 이 폴더 밖의 경로를 읽지 않는다."""
        cache, subdir, kind = contract.GENERATED_IMAGE_CACHE_SOURCE
        media = self.source(cache)
        self.assertIn('GENERATED_SUBDIR = "%s"' % contract.GENERATED_IMAGE_CACHE_PARTS[1], media)
        self.assertIn('get_hermes_home() / "%s" / kind' % contract.GENERATED_IMAGE_CACHE_PARTS[0], media)
        self.assertIn(subdir, media)
        self.assertIn(kind, self.source(contract.GENERATED_IMAGE_RESULT[0]))

    def test_image_service_is_read_from_profile_config(self):
        """이미지 서비스는 `image_gen.provider` 에서 읽고, 넣는 서비스의 plugin 이 Hermes 에 있다."""
        path, call = contract.IMAGE_SERVICE_CONFIG_READ
        self.assertIn(call, self.source(path))
        path, key = contract.IMAGE_SERVICE_CONFIG_KEY
        self.assertIn(key, self.source(path))
        self.assertTrue((self.root / contract.IMAGE_SERVICE_PLUGIN).is_file(), "이미지 서비스 plugin 이 없다")

    def test_modify_overrides_original_args(self):
        """`modify` 의 `args` 는 원래 인자 뒤에 병합돼 같은 키를 덮는다. 내장 도구와 MCP 도구를 나누지 않는다."""
        path, marker, merge = contract.PRE_TOOL_CALL_MODIFY
        source = self.source(path)
        self.assertIn(marker, source)
        self.assertIn(merge, source)

    def test_image_generate_reads_both_source_args(self):
        """바탕은 `image_url`, 참고는 `reference_image_urls` 로 읽는다. plugin 이 이 두 칸을 사본 경로로 바꾼다."""
        path, call = contract.IMAGE_SOURCE_ARGS
        self.assertIn(call, self.source(path))

    def test_media_cache_is_read_on_host(self):
        """실행 공간이 있어도 Hermes 홈의 media cache 아래 경로는 호스트에서 읽는다. plugin 은 그 아래에 사본을 쓴다."""
        path, name = contract.IMAGE_SOURCE_HOST_READ
        tree = ast.parse(self.source(path))
        found = next(node.value for node in tree.body if isinstance(node, ast.Assign)
                     and any(isinstance(target, ast.Name) and target.id == name for target in node.targets))
        subdirs = {item.value for item in found.elts if isinstance(item, ast.Constant)}
        plugin = load_plugin("fos_ctx_image_source_contract", ROOT / "plugins/fos-ctx/__init__.py", self.addCleanup)
        self.assertIn(plugin.image_source.COPY_PARTS[0], subdirs)

    def test_image_service_takes_sixteen_sources(self):
        """이미지 서비스는 바탕과 참고를 합쳐 16장까지 받고 넘는 것은 버린다. plugin 은 넘으면 막는다."""
        path, name, limit, call = contract.IMAGE_SOURCE_LIMIT
        source = self.source(path)
        self.assertIn("%s = %d" % (name, limit), source)
        self.assertIn(call, source)

    def test_transform_hook_receives_args(self):
        """결과 hook 은 도구 인자를 `args` 로 받는다. plugin 이 여기서 지시문을 읽는다."""
        path, call = contract.TRANSFORM_RESULT_ARGS
        self.assertIn(call, self.source(path))


if __name__ == "__main__":
    unittest.main()
