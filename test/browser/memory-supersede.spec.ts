import type {
  Page,
  Route,
} from "../../web/node_modules/@playwright/test/index.js";
import { expect, test } from "./fixtures.ts";
import {
  createMemory,
  memoryRow,
  openRow,
  reloadMemoryListBySaving,
} from "./memory-page.ts";

/** 모델이 쓴 제목이 글자 그대로 보이는지 보려고 태그 모양의 글을 넣는다. */
const NEW_TITLE = "<b>새 주소</b> 부산";
const OLD_TITLE = "옛 주소 서울";

function stub(
  id: number,
  title: string,
  overrides: Record<string, unknown> = {},
) {
  return {
    id,
    scope: "GROUP",
    ownerUserId: null,
    title,
    content: `${title} 내용`,
    alwaysInject: false,
    status: "ACCEPTED",
    proposedByExecutionId: 7,
    sourceAgentName: null,
    sourceAgentDeleted: false,
    sensitive: false,
    omittedFromContext: false,
    supersededBy: null,
    createdAt: "2026-10-01T00:00:00Z",
    updatedAt: "2026-10-01T00:00:00Z",
    ...overrides,
  };
}

/**
 * 화면의 기억 목록과 대체 제안 요청을 가짜 상태로 바꾸고 다시 읽게 한다. 서버가 그린 첫 목록은 바꿀 수 없어 실제 기억 하나를 고쳐 저장해
 * 다시 읽기를 일으킨다.
 */
async function showStubs(
  page: Page,
  state: { superseded: boolean; pending: boolean },
) {
  await page.route("**/api/memories", async (route) =>
    route.request().method() === "GET"
      ? route.fulfill({
          json: [
            stub(951, NEW_TITLE),
            stub(950, OLD_TITLE, {
              supersededBy: state.superseded
                ? { linkId: 61, memoryId: 951, title: NEW_TITLE }
                : null,
            }),
          ],
        })
      : route.fallback(),
  );
  await page.route("**/api/memory-links/pending", (route: Route) =>
    route.fulfill({
      json: state.pending
        ? [
            {
              id: 61,
              newMemory: { id: 951, title: NEW_TITLE },
              oldMemory: { id: 950, title: OLD_TITLE },
              origin: "AGENT",
              createdAt: "2026-10-17T00:00:00Z",
            },
          ]
        : [],
    }),
  );
  const title = `대체 목록 준비 ${Date.now()} ${Math.random()}`;
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

test("대체 제안을 받아들이면 옛 기억이 대체됨이 되고 되돌리면 표시가 사라진다", async ({
  page,
}) => {
  const state = { superseded: false, pending: true };
  const decided: string[] = [];
  await page.route("**/api/memory-links/61/*", (route: Route) => {
    const action = new URL(route.request().url()).pathname.split("/").at(-1)!;
    decided.push(action);
    if (action === "accept") {
      state.pending = false;
      state.superseded = true;
    }
    if (action === "revoke") state.superseded = false;
    return route.fulfill({
      json: { id: 61, type: "SUPERSEDES", status: "ACTIVE" },
    });
  });
  await showStubs(page, state);

  const card = page.getByTestId("memory-supersede-proposal");
  await expect(card).toContainText("새 기억이 옛 기억을 대신해요");
  await expect(card).toContainText(NEW_TITLE);
  await expect(card).toContainText(OLD_TITLE);
  await expect(card.locator("b")).toHaveCount(0);

  await card.getByRole("button", { name: "받아들이기" }).click();

  await expect(page.getByTestId("memory-supersede-proposal")).toHaveCount(0);
  const oldRow = memoryRow(page, OLD_TITLE);
  await expect(oldRow.getByTestId("memory-superseded")).toHaveText("대체됨");
  await expect(
    memoryRow(page, NEW_TITLE).getByTestId("memory-superseded"),
  ).toHaveCount(0);
  await openRow(page, OLD_TITLE);
  await expect(oldRow).toContainText(`대신한 기억: ${NEW_TITLE}`);

  await oldRow.getByTestId("memory-supersede-revoke").click();

  await expect(oldRow.getByTestId("memory-superseded")).toHaveCount(0);
  expect(decided).toEqual(["accept", "revoke"]);
});

test("대체 제안을 거절하면 카드가 사라지고 기억은 그대로다", async ({
  page,
}) => {
  const state = { superseded: false, pending: true };
  await page.route("**/api/memory-links/61/reject", (route: Route) => {
    state.pending = false;
    return route.fulfill({
      json: { id: 61, type: "SUPERSEDES", status: "REVOKED" },
    });
  });
  await showStubs(page, state);

  await page
    .getByTestId("memory-supersede-proposal")
    .getByRole("button", { name: "거절" })
    .click();

  await expect(page.getByTestId("memory-supersede-proposal")).toHaveCount(0);
  await expect(
    memoryRow(page, OLD_TITLE).getByTestId("memory-superseded"),
  ).toHaveCount(0);
});
