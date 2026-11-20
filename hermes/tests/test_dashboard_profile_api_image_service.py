"""dashboard-profile-api 가 「그림 만들기」 를 켜는 도구 저장에서 이미지 서비스를 함께 넣는지 검사한다."""

import yaml
import dashboard_profile_api_support as support


class ProfileApiImageServiceTest(support.ProfileApiRouteTest):
    def image_body(self, ensure=True):
        body = self.toolset_body()
        body["config"]["platform_toolsets"]["api_server"] = ["delegation", "fos-assistant", "image_gen"]
        body["sandbox_owner"] = "user-1"
        if ensure is not None:
            body["ensure_image_service"] = ensure
        return body

    def write_image_section(self, section):
        path = self.root / "owner/config.yaml"
        config = yaml.safe_load(path.read_text(encoding="utf-8"))
        config["image_gen"] = section
        path.write_text(yaml.safe_dump(config), encoding="utf-8")
        return path

    def test_enabling_image_gen_writes_the_image_service(self):
        """이미지 서비스가 없던 profile 에 「그림 만들기」 를 켜면 같은 쓰기에서 서비스가 들어간다."""
        self.assertNotIn("image_gen", self.saved_config())

        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.image_body()), 200)

        saved = self.saved_config()
        self.assertEqual(saved["image_gen"], {"provider": "openai-codex"})
        self.assertIn("image_gen", saved["platform_toolsets"]["api_server"])
        self.assertEqual(saved["terminal"]["backend"], "docker")

    def test_existing_image_service_is_not_overwritten(self):
        """운영자가 넣은 서비스와 `image_gen` 의 다른 키는 그대로 둔다."""
        self.write_image_section({"provider": "fal", "model": "custom"})

        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.image_body()), 200)

        self.assertEqual(self.saved_config()["image_gen"], {"provider": "fal", "model": "custom"})

    def test_blank_provider_is_filled_and_other_keys_stay(self):
        """서비스 칸만 빈 설정은 서비스를 채우고 나머지 키를 지킨다."""
        self.write_image_section({"provider": " ", "use_gateway": False})

        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.image_body()), 200)

        self.assertEqual(self.saved_config()["image_gen"], {"provider": "openai-codex", "use_gateway": False})

    def test_request_without_the_flag_leaves_the_image_service_alone(self):
        """표시가 없는 옛 Control Plane 의 저장은 지금처럼 toolset 만 쓴다."""
        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.image_body(ensure=None)), 200)

        self.assertNotIn("image_gen", self.saved_config())

    def test_flag_is_rejected_without_writing_when_it_cannot_apply(self):
        """표시가 참 거짓이 아니거나 목록에 image_gen 이 없으면 400 이고 아무것도 쓰지 않는다."""
        not_bool = self.image_body(ensure="yes")
        without_toolset = self.toolset_body()
        without_toolset["ensure_image_service"] = True
        skills_only = self.skills_body([], None)
        skills_only["ensure_image_service"] = True
        path = self.root / "owner/config.yaml"
        original = path.read_bytes()
        for label, body in (("not bool", not_bool), ("no image_gen", without_toolset), ("no toolset list", skills_only)):
            with self.subTest(label=label):
                self.assertEqual(self.request("/api/config", "PUT", token="valid", body=body), 400)
                self.assertEqual(path.read_bytes(), original)

    def test_broken_image_section_fails_the_save(self):
        """`image_gen` 이 객체가 아니면 덮어쓰지 않고 저장을 실패로 답한다. 도구도 켜지지 않는다."""
        path = self.write_image_section("openai-codex")
        original = path.read_bytes()

        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.image_body()), 500)

        self.assertEqual(path.read_bytes(), original)

    def test_image_service_is_restored_when_the_handler_fails(self):
        """plugin 이 먼저 쓴 이미지 서비스는 처리기가 실패하면 원래 바이트로 되돌린다."""
        path = self.root / "owner/config.yaml"
        original = path.read_bytes()
        self.handler_status = 500

        self.assertEqual(self.request("/api/config", "PUT", token="valid", body=self.image_body()), 500)

        self.assertEqual(path.read_bytes(), original)

    def test_unregistered_profile_is_refused_before_the_image_service(self):
        """실행 공간 정책에 없는 profile 은 409 로 거절하고 이미지 서비스도 쓰지 않는다."""
        self.set_sandbox_policy(self.sandbox_policy(profiles={"alice": {}}))
        path = self.root / "owner/config.yaml"
        original = path.read_bytes()

        response = self.request("/api/config", "PUT", token="valid", body=self.image_body(), full_response=True)

        self.assertEqual(response.status_code, 409)
        self.assertEqual(response.body["code"], "sandbox_unavailable")
        self.assertEqual(path.read_bytes(), original)
