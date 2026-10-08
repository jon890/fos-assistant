import { SignJWT } from "../../web/node_modules/jose/dist/webapi/index.js";
import { expect, setSession, test } from "./fixtures.ts";
import { isolatedUser } from "./helpers.ts";
import { CONTROL_PLANE_BASE_URL, JWT_SECRET, TEST_EMAIL } from "./settings.ts";

const SUPPORT_PATH = `${CONTROL_PLANE_BASE_URL}/api/v1/test-support/usage/executions`;
const ADMIN_SUPPORT_PATH = `${CONTROL_PLANE_BASE_URL}/api/v1/test-support/usage/admin-user`;

type Seed = {
  agentCode: string;
  model: string;
  startedAt: string;
  inputTokens: number;
  outputTokens: number;
  contextChars: number;
  estimatedCostMicros: number;
  actualCostMicros: number | null;
};

async function tokenFor(email: string): Promise<string> {
  return new SignJWT({ name: "브라우저 검사 사용자" })
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(email)
    .setIssuedAt()
    .setExpirationTime("2m")
    .sign(new TextEncoder().encode(JWT_SECRET));
}

function seeds(count: number, prefix: string): Seed[] {
  const first = Date.parse("2026-10-08T00:00:00Z");
  return Array.from({ length: count }, (_, index) => ({
    agentCode: "browser",
    model: `${prefix}-${index + 1}`,
    startedAt: new Date(first + index * 1_000).toISOString(),
    inputTokens: 1_234,
    outputTokens: 56,
    contextChars: 789,
    estimatedCostMicros: 12_345,
    actualCostMicros: null,
  }));
}

async function replaceExecutions(
  page: import("../../web/node_modules/@playwright/test/index.js").Page,
  email: string,
  values: Seed[],
): Promise<void> {
  const headers = { Authorization: `Bearer ${await tokenFor(email)}` };
  expect(
    (await page.request.delete(SUPPORT_PATH, { headers })).ok(),
  ).toBeTruthy();
  expect(
    (await page.request.post(SUPPORT_PATH, { headers, data: values })).ok(),
  ).toBeTruthy();
}

async function createAdminUser(
  page: import("../../web/node_modules/@playwright/test/index.js").Page,
  user: { email: string; name: string },
): Promise<void> {
  const response = await page.request.post(ADMIN_SUPPORT_PATH, {
    headers: { Authorization: `Bearer ${await tokenFor(TEST_EMAIL)}` },
    data: { email: user.email, displayName: user.name },
  });
  expect(response.ok()).toBeTruthy();
}

function records(
  page: import("../../web/node_modules/@playwright/test/index.js").Page,
  project: string,
) {
  return project === "mobile"
    ? page.getByTestId("execution-cards").locator("article")
    : page.getByTestId("execution-table").locator("tbody tr");
}

async function expectNoOverflow(
  page: import("../../web/node_modules/@playwright/test/index.js").Page,
  project: string,
) {
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth),
  ).toBeLessThanOrEqual(project === "mobile" ? 390 : 1280);
}

async function provision(
  context: import("../../web/node_modules/@playwright/test/index.js").BrowserContext,
  page: import("../../web/node_modules/@playwright/test/index.js").Page,
  user: { email: string; name: string },
) {
  await setSession(context, user);
  expect((await page.request.get("/api/agents")).ok()).toBeTruthy();
}

test("관리자 화면은 SSR 첫 50개 뒤 실제 다음 쪽을 정렬대로 한 번씩 끝까지 읽는다", async ({
  context,
  page,
}, testInfo) => {
  const user = isolatedUser(testInfo, "usage-admin");
  await createAdminUser(page, user);
  await provision(context, page, user);
  await replaceExecutions(page, user.email, seeds(55, "page-model"));
  try {
    await page.goto("/admin/usage?tab=executions");
    await expect(records(page, testInfo.project.name)).toHaveCount(50);
    await expect(page.getByRole("button", { name: "더 보기" })).toBeVisible();

    const next = page.waitForResponse("**/api/usage/executions/page?*");
    await page.getByRole("button", { name: "더 보기" }).click();
    expect((await next).ok()).toBeTruthy();
    await expect(records(page, testInfo.project.name)).toHaveCount(55);
    await expect(page.getByRole("button", { name: "더 보기" })).toHaveCount(0);
    await expectNoOverflow(page, testInfo.project.name);

    const ids = await records(page, testInfo.project.name)
      .locator('a[href^="/admin/executions/"]')
      .evaluateAll((links) =>
        links.map((link) =>
          Number(link.getAttribute("href")!.split("/").at(-1)),
        ),
      );
    expect(ids).toHaveLength(55);
    expect(new Set(ids).size).toBe(55);
    expect(
      ids.every((id, index) => index === 0 || ids[index - 1] > id),
    ).toBeTruthy();
  } finally {
    await replaceExecutions(page, user.email, []);
  }
});

test("빈 커서는 검증 오류로 되돌아온다", async ({
  context,
  page,
}, testInfo) => {
  const user = isolatedUser(testInfo, "usage-empty-cursor");
  await provision(context, page, user);

  const response = await page.request.get("/api/usage/executions/page?cursor=");

  expect(response.status()).toBe(400);
  expect(await response.json()).toEqual(
    expect.objectContaining({ code: "VALIDATION_FAILED" }),
  );
});

test("시험 관리자 준비는 관리자만 새 사용자에게 허용하고 기존 역할을 바꾸지 않는다", async ({
  context,
  page,
}, testInfo) => {
  const member = isolatedUser(testInfo, "usage-admin-fixture-member");
  await provision(context, page, member);
  const memberHeaders = {
    Authorization: `Bearer ${await tokenFor(member.email)}`,
  };

  const forbidden = await page.request.post(ADMIN_SUPPORT_PATH, {
    headers: memberHeaders,
    data: {
      email: isolatedUser(testInfo, "forbidden-admin").email,
      displayName: "사용자A",
    },
  });
  expect(forbidden.status()).toBe(403);

  const duplicate = await page.request.post(ADMIN_SUPPORT_PATH, {
    headers: { Authorization: `Bearer ${await tokenFor(TEST_EMAIL)}` },
    data: { email: member.email, displayName: member.name },
  });
  expect(duplicate.status()).toBe(400);
  expect(await duplicate.json()).toEqual(
    expect.objectContaining({ code: "VALIDATION_FAILED" }),
  );

  const me = await page.request.get(`${CONTROL_PLANE_BASE_URL}/api/v1/me`, {
    headers: memberHeaders,
  });
  expect(me.ok()).toBeTruthy();
  expect(await me.json()).toEqual(expect.objectContaining({ role: "MEMBER" }));
});

test("다음 쪽 실패 뒤 기존 기록을 지키고 재시도하며 빠른 두 클릭은 한 요청만 낸다", async ({
  context,
  page,
}, testInfo) => {
  const user = isolatedUser(testInfo, "usage-retry");
  await provision(context, page, user);
  await replaceExecutions(page, user.email, seeds(52, "retry-model"));
  await page.goto("/usage?tab=executions");
  const more = page.getByRole("button", { name: "더 보기" });
  await expect(records(page, testInfo.project.name)).toHaveCount(50);

  await page.route("**/api/usage/executions/page?*", (route) =>
    route.fulfill({
      status: 500,
      contentType: "application/json",
      body: '{"code":"UNAVAILABLE","message":"실패"}',
    }),
  );
  await more.click();
  await expect(
    page.getByRole("alert").filter({ hasText: "실패" }),
  ).toBeVisible();
  await expect(records(page, testInfo.project.name)).toHaveCount(50);
  await page.unroute("**/api/usage/executions/page?*");

  let requests = 0;
  let release: () => void = () => {};
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route("**/api/usage/executions/page?*", async (route) => {
    requests += 1;
    await held;
    await route.continue();
  });
  await more.dblclick();
  await expect.poll(() => requests).toBe(1);
  release();
  await expect(records(page, testInfo.project.name)).toHaveCount(52);
  expect(requests).toBe(1);
  await page.unroute("**/api/usage/executions/page?*");
});

test("일반 사용자는 다음 쪽까지 내부 값을 보지 않는다", async ({
  context,
  page,
  isolatedMember,
}, testInfo) => {
  await provision(context, page, isolatedMember);
  const hiddenModel = "internal-model-value";
  await replaceExecutions(page, isolatedMember.email, seeds(51, hiddenModel));

  await page.goto("/usage?tab=executions");
  await expect(records(page, testInfo.project.name)).toHaveCount(50);
  await expect(page.locator("body")).not.toContainText(hiddenModel);
  await page.getByRole("button", { name: "더 보기" }).click();
  await expect(records(page, testInfo.project.name)).toHaveCount(51);
  await expect(page.locator("body")).not.toContainText(hiddenModel);
  await expect(page.locator("body")).not.toContainText("1,234");
  await expectNoOverflow(page, testInfo.project.name);
});
