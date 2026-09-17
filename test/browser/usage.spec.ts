import { encode } from "../../web/node_modules/next-auth/jwt.js";
import { expect, test } from "./fixtures.ts";
import { AUTH_SECRET, WEB_BASE_URL } from "./settings.ts";

const SESSION_COOKIE = "authjs.session-token";

test("화면 폭에 맞춰 실행 기록을 카드나 표로 보인다", async ({ page }, testInfo) => {
  const response = await page.request.post("/api/chat", {
    data: { text: "사용량 화면 검사", agentCode: "browser" },
  });
  expect(response.ok()).toBeTruthy();
  await page.goto("/usage");

  const cards = page.getByTestId("execution-cards");
  const table = page.getByTestId("execution-table");
  if (testInfo.project.name === "mobile") {
    await expect(cards).toBeVisible();
    await expect(table).toBeHidden();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  } else {
    await expect(table).toBeVisible();
    await expect(cards).toBeHidden();
  }
});

test("이번 달 합계와 가격을 찾지 못한 실행을 구분한다", async ({ page }, testInfo) => {
  const response = await page.request.post("/api/chat", {
    data: { text: "가격 없음 검사", agentCode: "browser" },
  });
  expect(response.ok()).toBeTruthy();
  const freeResponse = await page.request.post("/api/chat", {
    data: { text: "무료 모델 검사", agentCode: "browser" },
  });
  expect(freeResponse.ok()).toBeTruthy();
  await page.goto("/usage");

  await expect(page.getByText("이번 달 환산 합계", { exact: false })).toBeVisible();
  await expect(page.getByText("가격을 찾지 못한 실행", { exact: true })).toBeVisible();
  const records = page.getByTestId(testInfo.project.name === "mobile" ? "execution-cards" : "execution-table");
  await expect(records.getByText("가격 없음").first()).toBeVisible();
  await expect(records.getByText("0.0000 USD").first()).toBeVisible();
});

test("실행 기록이 없으면 빈 상태를 보인다", async ({ context, page }) => {
  const token = await encode({
    salt: SESSION_COOKIE,
    secret: AUTH_SECRET,
    token: { sub: "empty@example.com", email: "empty@example.com", name: "빈 사용자" },
  });
  await context.addCookies([{
    name: SESSION_COOKIE,
    value: token,
    url: WEB_BASE_URL,
    httpOnly: true,
    sameSite: "Lax",
  }]);

  const provision = await page.request.get("/api/agents");
  expect(provision.ok()).toBeTruthy();

  await page.goto("/usage");
  await expect(page.getByText("아직 실행 기록이 없다", { exact: true })).toBeVisible();
});
