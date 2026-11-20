"""모델에게 돌려주는 실패 글의 끝 줄을 만든다.

실패 글은 「다음 방법: …」 이나 「사용자에게 바로 알린다.」 한 줄로 끝난다(fos-assistant `docs/features/tool-fallback/spec.md` 의 REQ3).
두 끝 줄의 글은 Control Plane 의 `shared/util/ToolFailureText.java` 와 같다. Control Plane 이 보낸 판정 글에는 이미 끝 줄이 있으므로
fos-ctx 는 덧붙이지 않고, fos-ctx 가 직접 쓰는 글만 이 함수로 만든다.
"""

from __future__ import annotations

NEXT_PREFIX = "다음 방법: "
ASK_USER = "사용자에게 바로 알린다."


def alternative(message: str, next_step: str) -> str:
    """모델이 해 볼 다른 길이 있는 실패다. 다른 길이 없으면 next_step 에 해 본 것을 알리고 묻는다고 쓴다."""
    if not next_step or not next_step.strip():
        raise ValueError("다음 방법이 비었다")
    return message.rstrip() + "\n" + NEXT_PREFIX + next_step


def ask_user(message: str) -> str:
    """다른 방법을 찾지 않고 바로 사용자에게 알릴 실패다."""
    return message.rstrip() + "\n" + ASK_USER


def ends_with_step(text: str) -> bool:
    """앞뒤 공백을 걷어 낸 마지막 줄이 두 끝 줄 가운데 하나인지 본다."""
    last = text.rstrip().rsplit("\n", 1)[-1].strip()
    return last.startswith(NEXT_PREFIX) or last == ASK_USER
