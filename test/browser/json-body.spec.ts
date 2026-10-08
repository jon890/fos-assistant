import { expect, test } from "./fixtures.ts";

const CONVERSATION = "0b1c2d3e-4f50-4a6b-8c7d-9e0f1a2b3c4d";

/**
 * 본문을 읽는 서버 라우트 전체 목록이다.
 *
 * <p>경로 칸은 형식 검사를 지나도록 채운다. 본문을 읽지 못하면 Control Plane 을 부르기 전에 끝나므로
 * 없는 대화나 기억을 가리켜도 된다.
 */
const ROUTES: ReadonlyArray<{ method: "POST" | "PUT" | "PATCH"; path: string }> = [
  { method: "POST", path: "/api/chat" },
  { method: "POST", path: "/api/chat/stream" },
  { method: "POST", path: "/api/chat/conversations" },
  { method: "PATCH", path: `/api/chat/conversations/${CONVERSATION}` },
  { method: "PUT", path: `/api/chat/conversations/${CONVERSATION}/model` },
  { method: "POST", path: "/api/agents" },
  { method: "PATCH", path: "/api/agents/browser/visibility" },
  { method: "PUT", path: "/api/agents/browser/persona" },
  { method: "PUT", path: "/api/agents/browser/skills/json-body-check" },
  { method: "PUT", path: "/api/agents/browser/skills/json-body-check/enabled" },
  { method: "PUT", path: "/api/agents/browser/tools" },
  { method: "POST", path: "/api/admin/agents" },
  { method: "PATCH", path: "/api/admin/agents/browser" },
  { method: "PUT", path: "/api/admin/agents/browser/tools" },
  { method: "POST", path: "/api/admin/people" },
  { method: "PATCH", path: "/api/admin/people/1" },
  { method: "POST", path: "/api/memories" },
  { method: "PATCH", path: "/api/memories/1" },
  { method: "PUT", path: "/api/check-findings/1/reaction" },
];

const INVALID = { code: "VALIDATION_FAILED", message: "요청 내용이 올바르지 않아요." };

test("본문을 읽는 라우트는 JSON 이 아닌 본문에 400 JSON 으로 답한다", async ({ page }) => {
  for (const { method, path } of ROUTES) {
    const response = await page.request.fetch(path, {
      method,
      headers: { "Content-Type": "application/json" },
      data: "{not json",
    });
    expect(response.status(), `${method} ${path}`).toBe(400);
    expect(response.headers()["content-type"], `${method} ${path}`).toContain("application/json");
    expect(await response.json(), `${method} ${path}`).toEqual(INVALID);
  }
});

test("본문이 JSON 객체가 아니면 칸을 읽기 전에 400 으로 답한다", async ({ page }) => {
  for (const data of ["null", "[]", "\"text\""]) {
    const response = await page.request.post("/api/chat", {
      headers: { "Content-Type": "application/json" },
      data,
    });
    expect(response.status(), data).toBe(400);
    expect(await response.json(), data).toEqual(INVALID);
  }
});
