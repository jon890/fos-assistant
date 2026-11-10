import { conversationIdOf, expect, test } from "./fixtures.ts";
import { TOOL_DETAIL_SECRETS } from "../e2e/fake-hermes.ts";

test("작업 과정과 SSE와 실행 조회에서 비밀값과 UUID 원문을 내보내지 않는다", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("radio", { name: "브라우저 비서" }).click();
  const streamed = page.waitForResponse((response) => response.request().method() === "POST"
    && response.url().endsWith("/api/chat/stream"));
  await page.getByRole("textbox", { name: "메시지" }).fill("도구 가리기 검사");
  await page.getByRole("button", { name: "보내기" }).click();
  const streamBody = await (await streamed).text();
  const block = page.locator('[data-testid="activity-block"][data-mode="saved"]').last();
  await expect(block).toBeVisible({ timeout: 30_000 });
  await block.getByTestId("activity-toggle").click();
  const tree = page.waitForResponse((response) => /\/api\/usage\/executions\/\d+\/tree$/.test(response.url()));
  await block.getByTestId("activity-open-panel").click();
  const treeBody = await (await tree).text();
  await expect(page.getByTestId("activity-panel").getByTestId("execution-tree")).toBeVisible();
  const history = await page.request.get(`/api/chat/conversations/${conversationIdOf(page.url())}/messages`);
  expect(history.ok()).toBeTruthy();
  const bodies = [streamBody, treeBody, await history.text(), await page.locator("body").innerText()];
  for (const body of bodies) {
    for (const secret of TOOL_DETAIL_SECRETS) {
      expect(body).not.toContain(secret);
    }
  }
  expect(streamBody).toContain("[가림]");
  expect(treeBody).toContain("[항목 1]");
  await page.reload();
  for (const secret of TOOL_DETAIL_SECRETS) {
    await expect(page.locator("body")).not.toContainText(secret);
  }
});
