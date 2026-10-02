import { ASK_CARD_PROBE, LONG_ACTIVITY_PROBE } from "../e2e/fake-hermes.ts";
import { expect, test } from "./fixtures.ts";
import type { Page } from "../../web/node_modules/@playwright/test/index.js";

async function sendAndWait(page: Page, text: string, answers: number) {
  await page.getByRole("textbox", { name: "메시지" }).fill(text);
  await page.getByRole("button", { name: "보내기", exact: true }).click();
  await expect(page.getByTestId("assistant-message")).toHaveCount(answers, { timeout: 30_000 });
}

test("긴 대화에서도 바깥 영역은 스크롤되지 않고 입력창이 화면 안 아래에 있다", async ({ page, hermes }) => {
  await page.goto("/");
  await sendAndWait(page, ASK_CARD_PROBE, 1);
  for (let index = 2; index <= 9; index += 1) {
    await sendAndWait(page, `긴 대화를 만드는 ${index}번째 물음`, index);
  }
  // 도는 중이던 작업 과정이 끝나 저장된 블록과 숨은 안내 글이 함께 쌓이게 한다.
  await hermes.holdNextRun();
  await page.getByRole("textbox", { name: "메시지" }).fill(LONG_ACTIVITY_PROBE);
  await page.getByRole("button", { name: "보내기", exact: true }).click();
  await hermes.waitForHeldRun();
  await hermes.releaseHeldRun();
  await hermes.releaseLongActivity();
  await hermes.releaseLongActivity();
  await expect(page.getByTestId("assistant-message")).toHaveCount(10, { timeout: 30_000 });

  const list = page.getByTestId("message-scroll");
  const inner = await list.evaluate((element) => ({
    scrollHeight: element.scrollHeight,
    clientHeight: element.clientHeight,
  }));
  // 이 검사가 의미가 있으려면 목록이 실제로 넘쳐야 한다.
  expect(inner.scrollHeight).toBeGreaterThan(inner.clientHeight);

  const outer = () => page.locator("main").first().evaluate((element) => {
    element.scrollTop = element.scrollHeight;
    return {
      scrollHeight: element.scrollHeight,
      clientHeight: element.clientHeight,
      scrollTop: element.scrollTop,
    };
  });
  const measured = await outer();
  expect(measured.scrollHeight).toBeLessThanOrEqual(measured.clientHeight);
  expect(measured.scrollTop).toBe(0);

  const composer = page.getByRole("textbox", { name: "메시지" });
  await expect(composer).toBeInViewport();
  const box = await composer.boundingBox();
  const viewport = page.viewportSize();
  expect(box).not.toBeNull();
  expect(box!.y + box!.height).toBeLessThanOrEqual(viewport!.height);

  // 목록을 맨 위로 올렸다가 내려도 입력창 자리는 그대로다.
  await list.evaluate((element) => { element.scrollTop = 0; });
  expect((await composer.boundingBox())!.y).toBe(box!.y);
});
