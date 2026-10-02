import assert from "node:assert/strict";
import test from "node:test";
import { serviceTokenStatus } from "../../web/src/lib/service-token.ts";

const NOW = new Date("2026-10-02T00:00:00Z");
const DAY_MS = 24 * 60 * 60 * 1000;

function expiresIn(milliseconds: number): string {
  return new Date(NOW.getTime() + milliseconds).toISOString();
}

test("만료가 30일 뒤면 표시가 없다", () => {
  assert.equal(serviceTokenStatus({ expiresAt: expiresIn(30 * DAY_MS), revokedAt: null }, NOW), "active");
});

test("만료가 정확히 14일 뒤면 곧 만료다", () => {
  assert.equal(serviceTokenStatus({ expiresAt: expiresIn(14 * DAY_MS), revokedAt: null }, NOW), "expiring");
});

test("만료가 14일을 1초 넘게 남으면 표시가 없다", () => {
  assert.equal(serviceTokenStatus({ expiresAt: expiresIn(14 * DAY_MS + 1000), revokedAt: null }, NOW), "active");
});

test("만료가 3일 뒤면 곧 만료다", () => {
  assert.equal(serviceTokenStatus({ expiresAt: expiresIn(3 * DAY_MS), revokedAt: null }, NOW), "expiring");
});

test("만료가 1초 전이면 만료됐다", () => {
  assert.equal(serviceTokenStatus({ expiresAt: expiresIn(-1000), revokedAt: null }, NOW), "expired");
});

test("만료 시각이 지금과 같으면 만료됐다", () => {
  assert.equal(serviceTokenStatus({ expiresAt: expiresIn(0), revokedAt: null }, NOW), "expired");
});

test("폐기한 토큰은 만료가 남았어도 폐기로 보인다", () => {
  assert.equal(
    serviceTokenStatus({ expiresAt: expiresIn(3 * DAY_MS), revokedAt: "2026-10-01T00:00:00Z" }, NOW),
    "revoked",
  );
});

test("폐기한 토큰은 만료가 지났어도 폐기로 보인다", () => {
  assert.equal(
    serviceTokenStatus({ expiresAt: expiresIn(-DAY_MS), revokedAt: "2026-09-01T00:00:00Z" }, NOW),
    "revoked",
  );
});
