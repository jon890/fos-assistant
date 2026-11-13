import { afterEach, expect, test } from "bun:test";
import { createHash, randomUUID } from "node:crypto";
import { takeApproval, verifyApproval } from "../src/approval.ts";

/** 실행 원문. 합성 값이다. */
const ARGS = {
  checkout: 1,
  url: "https://smartstore.naver.com/shop/products/1",
  optionLabels: [],
  quantity: 1,
  expectedTotalKrw: 3400,
};
const sha256 = (raw: string) =>
  createHash("sha256").update(raw, "utf8").digest("hex");

type Reply = { status?: number; body?: unknown; raw?: string };
let server: ReturnType<typeof Bun.serve> | null = null;
let claims = 0;
afterEach(() => {
  server?.stop(true);
  server = null;
  claims = 0;
});

/** 응답을 바꿀 수 있는 가짜 claim 창구. 받은 요청 수를 센다. */
function claimServer(reply: (payload: any) => Reply) {
  server = Bun.serve({
    port: 0,
    async fetch(request) {
      claims += 1;
      const body = (await request.json()) as any;
      const payload = JSON.parse(
        Buffer.from(body.ticket.split(".")[0], "base64url").toString(),
      );
      const answer = reply(payload);
      return new Response(answer.raw ?? JSON.stringify(answer.body ?? {}), {
        status: answer.status ?? 200,
        headers: { "content-type": "application/json" },
      });
    },
  });
  return `http://127.0.0.1:${server.port}/internal/connector-executions/claim`;
}

const okReply = (payload: any): Reply => ({
  body: {
    v: 1,
    allowed: true,
    ticketId: payload.ticketId,
    expiresAt: payload.expiresAt,
  },
});

function authority(
  url: string,
  overrides: Record<string, unknown> = {},
  raw = JSON.stringify(ARGS),
) {
  const issued = Date.now() - 1_000;
  const payload = {
    v: 1,
    ticketId: randomUUID(),
    tool: "fill_checkout",
    argsSha256: sha256(raw),
    scopeSha256: sha256("{}"),
    issuedAt: new Date(issued).toISOString(),
    expiresAt: new Date(issued + 30_000).toISOString(),
    ...overrides,
  };
  const ticket = `${Buffer.from(JSON.stringify(payload)).toString("base64url")}.${Buffer.alloc(32, 7).toString("base64url")}`;
  return takeApproval({
    FOS_APPROVAL_ARGS_JSON: raw,
    FOS_APPROVAL_TICKET: ticket,
    FOS_APPROVAL_CLAIM_URL: url,
  });
}

const REJECTED = { code: "NAVER_SHOPPING_CLAIM_REJECTED" };

test("맞는 원문·ticket·응답이면 claim 을 한 번 보내고 통과한다", async () => {
  const url = claimServer(okReply);
  const claim = verifyApproval(authority(url), "fill_checkout", ARGS);
  await claim();
  expect(claims).toBe(1);
});

const issuedNow = Date.now();
for (const [name, overrides, raw] of [
  [
    "만료된 ticket",
    {
      expiresAt: new Date(issuedNow - 500).toISOString(),
      issuedAt: new Date(issuedNow - 10_000).toISOString(),
    },
  ],
  [
    "발급 시각이 미래",
    {
      issuedAt: new Date(issuedNow + 60_000).toISOString(),
      expiresAt: new Date(issuedNow + 70_000).toISOString(),
    },
  ],
  [
    "수명이 60초 넘음",
    { expiresAt: new Date(issuedNow + 120_000).toISOString() },
  ],
  ["다른 도구", { tool: "create_order" }],
  ["다른 scope", { scopeSha256: sha256('{"account_seq":"1"}') }],
  ["UUID 가 아닌 ticketId", { ticketId: "not-a-uuid" }],
  ["다른 원문 해시", { argsSha256: sha256("{}") }],
  [
    "중복 키 원문",
    {},
    '{"checkout":1,"checkout":1,"url":"https://smartstore.naver.com/shop/products/1","optionLabels":[],"quantity":1,"expectedTotalKrw":3400}',
  ],
] as const) {
  test(`${name}이면 claim 없이 거절한다`, () => {
    const url = claimServer(okReply);
    const auth = authority(url, overrides as Record<string, unknown>, raw);
    let caught: unknown = null;
    try {
      verifyApproval(auth, "fill_checkout", ARGS);
    } catch (error) {
      caught = error;
    }
    expect(caught).toMatchObject(REJECTED);
    expect(claims).toBe(0);
  });
}

for (const [name, path] of [
  ["다른 경로", "/internal/other"],
  ["조회 글", "/internal/connector-executions/claim?x=1"],
] as const) {
  test(`claim 주소가 ${name}이면 거절한다`, () => {
    const base = claimServer(okReply).replace(
      "/internal/connector-executions/claim",
      "",
    );
    expect(() =>
      verifyApproval(authority(base + path), "fill_checkout", ARGS),
    ).toThrow();
    expect(claims).toBe(0);
  });
}

for (const [name, reply] of [
  ["409", () => ({ status: 409 })],
  ["3xx", () => ({ status: 302 })],
  [
    "allowed 거짓",
    (p: any) => ({ body: { ...(okReply(p).body as object), allowed: false } }),
  ],
  [
    "키 추가",
    (p: any) => ({ body: { ...(okReply(p).body as object), extra: 1 } }),
  ],
  [
    "다른 ticketId",
    (p: any) => ({
      body: { ...(okReply(p).body as object), ticketId: randomUUID() },
    }),
  ],
  [
    "다른 expiresAt",
    (p: any) => ({
      body: {
        ...(okReply(p).body as object),
        expiresAt: new Date().toISOString(),
      },
    }),
  ],
  [
    "4096바이트 넘는 응답",
    () => ({ raw: `{"v":1,"pad":"${"x".repeat(5000)}"}` }),
  ],
] as const) {
  test(`claim 응답이 ${name}이면 거절한다`, async () => {
    const url = claimServer(reply as (payload: any) => Reply);
    const claim = verifyApproval(authority(url), "fill_checkout", ARGS);
    await expect(claim()).rejects.toMatchObject(REJECTED);
    expect(claims).toBe(1);
  });
}

test("권한은 한 번만 꺼낸다. 두 번째 대조는 거절이다", () => {
  const url = claimServer(okReply);
  const auth = authority(url);
  verifyApproval(auth, "fill_checkout", ARGS);
  expect(() => verifyApproval(auth, "fill_checkout", ARGS)).toThrow();
});
