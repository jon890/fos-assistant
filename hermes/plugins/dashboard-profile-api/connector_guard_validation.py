"""원문·SDK 인자와 금융 준비 결과의 키·타입·표시 값을 대조한다."""

from __future__ import annotations

import base64
import hashlib
import json
import re

from .connector_guard import HEX, PROTOCOL, _strict_json


def _require(condition):
    if not condition:
        raise ValueError("금융 입력을 검증하지 못했다")


def _bounded(value, limit, depth=0):
    _require(depth <= 5)
    if isinstance(value, dict):
        _require(all(isinstance(key, str) for key in value))
        for item in value.values():
            _bounded(item, limit, depth + 1)
    elif isinstance(value, list):
        for item in value:
            _bounded(item, limit, depth + 1)
    else:
        _require(value is None or type(value) in (str, bool, int, float))
    if depth == 0:
        _require(len(json.dumps(value, ensure_ascii=False, allow_nan=False).encode("utf-8")) <= limit)


def _same_json(left, right):
    if type(left) is not type(right):
        return False
    if isinstance(left, dict):
        return left.keys() == right.keys() and all(_same_json(left[key], right[key]) for key in left)
    if isinstance(left, list):
        return len(left) == len(right) and all(_same_json(a, b) for a, b in zip(left, right))
    return left == right


def _execution_env(execution, args, context=None):
    _require(isinstance(execution, dict) and set(execution) == {"v", "protocol", "ticket", "argsJson", "argsSha256"})
    _require(type(execution["v"]) is int and execution["v"] == 1 and execution["protocol"] == PROTOCOL)
    raw, digest, ticket = execution["argsJson"], execution["argsSha256"], execution["ticket"]
    _require(isinstance(raw, str) and len(raw.encode("utf-8")) <= 16384
             and isinstance(digest, str) and HEX.fullmatch(digest)
             and hashlib.sha256(raw.encode("utf-8")).hexdigest() == digest)
    parsed = _strict_json(raw)
    _require(isinstance(parsed, dict) and _same_json(parsed, args))
    _bounded(parsed, 16384)
    _require(isinstance(ticket, str) and 1 <= len(ticket) <= 4096
             and re.fullmatch(r"[A-Za-z0-9_-]+\.[A-Za-z0-9_-]{43}", ticket))
    payload, signature = ticket.split(".")
    decoded = base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4))
    _require(len(decoded) <= 3072 and base64.urlsafe_b64encode(decoded).decode().rstrip("=") == payload)
    sig = base64.urlsafe_b64decode(signature + "=")
    _require(len(sig) == 32 and base64.urlsafe_b64encode(sig).decode().rstrip("=") == signature)
    claims = _strict_json(decoded)
    _require(isinstance(claims, dict) and set(claims) == {
        "v", "ticketId", "actionId", "userId", "agentId", "connectionId", "bindingId", "profile", "connectorId",
        "tool", "argsSha256", "scopeSha256", "issuedAt", "expiresAt"})
    _require(type(claims["v"]) is int and claims["v"] == 1 and claims["argsSha256"] == digest)
    _require(all(type(claims[key]) is int and 0 < claims[key] <= 9223372036854775807
                 for key in ("userId", "agentId", "connectionId", "bindingId")))
    import uuid
    for key in ("ticketId", "actionId"):
        _require(isinstance(claims[key], str) and str(uuid.UUID(claims[key])) == claims[key])
    for key, limit in (("profile", 64), ("connectorId", 64), ("tool", 128)):
        _require(isinstance(claims[key], str) and 1 <= len(claims[key]) <= limit)
    _require(isinstance(claims["scopeSha256"], str) and HEX.fullmatch(claims["scopeSha256"]))
    from .connector_guard import REVISION, _guard_instant
    for key in ("issuedAt", "expiresAt"):
        _require(isinstance(claims[key], str) and REVISION.fullmatch(claims[key]))
    issued, expires = (_guard_instant(claims[key]) for key in ("issuedAt", "expiresAt"))
    _require(0 < (expires - issued).total_seconds() <= 60)
    if context is not None:
        _require(all(_same_json(claims.get(key), value) for key, value in context.items()))
    # 인증·만료·현재 권한의 최종 판정은 실제 claim producer가 맡는다.
    return {"FOS_APPROVAL_TICKET": ticket, "FOS_APPROVAL_ARGS_JSON": raw}


SUMMARY = set("account symbol market currency side quantity orderAmount price orderType timeInForce operation orderId original normalization".split())
ORIGINAL = set("orderId symbol market currency side quantity orderAmount price orderType timeInForce status filledQuantity".split())
NORMALIZED = {"symbol", "quantity", "orderAmount", "price", "timeInForce"}


def _decimal(value, zero=False):
    _require(isinstance(value, str) and re.fullmatch(r"[0-9]+(?:\.[0-9]+)?", value) and len(value) <= 30)
    from decimal import Decimal
    _require(Decimal(value) >= 0 if zero else Decimal(value) > 0)


def _display_text(value, maximum):
    _require(isinstance(value, str))
    value.encode("utf-8")
    # producer의 Java String.length와 ISO control 판정을 따른다. 본문 byte 상한은 _bounded가 맡는다.
    _require(1 <= len(value.encode("utf-16-le")) // 2 <= maximum
             and not any(ord(ch) <= 0x1f or 0x7f <= ord(ch) <= 0x9f for ch in value))


def _order(value, original=False):
    _require(isinstance(value["symbol"], str) and re.fullmatch(r"[A-Za-z0-9.-]{1,32}", value["symbol"]))
    _display_text(value["market"], 16)
    _require(isinstance(value["currency"], str) and re.fullmatch(r"[A-Z]{3}", value["currency"]))
    _require(value["side"] in ("BUY", "SELL") and value["orderType"] in ("LIMIT", "MARKET")
             and value["timeInForce"] in ("DAY", "CLS", "OPG"))
    for field in ("quantity", "orderAmount", "price"):
        if value[field] is not None:
            _decimal(value[field])
    _require((value["price"] is not None) == (value["orderType"] == "LIMIT"))
    _require(value["orderAmount"] is None or value["orderType"] == "MARKET")
    if original:
        _require(value["quantity"] is not None)
        _display_text(value["status"], 32)
        _decimal(value["filledQuantity"], zero=True)


def _prepare_result(payload, original_args, manifest, tool, env):
    _bounded(original_args, 16384)
    _require(isinstance(payload, dict) and set(payload) == {"v", "executionArgs", "summary"}
             and type(payload["v"]) is int and payload["v"] == 1)
    _bounded(payload, 32768)
    args, summary = payload["executionArgs"], payload["summary"]
    _require(isinstance(args, dict) and isinstance(summary, dict) and set(summary) == SUMMARY)
    _bounded(args, 16384)
    _bounded(summary, 8192)
    operation = manifest["execution_guard"]["operations"][tool]
    allowed = ({"symbol", "side", "orderType", "quantity", "orderAmount", "price", "timeInForce", "confirmHighValueOrder"}
               if operation == "CREATE" else {"orderId", "orderType", "quantity", "price", "confirmHighValueOrder"}
               if operation == "MODIFY" else {"orderId"})
    _require(set(original_args) <= allowed)
    mandatory = {"symbol", "side", "orderType"} if operation == "CREATE" else {"orderId", "orderType"} if operation == "MODIFY" else {"orderId"}
    _require(mandatory <= set(original_args))
    if operation != "CANCEL":
        _require(original_args["orderType"] in ("LIMIT", "MARKET")
                 and (original_args["orderType"] == "LIMIT") == ("price" in original_args))
        _require(("quantity" in original_args) != ("orderAmount" in original_args) if operation == "CREATE"
                 else "orderAmount" not in original_args)
        for field in {"quantity", "orderAmount", "price"} & set(original_args):
            _decimal(original_args[field])
        if "timeInForce" in original_args:
            _require(original_args["timeInForce"] in ("DAY", "CLS", "OPG"))
    if operation == "CREATE":
        _require(isinstance(original_args["symbol"], str) and re.fullmatch(r"[A-Za-z0-9.-]{1,32}", original_args["symbol"])
                 and original_args["side"] in ("BUY", "SELL"))
    else:
        _require(isinstance(original_args["orderId"], str) and 1 <= len(original_args["orderId"]) <= 256)
    _require(summary["operation"] == operation and isinstance(summary["account"], str)
             and re.fullmatch(r"[0-9]{4}", summary["account"]))
    _order(summary)
    scopes = manifest["execution_guard"]["scope_fields"]
    fields = {field["key"]: field for field in manifest["fields"]}
    scope_keys = {field["arg"] for field in scopes}
    for field in scopes:
        value = args.get(field["arg"])
        _require(isinstance(value, str) and 1 <= len(value.encode("utf-8")) <= 128
                 and value == env.get(fields[field["field"]]["env"]))
    common = scope_keys | {"market", "currency"}
    if operation == "CREATE":
        expected = common | {"symbol", "side", "orderType", "timeInForce", "clientOrderId"}
        _require(("quantity" in args) != ("orderAmount" in args))
        expected |= {"quantity"} if "quantity" in args else {"orderAmount"}
        if summary["orderType"] == "LIMIT":
            expected.add("price")
        _require(summary["orderId"] is None and summary["original"] is None)
        import uuid
        _require(isinstance(args.get("clientOrderId"), str) and str(uuid.UUID(args["clientOrderId"])) == args["clientOrderId"])
    else:
        expected = common | {"orderId", "expected_order"}
        if operation == "MODIFY":
            expected.add("orderType")
            expected |= set(args) & {"quantity", "price"}
        order_id = summary["orderId"]
        _require(isinstance(order_id, str) and 1 <= len(order_id) <= 256 and not any(ord(ch) < 32 or ord(ch) == 127 for ch in order_id))
        original = summary["original"]
        _require(isinstance(original, dict) and set(original) == ORIGINAL)
        _order(original, original=True)
        _require(original["market"] == args["market"] and original["currency"] == args["currency"]
                 and original["orderId"] == order_id)
        expected_order = {key: value for key, value in original.items() if key not in {"market", "filledQuantity"}}
        expected_order["execution"] = {"filledQuantity": original["filledQuantity"]}
        _require(_same_json(args.get("expected_order"), expected_order) and args.get("orderId") == order_id)
    _require(set(args) == expected)
    for field in {"symbol", "market", "currency", "side", "quantity", "orderAmount", "price", "orderType", "timeInForce"}:
        if field in args:
            _require(_same_json(args[field], summary[field]))
        elif operation == "CREATE" or (field == "price" and operation == "MODIFY"):
            _require(summary[field] is None)
        else:
            _require(summary[field] == summary["original"][field])
    changes = summary["normalization"]
    _require(isinstance(changes, list) and len(changes) <= 8)
    seen = set()
    for change in changes:
        _require(isinstance(change, dict) and set(change) == {"field", "before", "after"})
        field = change["field"]
        _require(isinstance(field, str) and field in NORMALIZED and field not in seen)
        seen.add(field)
        _require(all(value is None or (isinstance(value, str) and 1 <= len(value) <= 32) for value in (change["before"], change["after"])))
        _require(change["before"] == original_args.get(field) and change["after"] == args.get(field))
    _require(seen == {field for field in NORMALIZED if original_args.get(field) != args.get(field)})
    _require("confirmHighValueOrder" not in original_args or original_args["confirmHighValueOrder"] is False)
    for field in {"orderId", "side", "orderType"} & set(original_args):
        _require(_same_json(original_args[field], args.get(field)))
    return payload
