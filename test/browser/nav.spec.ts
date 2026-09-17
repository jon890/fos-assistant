import { expect, setSession, test } from "./fixtures.ts";

test("역할에 맞는 메뉴와 로그인한 사람의 이름을 보인다", async ({ context, page }) => {
  await page.goto("/");
  await expect(page.getByRole("link", { name: "에이전트 관리" })).toBeVisible();
  await expect(page.getByText("브라우저 테스트", { exact: true })).toBeVisible();

  await setSession(context, { email: "member@example.com", name: "가족 구성원" });
  await page.goto("/");
  await expect(page.getByRole("link", { name: "에이전트 관리" })).toHaveCount(0);
  await expect(page.getByText("가족 구성원", { exact: true })).toBeVisible();
});
