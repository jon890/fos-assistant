import assert from "node:assert/strict";
import test from "node:test";
import { readControlPlaneResult } from "../../web/src/lib/control-plane-result.ts";

const HTML = "<html><body><h1>502 Bad Gateway</h1></body></html>";

test("성공 상태인데 본문이 HTML 이면 원문 없이 Control Plane 에 닿지 못했을 때와 같은 오류로 알린다", () => {
  assert.deepEqual(readControlPlaneResult(200, HTML), {
    ok: false,
    status: 502,
    code: "INTERNAL_ERROR",
    message: "요청을 처리하지 못했어요.",
  });
});

test("오류 상태의 HTML 본문은 상태를 두고 원문 대신 기본 문구로 알린다", () => {
  assert.deepEqual(readControlPlaneResult(503, HTML), {
    ok: false,
    status: 503,
    code: "INTERNAL_ERROR",
    message: "요청을 처리하지 못했어요.",
  });
});

test("JSON 오류 본문은 Control Plane 의 코드와 문구를 그대로 옮긴다", () => {
  assert.deepEqual(readControlPlaneResult(400, JSON.stringify({ code: "VALIDATION_FAILED", message: "이름을 입력해 주세요." })), {
    ok: false,
    status: 400,
    code: "VALIDATION_FAILED",
    message: "이름을 입력해 주세요.",
  });
});

test("성공 본문은 읽은 값을, 빈 본문은 null 을 준다", () => {
  assert.deepEqual(readControlPlaneResult(200, JSON.stringify({ id: 1 })), { ok: true, status: 200, data: { id: 1 } });
  assert.deepEqual(readControlPlaneResult(204, ""), { ok: true, status: 204, data: null });
});

test("오류 상태의 빈 본문이나 code 가 없는 JSON 은 기본 코드와 문구로 채운다", () => {
  const fallback = { ok: false, status: 500, code: "INTERNAL_ERROR", message: "요청을 처리하지 못했어요." };
  assert.deepEqual(readControlPlaneResult(500, ""), fallback);
  assert.deepEqual(readControlPlaneResult(500, JSON.stringify({ error: "boom" })), fallback);
});
