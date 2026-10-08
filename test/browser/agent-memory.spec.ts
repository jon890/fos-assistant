import { expect, PERSONA_EMPTY_AGENT_CODE, test } from "./fixtures.ts";
import { clickAndWaitForResponse } from "./helpers.ts";
import { cleanupMemories, createDocument } from "./memory-page.ts";
import type { Page } from "../../web/node_modules/@playwright/test/index.js";

/** 아무도 대화하지 않는 에이전트다. 받는 영역을 바꿔도 다른 검사의 실행에 닿지 않는다. */
const AGENT_CODE = PERSONA_EMPTY_AGENT_CODE;
const API_PATH = new RegExp(`^/api/admin/agents/${AGENT_CODE}/memory-collections$`);

function section(page: Page) {
  return page.getByTestId("agent-memory-section");
}

function careerRow(page: Page) {
  return section(page)
    .getByRole("listitem")
    .filter({
      has: page.getByRole("checkbox", { name: "커리어 받음", exact: true }),
    });
}

/** 에이전트가 받는 영역을 시작 상태(core 만)로 되돌린다. */
async function reset(page: Page): Promise<void> {
  const response = await page.request.put(
    `/api/admin/agents/${AGENT_CODE}/memory-collections`,
    { data: { collections: [{ collection: "core", allowSensitive: false }] } },
  );
  expect(response.ok(), `받는 영역을 되돌리지 못했다: ${response.status()}`).toBeTruthy();
}

async function openAgent(page: Page): Promise<void> {
  await page.goto(`/admin/agents/${AGENT_CODE}`);
  await expect(page.getByRole("main")).toHaveAttribute("aria-busy", "false");
  await expect(careerRow(page)).toBeVisible();
}

test("관리자가 빠진 영역을 보고 붙이면 다시 열어도 남고 최근 변경에 보인다", async ({
  page,
}, testInfo) => {
  const title = `기억 영역 시험 문서 ${testInfo.project.name} ${testInfo.repeatEachIndex}`;
  await reset(page);
  try {
    await createDocument(page, {
      collection: "career",
      documentKey: `agent-memory-${testInfo.project.name}-${testInfo.repeatEachIndex}`,
      title,
      content: "기억 영역 시험용 본문",
      sensitive: true,
    });
    await openAgent(page);

    const row = careerRow(page);
    await expect(row.getByText("항목 1개가 있지만 받지 않아요.")).toBeVisible();
    const save = section(page).getByRole("button", { name: "기억 영역 저장" });
    await expect(save).toBeDisabled();

    await row.getByRole("checkbox", { name: "커리어 받음", exact: true }).check();
    // 받으면서 민감 항목을 허용하지 않았으므로 안내가 민감 항목 쪽으로 바뀐다.
    await expect(row.getByText("민감 항목 1개는 받지 않아요.")).toBeVisible();
    await row.getByRole("checkbox", { name: "커리어 민감 항목까지", exact: true }).check();
    await expect(row.getByText(/받지 않아요/)).toHaveCount(0);
    await expect(save).toBeEnabled();

    await clickAndWaitForResponse(page, save, "PUT", API_PATH);
    await expect(section(page).getByRole("status")).toHaveText("저장했어요.");
    await expect(save).toBeDisabled();

    await openAgent(page);
    await expect(careerRow(page).getByRole("checkbox", { name: "커리어 받음", exact: true })).toBeChecked();
    await expect(
      careerRow(page).getByRole("checkbox", { name: "커리어 민감 항목까지", exact: true }),
    ).toBeChecked();
    await expect(careerRow(page).getByText(/받지 않아요/)).toHaveCount(0);
    // 반복 실행에서 같은 기록이 쌓이므로 최근 변경 목록의 첫 줄만 본다.
    await expect(
      section(page).getByRole("list", { name: "최근 변경" }).getByRole("listitem").first(),
    ).toContainText("커리어 붙임(민감 항목 허용)");
  } finally {
    await reset(page);
    await cleanupMemories(page, [title]);
  }
});

test("저장이 실패하면 고른 값이 남고 오류를 보인다", async ({ page }) => {
  await reset(page);
  try {
    await page.route(`**/api/admin/agents/${AGENT_CODE}/memory-collections`, async (route) => {
      if (route.request().method() !== "PUT") return route.fallback();
      return route.fulfill({
        status: 409,
        contentType: "application/json",
        body: JSON.stringify({ code: "AGENT_BUSY", message: "busy" }),
      });
    });
    await openAgent(page);

    const row = careerRow(page);
    await row.getByRole("checkbox", { name: "커리어 받음", exact: true }).check();
    const failed = page.waitForResponse(
      (response) =>
        response.request().method() === "PUT" && API_PATH.test(new URL(response.url()).pathname),
    );
    await section(page).getByRole("button", { name: "기억 영역 저장" }).click();
    expect((await failed).status()).toBe(409);

    await expect(section(page).getByRole("alert")).toBeVisible();
    await expect(row.getByRole("checkbox", { name: "커리어 받음", exact: true })).toBeChecked();
    await expect(section(page).getByRole("button", { name: "기억 영역 저장" })).toBeEnabled();
  } finally {
    await page.unroute(`**/api/admin/agents/${AGENT_CODE}/memory-collections`);
    await reset(page);
  }
});
