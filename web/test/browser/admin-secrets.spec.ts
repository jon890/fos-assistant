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
    vapid: {
      set: false,
      publicKey: null,
      subject: null,
      updatedBy: null,
      updatedAt: null,
      deviceCount: 0,
    },
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
      "브라우저 플러그인 바인딩 2개를 다시 설치합니다. 자동으로 반영될 때까지 그 플러그인은 동작하지 않습니다.",
    ),
  ).toBeVisible();
  await clickAndWaitForResponse(
    page,
    dialog.getByRole("button", { name: "새로 만들기" }),
    "POST",
    /^\/api\/admin\/secrets\/browser-gateway\/regenerate$/,
  );
  await expect(page.getByText("2개를 다시 설치했어요. 자동으로 반영될 때까지 브라우저 플러그인은 동작하지 않습니다.")).toBeVisible();
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

test("VAPID 키가 있으면 기기 해제 확인 창을 거쳐 새로 만든다", async ({ page }) => {
  const vapid = {
    set: true,
    publicKey: "BPublicKeyValue",
    subject: "mailto:admin@example.com",
    updatedBy: { id: 1, name: "관리자" },
    updatedAt: "2026-11-20T01:00:00Z",
    deviceCount: 3,
  };
  await page.route("**/api/admin/secrets", (route) =>
    json(route, { ...overview(false), vapid }),
  );
  await page.route("**/api/admin/secrets/vapid/regenerate", (route) =>
    json(route, { storeAvailable: true, vapid, releasedUsers: 2 }),
  );
  await page.goto("/admin/secrets");
  const row = page.getByTestId("secret-vapid");
  await expect(row.getByText("BPublicKeyValue")).toBeVisible();

  await row.getByRole("button", { name: "새로 만들기" }).click();
  const dialog = page.getByRole("alertdialog");
  await expect(
    dialog.getByText("등록된 기기 3대가 모두 해제되고 사용자마다 다시 등록해야 합니다."),
  ).toBeVisible();
  await clickAndWaitForResponse(
    page,
    dialog.getByRole("button", { name: "새로 만들기" }),
    "POST",
    /^\/api\/admin\/secrets\/vapid\/regenerate$/,
  );
});

test("단추 연결 앱 줄은 client ID 와 App 이름을 원문으로, secret 은 앞부분만 보이고 저장하면 세 값을 보낸다", async ({ page }) => {
  const app = {
    connectorId: "career",
    connectorTitle: "커리어",
    provider: "github",
    clientId: "Iv1.public",
    appSlug: "career-app",
    set: true,
    prefix: "abcd",
    updatedBy: { id: 1, name: "관리자" },
    updatedAt: "2026-11-21T01:00:00Z",
  };
  const signing = {
    storeAvailable: true,
    set: true,
    updatedBy: null,
    updatedAt: "2026-11-21T01:00:00Z",
    passageConfigured: true,
    connectionCount: 2,
  };
  let saved: unknown;
  await page.route("**/api/admin/secrets", (route) => json(route, overview(true)));
  await page.route("**/api/admin/secrets/connector-oauth/apps", (route) =>
    json(route, { storeAvailable: true, apps: [app] }),
  );
  await page.route("**/api/admin/secrets/connector-oauth/apps/career", (route) => {
    saved = route.request().postDataJSON();
    return json(route, app);
  });
  await page.route("**/api/admin/secrets/connector-oauth/signing", (route) => json(route, signing));
  await page.route("**/api/admin/secrets/connector-oauth/signing/regenerate", (route) =>
    json(route, { signing, rewrite: { total: 2, failed: 1 } }),
  );
  await page.goto("/admin/secrets");

  const row = page.getByTestId("secret-oauth-app-career");
  await expect(row.getByText("GitHub 앱(커리어)")).toBeVisible();
  await expect(row.getByText("Iv1.public")).toBeVisible();
  await expect(row.getByText("career-app")).toBeVisible();
  await expect(row.getByText("abcd…")).toBeVisible();
  await row.getByLabel("GitHub 앱(커리어) client secret").fill("secret-value");
  await clickAndWaitForResponse(
    page,
    row.getByRole("button", { name: "저장" }),
    "PUT",
    /^\/api\/admin\/secrets\/connector-oauth\/apps\/career$/,
  );
  expect(saved).toEqual({ clientId: "Iv1.public", clientSecret: "secret-value", appSlug: "career-app" });
  await expect(page.getByText("앱 설정을 저장했어요.")).toBeVisible();
  await expect(row.getByLabel("GitHub 앱(커리어) client secret")).toHaveValue("");

  const signingRow = page.getByTestId("secret-oauth-signing");
  await expect(signingRow.getByText("자동 관리")).toBeVisible();
  await signingRow.getByRole("button", { name: "새로 만들기" }).click();
  const dialog = page.getByRole("alertdialog");
  await expect(dialog.getByText(/^단추 연결 2개의 토큰 통로 주소를 다시 써요/)).toBeVisible();
  await clickAndWaitForResponse(
    page,
    dialog.getByRole("button", { name: "새로 만들기" }),
    "POST",
    /^\/api\/admin\/secrets\/connector-oauth\/signing\/regenerate$/,
  );
  await expect(page.getByText("단추 연결 1개의 통로 주소를 다시 썼어요.")).toBeVisible();
  await expect(page.getByText(/1개는 다시 쓰지 못했어요/)).toBeVisible();
});
