import type { Page } from "../../web/node_modules/@playwright/test/index.js";
import { expect, test } from "./fixtures.ts";

function tokenForm(page: Page) {
  return page.locator("form").filter({ has: page.getByRole("heading", { name: "새 토큰" }) });
}

function tokenItem(page: Page, label: string) {
  return page.getByRole("heading", { name: label, exact: true }).locator("xpath=ancestor::article");
}

async function issueToken(page: Page, label: string, withSensitive: boolean) {
  const form = tokenForm(page);
  await form.getByLabel("이름").fill(label);
  await form.getByLabel("신원", { exact: true }).check();
  if (withSensitive) await form.getByLabel("민감한 문서도 읽기").check();
  await form.getByRole("button", { name: "토큰 만들기" }).click();
}

/** 검사가 만든 토큰을 폐기한다. 이미 폐기한 것은 건너뛴다. */
async function revokeByLabel(page: Page, label: string) {
  const tokens = (await (await page.request.get("/api/service-tokens")).json()) as {
    id: number;
    label: string;
    revokedAt: string | null;
  }[];
  for (const token of tokens.filter((candidate) => candidate.label === label && !candidate.revokedAt)) {
    await page.request.delete(`/api/service-tokens/${token.id}`);
  }
}

/** 목록 다시 읽기와 발급을 대역으로 바꾼다. 브라우저 검사의 사용자는 허용 목록에 없어 실제 토큰은 읽기 경로에서 401 을 받는다. */
async function stubTokens(page: Page, tokens: unknown[]) {
  await page.route("**/api/service-tokens", async (route) => {
    const method = route.request().method();
    if (method === "GET") return route.fulfill({ json: tokens });
    return route.fulfill({ json: { info: tokens[0], token: "fos_svc_stub" } });
  });
}

function fakeToken(label: string, patch: Record<string, unknown>) {
  return {
    id: 9000 + label.length,
    label,
    createdAt: new Date(Date.now() - 86_400_000).toISOString(),
    expiresAt: new Date(Date.now() + 90 * 86_400_000).toISOString(),
    lastUsedAt: null,
    revokedAt: null,
    collections: [{ collection: "identity", allowSensitive: false }],
    ...patch,
  };
}

test("토큰을 만들면 원문은 한 번만 보이고 브라우저 저장소에 남지 않는다", async ({ page }, testInfo) => {
  const label = `career-os ${testInfo.project.name}`;
  await page.goto("/memory");
  const form = tokenForm(page);
  await expect(form.getByLabel("만료")).toHaveValue("90");
  await expect(form.getByLabel("만료").locator("option:checked")).toHaveText("90일");

  await issueToken(page, label, true);
  const issued = page.getByTestId("issued-service-token");
  await expect(issued).toHaveText(/^fos_svc_/);
  const raw = (await issued.textContent()) ?? "";
  const item = tokenItem(page, label);
  await expect(item).toContainText("신원");
  await expect(item).toContainText("민감 포함");
  await expect(item).toContainText("아직 쓰지 않았어요");

  // 원문은 화면의 상태에만 있다. 저장소와 주소와 쿠키에는 없다.
  const stored = await page.evaluate(() => JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage }) + document.cookie + location.href);
  expect(stored).not.toContain(raw);
  expect((await page.context().cookies()).map((cookie) => cookie.value).join()).not.toContain(raw);

  const viewportWidth = page.viewportSize()?.width;
  expect(viewportWidth).toBeDefined();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewportWidth!);

  await page.reload();
  await expect(page.getByTestId("issued-service-token")).toHaveCount(0);
  await expect(tokenItem(page, label)).toBeVisible();
  await revokeByLabel(page, label);
});

test("「확인했어요」 를 누르면 원문이 화면에서 사라진다", async ({ page }, testInfo) => {
  const label = `확인 ${testInfo.project.name}`;
  await page.goto("/memory");
  await issueToken(page, label, false);
  await expect(page.getByTestId("issued-service-token")).toBeVisible();
  await page.getByRole("button", { name: "확인했어요" }).click();
  await expect(page.getByTestId("issued-service-token")).toHaveCount(0);
  await revokeByLabel(page, label);
});

test("만료 선택지에 「만료 없음」 이 없다", async ({ page }) => {
  await page.goto("/memory");
  const options = await tokenForm(page).getByLabel("만료").locator("option").allTextContents();
  expect(options).toEqual(["30일", "90일", "1년"]);
});

test("영역을 고르지 않으면 만들지 못하고 고른 영역 아래에만 민감 허용이 보인다", async ({ page }) => {
  await page.goto("/memory");
  const form = tokenForm(page);
  await form.getByLabel("이름").fill("영역 없음");
  await expect(form.getByRole("button", { name: "토큰 만들기" })).toBeDisabled();
  await expect(form.getByLabel("민감한 문서도 읽기")).toHaveCount(0);
  await form.getByLabel("신원", { exact: true }).check();
  await expect(form.getByLabel("민감한 문서도 읽기")).toHaveCount(1);
  await expect(form.getByLabel("민감한 문서도 읽기")).not.toBeChecked();
  await expect(form.getByRole("button", { name: "토큰 만들기" })).toBeEnabled();
});

test("마지막 사용 날짜를 보인다", async ({ page }, testInfo) => {
  const label = `사용 ${testInfo.project.name}`;
  await stubTokens(page, [fakeToken(label, { lastUsedAt: new Date(Date.now() - 3_600_000).toISOString() })]);
  await page.goto("/memory");
  await issueToken(page, "대역", false);
  const item = tokenItem(page, label);
  await expect(item).toContainText("마지막 사용");
  await expect(item).not.toContainText("아직 쓰지 않았어요");
});

test("만료가 가까운 토큰과 만료된 토큰에 표시를 달고 만료된 토큰은 폐기하지 못한다", async ({ page }) => {
  await stubTokens(page, [
    fakeToken("곧 만료 토큰", { expiresAt: new Date(Date.now() + 3 * 86_400_000).toISOString() }),
    fakeToken("만료 토큰", { expiresAt: new Date(Date.now() - 86_400_000).toISOString() }),
  ]);
  await page.goto("/memory");
  await issueToken(page, "대역", false);
  const expiring = tokenItem(page, "곧 만료 토큰");
  await expect(expiring.getByText("곧 만료돼요")).toBeVisible();
  await expect(expiring.getByRole("button", { name: "폐기", exact: true })).toBeVisible();
  const expired = tokenItem(page, "만료 토큰");
  await expect(expired.getByText("만료됐어요")).toBeVisible();
  await expect(expired.getByRole("button", { name: "폐기", exact: true })).toHaveCount(0);
});

test("폐기하면 표시가 바뀌고 폐기 단추가 사라진다", async ({ page }, testInfo) => {
  const label = `폐기 ${testInfo.project.name}`;
  await page.goto("/memory");
  await issueToken(page, label, false);
  const item = tokenItem(page, label);
  await item.getByRole("button", { name: "폐기", exact: true }).click();
  await expect(page.getByText("이 토큰을 쓰는 프로그램은 더 이상 문서를 읽지 못해요.")).toBeVisible();
  await page.getByRole("button", { name: "폐기하기" }).click();
  await expect(item.getByText("폐기했어요")).toBeVisible();
  await expect(item.getByRole("button", { name: "폐기", exact: true })).toHaveCount(0);
});
