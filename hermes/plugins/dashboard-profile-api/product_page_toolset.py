"""상품 페이지 도구의 toolset(`fos-product-page`)을 웹 검색(`web`)과 같은 profile 에서만 켜지게 한다.

Hermes 는 plugin 이 등록한 toolset 을 그 platform 의 `known_plugin_toolsets` 에 없으면 허용 목록과 상관없이 켠다.
그래서 API 경로의 `known_plugin_toolsets` 에 이 이름을 적어 두고, 웹 검색이 켜진 목록에만 이 이름을 함께 둔다.
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


def _with_product_page(allowed: list) -> list:
    """요청한 API 목록의 상품 페이지 toolset 을 `web` 에 맞춘 목록이다. `web` 이 있으면 더하고 없으면 뺀다.

    새 이름을 모르는 이전 Control Plane 이 `web` 만 보내도 상품 페이지 도구가 함께 켜진다(전환 배포).
    """
    names = [name for name in allowed if name != PRODUCT_PAGE_TOOLSET]
    if WEB_TOOLSET in names:
        names.append(PRODUCT_PAGE_TOOLSET)
    return names


def _keep_api_toolsets(path, sent: list, corrected: list) -> None:
    """Hermes 처리기가 요청 본문의 목록을 쓴 뒤, 그 목록을 보정한 목록으로 바꿔 쓴다.

    처리기는 본문만 읽어 plugin 이 고친 목록을 모른다. 파일의 목록이 보낸 목록 그대로일 때만 바꾼다.
    """
    try:
        saved = yaml.safe_load(path.read_bytes()) or {}
        platforms = saved.get("platform_toolsets") if isinstance(saved, dict) else None
        if not isinstance(platforms, dict) or platforms.get(API_PLATFORM) != sent:
            logger.warning("dashboard-profile-api: 저장한 API 목록이 보낸 목록과 달라 상품 페이지 보정을 쓰지 않았다")
            return
        updated = {**saved, "platform_toolsets": {**platforms, API_PLATFORM: corrected}}
        _atomic_private_write(path, yaml.safe_dump(updated, sort_keys=False, allow_unicode=True).encode())
    except Exception as error:
        # 처리기는 이미 저장했다. 보정을 쓰지 못하면 상품 페이지 도구만 꺼진 채로 남고 웹 검색 권한은 넓어지지 않는다.
        logger.warning("dashboard-profile-api: 상품 페이지 보정을 쓰지 못했다: %s", type(error).__name__)


def _reconciled(config: dict, listed, web_enabled: bool) -> dict:
    """이미 있는 profile 설정을 지금 계약에 맞춘다.

    `listed` 는 Hermes 가 해석한 API 허용 목록(문자열로 저장된 옛 형식도 목록으로 푼 것)이고, `web_enabled` 는
    `agent.disabled_toolsets` 까지 적용한 Hermes 의 최종 계산에서 웹 검색이 켜졌는가다.
    목록이 있으면 상품 페이지를 그 계산에 맞춰 넣거나 빼고 기록에 적는다. 목록이 없으면 Hermes 기본 묶음을 쓴다.
    그때 웹 검색이 켜졌으면 상품 페이지도 기본으로 켜지게 두고, 꺼졌으면 기록에 적어 끈다.
    """
    if not isinstance(listed, list):
        return config if web_enabled else _with_product_page_known(config)
    names = [str(name) for name in listed if str(name) != PRODUCT_PAGE_TOOLSET]
    if web_enabled:
        names.append(PRODUCT_PAGE_TOOLSET)
    updated = _with_product_page_known(config)
    platforms = config.get("platform_toolsets")
    if names != platforms.get(API_PLATFORM):
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
        from hermes_cli.tools_config import _coerce_platform_toolsets_value, _get_platform_tools
        from hermes_cli.web_server_profiles import _config_profile_scope

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
            platforms = saved.get("platform_toolsets")
            raw = platforms.get(API_PLATFORM) if isinstance(platforms, dict) else None
            # 계산 함수가 그 profile 의 plugin 과 비밀값을 읽으므로 대시보드 처리기와 같은 scope 에서 부른다.
            with _config_profile_scope(name):
                listed = _coerce_platform_toolsets_value(raw, API_PLATFORM)
                web_enabled = WEB_TOOLSET in _get_platform_tools(saved, API_PLATFORM)
            updated = _reconciled(saved, listed, web_enabled)
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
