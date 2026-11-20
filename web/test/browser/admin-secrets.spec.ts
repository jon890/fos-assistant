import type { Route } from "../../node_modules/@playwright/test/index.js";
import { expect, HOME_URL, setSession, test } from "./fixtures.ts";
import { clickAndWaitForResponse, openAdminMenu } from "./helpers.ts";

/** 관리자 「서버 비밀값」 화면(서버 비밀값 스펙 REQ1-REQ4, REQ18). Control Plane 응답은 고정한다. */

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: "application/json",
    body: JSON.stringify(body),
  });
}

function overview(set: boolean, overrides: Record<string, unknown> = {}) {
  return {
    storeAvailable: true,
    browserGateway: {
      set,
      prefix: set ? "a1b2" : null,
      updatedBy: set ? { id: 1, name: "관리자" } : null,
      updatedAt: set ? "2026-11-20T01:00:00Z" : null,
      gatewayUrlConfigured: true,
      bindingCount: 2,
      ...overrides,
    },
  };
}

test("관리자는 서버 비밀값 메뉴에서 입력됨과 앞부분을 보고 확인 창을 거쳐 다시 만든다", async ({ page }, testInfo) => {
  await page.route("**/api/admin/secrets", (route) => json(route, overview(true)));
  await page.route("**/api/admin/secrets/browser-gateway/regenerate", (route) =>
    json(route, {
      ...overview(true),
      reinstall: { total: 2, restartPending: 2, failed: 0, catalogUnavailable: false },
    }),
  );
  await page.goto("/admin/secrets");

  await expect(
    (await openAdminMenu(page, testInfo)).getByRole("link", { name: "서버 비밀값" }),
  ).toHaveAttribute("aria-current", "page");
  if (testInfo.project.name === "mobile") await page.keyboard.press("Escape");
  const row = page.getByTestId("secret-browser-gateway");
  await expect(row.getByText("입력됨")).toBeVisible();
  await expect(row.getByText("a1b2…")).toBeVisible();
  await expect(row.getByText(/관리자/)).toBeVisible();

  await row.getByRole("button", { name: "새로 만들기" }).click();
  const dialog = page.getByRole("alertdialog");
  await expect(
    dialog.getByText(
      "브라우저 플러그인 바인딩 2개를 다시 설치합니다. Hermes 를 다시 띄우기(반영 완료) 전까지 그 플러그인은 동작하지 않습니다.",
    ),
  ).toBeVisible();
  await clickAndWaitForResponse(
    page,
    dialog.getByRole("button", { name: "새로 만들기" }),
    "POST",
    /^\/api\/admin\/secrets\/browser-gateway\/regenerate$/,
  );
  await expect(page.getByText("2개를 다시 설치했어요. 반영 완료 전까지 브라우저 플러그인은 동작하지 않습니다.")).toBeVisible();
});

test("값이 비어 있으면 확인 창 없이 만들고 플러그인 목록을 못 읽은 결과를 알린다", async ({ page }) => {
  await page.route("**/api/admin/secrets", (route) => json(route, overview(false, { bindingCount: null })));
  await page.route("**/api/admin/secrets/browser-gateway/regenerate", (route) =>
    json(route, {
      ...overview(true, { bindingCount: null }),
      reinstall: { total: 0, restartPending: 0, failed: 0, catalogUnavailable: true },
    }),
  );
  await page.goto("/admin/secrets");
  const row = page.getByTestId("secret-browser-gateway");
  await expect(row.getByText("비어 있음")).toBeVisible();

  await clickAndWaitForResponse(
    page,
    row.getByRole("button", { name: "새로 만들기" }),
    "POST",
    /^\/api\/admin\/secrets\/browser-gateway\/regenerate$/,
  );
  await expect(page.getByRole("alertdialog")).toHaveCount(0);
  await expect(page.getByText(/바인딩 목록을 읽지 못해 바인딩을 다시 설치하지 못했어요/)).toBeVisible();
});

test("KEK 가 없으면 단추가 꺼지고 안내가 보인다", async ({ page }) => {
  await page.route("**/api/admin/secrets", (route) =>
    json(route, { ...overview(false), storeAvailable: false }),
  );
  await page.goto("/admin/secrets");

  await expect(page.getByText("KEK 가 없어 저장할 수 없습니다")).toBeVisible();
  await expect(page.getByTestId("secret-browser-gateway").getByRole("button", { name: "새로 만들기" })).toBeDisabled();
});

test("상태를 읽지 못하면 오류 안내가 보인다", async ({ page }) => {
  await page.route("**/api/admin/secrets", (route) =>
    json(route, { code: "INTERNAL_ERROR", message: "x" }, 500),
  );
  await page.goto("/admin/secrets");

  await expect(page.getByText("서버 비밀값을 읽지 못했어요. 화면을 다시 열어 주세요.")).toBeVisible();
});

test("MEMBER 역할이 서버 비밀값 주소를 열면 홈으로 넘어간다", async ({ context, page }) => {
  await setSession(context, { email: "member@example.com", name: "가족 사용자" });
  await page.goto("/admin/secrets");
  await expect(page).toHaveURL(HOME_URL);
});
