import type {
  Page,
  Route,
  TestInfo,
} from "../../web/node_modules/@playwright/test/index.js";
import { expect, test } from "./fixtures.ts";

/** 결과물 패널의 `FRAME_SANDBOX` 와 같은 값이다. HTML 미리보기도 이 값만 쓴다. */
const FRAME_SANDBOX =
  "allow-same-origin allow-popups allow-popups-to-escape-sandbox";

const MODIFIED_AT = "2026-10-07T03:00:00Z";

function file(name: string, size: number, extra: object = {}) {
  return {
    name,
    kind: "FILE",
    size,
    modifiedAt: MODIFIED_AT,
    readable: true,
    openable: true,
    ...extra,
  };
}

const ROOT_ENTRIES = [
  {
    name: "보고서",
    kind: "DIRECTORY",
    size: null,
    modifiedAt: MODIFIED_AT,
    readable: true,
    openable: true,
  },
  {
    name: "50%",
    kind: "DIRECTORY",
    size: null,
    modifiedAt: MODIFIED_AT,
    readable: true,
    openable: false,
  },
  file("50%.txt", 10, { openable: false }),
  file("a.txt", 30),
  file("b.csv", 40),
  file("c.html", 50),
  file("d.bin", 60),
  {
    name: "바로가기",
    kind: "LINK",
    size: null,
    modifiedAt: MODIFIED_AT,
    readable: false,
    openable: true,
  },
];

const BODIES: Record<string, { contentType: string; body: string }> = {
  "a.txt": { contentType: "text/plain; charset=utf-8", body: "안녕하세요\n파일 공간 글" },
  "b.csv": {
    contentType: "text/plain; charset=utf-8",
    body: 'name,score\n"김, 철수",90\n',
  },
  "c.html": { contentType: "text/html; charset=utf-8", body: "<p>만든 문서</p>" },
};

/** 화면이 부르는 서버 라우트를 대신 답하는 가짜 Control Plane 상태다. */
type FakeWorkspace = {
  available: boolean;
  /** 목록을 이 상태로 실패시키는 경로다. */
  failures?: Record<string, number>;
};

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: "application/json",
    body: JSON.stringify(body),
  });
}

async function fakeWorkspaceRoutes(page: Page, state: FakeWorkspace) {
  await page.route(
    (url) => url.pathname === "/api/workspace",
    (route) =>
      json(route, {
        available: state.available,
        deletable: false,
        exists: true,
        runningExecutions: 0,
        agents: [
          { code: "writer", name: "글쓰기 도우미", shared: false },
          { code: "family", name: "가족 비서", shared: true },
        ],
      }),
  );
  await page.route(
    (url) => url.pathname === "/api/workspace/entries",
    (route) => {
      const path = new URL(route.request().url()).searchParams.get("path") ?? "";
      const failure = state.failures?.[path];
      if (failure !== undefined)
        return json(route, { code: "FAILED", message: "실패" }, failure);
      const entries =
        path === "" ? ROOT_ENTRIES : path === "보고서" ? [file("1월.txt", 20)] : path === "50%" ? [file("a.txt", 30)] : [];
      return json(route, { path, entries, truncated: false });
    },
  );
  await page.route("**/api/workspace/files/**", (route) => {
    const name = decodeURIComponent(
      new URL(route.request().url()).pathname.split("/").at(-1) ?? "",
    );
    const found = BODIES[name];
    if (found === undefined)
      return json(route, { code: "WORKSPACE_ENTRY_NOT_FOUND", message: "없음" }, 404);
    return route.fulfill({ status: 200, contentType: found.contentType, body: found.body });
  });
}

async function openSidebar(page: Page, testInfo: TestInfo): Promise<void> {
  if (testInfo.project.name === "mobile") {
    await page.getByRole("button", { name: "사이드바 열기" }).click();
  }
}

function entryRow(page: Page, name: string) {
  return page
    .getByTestId("workspace-entry")
    .filter({ has: page.getByText(name, { exact: true }) });
}

function crumbNav(page: Page) {
  return page.getByRole("navigation", { name: "경로" });
}

test("사이드바의 「고급」 묶음에서 파일 공간을 열면 함께 쓰는 에이전트가 보인다", async ({ page }, testInfo) => {
  await fakeWorkspaceRoutes(page, { available: true });
  await page.goto("/usage");
  await openSidebar(page, testInfo);

  const advanced = page
    .getByRole("navigation", { name: "주요 화면" })
    .getByRole("group", { name: "고급" });
  await expect(advanced.getByRole("link")).toHaveText([/^파일 공간/, /^내 브라우저/]);
  await advanced.getByRole("link", { name: "파일 공간" }).click();

  await expect(page).toHaveURL(/\/files$/);
  await expect(page.getByRole("heading", { name: "파일 공간", level: 1 })).toBeVisible();
  const agents = page.getByRole("list", { name: "함께 쓰는 에이전트" });
  await expect(agents).toContainText("글쓰기 도우미");
  await expect(agents.getByRole("listitem").filter({ hasText: "가족 비서" })).toContainText("그룹 공개");
  await expect(page.getByText("다른 사람이 이 에이전트를 쓰면 그 파일도 여기 생겨요")).toBeVisible();
  // 지우기는 아직 없다. 목록이 그려진 뒤에 센다.
  await expect(entryRow(page, "a.txt")).toBeVisible();
  await expect(page.getByRole("main").getByRole("button", { name: /지우기/ })).toHaveCount(0);
});

test("디렉터리를 열고 경로 줄의 「파일 공간」 으로 돌아온다", async ({ page }) => {
  await fakeWorkspaceRoutes(page, { available: true });
  await page.goto("/files");

  await entryRow(page, "보고서").getByRole("link", { name: "보고서", exact: true }).click();
  await expect(page).toHaveURL(/\/files\?path=%EB%B3%B4%EA%B3%A0%EC%84%9C$/);
  await expect(crumbNav(page).getByText("보고서")).toHaveAttribute("aria-current", "page");
  await expect(entryRow(page, "1월.txt")).toBeVisible();
  await expect(entryRow(page, "a.txt")).toHaveCount(0);

  await crumbNav(page).getByRole("link", { name: "파일 공간" }).click();
  await expect(page).toHaveURL(/\/files$/);
  await expect(entryRow(page, "a.txt")).toBeVisible();
});

test("글, 표, HTML 을 미리 보고 정하지 않은 것은 미리보기가 없다", async ({ page }) => {
  await fakeWorkspaceRoutes(page, { available: true });
  await page.goto("/files");
  const preview = page.getByTestId("workspace-preview");

  await entryRow(page, "a.txt").getByRole("link", { name: "a.txt", exact: true }).click();
  await expect(preview.getByTestId("workspace-text")).toHaveText("안녕하세요\n파일 공간 글");
  await preview.getByRole("button", { name: "닫기" }).click();
  await expect(preview).toHaveCount(0);

  await entryRow(page, "b.csv").getByRole("link", { name: "b.csv", exact: true }).click();
  const table = preview.getByTestId("workspace-csv-table");
  await expect(table.getByRole("columnheader")).toHaveText(["name", "score"]);
  await expect(table.getByRole("cell")).toHaveText(["김, 철수", "90"]);
  await preview.getByRole("button", { name: "닫기" }).click();
  await expect(preview).toHaveCount(0);

  await entryRow(page, "c.html").getByRole("link", { name: "c.html", exact: true }).click();
  const frame = preview.getByTestId("workspace-frame");
  await expect(frame).toHaveAttribute("sandbox", FRAME_SANDBOX);
  await expect(frame).toHaveAttribute("src", "/api/workspace/files/c.html");
  await expect(page.frameLocator('[data-testid="workspace-frame"]').getByText("만든 문서")).toBeVisible();
  await preview.getByRole("button", { name: "닫기" }).click();
  await expect(preview).toHaveCount(0);

  await entryRow(page, "d.bin").getByRole("link", { name: "d.bin", exact: true }).click();
  await expect(preview).toContainText("미리보기가 없어요");
  await expect(preview.getByRole("link", { name: "내려받기" })).toHaveAttribute(
    "href",
    "/api/workspace/files/d.bin?download=1",
  );
});

test("내려받기는 download=1 이고 링크와 주소로 열 수 없는 이름은 열지 않는다", async ({ page }) => {
  await fakeWorkspaceRoutes(page, { available: true });
  await page.goto("/files");

  await expect(entryRow(page, "a.txt").getByRole("link", { name: "a.txt 내려받기" })).toHaveAttribute(
    "href",
    /download=1$/,
  );

  const link = entryRow(page, "바로가기");
  await expect(link).toContainText("링크");
  await expect(link.getByRole("link")).toHaveCount(0);
  await link.getByText("바로가기", { exact: true }).click();
  await expect(page).toHaveURL(/\/files$/);
  await expect(page.getByTestId("workspace-preview")).toHaveCount(0);

  const unaddressable = entryRow(page, "50%.txt");
  await expect(unaddressable).toContainText("주소로 열 수 없는 이름");
  await expect(unaddressable.getByRole("link")).toHaveCount(0);
});

test("주소로 쓸 수 없는 이름의 디렉터리는 열리지만 그 안의 파일은 미리보기와 내려받기를 열지 않는다", async ({ page }) => {
  await fakeWorkspaceRoutes(page, { available: true });
  await page.goto("/files");

  await entryRow(page, "50%").getByRole("link", { name: "50%", exact: true }).click();
  await expect(crumbNav(page).getByText("50%")).toHaveAttribute("aria-current", "page");
  const inside = entryRow(page, "a.txt");
  await expect(inside).toContainText("주소로 열 수 없는 이름");
  await expect(inside.getByRole("link")).toHaveCount(0);

  // 주소에 파일을 직접 적어도 미리보기를 열지 않는다.
  await page.goto(`/files?path=${encodeURIComponent("50%")}&file=${encodeURIComponent("50%/a.txt")}`);
  await expect(entryRow(page, "a.txt")).toContainText("주소로 열 수 없는 이름");
  await expect(page.getByTestId("workspace-preview")).toHaveCount(0);
});

test("목록이 400 이면 경로 안내와 맨 위로 가기가 보인다", async ({ page }) => {
  await fakeWorkspaceRoutes(page, { available: true, failures: { 잘못: 400 } });
  await page.goto(`/files?path=${encodeURIComponent("잘못")}`);

  await expect(page.getByText("경로가 올바르지 않아요.")).toBeVisible();
  await page.getByRole("link", { name: "맨 위로 가기" }).click();
  await expect(page).toHaveURL(/\/files$/);
  await expect(entryRow(page, "a.txt")).toBeVisible();
});

test("목록이 404 이면 찾을 수 없다는 안내와 맨 위로 가기가 보인다", async ({ page }) => {
  await fakeWorkspaceRoutes(page, { available: true, failures: { 없음: 404 } });
  await page.goto(`/files?path=${encodeURIComponent("없음")}`);

  await expect(page.getByText("찾을 수 없어요. 지워졌을 수 있어요.")).toBeVisible();
  await expect(page.getByRole("link", { name: "맨 위로 가기" })).toHaveAttribute("href", "/files");
});

test("파일 공간을 쓸 수 없으면 관리자에게 알리라는 안내만 보인다", async ({ page }) => {
  await fakeWorkspaceRoutes(page, { available: false });
  await page.goto("/files");

  await expect(page.getByText("파일 공간을 쓸 수 없어요. 관리자에게 알려 주세요.")).toBeVisible();
  await expect(page.getByTestId("workspace-entries")).toHaveCount(0);
});
