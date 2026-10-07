import type { Page } from "../../web/node_modules/@playwright/test/index.js";
import { expect, test } from "./fixtures.ts";
import { fixBrowserTime, FIXED_BROWSER_NOW } from "./helpers.ts";

test.beforeEach(async ({ page }) => {
  await fixBrowserTime(page);
});

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

type StubToken = {
  id: number;
  label: string;
  createdAt: string;
  expiresAt: string;
  lastUsedAt: string | null;
  revokedAt: string | null;
  collections: { collection: string; allowSensitive: boolean }[];
};

function fakeToken(label: string, patch: Partial<StubToken> = {}): StubToken {
  return {
    id: 9000 + label.length,
    label,
    createdAt: new Date(FIXED_BROWSER_NOW.getTime() - 86_400_000).toISOString(),
    expiresAt: new Date(FIXED_BROWSER_NOW.getTime() + 90 * 86_400_000).toISOString(),
    lastUsedAt: null,
    revokedAt: null,
    collections: [{ collection: "identity", allowSensitive: false }],
    ...patch,
  };
}

/**
 * 토큰 API 를 대역으로 바꾼다.
 *
 * <p>브라우저 검사의 사용자는 허용 목록에 없고, 허용 목록에서 꺼진 사용자는 토큰을 발급받지 못한다(ADR-056).
 * 그래서 발급과 폐기의 실제 왕복은 backend 테스트와 e2e 가 보고, 여기서는 화면이 요청을 어떻게 만들고 응답을 어떻게 그리는지만 본다.
 * 발급 요청의 본문은 `requests` 에 모아 단언에 쓴다.
 */
async function stubTokenApi(page: Page, seed: StubToken[] = []) {
  const tokens = [...seed];
  const requests: { expiresInDays: number; collections: { collection: string; allowSensitive: boolean }[] }[] = [];
  await page.route("**/api/service-tokens", async (route) => {
    if (route.request().method() === "GET") return route.fulfill({ json: tokens });
    const body = route.request().postDataJSON() as { label: string; expiresInDays: number; collections: StubToken["collections"] };
    requests.push({ expiresInDays: body.expiresInDays, collections: body.collections });
    const info = fakeToken(body.label, { collections: body.collections, expiresAt: new Date(FIXED_BROWSER_NOW.getTime() + body.expiresInDays * 86_400_000).toISOString() });
    tokens.push(info);
    return route.fulfill({ json: { info, token: `fos_svc_${"ab12".repeat(12)}` } });
  });
  await page.route("**/api/service-tokens/*", async (route) => {
    const id = Number(new URL(route.request().url()).pathname.split("/").pop());
    const token = tokens.find((candidate) => candidate.id === id);
    if (token) token.revokedAt = FIXED_BROWSER_NOW.toISOString();
    return route.fulfill({ status: 204 });
  });
  return { tokens, requests };
}

test("토큰을 만들면 원문은 한 번만 보이고 브라우저 저장소에 남지 않는다", async ({ page }, testInfo) => {
  const label = `career-os ${testInfo.project.name}`;
  const { requests } = await stubTokenApi(page);
  await page.goto("/memory");
  const form = tokenForm(page);
  await expect(form.getByLabel("만료")).toHaveValue("90");
  await expect(form.getByLabel("만료").locator("option:checked")).toHaveText("90일");

  await issueToken(page, label, true);
  expect(requests).toEqual([{ expiresInDays: 90, collections: [{ collection: "identity", allowSensitive: true }] }]);
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
});

test("「확인했어요」 를 누르면 원문이 화면에서 사라진다", async ({ page }, testInfo) => {
  await stubTokenApi(page);
  await page.goto("/memory");
  await issueToken(page, `확인 ${testInfo.project.name}`, false);
  await expect(page.getByTestId("issued-service-token")).toBeVisible();
  await page.getByRole("button", { name: "확인했어요" }).click();
  await expect(page.getByTestId("issued-service-token")).toHaveCount(0);
});

test("허용 목록에서 꺼진 사용자의 발급 거절은 할 일을 알리고 원문을 보이지 않는다", async ({ page }, testInfo) => {
  // 브라우저 검사의 사용자는 허용 목록에 없어 실제 backend 가 403 으로 거절한다.
  await page.goto("/memory");
  await issueToken(page, `거절 ${testInfo.project.name}`, false);
  await expect(page.getByText("이 계정으로는 토큰을 만들 수 없어요. 관리자에게 문의해 주세요.")).toBeVisible();
  await expect(page.getByTestId("issued-service-token")).toHaveCount(0);
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
  await stubTokenApi(page, [fakeToken(label, { lastUsedAt: new Date(FIXED_BROWSER_NOW.getTime() - 3_600_000).toISOString() })]);
  await page.goto("/memory");
  await issueToken(page, "대역", false);
  const item = tokenItem(page, label);
  await expect(item).toContainText("마지막 사용");
  await expect(item).not.toContainText("아직 쓰지 않았어요");
});

test("만료가 가까운 토큰과 만료된 토큰에 표시를 달고 만료된 토큰은 폐기하지 못한다", async ({ page }) => {
  await stubTokenApi(page, [
    fakeToken("곧 만료 토큰", { expiresAt: new Date(FIXED_BROWSER_NOW.getTime() + 3 * 86_400_000).toISOString() }),
    fakeToken("만료 토큰", { expiresAt: new Date(FIXED_BROWSER_NOW.getTime() - 86_400_000).toISOString() }),
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
  await stubTokenApi(page);
  await page.goto("/memory");
  await issueToken(page, label, false);
  const item = tokenItem(page, label);
  await item.getByRole("button", { name: "폐기", exact: true }).click();
  await expect(page.getByText("이 토큰을 쓰는 프로그램은 더 이상 문서를 읽지 못해요.")).toBeVisible();
  await page.getByRole("button", { name: "폐기하기" }).click();
  await expect(item.getByText("폐기했어요")).toBeVisible();
  await expect(item.getByRole("button", { name: "폐기", exact: true })).toHaveCount(0);
});
