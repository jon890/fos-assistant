import { expect, test } from "./fixtures.ts";

test("관리자 목록에서 평가 사용자 이름에만 평가용 표시를 붙인다", async ({
  page,
}) => {
  const response = await page.request.post("/api/admin/quality/evaluation-user", {
    data: { hermesProfile: "evaluation" },
  });
  expect(response.ok(), await response.text()).toBeTruthy();

  await page.goto("/admin/people");
  const people = page.getByRole("table", { name: "등록된 사용자" });
  const evaluation = people.getByRole("row").filter({
    hasText: "evaluation@quality.invalid",
  });
  await expect(evaluation.getByRole("rowheader").getByText("평가용")).toBeVisible();
  await expect(people.getByText("평가용", { exact: true })).toHaveCount(1);
});
