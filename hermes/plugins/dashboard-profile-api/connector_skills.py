"""커넥터 스킬의 지침 본문과 profile 에 복사할 파일을 읽는다."""

from __future__ import annotations

import os
import pathlib
import re

from .common import (
    SKILL_NAME_RE,
)

from .connector_schema import (
    CONNECTOR_PERSONA_MAX_CHARS,
    CONNECTOR_SKILL_MAX_CHARS,
    CONNECTOR_SKILL_MAX_FILES,
    CONNECTOR_SKILL_PARTS,
)


def _connector_persona(skill_dirs: list) -> str | None:
    """스킬 디렉터리들의 `<스킬>/SKILL.md` 본문을 이름 순으로 이어 붙인다. 스킬이 없으면 None 이다.

    `SKILL.md` 밖의 파일은 읽지 않는다. 스킬 디렉터리나 `SKILL.md` 가 링크이면 읽지 않고 예외를 낸다.
    링크가 plugin 밖의 파일을 가리키면 그 내용이 모델의 지침으로 들어가기 때문이다.
    앞머리(frontmatter)는 뗀다. 합친 본문이 상한을 넘으면 예외다. 본문은 로그에 싣지 않는다.
    """
    bodies = []
    for directory in skill_dirs:
        for skill in sorted(directory.iterdir()):
            source = skill / "SKILL.md"
            if skill.is_symlink() or source.is_symlink():
                raise ValueError("스킬 디렉터리나 SKILL.md 가 링크다")
            if not skill.is_dir() or not source.is_file():
                continue
            # BOM 과 CRLF 가 있어도 앞머리를 알아보게 맞춘다.
            text = source.read_text(encoding="utf-8-sig").replace("\r\n", "\n")
            if text.startswith("---\n"):
                head, separator, rest = text[4:].partition("\n---\n")
                if not separator:
                    raise ValueError("SKILL.md 의 앞머리가 닫히지 않았다")
                text = rest
            if text.strip():
                bodies.append(text.strip())
    if not bodies:
        return None
    persona = "\n\n".join(bodies) + "\n"
    if len(persona) > CONNECTOR_PERSONA_MAX_CHARS:
        raise ValueError("스킬 본문이 %d자를 넘는다" % CONNECTOR_PERSONA_MAX_CHARS)
    return persona


def _skill_name(skill_md: str, fallback: str) -> str:
    """`SKILL.md` 앞머리의 `name` 이다. 없으면 디렉터리 이름이다. 경로 조각으로 쓸 수 없는 이름이면 예외다.

    Hermes 가 스킬 목록을 만들 때 같은 규칙으로 이름을 정한다(`tools/skills_tool.py` 의 `_find_all_skills`).

    앞머리가 환경 값이나 자격 증명 파일을 요청하면 예외다. Hermes 는 스킬을 읽을 때 그 칸의 이름으로
    profile `.env` 의 값과 profile 안의 파일을 셸 실행 공간에 넣는다. 바인딩 설치는 커넥터 값을 그 `.env` 에
    복사하므로 실행 공간이 커넥터 비밀을 받게 된다. 칸 목록은 Control Plane 이 올린 스킬에 거는 것과 같다(ADR-086).
    """
    import yaml
    text = skill_md.lstrip("﻿").replace("\r\n", "\n")
    name = fallback
    # 앞머리를 알아보는 규칙도 Hermes 와 같게 둔다(`agent/skill_utils.py` 의 `parse_frontmatter`).
    # 첫 줄이 `--- ` 처럼 정확히 `---` 가 아니어도 Hermes 는 앞머리로 읽으므로 여기서도 읽어 검사한다.
    if text.startswith("---"):
        closing = re.search(r"\n---\s*\n", text[3:])
        if closing is None:
            raise ValueError("SKILL.md 의 앞머리가 닫히지 않았다")
        head = text[3:3 + closing.start()]
        front = yaml.safe_load(head) if head.strip() else {}
        if not isinstance(front, dict):
            raise ValueError("SKILL.md 의 앞머리가 객체가 아니다")
        setup = front.get("setup")
        prerequisites = front.get("prerequisites")
        if ("required_environment_variables" in front or "required_credential_files" in front
                or (isinstance(setup, dict) and "collect_secrets" in setup)
                or (isinstance(prerequisites, dict) and "env_vars" in prerequisites)):
            raise ValueError("SKILL.md 의 앞머리가 환경 값이나 자격 증명 파일을 요청한다")
        name = front.get("name", fallback)
    # 설치와 떼기가 이 이름을 profile 의 스킬 디렉터리 이름으로 쓴다.
    if not isinstance(name, str) or not SKILL_NAME_RE.match(name) or ".." in name:
        raise ValueError("스킬 이름이 경로로 쓸 수 없는 모양이다")
    return name


def _connector_skills(skill_dirs: list) -> dict:
    """바인딩 설치가 profile 에 복사할 스킬이다. `{스킬 이름: {상대 경로: 파일 바이트}}` 다. 틀리면 예외다.

    스킬마다 `SKILL.md` 와 `references/`, `templates/` 아래 정규 파일을 읽는다.
    스킬 디렉터리 아래 어느 항목이든 링크이면 거절한다. 링크가 plugin 밖의 파일을 가리키면 그 내용이 profile 로 복사된다.
    UTF-8 로 읽히지 않는 파일, 상한을 넘는 스킬, 이름이 겹치는 스킬도 거절한다. 본문은 로그에 싣지 않는다.
    """
    skills = {}
    for directory in skill_dirs:
        for skill in sorted(directory.iterdir()):
            if skill.is_symlink():
                raise ValueError("스킬 디렉터리 아래에 링크가 있다")
            source = skill / "SKILL.md"
            if not skill.is_dir() or not source.is_file():
                continue
            for current, dirs, names in os.walk(skill):
                if any(os.path.islink(os.path.join(current, child)) for child in dirs + names):
                    raise ValueError("스킬 디렉터리 아래에 링크가 있다")
            paths = [source]
            for part in CONNECTOR_SKILL_PARTS:
                if (skill / part).is_dir():
                    for current, dirs, names in os.walk(skill / part):
                        dirs.sort()
                        paths.extend(pathlib.Path(current) / name for name in sorted(names)
                                     if (pathlib.Path(current) / name).is_file())
            if len(paths) > CONNECTOR_SKILL_MAX_FILES:
                raise ValueError("스킬 하나의 파일이 %d개를 넘는다" % CONNECTOR_SKILL_MAX_FILES)
            files = {}
            for path in paths:
                relative = path.relative_to(skill)
                if any(part in ("", ".", "..") for part in relative.parts):
                    raise ValueError("스킬 파일 경로에 . 이나 .. 이 있다")
                data = path.read_bytes()
                try:
                    text = data.decode("utf-8")
                except UnicodeDecodeError:
                    raise ValueError("스킬 파일이 UTF-8 이 아니다") from None
                if len(text) > CONNECTOR_SKILL_MAX_CHARS:
                    raise ValueError("스킬 파일이 %d자를 넘는다" % CONNECTOR_SKILL_MAX_CHARS)
                files[relative.as_posix()] = data
            name = _skill_name(files["SKILL.md"].decode("utf-8"), skill.name)
            if name in skills:
                raise ValueError("스킬 이름이 겹친다")
            skills[name] = files
    return skills
