"""타입·표기 검사 뒤에 변경된 prepare 칸의 의미 보존을 판정한다."""


def preserves(field, before, after):
    if not isinstance(after, str):
        return False
    if field == "timeInForce":
        return before is None and after == "DAY"
    if not isinstance(before, str):
        return False
    if field == "symbol":
        return after == before.upper()
    if field in {"quantity", "orderAmount", "price"}:
        # 양의 decimal 구문과 길이는 기존 모델·summary 검사가 맡는다.
        whole, _, fraction = before.partition(".")
        integer = whole.lstrip("0") or "0"
        tail = fraction.rstrip("0")
        return after == integer + ("." + tail if tail else "")
    return False
