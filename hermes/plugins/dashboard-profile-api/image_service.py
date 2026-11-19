from __future__ import annotations


# 「그림 만들기」 toolset 이다. 이 이름이 API 도구 목록에 있을 때만 이미지 서비스를 넣는다.
IMAGE_TOOLSET = "image_gen"
# profile 설정에서 이미지 서비스를 고르는 자리다. Hermes 는 `image_gen.provider` 가 있어야 `image_generate` 를 모델에 보인다
# (`hermes/docs/hermes-contract.md` 의 「`image_generate` 의 결과와 그림 파일」).
IMAGE_SERVICE_SECTION = "image_gen"
IMAGE_SERVICE_KEY = "provider"
# 넣는 이미지 서비스다. ChatGPT 구독의 gpt-image-2 하나다(fos-assistant ADR-20261119 / image-gen-defaults).
IMAGE_SERVICE_PROVIDER = "openai-codex"


def _with_image_service(saved: dict, updated: dict) -> dict:
    """이미지 서비스가 없는 profile 설정에 서비스를 넣은 설정을 돌려준다. 이미 있으면 그대로 돌려준다.

    운영자가 넣은 서비스와 `image_gen` 의 다른 키는 덮어쓰지 않는다. 인자는 바꾸지 않는다.
    """
    section = saved.get(IMAGE_SERVICE_SECTION)
    # 키가 없거나 값이 비었을 때(None)만 빈 객체로 본다. 객체가 아닌 값은 덮어쓰지 않고 실패한다.
    if section is None:
        section = {}
    elif not isinstance(section, dict):
        raise ValueError("image_gen 설정이 객체가 아니다")
    provider = section.get(IMAGE_SERVICE_KEY)
    if isinstance(provider, str) and provider.strip():
        return updated
    return {**updated, IMAGE_SERVICE_SECTION: {**section, IMAGE_SERVICE_KEY: IMAGE_SERVICE_PROVIDER}}
