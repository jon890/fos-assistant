import { expect, test } from "./fixtures.ts";

const pending = {
  status: "PENDING", tokenPrefix: "ab12cd34", familyUuid: null, checkedAt: null,
  agentCode: "accountbook-browser", restartRequired: false,
};
const ready = { ...pending, status: "READY", checkedAt: "2026-09-30T12:00:00Z", restartRequired: false };
const disconnected = { ...pending, status: "DISCONNECTED", tokenPrefix: null, familyUuid: null, agentCode: null, restartRequired: true };

test("토큰을 등록하고 확인한 뒤 해제 상태와 반영 안내를 본다", async ({ page }) => {
  await page.route("**/api/connections/accountbook", async (route) => {
    const method = route.request().method();
    if (method === "POST") return route.fulfill({ json: pending });
    if (method === "DELETE") return route.fulfill({ json: disconnected });
    return route.continue();
  });
  await page.route("**/api/connections/accountbook/check", (route) => route.fulfill({ json: ready }));
  await page.goto("/connections/accountbook");
  await page.getByLabel("가계부 토큰").fill("browser-secret-token");
  await page.getByRole("button", { name: "연결하기" }).click();
  await expect(page.getByText("상태:").locator("strong")).toHaveText("준비 중");
  await expect(page.getByRole("button", { name: "연결 다시 확인" })).toBeVisible();
  await page.getByRole("button", { name: "연결 다시 확인" }).click();
  await expect(page.getByRole("link", { name: "가계부 에이전트 열기" })).toHaveAttribute("href", "/agents/accountbook-browser");
  await expect(page.getByText(/마지막 확인:/)).toBeVisible();
  await page.getByRole("button", { name: "연결 해제" }).click();
  await expect(page.getByText("가계부 설정에서 이 토큰을 폐기하면 즉시 끊겨요.")).toBeVisible();
  await expect(page.getByRole("main").getByRole("status")).toContainText("관리자가 실행 반영을 확인할 때까지 기다려 주세요.");
});

test("등록 요청을 기다리는 동안 토큰 입력을 비우고 오류 원문을 보이지 않는다", async ({ page }) => {
  let submitted: unknown;
  let release: () => void = () => {};
  const held = new Promise<void>((resolve) => { release = resolve; });
  await page.route("**/api/connections/accountbook", async (route) => {
    if (route.request().method() !== "POST") return route.continue();
    submitted = route.request().postDataJSON();
    await held;
    await route.fulfill({ status: 400, json: { code: "ACCOUNTBOOK_TOKEN_REJECTED", message: "raw upstream secret" } });
  });
  await page.goto("/connections/accountbook");
  const token = page.getByLabel("가계부 토큰");
  await token.fill("browser-secret-token");
  await page.getByRole("button", { name: "연결하기" }).click();
  await expect(token).toHaveValue("");
  expect(submitted).toEqual({ token: "browser-secret-token" });
  release();
  await expect(page.getByRole("main").getByRole("alert")).toHaveText("가계부 토큰을 확인하지 못했어요. 토큰을 다시 확인해 주세요.");
  await expect(page.getByText("raw upstream secret")).toHaveCount(0);
});

test("관리자는 반영 대기 연결을 완료로 확인한다", async ({ page }) => {
  await page.route("**/api/admin/connections/accountbook", (route) => route.request().method() === "GET"
    ? route.fulfill({ json: [{ userId: 77, displayName: "연결 확인 사용자", status: "PENDING", agentCode: "accountbook-browser", restartRequired: true }] })
    : route.continue());
  await page.route("**/api/admin/connections/accountbook/77/confirm", (route) => route.fulfill({ json: { userId: 77, displayName: null, status: "READY", agentCode: "accountbook-browser", restartRequired: false } }));
  await page.goto("/connections/accountbook");
  await expect(page.getByTestId("accountbook-admin-panel")).toBeVisible();
  await expect(page.getByText("연결 확인 사용자")).toBeVisible();
  await page.getByRole("button", { name: "반영 완료 확인" }).click();
  await expect(page.getByText("반영 확인이 필요한 연결이 없어요.")).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});
