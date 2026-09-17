import { expect, test } from "./fixtures.ts";

test("가족 공개로 바꾸기 전에 확인하고 취소와 확인을 반영한다", async ({ page }) => {
  const reset = await page.request.patch("/api/admin/agents/browser", {
    data: { enabled: true, visibility: "PRIVATE", ownerEmail: "browser@example.com" },
  });
  expect(reset.ok()).toBeTruthy();
  await page.goto("/admin/agents");
  const agentList = page.getByRole("region", { name: "등록된 에이전트" });

  await expect(agentList.getByText("나만", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "가족 공개로 변경" }).click();
  const dialog = page.getByRole("dialog", { name: "브라우저 비서 에이전트를 가족에게 공개할까요?" });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText(/가족 구성원 누구나 이 에이전트를 골라 대화/)).toBeVisible();
  await dialog.getByRole("button", { name: "취소" }).click();
  await expect(dialog).toBeHidden();
  await expect(agentList.getByText("나만", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "가족 공개로 변경" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "가족 공개" }).click();
  await expect(agentList.getByText("가족 공개", { exact: true })).toBeVisible();
});
