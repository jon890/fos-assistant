import type { Locator } from "../../node_modules/@playwright/test/index.js";
import { expect, test } from "./fixtures.ts";

const RANGE = "항공권 40만~70만 원, 숙박 30만~55만 원";
const SOURCE = `${RANGE}\n\n| 경비 |\n| --- |\n| ${RANGE} |\n\n~~취소~~`;

async function expectRanges(body: Locator) {
  await expect(body.locator("p").filter({ hasText: RANGE })).toHaveText(RANGE);
  await expect(body.getByRole("cell", { name: RANGE, exact: true })).toBeVisible();
  await expect(body.locator("p, td").filter({ hasText: RANGE }).locator("del")).toHaveCount(0);
  await expect(body.locator("del")).toHaveText("취소");
}

test("채팅의 문단과 표는 범위 물결을 보존하고 두 물결만 취소선으로 그리며 원문을 복사한다", async ({ context, page }) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  const created = await page.request.post("/api/chat", {
    data: { text: "물결 범위 검사", agentCode: "browser" },
  });
  expect(created.ok()).toBeTruthy();
  const { conversationId } = await created.json() as { conversationId: string };
  await page.route(`**/api/chat/conversations/${conversationId}/messages`, async (route) => {
    const response = await route.fetch();
    const messages = await response.json() as { role: string; content: string }[];
    const answer = messages.findLast((message) => message.role === "ASSISTANT");
    expect(answer).toBeTruthy();
    answer!.content = SOURCE;
    await route.fulfill({ response, json: messages });
  });
  await page.goto(`/chat/${conversationId}`);
  const answer = page.getByTestId("assistant-message").last();
  await expectRanges(answer);
  await answer.getByRole("button", { name: "답 복사", exact: true }).click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe(SOURCE);
});

test("개발 문서의 문단과 표는 범위 물결을 보존하고 두 물결만 취소선으로 그린다", async ({ page }) => {
  await page.goto("/dev-docs/docs/features/sample.md");
  await expectRanges(page.locator("article"));
});
