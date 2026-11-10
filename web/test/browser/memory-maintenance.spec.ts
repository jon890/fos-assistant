import type {
  Page,
  Route,
} from "../../web/node_modules/@playwright/test/index.js";
import { expect, test } from "./fixtures.ts";
import {
  cleanupMemories,
  createMemory,
  memoryRow,
  openRow,
  reloadMemoryListBySaving,
} from "./memory-page.ts";

/** 제목이 글자 그대로 보이는지 보려고 태그 모양의 글을 넣는다. */
const ARCHIVE_TITLE = "<b>오래된</b> 이사 일정";
const DEDUP_TITLE = "겹치는 학원 메모";

function candidate(
  id: number,
  memoryId: number,
  title: string,
  action: "ARCHIVE" | "DEDUP",
) {
  return {
    id,
    memoryId,
    scope: "USER",
    title,
    action,
    summary: `${title} 근거 요약`,
    archivable: action === "ARCHIVE",
    proposedAt: "2026-10-30T00:00:00Z",
  };
}

/**
 * 정비 후보 요청을 가짜로 바꾸고 다시 읽게 한다. 서버가 그린 첫 화면은 바꿀 수 없어 실제 기억 하나를 고쳐 저장해
 * 목록 다시 읽기를 일으킨다.
 */
async function showCandidates(page: Page, rows: () => unknown[]) {
  await page.route("**/api/memory/maintenance-candidates", (route: Route) =>
    route.fulfill({ json: rows() }),
  );
  const title = `정비 준비 ${Date.now()} ${Math.random()}`;
  const { id } = await createMemory(page, {
    scope: "USER",
    title,
    content: "목록 다시 읽기 검사",
  });
  try {
    await page.goto("/memory");
    await reloadMemoryListBySaving(page, title);
  } finally {
    await page.request.delete(`/api/memories/${id}`);
  }
}

test.afterEach(async ({ page }) => {
  await page.unrouteAll({ behavior: "ignoreErrors" });
});

test("정비 후보의 맞음과 아님은 확인 결과만 보내고 그 줄을 뺀다", async ({
  page,
}) => {
  let rows = [
    candidate(71, 951, ARCHIVE_TITLE, "ARCHIVE"),
    candidate(72, 952, DEDUP_TITLE, "DEDUP"),
  ];
  const sent: { id: string; body: unknown }[] = [];
  await page.route(
    "**/api/memory/maintenance-candidates/*/review",
    (route: Route) => {
      const id = new URL(route.request().url()).pathname.split("/").at(-2)!;
      sent.push({ id, body: route.request().postDataJSON() });
      rows = rows.filter((row) => String(row.id) !== id);
      return route.fulfill({ status: 204, body: "" });
    },
  );
  await showCandidates(page, () => rows);

  const section = page.getByRole("region", { name: "정비 후보" });
  await expect(section).toContainText("확인 결과만 남아요");
  const items = section.getByTestId("memory-maintenance-candidate");
  await expect(items).toHaveCount(2);
  await expect(items.nth(0)).toContainText(ARCHIVE_TITLE);
  await expect(items.nth(0).locator("b")).toHaveCount(0);
  await expect(
    items.nth(0).getByTestId("memory-maintenance-action"),
  ).toHaveText("보관");
  await expect(items.nth(0)).toContainText(`${ARCHIVE_TITLE} 근거 요약`);
  await expect(
    items.nth(1).getByTestId("memory-maintenance-action"),
  ).toHaveText("중복 정리");
  await expect(
    items.nth(0).getByRole("link", { name: `${ARCHIVE_TITLE} 기억 열기` }),
  ).toHaveAttribute("href", "/memory?memory=951");

  await items.nth(0).getByRole("button", { name: "맞음" }).click();
  await expect(items).toHaveCount(1);
  await items.nth(0).getByRole("button", { name: "아님" }).click();
  await expect(section).toHaveCount(0);

  expect(sent).toEqual([
    { id: "71", body: { review: "CORRECT" } },
    { id: "72", body: { review: "INCORRECT" } },
  ]);
});

test("이미 없는 후보를 누르면 404 여도 그 줄이 빠진다", async ({ page }) => {
  const rows = [candidate(73, 953, DEDUP_TITLE, "DEDUP")];
  await page.route(
    "**/api/memory/maintenance-candidates/73/review",
    (route: Route) =>
      route.fulfill({
        status: 404,
        json: { code: "NOT_FOUND", message: "not found" },
      }),
  );
  await showCandidates(page, () => rows);

  await page
    .getByRole("button", { name: `${DEDUP_TITLE} 정비 후보: 맞음` })
    .click();

  await expect(page.getByTestId("memory-maintenance-candidate")).toHaveCount(0);
});

test("자동 보관 허락을 켜고 끄면 고치기 요청에 archivable 이 실린다", async ({
  page,
}) => {
  const title = `자동 보관 ${Date.now()} ${Math.random()}`;
  const { id } = await createMemory(page, {
    scope: "USER",
    title,
    content: "자동 보관 검사",
  });
  try {
    const bodies: Record<string, unknown>[] = [];
    await page.route(`**/api/memories/${id}`, (route: Route) => {
      if (route.request().method() !== "PATCH") return route.fallback();
      bodies.push(route.request().postDataJSON());
      return route.fulfill({ json: {} });
    });
    await page.goto("/memory");
    await expect(
      memoryRow(page, title).getByTestId("memory-archivable"),
    ).toHaveCount(0);
    await openRow(page, title);
    const row = memoryRow(page, title);
    await row.getByRole("button", { name: "고치기" }).click();
    const checkbox = row.getByRole("checkbox", { name: "자동 보관 허락" });
    await expect(checkbox).not.toBeChecked();
    await checkbox.check();
    await row.getByRole("button", { name: "저장" }).click();
    await expect.poll(() => bodies.length).toBe(1);
    expect(bodies[0]).toMatchObject({ archivable: true });
  } finally {
    await cleanupMemories(page, [title]);
  }
});

test("목록의 archivable 기억에는 자동 보관 허락 표시가 보인다", async ({
  page,
}) => {
  const title = `허락 표시 ${Date.now()} ${Math.random()}`;
  const { id } = await createMemory(page, {
    scope: "USER",
    title,
    content: "표시 검사",
  });
  try {
    await page.goto("/memory");
    const response = await page.request.get("/api/memories");
    const real = ((await response.json()) as { id: number }[]).filter(
      (memory) => memory.id === id,
    );
    await page.route("**/api/memories", (route: Route) =>
      route.request().method() === "GET"
        ? route.fulfill({
            json: real.map((memory) => ({ ...memory, archivable: true })),
          })
        : route.fallback(),
    );
    await reloadMemoryListBySaving(page, title);
    await expect(
      memoryRow(page, title).getByTestId("memory-archivable"),
    ).toHaveText("자동 보관 허락");
  } finally {
    await cleanupMemories(page, [title]);
  }
});

test("memory 주소 값이 가리키는 기억을 펼치고 화면에 보인다", async ({
  page,
}) => {
  const titles: string[] = [];
  let target = 0;
  // 목표 줄이 처음 화면 밖에 있도록 새 기억을 위쪽에 쌓는다.
  const stamp = `${Date.now()} ${Math.random()}`;
  const first = `열기 대상 ${stamp}`;
  target = (
    await createMemory(page, {
      scope: "USER",
      title: first,
      content: "주소로 열린 본문",
    })
  ).id;
  titles.push(first);
  for (let index = 0; index < 12; index += 1) {
    const title = `열기 채움 ${index} ${stamp}`;
    await createMemory(page, { scope: "USER", title, content: "채움" });
    titles.push(title);
  }
  try {
    await page.goto(`/memory?memory=${target}`);
    const row = memoryRow(page, first);
    await expect(row.getByRole("button", { name: first })).toHaveAttribute(
      "aria-expanded",
      "true",
    );
    await expect(row).toContainText("주소로 열린 본문");
    await expect(row).toBeInViewport();
  } finally {
    await cleanupMemories(page, titles);
  }
});
