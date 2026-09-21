import { expect, setSession, test } from "./fixtures.ts";

/**
 * 폭마다 같은 검사가 한 번씩 돌고 더한 사람은 지워지지 않는다.
 *
 * <p>그래서 폭 이름을 주소와 profile 이름에 섞어 서로 겹치지 않게 한다. 같은 값을 쓰면 두 번째 폭이
 * 「이미 있는 이메일」로 거절된다.
 */
function newcomer(project: string) {
  return {
    email: `aunt-${project}@example.com`,
    displayName: `이모 ${project}`,
    hermesProfile: `aunt${project}`,
  };
}

test("관리자가 사람을 더하면 목록에 한 줄이 늘어난다", async ({ page }, testInfo) => {
  const person = newcomer(testInfo.project.name);
  await page.goto("/admin/people");

  const list = page.getByRole("table", { name: "더해진 사람" });
  await expect(list.getByText(person.email)).toHaveCount(0);

  await page.getByLabel("이메일").fill(person.email);
  await page.getByLabel("이름").fill(person.displayName);
  await page.getByLabel("Hermes profile").fill(person.hermesProfile);
  await page.getByRole("button", { name: "더하기" }).click();

  const row = list.getByRole("row").filter({ hasText: person.email });
  await expect(row).toHaveCount(1);
  await expect(row.getByText(person.hermesProfile, { exact: true })).toBeVisible();
  // 아직 한 번도 들어오지 않았으므로 그 사람의 사용자와 에이전트는 없다.
  await expect(row.getByText("아직 없음", { exact: true })).toBeVisible();
  await expect(row.getByText("켜짐", { exact: true })).toBeVisible();
});

test("이미 쓰는 profile 이름으로 더하면 무엇이 겹쳤는지 알린다", async ({ page }, testInfo) => {
  const person = newcomer(testInfo.project.name);
  await page.goto("/admin/people");

  await page.getByLabel("이메일").fill(`uncle-${testInfo.project.name}@example.com`);
  await page.getByLabel("이름").fill("삼촌");
  await page.getByLabel("Hermes profile").fill(person.hermesProfile);
  await page.getByRole("button", { name: "더하기" }).click();

  await expect(page.getByTestId("people-error")).toContainText("profile 이름은 이미 쓰고 있다");
});

test("사용 중지하고 다시 허용할 수 있다", async ({ page }, testInfo) => {
  const person = newcomer(testInfo.project.name);
  await page.goto("/admin/people");
  const row = page
    .getByRole("table", { name: "더해진 사람" })
    .getByRole("row")
    .filter({ hasText: person.email });

  await row.getByRole("button", { name: `${person.displayName} 사용 중지` }).click();
  await expect(row.getByText("꺼짐", { exact: true })).toBeVisible();

  await row.getByRole("button", { name: `${person.displayName} 다시 허용` }).click();
  await expect(row.getByText("켜짐", { exact: true })).toBeVisible();
});

test("관리자가 아닌 사람에게는 사람 관리 화면이 보이지 않는다", async ({ context, page }) => {
  await setSession(context, { email: "member@example.com", name: "가족 구성원" });

  await page.goto("/");
  await expect(page.getByRole("link", { name: "사람 관리" })).toHaveCount(0);

  await page.goto("/admin/people");
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole("table", { name: "더해진 사람" })).toHaveCount(0);
});
