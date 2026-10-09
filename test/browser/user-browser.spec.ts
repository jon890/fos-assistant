import type {
  Page,
  Route,
} from "../../web/node_modules/@playwright/test/index.js";
import { expect, test } from "./fixtures.ts";
import { clickAndWaitForResponse } from "./helpers.ts";

type Status = "STOPPED" | "STARTING" | "RUNNING" | "STOPPING" | "FAILED";

/** 화면이 부르는 서버 라우트를 대신 답하는 가짜 Control Plane 상태다. */
type FakeBrowser = {
  enabled: boolean;
  status: Status | null;
  /** 켜기 뒤 「켜는 중」 으로 답할 남은 읽기 횟수다. 0 이 되면 켜진다. */
  startingReads: number;
  startError?: { status: number; code: string; message: string };
  stopError?: { status: number; code: string; message: string };
  /** `FAILED` 일 때 Control Plane 이 남기는 까닭 코드다. */
  lastError?: string;
};

function view(state: FakeBrowser) {
  if (!state.enabled) return { enabled: false };
  if (state.status === null)
    return { enabled: true, exists: false, idleTimeoutSeconds: 600 };
  return {
    enabled: true,
    exists: true,
    status: state.status,
    lastError: state.status === "FAILED" ? (state.lastError ?? null) : null,
    startedAt: null,
    lastActiveAt: null,
    idleTimeoutSeconds: 600,
  };
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: "application/json",
    body: JSON.stringify(body),
  });
}

async function fakeBrowserRoutes(page: Page, state: FakeBrowser) {
  await page.route("**/api/browser", (route) => {
    const method = route.request().method();
    if (method === "POST") state.status = "STOPPED";
    if (method === "DELETE") state.status = null;
    if (method === "GET" && state.status === "STARTING") {
      if (state.startingReads > 0) state.startingReads -= 1;
      else state.status = "RUNNING";
    }
    return json(route, view(state));
  });
  await page.route("**/api/browser/start", (route) => {
    if (state.startError) {
      state.status = "FAILED";
      state.lastError = "start_failed";
      const { status, code, message } = state.startError;
      return json(route, { code, message }, status);
    }
    state.status = "STARTING";
    state.startingReads = 1;
    return json(route, view(state));
  });
  await page.route("**/api/browser/stop", (route) => {
    if (state.stopError) {
      state.status = "FAILED";
      state.lastError = "stop_failed";
      const { status, code, message } = state.stopError;
      return json(route, { code, message }, status);
    }
    state.status = "STOPPED";
    return json(route, view(state));
  });
}

test("내 브라우저를 만들고 켜고 끄고 지운다", async ({ page }) => {
  const state: FakeBrowser = { enabled: true, status: null, startingReads: 0 };
  await fakeBrowserRoutes(page, state);
  await page.goto("/browser");

  await expect(page.getByText("아직 브라우저가 없어요")).toBeVisible();
  await clickAndWaitForResponse(
    page,
    page.getByRole("button", { name: "브라우저 만들기" }),
    "POST",
    /^\/api\/browser$/,
  );
  const status = page.getByTestId("browser-status");
  await expect(status).toHaveText("꺼져 있어요");
  await expect(
    page.getByText("쓰지 않으면 10분 뒤 저절로 꺼져요."),
  ).toBeVisible();

  await clickAndWaitForResponse(
    page,
    page.getByRole("button", { name: "켜기" }),
    "POST",
    /^\/api\/browser\/start$/,
  );
  await expect(status).toHaveText("켜는 중이에요");
  await expect(status).toHaveText("켜져 있어요");

  await clickAndWaitForResponse(
    page,
    page.getByRole("button", { name: "끄기" }),
    "POST",
    /^\/api\/browser\/stop$/,
  );
  await expect(status).toHaveText("꺼져 있어요");

  await page.getByRole("button", { name: "지우기" }).click();
  const dialog = page.getByRole("alertdialog");
  await expect(
    dialog.getByRole("heading", { name: "브라우저를 지울까요?" }),
  ).toBeVisible();
  await expect(
    dialog.getByText("로그인한 사이트에서 모두 로그아웃돼요."),
  ).toBeVisible();
  await dialog.getByRole("button", { name: "취소" }).click();
  await expect(dialog).toHaveCount(0);
  await expect(status).toHaveText("꺼져 있어요");

  await page.getByRole("button", { name: "지우기" }).click();
  await clickAndWaitForResponse(
    page,
    page.getByRole("alertdialog").getByRole("button", { name: "지우기" }),
    "DELETE",
    /^\/api\/browser$/,
  );
  await expect(page.getByText("아직 브라우저가 없어요")).toBeVisible();
});

test("기능이 꺼져 있으면 준비 중 안내만 보인다", async ({ page }) => {
  await fakeBrowserRoutes(page, {
    enabled: false,
    status: null,
    startingReads: 0,
  });
  await page.goto("/browser");

  await expect(page.getByText("아직 준비 중이에요.")).toBeVisible();
  await expect(page.getByRole("button", { name: "브라우저 만들기" })).toHaveCount(0);
});

test("켜지 못하면 할 일만 알리고 오류 코드는 그리지 않는다", async ({ page }) => {
  await fakeBrowserRoutes(page, {
    enabled: true,
    status: "STOPPED",
    startingReads: 0,
    startError: {
      status: 502,
      code: "BROWSER_START_FAILED",
      message: "browser did not answer",
    },
  });
  await page.goto("/browser");

  const start = page.getByRole("button", { name: "켜기" });
  await start.click();
  await expect(
    page.getByRole("alert").filter({
      hasText: "브라우저를 켜지 못했어요. 잠시 뒤 다시 켜 주세요.",
    }),
  ).toBeVisible();
  await expect(page.getByTestId("browser-status")).toHaveText("켜지 못했어요");
  await expect(page.getByText("BROWSER_START_FAILED")).toHaveCount(0);
  await expect(page.getByText("start_failed")).toHaveCount(0);
});

test("끄지 못하면 끄기 실패로 알리고 오류 코드는 그리지 않는다", async ({ page }) => {
  await fakeBrowserRoutes(page, {
    enabled: true,
    status: "RUNNING",
    startingReads: 0,
    stopError: {
      status: 502,
      code: "BROWSER_STOP_FAILED",
      message: "browser did not stop",
    },
  });
  await page.goto("/browser");

  await page.getByRole("button", { name: "끄기" }).click();
  await expect(
    page.getByRole("alert").filter({
      hasText: "브라우저를 끄지 못했어요. 잠시 뒤 다시 꺼 주세요.",
    }),
  ).toBeVisible();
  await expect(page.getByTestId("browser-status")).toHaveText("끄지 못했어요");
  await expect(page.getByText("잠시 뒤 다시 꺼 주세요.", { exact: true })).toBeVisible();
  await expect(page.getByText("stop_failed")).toHaveCount(0);
  await expect(page.getByText("BROWSER_STOP_FAILED")).toHaveCount(0);
});

test("동시에 켤 수 있는 수가 차면 잠시 뒤 다시 켜라고 알린다", async ({
  page,
}) => {
  await fakeBrowserRoutes(page, {
    enabled: true,
    status: "STOPPED",
    startingReads: 0,
    startError: {
      status: 409,
      code: "BROWSER_CAPACITY",
      message: "capacity",
    },
  });
  await page.goto("/browser");

  await page.getByRole("button", { name: "켜기" }).click();
  await expect(
    page.getByRole("alert").filter({
      hasText: "지금은 켤 수 있는 브라우저가 다 찼어요. 잠시 뒤 다시 켜 주세요.",
    }),
  ).toBeVisible();
});

test("연결 화면에서 내 브라우저로 간다", async ({ page }) => {
  await fakeBrowserRoutes(page, {
    enabled: true,
    status: null,
    startingReads: 0,
  });
  await page.goto("/connections");

  await page.getByRole("main").getByRole("link", { name: "내 브라우저" }).click();
  await expect(page).toHaveURL(/\/browser$/);
  await expect(page.getByRole("heading", { name: "내 브라우저" })).toBeVisible();
});

test("관리자는 브라우저 목록에서 켜진 브라우저를 끈다", async ({ page }) => {
  const rows = [
    {
      id: 1,
      userId: 11,
      userName: "사용자A",
      status: "RUNNING",
      lastError: null,
      startedAt: "2026-10-07T02:00:00Z",
      lastActiveAt: "2026-10-07T02:30:00Z",
    },
    {
      id: 2,
      userId: 12,
      userName: "사용자B",
      status: "FAILED",
      lastError: "BROWSER_START_FAILED",
      startedAt: null,
      lastActiveAt: null,
    },
  ];
  await page.route("**/api/admin/browsers", (route) => json(route, rows));
  await page.route("**/api/admin/browsers/1/stop", (route) => {
    rows[0]!.status = "STOPPED";
    return json(route, rows[0]);
  });
  await page.goto("/admin/browsers");

  await expect(
    page.getByRole("navigation", { name: "관리자 메뉴" }).getByRole("link", {
      name: "브라우저",
    }),
  ).toHaveAttribute("aria-current", "page");
  const first = page.getByTestId("admin-browser").filter({ hasText: "사용자A" });
  const second = page.getByTestId("admin-browser").filter({ hasText: "사용자B" });
  await expect(first.getByText("켜져 있어요")).toBeVisible();
  await expect(second.getByText("오류 코드 BROWSER_START_FAILED")).toBeVisible();

  await clickAndWaitForResponse(
    page,
    first.getByRole("button", { name: "끄기" }),
    "POST",
    /^\/api\/admin\/browsers\/1\/stop$/,
  );
  await expect(first.getByText("꺼져 있어요")).toBeVisible();
  await expect(first.getByRole("button", { name: "끄기" })).toHaveCount(0);
});
