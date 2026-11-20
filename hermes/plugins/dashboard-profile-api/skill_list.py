"""Hermes 스킬 목록에 profile 이 기록한 플러그인 설치 출처를 더한다."""

import asyncio
import json

from .common import logger
from .connector_guard import _strict_json
from .connector_manifest import _connector_manifest
from .connector_schema import CONNECTOR_STATE
from .connector_state import PROFILE_SKILLS_DIR, _connector_state


def _skill_owners(profile):
    from hermes_cli.profiles import get_profile_dir

    directory = get_profile_dir(profile)
    try:
        state = _connector_state(_strict_json((directory / CONNECTOR_STATE).read_bytes()))
    except FileNotFoundError:
        return {}
    except (OSError, ValueError):
        logger.warning("dashboard-profile-api: 스킬 설치 출처를 읽지 못했다")
        return {}

    owners = {}
    for plugin, entry in state.items():
        owned = [name for name in entry["skills"]
                 if (directory / PROFILE_SKILLS_DIR / name / "SKILL.md").is_file()]
        if owned:
            manifest = _connector_manifest(plugin)
            title = manifest["title"] if manifest else plugin
            owners.update((name, (plugin, title)) for name in owned)
    return owners


async def _with_skill_owners(profile, response):
    from starlette.responses import JSONResponse

    owners = await asyncio.to_thread(_skill_owners, profile)
    if not owners:
        return response

    payload = json.loads(b"".join([chunk async for chunk in response.body_iterator]))
    for skill in payload:
        owner = owners.get(skill["name"])
        if owner is not None:
            skill["connector_id"], skill["connector_title"] = owner
    headers = dict(response.headers)
    headers.pop("content-length", None)
    return JSONResponse(payload, headers=headers)
