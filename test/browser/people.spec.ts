import { expect, setSession, test } from "./fixtures.ts";
import type { Page } from "../../web/node_modules/@playwright/test/index.js";

type Newcomer = { email: string; displayName: string; hermesProfile: string };

/**
 * 검사마다 자기 전용 사람을 만들어 쓴다.
 *
 * <p>더한 사람은 지워지지 않고 폭마다 같은 검사가 한 번씩 돈다. 그래서 검사를 가리키는 이름과 폭
 * 이름을 주소와 profile 이름에 함께 섞는다. 같은 값을 쓰면 뒤에 도는 쪽이 「이미 있는 이메일」로
 * 거절된다.
 */
function newcomer(slot: string, label: string, project: string): Newcomer {
  return {
    email: `${slot}-${project}@example.com`,
    displayName: `${label} ${project}`,
    hermesProfile: `${slot}${project}`,
  };
}

/**
 * 검사가 볼 사람을 화면을 거치지 않고 미리 만든다.
 *
 * <p>앞선 검사가 만들어 둔 것에 기대면 하나만 골라 돌리거나 순서가 바뀔 때 그 행을 찾지 못한다.
 */
async function addPerson(page: Page, person: Newcomer): Promise<void> {
  const response = await page.request.post("/api/admin/people", { data: person });
  expect(
    response.ok(),
    `사람을 미리 더하지 못했다: ${response.status()} ${await response.text()}`,
  ).toBeTruthy();
}

test("관리자가 사람을 더하면 목록에 한 줄이 늘어난다", async ({ page }, testInfo) => {
  const person = newcomer("aunt", "이모", testInfo.project.name);
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
  // 겹칠 대상을 이 검사가 직접 만든다.
  const owner = newcomer("uncle", "삼촌", testInfo.project.name);
  await addPerson(page, owner);
  await page.goto("/admin/people");

  await page.getByLabel("이메일").fill(`uncle-twin-${testInfo.project.name}@example.com`);
  await page.getByLabel("이름").fill("삼촌의 쌍둥이");
  await page.getByLabel("Hermes profile").fill(owner.hermesProfile);
  await page.getByRole("button", { name: "더하기" }).click();

  await expect(page.getByTestId("people-error")).toContainText("profile 이름은 이미 쓰고 있다");
});

test("사용 중지하고 다시 허용할 수 있다", async ({ page }, testInfo) => {
  // 켜고 끌 사람을 이 검사가 직접 만든다.
  const person = newcomer("cousin", "사촌", testInfo.project.name);
  await addPerson(page, person);
  await page.goto("/admin/people");
  const row = page
    .getByRole("table", { name: "더해진 사람" })
    .getByRole("row")
    .filter({ hasText: person.email });

  await expect(row.getByText("켜짐", { exact: true })).toBeVisible();

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
