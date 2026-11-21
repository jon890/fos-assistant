"""상품 페이지 도구의 toolset(`fos-product-page`)을 웹 검색(`web`)과 같은 profile 에서만 켜지게 한다.

Hermes 는 plugin 이 등록한 toolset 을 그 platform 의 `known_plugin_toolsets` 에 없으면 허용 목록과 상관없이 켠다.
그래서 API 경로의 `known_plugin_toolsets` 에 이 이름을 적어 두고, 허용 목록에 `web` 이 있을 때만 이 이름을 함께 둔다.
계약은 `hermes/docs/hermes-contract.md` 의 「plugin toolset 이름」 이 갖는다.
"""

from __future__ import annotations

import yaml

from .common import _atomic_private_write, logger


# fos-ctx 의 `product_page.TOOLSET` 과 같은 이름이다. 두 plugin 은 서로 import 하지 않는다.
PRODUCT_PAGE_TOOLSET = "fos-product-page"
WEB_TOOLSET = "web"
API_PLATFORM = "api_server"
MIGRATION_COUNTS = ("changed", "kept", "unreadable")


def _with_product_page_known(config: dict) -> dict:
    """API 경로의 `known_plugin_toolsets` 에 상품 페이지 toolset 을 더한 설정이다. 이미 있으면 그대로 돌려준다."""
    known = config.get("known_plugin_toolsets")
    known = dict(known) if isinstance(known, dict) else {}
    names = known.get(API_PLATFORM)
    names = [str(name) for name in names] if isinstance(names, list) else []
    if PRODUCT_PAGE_TOOLSET in names:
        return config
    known[API_PLATFORM] = sorted(names + [PRODUCT_PAGE_TOOLSET])
    return {**config, "known_plugin_toolsets": known}


def _product_page_rejection(allowed: list):
    """웹 검색 없이 상품 페이지 도구만 켜는 목록이면 까닭을 돌려준다."""
    if PRODUCT_PAGE_TOOLSET in allowed and WEB_TOOLSET not in allowed:
        return "상품 페이지 도구는 웹 검색과 함께만 켠다"
    return None


def _reconciled(config: dict) -> dict:
    """이미 있는 profile 설정을 지금 계약에 맞춘다. 허용 목록이 명시된 profile 만 바꾼다.

    허용 목록이 없으면 Hermes 의 API 기본 묶음을 써서 `web` 도 켜진다. 그때는 상품 페이지도 기본으로 켜져 그대로 둔다.
    """
    platforms = config.get("platform_toolsets")
    allowed = platforms.get(API_PLATFORM) if isinstance(platforms, dict) else None
    if not isinstance(allowed, list):
        return config
    names = [str(name) for name in allowed]
    if WEB_TOOLSET in names and PRODUCT_PAGE_TOOLSET not in names:
        names.append(PRODUCT_PAGE_TOOLSET)
    elif WEB_TOOLSET not in names and PRODUCT_PAGE_TOOLSET in names:
        names.remove(PRODUCT_PAGE_TOOLSET)
    updated = _with_product_page_known(config)
    if names != allowed:
        updated = {**updated, "platform_toolsets": {**platforms, API_PLATFORM: names}}
    return updated


def _migrate_product_page_toolset() -> dict:
    """기동 때 모든 profile 의 API 허용 목록을 상품 페이지 계약에 맞춘다. 수를 담은 dict 를 돌려준다.

    `register` 가 경로를 감싸기 전에 부른다. 그래서 요청과 겹치지 않는다. 다시 돌려도 바뀌는 것이 없다.
    예외를 밖으로 내지 않는다. 기동은 이어 간다.
    """
    counts = dict.fromkeys(MIGRATION_COUNTS, 0)
    try:
        from hermes_cli.profiles import get_profile_dir, list_profile_names

        names = sorted(list_profile_names())
    except Exception as error:
        logger.warning("dashboard-profile-api: 상품 페이지 toolset 이관이 profile 목록을 읽지 못했다: %s",
                       type(error).__name__)
        return counts
    for name in names:
        try:
            path = get_profile_dir(name) / "config.yaml"
            if path.is_symlink() or not path.is_file():
                continue
            saved = yaml.safe_load(path.read_bytes()) or {}
            if not isinstance(saved, dict):
                raise ValueError("profile 설정이 객체가 아니다")
            updated = _reconciled(saved)
            if updated == saved:
                counts["kept"] += 1
                continue
            _atomic_private_write(path, yaml.safe_dump(updated, sort_keys=False, allow_unicode=True).encode())
            counts["changed"] += 1
        except Exception as error:
            logger.warning("dashboard-profile-api: 상품 페이지 toolset 이관이 profile 설정을 다루지 못했다: %s",
                           type(error).__name__)
            counts["unreadable"] += 1
    logger.info("dashboard-profile-api: 상품 페이지 toolset 이관: 바꿈 %d, 그대로 %d, 읽지 못함 %d",
                counts["changed"], counts["kept"], counts["unreadable"])
    return counts
