import { mkdir, readdir, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import type {
  Page,
  Locator,
} from "../../web/node_modules/@playwright/test/index.js";
import { expect, test } from "./fixtures.ts";
import { clickAndWaitForResponse } from "./helpers.ts";

/** Next → Control Plane → WorkspaceTree가 읽는 실제 합성 디렉터리다. API 응답 대역은 쓰지 않는다. */
async function preparePages(ownerDir: string) {
  const directory = join(ownerDir, "pages");
  await mkdir(directory);
  for (let i = 1_001; i >= 0; i--) {
    await writeFile(join(directory, nameOf(i)), `합성 파일 ${i}`);
  }
  return directory;
}

function nameOf(index: number) {
  return `f${String(index).padStart(4, "0")}.txt`;
}
function rows(page: Page) {
  return page.getByTestId("workspace-entry");
}
function next(page: Page) {
  return page.getByRole("button", { name: "다음 목록", exact: true });
}
function previous(page: Page) {
  return page.getByRole("button", { name: "이전 목록", exact: true });
}
function refresh(page: Page) {
  return page.getByRole("button", { name: "다시 읽기", exact: true });
}
function crumb(page: Page) {
  return page.getByRole("navigation", { name: "경로" });
}

async function turn(page: Page, button: Locator) {
  return clickAndWaitForResponse(
    page,
    button,
    "GET",
    /^\/api\/workspace\/entries$/,
  );
}

test("실제 1,002건 목록의 끝에 도달하고 이전 목록과 경로 이동은 첫 페이지로 돌아온다", async ({
  page,
  workspace,
}) => {
  await preparePages(workspace.ownerDir);
  await page.goto("/files?path=pages");
  await expect(rows(page)).toHaveCount(1_000);
  await expect(previous(page)).toBeDisabled();
  const response = await turn(page, next(page));
  const last = await response.json();
  expect(last.entries.map((entry: { name: string }) => entry.name)).toEqual([
    "f1000.txt",
    "f1001.txt",
  ]);
  expect(last.nextCursor).toBeNull();
  expect(last.truncated).toBe(false);
  await expect(rows(page)).toHaveCount(2);
  await expect(rows(page).last()).toContainText("f1001.txt");
  await expect(next(page)).toBeDisabled();
  await turn(page, previous(page));
  await expect(rows(page)).toHaveCount(1_000);
  await expect(rows(page).first()).toContainText("f0000.txt");
  await turn(page, next(page));
  await crumb(page)
    .getByRole("link", { name: "파일 공간", exact: true })
    .click();
  await expect(rows(page)).toHaveCount(1);
  await rows(page).getByRole("link", { name: "pages", exact: true }).click();
  await expect(rows(page)).toHaveCount(1_000);
  await expect(previous(page)).toBeDisabled();
  await expect(page.getByText("파일이 바뀌면 다시 읽어 주세요")).toBeVisible();
});

test("선택 파일의 주소와 미리보기는 다음 이전 빈 페이지에서도 유지된다", async ({
  page,
  workspace,
}) => {
  const directory = await preparePages(workspace.ownerDir);
  await page.goto("/files?path=pages&file=pages%2Ff0000.txt");
  const preview = page.getByTestId("workspace-preview");
  await expect(preview.getByTestId("workspace-text")).toHaveText("합성 파일 0");
  // 모바일 시트가 목록을 덮어도 페이지 상태와 선택 상태의 독립성을 검사한다. 실제 페이지 요청과 같은 클릭 핸들러를 실행한다.
  const navigateWhileSelected = async (label: string) => {
    const response = page.waitForResponse(
      (candidate) =>
        new URL(candidate.url()).pathname === "/api/workspace/entries",
    );
    await page
      .getByRole("button", { name: label, exact: true, includeHidden: true })
      .evaluate((button: HTMLButtonElement) => button.click());
    expect((await response).ok()).toBe(true);
    await expect(preview.getByTestId("workspace-text")).toHaveText(
      "합성 파일 0",
    );
    expect(new URL(page.url()).searchParams.get("file")).toBe(
      "pages/f0000.txt",
    );
  };
  await navigateWhileSelected("다음 목록");
  await expect(rows(page)).toHaveCount(2);
  await navigateWhileSelected("이전 목록");
  await expect(rows(page)).toHaveCount(1_000);
  await rm(join(directory, "f1000.txt"));
  await rm(join(directory, "f1001.txt"));
  await navigateWhileSelected("다음 목록");
  await expect(rows(page)).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "이전 목록", includeHidden: true }),
  ).toBeEnabled();
  await navigateWhileSelected("다시 읽기");
  await expect(rows(page)).toHaveCount(1_000);
  await expect(
    page.getByRole("button", { name: "이전 목록", includeHidden: true }),
  ).toBeDisabled();
});

test("잘못된 cursor도 실제 서버가 400으로 거절하고 실패 뒤 페이지와 이력은 유지된다", async ({
  page,
  workspace,
}) => {
  await preparePages(workspace.ownerDir);
  await page.goto("/files?path=pages");
  await expect(rows(page)).toHaveCount(1_000);
  const matches = (url: URL) =>
    url.pathname === "/api/workspace/entries" && url.searchParams.has("cursor");
  // 응답은 대체하지 않는다. 손상된 인자를 실제 Next와 Control Plane에 보내 거절 경계를 검사한다.
  await page.route(matches, async (route) => {
    const url = new URL(route.request().url());
    url.searchParams.set("cursor", "!");
    await route.continue({ url: url.toString() });
  });
  const response = page.waitForResponse(
    (candidate) =>
      candidate.status() === 400 &&
      new URL(candidate.url()).pathname === "/api/workspace/entries",
  );
  await next(page).click();
  expect((await response).status()).toBe(400);
  await expect(page.getByRole("main").getByRole("alert")).toContainText(
    "경로가 올바르지 않아요.",
  );
  await expect(rows(page)).toHaveCount(1_000);
  await expect(previous(page)).toBeDisabled();
  await page.unroute(matches);
  await turn(page, next(page));
  await expect(rows(page)).toHaveCount(2);
  await turn(page, refresh(page));
  await expect(rows(page)).toHaveCount(1_000);
  await expect(previous(page)).toBeDisabled();
});

test("빠른 중복 클릭과 늦은 이전 경로 응답이 현재 목록을 바꾸지 않는다", async ({
  page,
  workspace,
}) => {
  await preparePages(workspace.ownerDir);
  await page.goto("/files?path=pages");
  await expect(rows(page)).toHaveCount(1_000);
  let release = () => {};
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  let requests = 0;
  const matches = (url: URL) =>
    url.pathname === "/api/workspace/entries" && url.searchParams.has("cursor");
  await page.route(matches, async (route) => {
    requests++;
    await gate;
    await route.continue().catch(() => {});
  });
  const pending = page.waitForRequest((request) =>
    new URL(request.url()).searchParams.has("cursor"),
  );
  await next(page).evaluate((button: HTMLButtonElement) => {
    button.click();
    button.click();
  });
  await pending;
  await expect(next(page)).toBeDisabled();
  expect(requests).toBe(1);
  try {
    await crumb(page)
      .getByRole("link", { name: "파일 공간", exact: true })
      .click();
    await expect(rows(page)).toHaveCount(1);
  } finally {
    release();
  }
  await page.unroute(matches, { behavior: "wait" });
  await expect(rows(page)).toHaveCount(1);
  await expect(rows(page)).toContainText("pages");
  expect(await readdir(workspace.ownerDir)).toEqual(["pages"]);
});
