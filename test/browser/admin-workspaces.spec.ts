import type { Page } from "../../web/node_modules/@playwright/test/index.js";
import { expect, test } from "./fixtures.ts";

/** 응답이 계약 밖의 칸으로 파일 이름을 실어 보내도 화면은 그리지 않아야 한다. */
const STRAY_FILE_NAME = "가족-여행-계획.txt";

const SPACES = [
  { kind: "USER", id: 1, name: "엄마", bytes: 1_572_864, entries: 1234, partial: false },
  { kind: "AGENT", id: 7, name: "리서치", bytes: 2048, entries: 3, partial: true, path: `reports/${STRAY_FILE_NAME}` },
  { kind: "USER", id: 9, name: null, bytes: 0, entries: 0, partial: false },
];

/** `/api/admin/workspaces` 를 차례로 답한다. 마지막 응답은 그 뒤 요청에도 되풀이한다. */
async function fakeUsage(page: Page, ...responses: Array<{ status: number; json: unknown }>): Promise<void> {
  let index = 0;
  await page.route("**/api/admin/workspaces", (route) => {
    const response = responses[Math.min(index, responses.length - 1)];
    index += 1;
    return route.fulfill(response);
  });
}

function usageTable(page: Page) {
  return page.getByRole("table", { name: "실행 공간별 용량" });
}

test("관리자 메뉴의 파일 공간은 주인, 용량, 항목 수와 일부만 셌는지를 보이고 파일 이름은 보이지 않는다", async ({ page }) => {
  await fakeUsage(page, { status: 200, json: { available: true, spaces: SPACES } });
  await page.goto("/admin/people");

  await page.getByRole("navigation", { name: "관리자 메뉴" }).getByRole("link", { name: "파일 공간", exact: true }).click();
  await expect(page).toHaveURL(/\/admin\/workspaces$/);
  await expect(page).toHaveTitle(/파일 공간/);
  await expect(page.getByRole("heading", { level: 1 })).toHaveText("파일 공간");

  const rows = usageTable(page).getByTestId("admin-workspace");
  await expect(rows).toHaveCount(3);
  await expect(rows.nth(0).locator("th, td")).toHaveText(["엄마", "1.5 MB", "1,234개", ""]);
  await expect(rows.nth(1).locator("th, td")).toHaveText(["에이전트 · 리서치", "2.0 KB", "3개", "일부만 셈"]);
  await expect(rows.nth(2).locator("th, td")).toHaveText(["알 수 없음", "0 B", "0개", ""]);
  await expect(page.getByText(STRAY_FILE_NAME)).toHaveCount(0);
});

test("파일 공간을 쓸 수 없으면 표 대신 안내만 보인다", async ({ page }) => {
  await fakeUsage(page, { status: 200, json: { available: false, spaces: [] } });
  await page.goto("/admin/workspaces");

  await expect(page.getByRole("main").getByRole("alert")).toHaveText("파일 공간을 쓸 수 없어요");
  await expect(usageTable(page)).toHaveCount(0);
});

test("실행 공간이 하나도 없으면 빈 안내가 보인다", async ({ page }) => {
  await fakeUsage(page, { status: 200, json: { available: true, spaces: [] } });
  await page.goto("/admin/workspaces");

  await expect(page.getByText("아직 실행 공간이 없어요")).toBeVisible();
  await expect(usageTable(page)).toHaveCount(0);
});

test("읽지 못하면 오류 코드 없이 불러오지 못했다는 안내를 보이고 다시 읽으면 표가 나온다", async ({ page }) => {
  await fakeUsage(
    page,
    { status: 500, json: { code: "INTERNAL_ERROR", message: "용량을 세지 못했어요." } },
    { status: 200, json: { available: true, spaces: SPACES } },
  );
  await page.goto("/admin/workspaces");

  const alert = page.getByRole("main").getByRole("alert");
  await expect(alert).toContainText("불러오지 못했어요");
  await expect(page.getByText("INTERNAL_ERROR")).toHaveCount(0);
  await expect(usageTable(page)).toHaveCount(0);

  await alert.getByRole("button", { name: "다시 읽기" }).click();
  await expect(usageTable(page).getByTestId("admin-workspace")).toHaveCount(3);
  await expect(page.getByRole("main").getByRole("alert")).toHaveCount(0);
});
