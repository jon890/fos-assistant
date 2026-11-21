import { createHash } from "node:crypto";
import { SignJWT } from "../../node_modules/jose/dist/webapi/index.js";
import type {
  BrowserContext,
  Page,
  TestInfo,
} from "../../node_modules/@playwright/test/index.js";
import { expect, setSession, test } from "./fixtures.ts";
import { isolatedUser } from "./helpers.ts";
import { CONTROL_PLANE_BASE_URL, JWT_SECRET, TEST_EMAIL } from "./settings.ts";

const SUPPORT_PATH = `${CONTROL_PLANE_BASE_URL}/api/v1/test-support/usage/executions`;
const ADMIN_SUPPORT_PATH = `${CONTROL_PLANE_BASE_URL}/api/v1/test-support/usage/admin-user`;
const DAY = 24 * 60 * 60 * 1_000;

type User = { email: string; name: string };

type Seed = {
  startedAt: string;
  status?: "FAILED" | "SUCCEEDED";
  errorCode?: string;
  estimatedCostMicros?: number | null;
  actualCostMicros?: number | null;
};

async function tokenFor(email: string): Promise<string> {
  return new SignJWT({ name: "브라우저 검사 사용자" })
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(email)
    .setIssuedAt()
    .setExpirationTime("2m")
    .sign(new TextEncoder().encode(JWT_SECRET));
}

/**
 * 검사마다 새 그룹 번호다. 요약은 그룹 전체를 세므로 다른 검사의 실행과 섞이지 않게 한다.
 *
 * <p>해시 앞 12자리(48비트)라 JS 수로 정확하다. 심은 사용자는 지우지 않는다. 그룹이 매번 새것이라 섞이지 않는다.
 */
function groupIdFor(testInfo: TestInfo): number {
  const hex = createHash("sha256")
    .update(isolatedUser(testInfo, "overview-group").email)
    .digest("hex")
    .slice(0, 12);
  return Number.parseInt(hex, 16) + 1_000_000;
}

async function createAdmin(page: Page, user: User, groupId: number): Promise<void> {
  const response = await page.request.post(ADMIN_SUPPORT_PATH, {
    headers: { Authorization: `Bearer ${await tokenFor(TEST_EMAIL)}` },
    data: { email: user.email, displayName: user.name, groupId },
  });
  expect(response.ok(), `관리자를 만들지 못했다: ${response.status()}`).toBeTruthy();
}

async function replaceExecutions(page: Page, user: User, seeds: Seed[]): Promise<void> {
  const headers = { Authorization: `Bearer ${await tokenFor(user.email)}` };
  expect((await page.request.delete(SUPPORT_PATH, { headers })).ok()).toBeTruthy();
  if (seeds.length === 0) return;
  const data = seeds.map((seed) => ({
    agentCode: "browser",
    startedAt: seed.startedAt,
    inputTokens: 100,
    outputTokens: 10,
    contextChars: 10,
    estimatedCostMicros: seed.estimatedCostMicros === undefined ? 1_000 : seed.estimatedCostMicros,
    actualCostMicros: seed.actualCostMicros === undefined ? 500 : seed.actualCostMicros,
    status: seed.status ?? "SUCCEEDED",
    errorCode: seed.errorCode ?? null,
  }));
  expect((await page.request.post(SUPPORT_PATH, { headers, data })).ok()).toBeTruthy();
}

async function provision(context: BrowserContext, page: Page, user: User): Promise<void> {
  await setSession(context, user);
  expect((await page.request.get("/api/agents")).ok()).toBeTruthy();
}

function ago(ms: number): string {
  return new Date(Date.now() - ms).toISOString();
}

test("요약은 그룹 전체의 7일 실패를 세고 남의 실행은 열지 못하며 이번 달 실행 수를 합친다", async ({
  context,
  page,
}, testInfo) => {
  const groupId = groupIdFor(testInfo);
  const viewer = { ...isolatedUser(testInfo, "overview-a"), name: "보는 관리자" };
  const other = { ...isolatedUser(testInfo, "overview-b"), name: "다른 사용자" };
  await createAdmin(page, viewer, groupId);
  await createAdmin(page, other, groupId);
  await provision(context, page, viewer);
  try {
    // 최근 세 건에 다른 사용자 두 건과 내 한 건이 함께 든다. 8일 전 실패는 세지 않는다.
    await replaceExecutions(page, viewer, [
      { startedAt: ago(60_000), status: "FAILED", errorCode: "MINE_LATEST" },
      { startedAt: ago(3 * 60 * 60_000), status: "FAILED", errorCode: "MINE_OLDER" },
      { startedAt: ago(8 * DAY), status: "FAILED", errorCode: "TOO_OLD" },
    ]);
    await replaceExecutions(page, other, [
      { startedAt: ago(30_000), status: "FAILED", errorCode: "OTHER_1" },
      { startedAt: ago(45_000), status: "FAILED", errorCode: "OTHER_2" },
      { startedAt: ago(2 * DAY), status: "FAILED", errorCode: "OTHER_3" },
    ]);

    await page.goto("/admin");
    const failures = page.getByRole("region", { name: "품질과 실패" });
    await expect(failures.getByTestId("overview-failure-total")).toHaveText("5건");
    const lines = failures.getByTestId("overview-failure");
    await expect(lines).toHaveCount(3);
    await expect(lines.nth(0)).toContainText("다른 사용자");
    await expect(lines.nth(0)).toContainText("OTHER_1");
    await expect(lines.nth(0).getByRole("link")).toHaveCount(0);
    await expect(lines.nth(2)).toContainText("MINE_LATEST");
    await expect(failures).not.toContainText("TOO_OLD");
    await expect(failures.getByRole("link", { name: "배포 뒤 확인 기록은 품질 화면에서 봐요" })).toHaveAttribute("href", "/admin/quality");

    // 이번 달 실행 수는 두 사람의 합이다. 서버처럼 서울 달력으로 세어 지난달에 든 실행은 뺀다.
    const seoulMonth = (date: Date) =>
      new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Seoul", year: "numeric", month: "2-digit" }).format(date);
    const thisMonth = (iso: string) => seoulMonth(new Date(iso)) === seoulMonth(new Date());
    const expected = [ago(60_000), ago(3 * 60 * 60_000), ago(8 * DAY), ago(30_000), ago(45_000), ago(2 * DAY)]
      .filter(thisMonth).length;
    const monthly = page.getByRole("region", { name: "이번 달 사용량" });
    await expect(monthly.getByTestId("overview-monthly-total")).toHaveText(`${expected}건`);
    await expect(monthly.getByText("금액을 확인하지 못한 실행이 있어 실제보다 적을 수 있어요")).toHaveCount(0);

    const otherId = await page.request
      .get(`/api/admin/overview/failures`)
      .then(async (response) => ((await response.json()) as { recent: { id: number; mine: boolean }[] }).recent.find((line) => !line.mine)!.id);

    await lines.nth(2).getByRole("link").click();
    await expect(page).toHaveURL(/\/admin\/executions\/\d+$/);

    // 남의 실행 번호로 바로 열어도 상세가 보이지 않고 실행 기록으로 돌아간다.
    await page.goto(`/admin/executions/${otherId}`);
    await expect(page).toHaveURL(/\/admin\/usage$/);
  } finally {
    await replaceExecutions(page, viewer, []);
    await replaceExecutions(page, other, []);
  }
});

test("실패가 없는 그룹은 안내를 보이고 가격 없는 실행이 있으면 금액이 적을 수 있다고 알린다", async ({
  context,
  page,
}, testInfo) => {
  const viewer = { ...isolatedUser(testInfo, "overview-empty"), name: "보는 관리자" };
  await createAdmin(page, viewer, groupIdFor(testInfo));
  await provision(context, page, viewer);
  try {
    await replaceExecutions(page, viewer, [
      { startedAt: ago(60_000), estimatedCostMicros: null, actualCostMicros: null },
    ]);
    await page.goto("/admin");
    const failures = page.getByRole("region", { name: "품질과 실패" });
    await expect(failures.getByText("최근 7일 동안 실패한 실행이 없어요")).toBeVisible();
    await expect(failures.getByRole("link", { name: "배포 뒤 확인 기록은 품질 화면에서 봐요" })).toHaveAttribute("href", "/admin/quality");
    const monthly = page.getByRole("region", { name: "이번 달 사용량" });
    await expect(monthly.getByText("금액을 확인하지 못한 실행이 있어 실제보다 적을 수 있어요")).toBeVisible();
  } finally {
    await replaceExecutions(page, viewer, []);
  }
});

test("한 카드를 읽지 못하면 그 카드에만 안내가 보이고 다시 읽으면 값이 보인다", async ({ page }) => {
  let fail = true;
  await page.route("**/api/admin/overview/failures", (route) =>
    fail ? route.fulfill({ status: 500, json: { code: "INTERNAL", message: "boom" } }) : route.continue(),
  );
  await page.goto("/admin");
  const failures = page.getByRole("region", { name: "품질과 실패" });
  await expect(failures.getByRole("alert")).toHaveText(/불러오지 못했어요/);
  await expect(failures).not.toContainText("INTERNAL");
  const monthly = page.getByRole("region", { name: "이번 달 사용량" });
  await expect(monthly.getByTestId("overview-monthly-total")).toBeVisible();

  fail = false;
  await failures.getByRole("button", { name: "다시 읽기" }).click();
  await expect(failures.getByTestId("overview-failure-total")).toBeVisible();
});
