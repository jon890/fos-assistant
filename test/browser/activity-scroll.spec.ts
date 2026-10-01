import { expect, test } from "./fixtures.ts";
import { LONG_ACTIVITY_PROBE } from "../e2e/fake-hermes.ts";
import type { Page } from "../../web/node_modules/@playwright/test/index.js";

async function send(page: Page, text: string) {
  await page.goto("/");
  await page.getByRole("radio", { name: "브라우저 비서" }).click();
  await page.getByRole("textbox", { name: "메시지" }).fill(text);
  await page.getByRole("button", { name: "보내기" }).click();
}

test("펼친 작업 과정 목록은 높이 안에서 스크롤하고 맨 아래에 있으면 따라가고 위로 올려 읽으면 따라가지 않는다", async ({ page, hermes }) => {
  await hermes.holdNextRun();
  let runReleased = false;
  try {
    await send(page, LONG_ACTIVITY_PROBE);
    await hermes.waitForHeldRun();

    const block = page.locator('[data-testid="activity-block"][data-mode="live"]');
    await expect(block).toBeVisible();
    await block.getByTestId("activity-toggle").click();
    const items = block.getByTestId("activity-item");
    await expect(items).toHaveCount(31);

    const title = block.getByTestId("activity-toggle");
    await expect(title).toContainText("작업하고 있어요");
    await expect(title).not.toContainText("terminal");

    const scroll = block.getByTestId("activity-scroll");
    const measure = () => scroll.evaluate((element) => ({
      clientHeight: element.clientHeight,
      scrollHeight: element.scrollHeight,
      fromBottom: element.scrollHeight - element.scrollTop - element.clientHeight,
      scrollTop: element.scrollTop,
    }));
    await expect.poll(async () => (await measure()).fromBottom).toBeLessThanOrEqual(16);
    const measured = await measure();
    expect(measured.clientHeight).toBeLessThanOrEqual(257);
    expect(measured.scrollHeight).toBeGreaterThan(measured.clientHeight);
    expect((await block.boundingBox())?.height).toBeLessThanOrEqual(360);
    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth))
      .toBeLessThanOrEqual(0);

    // 맨 아래를 보고 있으면 새 줄이 와도 맨 아래를 따라간다.
    await hermes.releaseLongActivity();
    await expect(items).toHaveCount(42);
    await expect.poll(async () => (await measure()).fromBottom).toBeLessThanOrEqual(16);

    // 사용자가 위로 올려 읽는 중이면 새 줄이 와도 따라가지 않는다.
    await scroll.evaluate((element) => new Promise<void>((done) => {
      element.scrollTop = 0;
      requestAnimationFrame(() => requestAnimationFrame(() => done()));
    }));
    await hermes.releaseLongActivity();
    await expect(items).toHaveCount(52);
    expect((await measure()).scrollTop).toBe(0);

    await hermes.releaseHeldRun();
    runReleased = true;
    const saved = page.locator('[data-testid="activity-block"][data-mode="saved"]').last();
    await expect(saved).toBeVisible({ timeout: 30_000 });
    await page.reload();
    const reloaded = page.locator('[data-testid="activity-block"][data-mode="saved"]').last();
    await expect(reloaded).toBeVisible();
    await reloaded.getByTestId("activity-toggle").click();
    await expect(reloaded.getByTestId("activity-item")).toHaveCount(52);
    const savedScroll = reloaded.getByTestId("activity-scroll");
    expect(await savedScroll.evaluate((element) => element.scrollTop)).toBe(0);
    expect(await savedScroll.evaluate((element) => element.scrollHeight > element.clientHeight)).toBe(true);
  } finally {
    await hermes.releaseLongActivity();
    await hermes.releaseLongActivity();
    if (!runReleased) await hermes.releaseHeldRun();
  }
});
