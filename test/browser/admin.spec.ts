import { expect, test, MODELS_AGENT_CODE } from "./fixtures.ts";

test("가족 공개로 바꾸기 전에 확인하고 취소와 확인을 반영한다", async ({ page }) => {
  const reset = await page.request.patch("/api/admin/agents/browser", {
    data: { enabled: true, visibility: "PRIVATE", ownerEmail: "browser@example.com" },
  });
  expect(reset.ok()).toBeTruthy();
  await page.goto("/admin/agents");
  // 에이전트가 여럿이라 이 검사가 보는 카드 하나로 좁힌다.
  const card = page
    .getByRole("region", { name: "등록된 에이전트" })
    .locator("article")
    .filter({ hasText: "브라우저 비서" });

  await expect(card.getByText("나만", { exact: true })).toBeVisible();
  await card.getByRole("button", { name: "가족 공개로 변경" }).click();
  const dialog = page.getByRole("dialog", { name: "브라우저 비서 에이전트를 가족에게 공개할까요?" });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText(/가족 구성원 누구나 이 에이전트를 골라 대화/)).toBeVisible();
  await dialog.getByRole("button", { name: "취소" }).click();
  await expect(dialog).toBeHidden();
  await expect(card.getByText("나만", { exact: true })).toBeVisible();

  await card.getByRole("button", { name: "가족 공개로 변경" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "가족 공개" }).click();
  await expect(card.getByText("가족 공개", { exact: true })).toBeVisible();
});

test("모델 목록을 고쳐 저장하면 그 순서로 남는다", async ({ page }) => {
  await page.request.put(`/api/admin/agents/${MODELS_AGENT_CODE}/models`, {
    data: { models: [{ provider: "openai-codex", model: "gpt-5.5" }] },
  });
  await page.goto("/admin/agents");
  const card = page
    .getByRole("region", { name: "등록된 에이전트" })
    .locator("article")
    .filter({ hasText: "모델 목록 비서" });
  const list = card.getByTestId("agent-model-list");

  await expect(list.getByRole("button", { name: "지우기" })).toBeDisabled();

  await list.getByRole("button", { name: "모델 추가" }).click();
  await list.getByLabel("2순위 provider").fill("nvidia");
  await list.getByLabel("2순위 모델").fill("nemotron");
  await list.getByRole("button", { name: "모델 목록 저장" }).click();
  await expect(list.getByLabel("2순위 provider")).toHaveValue("nvidia");

  await list.getByRole("button", { name: "아래로" }).first().click();
  await list.getByRole("button", { name: "모델 목록 저장" }).click();
  await expect(list.getByLabel("1순위 provider")).toHaveValue("nvidia");

  await page.reload();
  await expect(list.getByLabel("1순위 provider")).toHaveValue("nvidia");
  await expect(list.getByLabel("2순위 provider")).toHaveValue("openai-codex");
});

test("막힌 provider 가 없으면 그 줄을 그리지 않고 목록이 가로로 넘치지 않는다", async ({ page }, testInfo) => {
  await page.goto("/admin/agents");

  await expect(page.getByTestId("blocked-providers")).toHaveCount(0);
  const width = testInfo.project.name === "mobile" ? 390 : 1280;
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
});
