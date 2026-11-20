import type { Page } from "../../node_modules/@playwright/test/index.js";
import { expect, test } from "./fixtures.ts";
import { fixBrowserTime } from "./helpers.ts";

// 「내 파일」 화면을 가짜 목록 응답으로 확인한다(스펙 workspace REQ9~REQ18). 목록 API 의 필터링과 쪽 나누기 자체는
// backend 의 ArtifactVaultListTest 가 본다. 여기서는 화면이 인자를 보내고 응답을 그리는지 본다.

const NOW = new Date("2026-11-20T03:00:00Z");
const GIB = 1024 ** 3;

type Item = {
  id: number;
  path: string;
  byteSize: number;
  fileCount: number;
  vaultedAt: string;
  title: string | null;
  description: string | null;
  kind: "IMAGE" | "DOCUMENT" | "TABLE";
  conversationId: string | null;
  conversationTitle: string | null;
};

const TRIP = "11111111-1111-4111-8111-111111111111";

function item(id: number, extra: Partial<Item>): Item {
  return {
    id,
    path: `f${id}.txt`,
    byteSize: 1024,
    fileCount: 1,
    vaultedAt: "2026-11-20T02:00:00Z",
    title: null,
    description: null,
    kind: "DOCUMENT",
    conversationId: TRIP,
    conversationTitle: "제주 여행 계획",
    ...extra,
  };
}

const ITEMS: Item[] = [
  item(6, { path: "out/가계부.xlsx", kind: "TABLE", title: "11월 가계부", description: "11월 지출을 모은 표" }),
  item(5, { path: "data.csv", kind: "TABLE", title: "숙소 목록", vaultedAt: "2026-11-17T03:00:00Z" }),
  item(4, { path: "notes.md", title: "회의록", vaultedAt: "2026-11-03T03:00:00Z", conversationId: null, conversationTitle: null }),
  item(3, { path: "old.txt", title: "지난 메모", vaultedAt: "2026-10-10T03:00:00Z" }),
];

/** 목록 API 의 가짜다. 종류와 낱말, 대화로 필터링하고 `limit` 개씩 나눠 준다. 받은 인자를 모은다. */
async function fakeList(
  page: Page,
  options: { items?: Item[]; limit?: number; full?: boolean; fail?: () => boolean } = {},
) {
  const items = [...(options.items ?? ITEMS)];
  const limit = options.limit ?? 30;
  const requests: URLSearchParams[] = [];
  const deleted: number[] = [];
  await page.route((url) => url.pathname === "/api/artifact-vault", async (route) => {
    const params = new URL(route.request().url()).searchParams;
    requests.push(params);
    if (options.fail?.()) return route.fulfill({ status: 503, json: { code: "ARTIFACT_VAULT_UNAVAILABLE", message: "x" } });
    const q = (params.get("q") ?? "").toLowerCase();
    const before = Number(params.get("before") ?? Infinity);
    const matched = items.filter(
      (candidate) =>
        candidate.id < before &&
        (!params.get("kind") || candidate.kind === params.get("kind")) &&
        (!params.get("conversation") || candidate.conversationId === params.get("conversation")) &&
        (q === "" || [candidate.title, candidate.description, candidate.path, candidate.conversationTitle]
          .some((value) => value?.toLowerCase().includes(q))),
    );
    const page_ = matched.slice(0, limit);
    await route.fulfill({
      status: 200,
      json: {
        items: page_,
        nextCursor: matched.length > page_.length ? page_.at(-1)!.id : null,
        usedBytes: items.reduce((sum, candidate) => sum + candidate.byteSize, 0),
        quotaBytes: 5 * GIB,
        full: options.full ?? false,
      },
    });
  });
  await page.route(/\/api\/artifact-vault\/\d+$/, async (route) => {
    const id = Number(new URL(route.request().url()).pathname.split("/").at(-1));
    if (route.request().method() === "DELETE") {
      deleted.push(id);
      items.splice(items.findIndex((candidate) => candidate.id === id), 1);
      return route.fulfill({ status: 204 });
    }
    const found = items.find((candidate) => candidate.id === id);
    return found ? route.fulfill({ status: 200, json: found }) : route.fulfill({ status: 404, json: { code: "ARTIFACT_NOT_FOUND", message: "x" } });
  });
  await page.route(/\/api\/artifact-vault\/\d+\/content/, (route) => {
    const id = Number(new URL(route.request().url()).pathname.split("/").at(-2));
    const body = id === 5 ? "이름,값\n쌀,1\n" : "# 회의록\n- 안건";
    return route.fulfill({ status: 200, contentType: "text/plain; charset=UTF-8", body });
  });
  return { requests, deleted };
}

function rows(page: Page) {
  return page.getByTestId("my-file");
}

test("파일 공간을 열면 탭 없이 내 파일이 날짜 구분 아래 최신순으로 보이고 작업 폴더 링크가 있다", async ({ page }) => {
  await fixBrowserTime(page, NOW);
  await fakeList(page);
  await page.goto("/files");

  await expect(page.getByRole("heading", { name: "파일 공간", level: 1 })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "파일 종류" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "에이전트 작업 폴더" })).toHaveAttribute("href", "/files/workspace");
  await expect(page.getByTestId("my-files-group").getByRole("heading")).toHaveText(["오늘", "이번 주", "이번 달", "2026년 10월"]);
  await expect(rows(page)).toHaveCount(4);
  await expect(rows(page).first()).toContainText("11월 가계부");
  await expect(rows(page).first()).toContainText("11월 지출을 모은 표");
  await expect(rows(page).first()).toContainText("제주 여행 계획");
  await expect(rows(page).first().getByRole("img", { name: "표" })).toBeVisible();
  await expect(page.getByTestId("my-files-usage")).toHaveText("4.0 KB / 5 GB 사용");
});

test("종류와 검색을 함께 고르면 그 조건으로 다시 읽고 전체로 돌아가면 모두 보인다", async ({ page }) => {
  await fixBrowserTime(page, NOW);
  const { requests } = await fakeList(page);
  await page.goto("/files");
  await expect(rows(page)).toHaveCount(4);

  await page.getByRole("group", { name: "종류" }).getByRole("button", { name: "표" }).click();
  await expect(rows(page)).toHaveCount(2);
  await page.getByRole("searchbox", { name: "내 파일 찾기" }).fill("숙소");
  await expect(rows(page)).toHaveCount(1);
  await expect(rows(page)).toContainText("숙소 목록");
  expect(requests.at(-1)?.get("kind")).toBe("TABLE");
  expect(requests.at(-1)?.get("q")).toBe("숙소");

  await page.getByRole("searchbox", { name: "내 파일 찾기" }).fill("없는 낱말");
  await expect(page.getByText("찾은 파일이 없어요")).toBeVisible();
  await page.getByRole("searchbox", { name: "내 파일 찾기" }).fill("");
  await page.getByRole("group", { name: "종류" }).getByRole("button", { name: "전체" }).click();
  await expect(rows(page)).toHaveCount(4);
});

test("대화 이름을 누르면 그 대화의 파일만 남고 대화 열기가 그 대화를 가리키며 지운 대화는 누를 수 없다", async ({ page }) => {
  await fixBrowserTime(page, NOW);
  const { requests } = await fakeList(page);
  await page.goto("/files");

  const deletedRow = rows(page).filter({ hasText: "회의록" });
  await expect(deletedRow.getByTestId("my-file-conversation")).toHaveText("지운 대화");
  await expect(deletedRow.getByRole("button", { name: "지운 대화" })).toHaveCount(0);

  await rows(page).first().getByRole("button", { name: "제주 여행 계획" }).click();
  const filter = page.getByTestId("my-files-conversation-filter");
  await expect(filter).toContainText("「제주 여행 계획」 에서 나온 파일");
  await expect(filter.getByRole("link", { name: "대화 열기" })).toHaveAttribute("href", `/chat/${TRIP}`);
  await expect(rows(page)).toHaveCount(3);
  expect(requests.at(-1)?.get("conversation")).toBe(TRIP);

  await filter.getByRole("button", { name: "모든 대화의 파일 보기" }).click();
  await expect(rows(page)).toHaveCount(4);
});

test("파일이 많으면 아래로 내려가며 빠지거나 겹치는 줄 없이 이어서 읽는다", async ({ page }) => {
  await fixBrowserTime(page, NOW);
  const many = Array.from({ length: 7 }, (_, index) => item(100 - index, { title: `파일 ${100 - index}` }));
  const { requests } = await fakeList(page, { items: many, limit: 3 });
  await page.goto("/files");

  // 목록 끝이 화면 안에 있으면 끝까지 이어 읽는다.
  await expect(rows(page)).toHaveCount(7);
  await expect(page.getByRole("button", { name: "더 보기" })).toHaveCount(0);
  const titles = await rows(page).locator("span.font-medium").allTextContents();
  expect(titles).toEqual(many.map((candidate) => candidate.title));
  expect(requests.map((params) => params.get("before"))).toEqual([null, "98", "95"]);
});

test("줄을 누르면 형식마다 미리보기가 열리고 엑셀은 미리보기 없이 내려받기만 된다", async ({ page }) => {
  await fixBrowserTime(page, NOW);
  await fakeList(page);
  await page.goto("/files");

  await rows(page).filter({ hasText: "숙소 목록" }).getByRole("button", { name: "숙소 목록 미리보기" }).click();
  const preview = page.getByTestId("my-file-preview");
  await expect(preview.getByRole("table")).toContainText("쌀");

  await preview.getByRole("button", { name: "닫기" }).click();
  await rows(page).filter({ hasText: "회의록" }).getByRole("button", { name: "회의록 미리보기" }).click();
  await expect(preview.getByTestId("my-file-text")).toHaveText("# 회의록\n- 안건");

  await preview.getByRole("button", { name: "닫기" }).click();
  await rows(page).first().getByRole("button", { name: "11월 가계부 미리보기" }).click();
  await expect(preview).toContainText("미리보기가 없어요");
  await expect(preview.getByRole("link", { name: "내려받기" })).toHaveAttribute("href", "/api/artifact-vault/6/content?download=1");
});

test("지우기는 확인을 한 번 거치고 지우면 줄이 사라지고 용량이 바뀐다", async ({ page }) => {
  await fixBrowserTime(page, NOW);
  const { deleted } = await fakeList(page);
  await page.goto("/files");

  await rows(page).first().getByRole("button", { name: "11월 가계부 지우기" }).click();
  const dialog = page.getByRole("alertdialog");
  await expect(dialog).toContainText("11월 가계부를 지울까요?");
  await dialog.getByRole("button", { name: "취소" }).click();
  await expect(rows(page)).toHaveCount(4);
  expect(deleted).toEqual([]);

  await rows(page).first().getByRole("button", { name: "11월 가계부 지우기" }).click();
  await dialog.getByRole("button", { name: "지우기" }).click();
  await expect(dialog).toHaveCount(0);
  await expect(rows(page)).toHaveCount(3);
  expect(deleted).toEqual([6]);
  await expect(page.getByTestId("my-files-usage")).toHaveText("3.0 KB / 5 GB 사용");
});

test("빈 목록, 가득 찬 용량, 불러오기 실패가 각각 다른 안내를 보이고 다시 읽기로 읽는다", async ({ page }) => {
  await fixBrowserTime(page, NOW);
  let failing = true;
  await fakeList(page, { items: [], full: true, fail: () => failing });
  await page.goto("/files");

  await expect(page.getByTestId("my-files-failure")).toContainText("지금은 보관하거나 열 수 없어요");
  failing = false;
  await page.getByRole("button", { name: "다시 읽기" }).click();
  await expect(page.getByText("아직 내 파일이 없어요")).toBeVisible();
  await expect(page.getByText("대화에서 에이전트가 만든 파일이 여기 모여요.", { exact: false })).toBeVisible();
  await expect(page.getByTestId("my-files-full")).toContainText("새 파일이 들어오지 않으니");
});

test("휴대폰 폭에서도 검색, 종류 고르기, 미리보기 시트, 내려받기, 지우기가 화면 안에 있다", async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 760 });
  await fixBrowserTime(page, NOW);
  await fakeList(page);
  await page.goto("/files");

  const search = page.getByRole("searchbox", { name: "내 파일 찾기" });
  const kinds = page.getByRole("group", { name: "종류" });
  await expect(search).toBeVisible();
  await expect(kinds).toBeVisible();
  const listTop = (await rows(page).first().boundingBox())!.y;
  expect((await search.boundingBox())!.y).toBeLessThan(listTop);
  expect((await kinds.boundingBox())!.y).toBeLessThan(listTop);
  for (const name of ["11월 가계부 내려받기", "11월 가계부 지우기"]) {
    const box = (await rows(page).first().getByRole(name.endsWith("내려받기") ? "link" : "button", { name }).boundingBox())!;
    expect(box.x).toBeGreaterThanOrEqual(0);
    expect(box.x + box.width).toBeLessThanOrEqual(360);
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(360);

  await rows(page).filter({ hasText: "회의록" }).getByRole("button", { name: "회의록 미리보기" }).click();
  const sheet = page.getByRole("dialog").filter({ has: page.getByTestId("my-file-text") });
  await expect(sheet.getByTestId("my-file-text")).toBeVisible();
  const sheetBox = (await page.getByTestId("my-file-preview").boundingBox())!;
  expect(sheetBox.width).toBeGreaterThanOrEqual(359);
});
