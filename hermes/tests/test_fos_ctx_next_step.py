"""fos-ctx 가 모델에게 직접 쓰는 실패 글이 끝 줄 하나로 끝나는지 본다.

계약은 fos-assistant `docs/features/tool-fallback/spec.md` 의 REQ3 이다. 실패 글은 「다음 방법: …」 이나
「사용자에게 바로 알린다.」 로 끝나고, 사용자에게 넘기라고만 시키는 글이 남지 않는다.
"""

import sys
import unittest

import test_fos_ctx as base

# 모듈마다 모델에게 돌려주는 실패 글 상수다. 성공 글과 글을 잇는 틀(HEAD)은 넣지 않는다.
FAILURE_CONSTANTS = {
    "image_source": ("NOT_REFERENCE", "TOO_MANY", "UNKNOWN", "GONE", "GONE_GENERATED", "FAILED", "OLD_SERVER", "NO_CONTEXT"),
    "attachment_inspect": ("FAILURE", "INVALID_ARGS", "REGION_REQUIRED"),
    "generated_image": ("FAILED_MESSAGE",),
    "approval_guard": ("DANGEROUS_COMMAND_MESSAGE", "APPROVAL_WRITE_MESSAGE", "COMPUTER_USE_MESSAGE"),
    "connector_policy": ("POLICY_BLOCK_MESSAGE", "CONTEXT_BLOCK_MESSAGE", "UNKNOWN_SERVER_MESSAGE",
                         "ARGS_TOO_LARGE_MESSAGE", "CODE_EXECUTION_MESSAGE"),
    "tool_guard": ("GUARD_BLOCK_MESSAGE",),
    "hooks": ("SKILL_MANAGE_MESSAGE", "DELEGATE_IMAGE_MESSAGE", "BLOCK_MESSAGE"),
}


class FailureTextTest(unittest.TestCase):
    def setUp(self):
        self.plugin = base.load_ctx(self.addCleanup)

    def module(self, name):
        return sys.modules[self.plugin.__name__ + "." + name]

    def failure_texts(self):
        texts = {}
        for module, names in FAILURE_CONSTANTS.items():
            for name in names:
                texts[module + "." + name] = getattr(self.module(module), name)
        for code, text in self.module("image_source").LINK_REASONS.items():
            texts["image_source.LINK_REASONS." + code] = text
        return texts

    def test_every_failure_text_ends_with_a_step(self):
        """실패 글 상수가 모두 다음 방법이나 바로 알림 줄로 끝난다."""
        next_step = self.module("next_step")
        for name, text in self.failure_texts().items():
            with self.subTest(name=name):
                self.assertTrue(next_step.ends_with_step(text), text)

    def test_no_text_only_hands_over_to_the_user(self):
        """사진을 올려 달라고만 하고 끝나는 글이 없다."""
        for name, text in self.failure_texts().items():
            with self.subTest(name=name):
                self.assertFalse(text.rstrip().endswith("올려 달라고 한다"), text)

    def test_link_without_image_asks_for_a_photo_or_link(self):
        """링크에서 사진을 찾지 못하면 같은 링크가 아니라 다른 사진으로 다시 부르고, 더 없으면 사진이나 링크를 보내 달라고 먼저 알리게 한다."""
        text = self.module("image_source").LINK_REASONS["IMAGE_SOURCE_LINK_NO_IMAGE"]
        self.assertIn("다음 방법: 상품, 사물, 장소처럼 찾을 수 있는 대상이면 검색·읽기 결과에 나온 다른 사진", text)
        self.assertTrue(text.endswith("설명만으로 비슷하게 그려 넣지 말고, 사진을 찾지 못한 것과 "
                                      "사용자가 사진을 올리거나 링크를 보내면 된다는 것을 먼저 알린다"))

    def test_reference_photo_failures_do_not_draw_a_lookalike(self):
        """참고 사진을 쓰지 못한 글은 비슷하게 그려 넣지 말고 사진이나 링크를 보내 달라고 먼저 알리게 한다(스펙 REQ7)."""
        image_source = self.module("image_source")
        texts = [image_source.NOT_REFERENCE, image_source.UNKNOWN, image_source.FAILED,
                 *(image_source.LINK_REASONS[code] for code in
                   ("IMAGE_SOURCE_LINK_NO_IMAGE", "IMAGE_SOURCE_LINK_FAILED", "IMAGE_SOURCE_LINK_TOO_LARGE", "IMAGE_SOURCE_LINK_BLOCKED"))]
        for text in texts:
            with self.subTest(text=text):
                self.assertIn("비슷하게 그려 넣지", text)
        for text in (image_source.NOT_REFERENCE, image_source.UNKNOWN, image_source.LINK_REASONS["IMAGE_SOURCE_LINK_NO_IMAGE"]):
            with self.subTest(text=text):
                self.assertIn("사용자가 사진을 올리거나 링크를 보내면 된다는 것을 먼저 알린다", text)

    def test_blank_next_step_is_rejected(self):
        """다음 방법이 빈 실패 글은 만들 수 없다."""
        next_step = self.module("next_step")
        with self.assertRaises(ValueError):
            next_step.alternative("실패", " ")

    def test_message_indents_steps_and_ends_with_the_last_step(self):
        """막는 글은 문제 글의 끝 줄을 들여 쓰고 마지막 문제의 끝 줄로 끝난다."""
        image_source = self.module("image_source")
        text = image_source.message([image_source.UNKNOWN, image_source.GONE])
        lines = text.split("\n")
        self.assertEqual(lines[0], image_source.HEAD)
        self.assertTrue(lines[1].startswith("- 이 대화에 보낸 사진"))
        self.assertTrue(lines[2].startswith("  다음 방법: "))
        self.assertEqual(lines[-1], "  사용자에게 바로 알린다.")
        self.assertTrue(self.module("next_step").ends_with_step(text))


if __name__ == "__main__":
    unittest.main()
