import { CONVERSATION_URL, expect, test } from "./fixtures.ts";
import { waitForStartComposer } from "./helpers.ts";
import type { Locator, Page, TestInfo } from "../../node_modules/@playwright/test/index.js";

/**
 * 입력창 안 아래 줄과 새 대화 및 대화 화면의 머리 줄을 본다(docs/features/app-shell/spec.md 의 REQ8-REQ10).
 *
 * <p>휴대폰 폭은 스펙이 정한 360px 로 줄여 본다. 프로젝트의 기본 폭은 그보다 넓다.
 */

async function narrowOnMobile(page: Page, testInfo: TestInfo) {
  if (testInfo.project.name === "mobile") await page.setViewportSize({ width: 360, height: 780 });
}

async function box(locator: Locator) {
  const found = await locator.boundingBox();
  expect(found).not.toBeNull();
  return found!;
}

test("입력창 아래 줄에 사진 단추와 모델 단계와 보내기가 테두리 안 한 줄에 겹치지 않게 있다", async ({ page }, testInfo) => {
  await narrowOnMobile(page, testInfo);
  await page.goto("/new");
  await waitForStartComposer(page);

  const shell = page.getByTestId("composer-shell");
  const attach = shell.getByRole("button", { name: "사진 첨부" });
  const tier = shell.getByTestId("model-tier-picker");
  const sendButton = shell.getByRole("button", { name: "보내기" });
  await expect(attach).toBeVisible();
  await expect(tier).toBeEnabled();
  await expect(sendButton).toBeVisible();

  const shellBox = await box(shell);
  const boxes = [await box(attach), await box(tier), await box(sendButton)];
  for (const item of boxes) {
    expect(item.x).toBeGreaterThanOrEqual(shellBox.x);
    expect(item.x + item.width).toBeLessThanOrEqual(shellBox.x + shellBox.width);
    expect(item.y + item.height).toBeLessThanOrEqual(shellBox.y + shellBox.height);
  }
  // 한 줄이다. 세 단추의 세로 가운데가 같은 줄에 있고 왼쪽부터 차례로 겹치지 않는다.
  const middles = boxes.map((item) => item.y + item.height / 2);
  expect(Math.max(...middles) - Math.min(...middles)).toBeLessThan(4);
  expect(boxes[0]!.x + boxes[0]!.width).toBeLessThanOrEqual(boxes[1]!.x);
  expect(boxes[1]!.x + boxes[1]!.width).toBeLessThanOrEqual(boxes[2]!.x);
  // 글 칸은 위 줄이다.
  const textBox = await box(page.getByRole("textbox", { name: "메시지" }));
  expect(textBox.y + textBox.height).toBeLessThanOrEqual(boxes[1]!.y + 1);

  // 입력창 아래에 따로 떠 있는 줄이 없다. 폼의 마지막 요소가 입력창 테두리다.
  const lastChild = await page.locator("form").filter({ has: shell }).evaluate((form) => form.lastElementChild?.getAttribute("data-testid"));
  expect(lastChild).toBe("composer-shell");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

test("새 대화 화면에 비서 아이콘과 이름이 없고 휴대폰에서는 앱 홈 링크가 보인다", async ({ page }, testInfo) => {
  await narrowOnMobile(page, testInfo);
  await page.goto("/new");
  await waitForStartComposer(page);

  await expect(page.getByRole("main").getByTestId("agent-badge")).toHaveCount(0);
  await expect(page.getByRole("main").getByText("브라우저 비서", { exact: true })).toHaveCount(0);
  if (testInfo.project.name === "mobile") {
    const banner = page.getByRole("banner");
    const home = banner.getByRole("link", { name: /홈$/ });
    await expect(home).toHaveText("검사용 비서");
    await expect(home).toBeVisible();
    await expect(home).toHaveAttribute("href", "/");
    await expect(banner.getByText("브라우저 비서", { exact: true })).toHaveCount(0);
    await expect(banner.getByTestId("agent-badge")).toHaveCount(0);
  }
});

test("새 대화에서 보내면 기본 비서의 대화가 시작되고 머리 줄에 비서 이름이 보인다", async ({ page }, testInfo) => {
  await narrowOnMobile(page, testInfo);
  await page.goto("/new");
  await waitForStartComposer(page);

  await page.getByRole("textbox", { name: "메시지" }).fill(`기본 비서 대화 ${testInfo.project.name}`);
  await page.getByRole("button", { name: "보내기" }).click();
  await expect(page).toHaveURL(CONVERSATION_URL);
  await expect(page.getByTestId("assistant-message").last()).toBeVisible({ timeout: 30_000 });

  // 메시지가 있는 대화는 머리 줄에 그 대화의 이름표만 있고 목록을 여는 단추가 없다(app-shell 스펙의 REQ10).
  if (testInfo.project.name === "mobile") {
    const home = page.getByRole("banner").getByRole("link", { name: /홈$/ });
    await expect(home).toHaveText("브라우저 비서");
    await expect(home).toHaveAttribute("href", "/");
  } else {
    await expect(page.getByRole("main").getByTitle("브라우저 비서")).toBeVisible();
    await page.getByRole("main").getByTestId("agent-badge").first().click();
    await expect(page.getByRole("menuitemradio")).toHaveCount(0);
  }
});

test("「＋」 를 누르면 메뉴 없이 사진을 고르는 창이 바로 열리고 고른 사진이 붙는다", async ({ page }, testInfo) => {
  await narrowOnMobile(page, testInfo);
  await page.goto("/new");
  await waitForStartComposer(page);

  const [chooser] = await Promise.all([
    page.waitForEvent("filechooser"),
    page.getByTestId("composer-shell").getByRole("button", { name: "사진 첨부" }).click(),
  ]);
  expect(chooser.isMultiple()).toBe(true);
  await chooser.setFiles([
    {
      name: "plus.png",
      mimeType: "image/png",
      buffer: Buffer.from(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        "base64",
      ),
    },
  ]);
  await expect(page.getByTestId("attachment-previews").locator("> div")).toHaveCount(1);
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);
  await expect(page.getByRole("menu")).toHaveCount(0);
});

test("에이전트 목록을 읽지 못하면 휴대폰 위 막대 가운데에 홈 링크가 그대로 있다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "mobile", "위 막대는 좁은 폭에만 있다");
  await narrowOnMobile(page, testInfo);
  await page.route("**/api/agents", async (route) => {
    await route.fulfill({ status: 500, body: "{}" });
  });
  await page.goto("/new");

  await expect(page.getByRole("button", { name: "다시 읽기" })).toBeVisible();
  await expect(page.getByRole("banner").getByRole("link", { name: /홈$/ })).toBeVisible();
});
