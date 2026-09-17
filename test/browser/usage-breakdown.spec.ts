import type { Page } from "../../web/node_modules/@playwright/test/index.js";
import { SignJWT } from "../../web/node_modules/jose/dist/webapi/index.js";
import { expect, test } from "./fixtures.ts";
import { CONTROL_PLANE_BASE_URL, JWT_SECRET, TEST_EMAIL } from "./settings.ts";

const SUPPORT_PATH = `${CONTROL_PLANE_BASE_URL}/api/v1/test-support/usage/executions`;

type Seed = {
  agentCode: string;
  model?: string;
  runtimeFingerprint?: string;
  startedAt: string;
  inputTokens: number;
  outputTokens: number;
  contextChars: number;
  estimatedCostMicros: number;
  actualCostMicros: number | null;
};

async function controlPlaneToken(): Promise<string> {
  return new SignJWT({ name: "브라우저 테스트" })
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(TEST_EMAIL)
    .setIssuedAt()
    .setExpirationTime("2m")
    .sign(new TextEncoder().encode(JWT_SECRET));
}

/** 이번 달 1일의 이른 시각이다. 달 경계를 넘지 않아 어느 날 돌려도 이번 달 합계에 들어간다. */
function thisMonthAt(hour: number): string {
  const now = new Date();
  return new Date(now.getFullYear(), now.getMonth(), 1, hour, 0, 0).toISOString();
}

async function replaceExecutions(page: Page, seeds: Seed[]): Promise<void> {
  const headers = { Authorization: `Bearer ${await controlPlaneToken()}` };
  const cleared = await page.request.delete(SUPPORT_PATH, { headers });
  expect(cleared.ok()).toBeTruthy();
  const seeded = await page.request.post(SUPPORT_PATH, { headers, data: seeds });
  expect(seeded.ok()).toBeTruthy();
}

function breakdown(page: Page, project: string) {
  return page.getByTestId(project === "mobile" ? "breakdown-cards" : "breakdown-table");
}

test("묶는 기준을 바꾸면 표가 그 축으로 바뀐다", async ({ page }, testInfo) => {
  await replaceExecutions(page, [
    {
      agentCode: "browser",
      model: "gpt-breakdown-big",
      startedAt: thisMonthAt(1),
      inputTokens: 1_000,
      outputTokens: 500,
      contextChars: 7_313,
      estimatedCostMicros: 14_090_000,
      actualCostMicros: null,
    },
    {
      agentCode: "browser",
      model: "gpt-breakdown-small",
      startedAt: thisMonthAt(2),
      inputTokens: 100,
      outputTokens: 50,
      contextChars: 570,
      estimatedCostMicros: 60_000,
      actualCostMicros: null,
    },
  ]);
  await page.goto("/usage");

  const rows = breakdown(page, testInfo.project.name);
  await expect(rows.getByText("브라우저 비서").first()).toBeVisible();
  await expect(rows.getByText("gpt-breakdown-big")).toHaveCount(0);

  await page.getByTestId("breakdown-axis").selectOption("model");

  await expect(rows.getByText("gpt-breakdown-big")).toBeVisible();
  await expect(rows.getByText("gpt-breakdown-small")).toBeVisible();
});

test("지문이 하나뿐이면 무엇이 달라졌나 절을 그리지 않는다", async ({ page }) => {
  await replaceExecutions(page, [
    {
      agentCode: "browser",
      runtimeFingerprint: "same-fingerprint",
      startedAt: thisMonthAt(1),
      inputTokens: 1_000,
      outputTokens: 500,
      contextChars: 600,
      estimatedCostMicros: 50_000,
      actualCostMicros: null,
    },
    {
      agentCode: "browser",
      runtimeFingerprint: "same-fingerprint",
      startedAt: thisMonthAt(2),
      inputTokens: 1_000,
      outputTokens: 500,
      contextChars: 620,
      estimatedCostMicros: 60_000,
      actualCostMicros: null,
    },
  ]);
  await page.goto("/usage");

  await expect(page.getByTestId("breakdown-axis")).toBeVisible();
  await expect(page.getByTestId("fingerprint-section")).toHaveCount(0);
});

test("지문이 둘이면 구간마다 실행당 평균을 보인다", async ({ page }) => {
  await replaceExecutions(page, [
    {
      agentCode: "browser",
      runtimeFingerprint: "8c11aaaa1111",
      startedAt: thisMonthAt(1),
      inputTokens: 1_000,
      outputTokens: 500,
      contextChars: 600,
      estimatedCostMicros: 60_000,
      actualCostMicros: null,
    },
    {
      agentCode: "browser",
      runtimeFingerprint: "a3f2bbbb2222",
      startedAt: thisMonthAt(3),
      inputTokens: 1_000,
      outputTokens: 500,
      contextChars: 900,
      estimatedCostMicros: 100_000,
      actualCostMicros: null,
    },
  ]);
  await page.goto("/usage");

  const section = page.getByTestId("fingerprint-section");
  await expect(section.getByText("지문 a3f2bbbb…", { exact: true })).toBeVisible();
  await expect(section.getByText("0.1000 USD/실행", { exact: true })).toBeVisible();
  await expect(section.getByText("0.0600 USD/실행", { exact: true })).toBeVisible();
});

test("두 폭 모두에서 축별 표가 가로로 넘치지 않는다", async ({ page }, testInfo) => {
  await replaceExecutions(page, [
    {
      agentCode: "browser",
      model: "아주-긴-모델-이름-gpt-breakdown-overflow-check",
      runtimeFingerprint: "8c11aaaa1111",
      startedAt: thisMonthAt(1),
      inputTokens: 1_234_567,
      outputTokens: 987_654,
      contextChars: 1_234_567,
      estimatedCostMicros: 14_090_000,
      actualCostMicros: 14_090_000,
    },
    {
      agentCode: "browser",
      model: "아주-긴-모델-이름-gpt-breakdown-overflow-other",
      runtimeFingerprint: "a3f2bbbb2222",
      startedAt: thisMonthAt(3),
      inputTokens: 2_345_678,
      outputTokens: 876_543,
      contextChars: 2_345_678,
      estimatedCostMicros: 23_450_000,
      actualCostMicros: 23_450_000,
    },
  ]);
  await page.goto("/usage");
  await page.getByTestId("breakdown-axis").selectOption("model");
  await expect(breakdown(page, testInfo.project.name).getByText("2,345,678").first()).toBeVisible();

  const width = testInfo.project.name === "mobile" ? 390 : 1280;
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
});
